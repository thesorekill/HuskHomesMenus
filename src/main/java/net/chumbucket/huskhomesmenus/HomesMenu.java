/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.huskhomesmenus;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HomesMenu implements Listener {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    // Matches: "1", "Home 1", "home_1", "home-1", "HOME   12" (case-insensitive)
    private static final Pattern HOME_INDEX_PATTERN =
            Pattern.compile("(?i)^\\s*(?:home\\s*[-_ ]*)?(\\d+)\\s*$");

    private final JavaPlugin plugin;
    private final HHMConfig config;

    private final Set<UUID> openMenus = ConcurrentHashMap.newKeySet();

    // --- Anti-spam / in-flight guard (fixes "delete clicked too quickly") ---
    private final Set<UUID> inFlightActions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastActionMs = new ConcurrentHashMap<>();
    private final long clickCooldownMs = 250L; // small debounce; adjust if you want

    public static final class HomesHolder implements InventoryHolder {
        private final UUID owner;
        private final int page;       // 0-based
        private final int maxHomes;   // HuskHomes max
        private final int perPage;    // homes per page (computed from layout + rows)
        private final Layout layout;  // frozen layout used to render (for click math)
        private final boolean navEnabled;
        private final int navPrevSlot, navPageSlot, navNextSlot, navCloseSlot;

        // slotNumber (1..maxHomes) -> actual HuskHomes home name (e.g., "Base")
        private final Map<Integer, String> slotToActualHomeName = new HashMap<>();

        private HomesHolder(UUID owner, int page, int maxHomes, int perPage,
                            Layout layout,
                            boolean navEnabled,
                            int navPrevSlot, int navPageSlot, int navNextSlot, int navCloseSlot) {
            this.owner = owner;
            this.page = page;
            this.maxHomes = maxHomes;
            this.perPage = perPage;
            this.layout = layout;
            this.navEnabled = navEnabled;
            this.navPrevSlot = navPrevSlot;
            this.navPageSlot = navPageSlot;
            this.navNextSlot = navNextSlot;
            this.navCloseSlot = navCloseSlot;
        }

        public UUID owner() { return owner; }
        public int page() { return page; }
        public int maxHomes() { return maxHomes; }
        public int perPage() { return perPage; }
        public Layout layout() { return layout; }

        public boolean navEnabled() { return navEnabled; }
        public int navPrevSlot() { return navPrevSlot; }
        public int navPageSlot() { return navPageSlot; }
        public int navNextSlot() { return navNextSlot; }
        public int navCloseSlot() { return navCloseSlot; }

        public Map<Integer, String> slotToActualHomeName() { return slotToActualHomeName; }

        @Override
        public Inventory getInventory() { return null; } // not used
    }

    public static final class Layout {
        final int rows;
        final int columns;

        final int teleportStartSlot;
        final int actionStartSlot;

        final int actionOffsetRows;
        final int lineStrideRows;

        final boolean useFiller;

        Layout(int rows, int columns, int teleportStartSlot, int actionStartSlot, int actionOffsetRows, int lineStrideRows, boolean useFiller) {
            this.rows = rows;
            this.columns = columns;
            this.teleportStartSlot = teleportStartSlot;
            this.actionStartSlot = actionStartSlot;
            this.actionOffsetRows = actionOffsetRows;
            this.lineStrideRows = lineStrideRows;
            this.useFiller = useFiller;
        }
    }

    public HomesMenu(JavaPlugin plugin, HHMConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean isHomesMenuOpen(Player p) {
        return p != null && openMenus.contains(p.getUniqueId());
    }

    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int page) {
        if (player == null || !player.isOnline()) return;
        if (!config.homesMenuEnabled()) return;

        final int maxHomes = getMaxHomes(player);

        final HuskHomesAPI api;
        try {
            api = HuskHomesAPI.getInstance();
        } catch (Throwable t) {
            player.sendMessage(AMP.deserialize(config.prefix()).append(AMP.deserialize("&cHuskHomes API not available.")));
            return;
        }

        final OnlineUser user = api.adaptUser(player);

        api.getUserHomes(user).thenAccept(homeList -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            buildAndOpen(player, Math.max(0, page), maxHomes, homeList == null ? List.of() : homeList);
        })).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(AMP.deserialize(config.prefix()).append(AMP.deserialize("&cFailed to load homes.")));
                }
            });
            if (config.debug()) plugin.getLogger().warning("Failed to load homes: " + ex.getMessage());
            return null;
        });
    }

    // ------------------------------------------------------------
    // Build / Render
    // ------------------------------------------------------------

    private void buildAndOpen(Player player, int requestedPage, int maxHomes, List<Home> homes) {
        final int rows = clamp(config.homesRows(), 1, 6);
        final int cols = clamp(config.homesLayoutColumns(), 1, 9);

        final int teleportStart = clamp(config.homesTeleportRowStartSlot(), 0, rows * 9 - 1);
        final int actionStart = clamp(config.homesActionRowStartSlot(), 0, rows * 9 - 1);

        final int actionOffsetRows = Math.max(1, config.homesActionRowOffsetRows());
        final int lineStrideRows = Math.max(1, config.homesLineStrideRows());

        final boolean useFiller = config.homesUseFiller();

        Layout layout = new Layout(rows, cols, teleportStart, actionStart, actionOffsetRows, lineStrideRows, useFiller);

        int lines = Math.max(1, computeLinesThatFit(layout));
        int perPage = Math.max(1, lines * cols);

        int pages = Math.max(1, (int) Math.ceil(maxHomes / (double) perPage));
        int page = Math.min(Math.max(0, requestedPage), pages - 1);

        boolean navEnabled = config.homesNavEnabled() && pages > 1;

        HomesHolder holder = new HomesHolder(
                player.getUniqueId(),
                page,
                maxHomes,
                perPage,
                layout,
                navEnabled,
                config.homesNavPrevSlot(),
                config.homesNavPageSlot(),
                config.homesNavNextSlot(),
                config.homesNavCloseSlot()
        );

        Inventory inv = Bukkit.createInventory(holder, rows * 9, AMP.deserialize(config.homesTitle()));
        renderIntoInventory(inv, holder, page, pages, maxHomes, homes);

        player.openInventory(inv);
        openMenus.add(player.getUniqueId());
    }

    /**
     * Re-renders the menu into an existing inventory (no flicker).
     */
    private void renderIntoInventory(Inventory inv, HomesHolder holder, int page, int pages, int maxHomes, List<Home> homes) {
        Layout layout = holder.layout();
        boolean useFiller = layout.useFiller;

        inv.clear();

        if (useFiller) {
            ItemStack filler = config.buildItem(config.homesFillerItem(), Map.of());
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        Map<Integer, String> slotMap = buildSlotToActualHomeNameMap(homes, maxHomes);
        holder.slotToActualHomeName().clear();
        holder.slotToActualHomeName().putAll(slotMap);

        HHMConfig.MenuItemTemplate savedBedTpl = config.homesTeleportItem();
        HHMConfig.MenuItemTemplate emptyBedTpl = config.homesEmptyBedItem();
        HHMConfig.MenuItemTemplate emptyActionTpl = config.homesEmptyActionItem();
        HHMConfig.MenuItemTemplate deleteActionTpl = config.homesDeleteActionItem();

        int startHome = page * holder.perPage() + 1;
        int endHome = Math.min(maxHomes, startHome + holder.perPage() - 1);

        for (int homeNumber = startHome; homeNumber <= endHome; homeNumber++) {
            int idx = homeNumber - startHome;
            int line = idx / layout.columns;
            int col = idx % layout.columns;

            int bedSlot = slotForTeleport(layout, line, col);
            int actionSlot = slotForAction(layout, line, col);
            if (!slotInInventory(bedSlot, inv.getSize()) || !slotInInventory(actionSlot, inv.getSize())) continue;

            String actualName = slotMap.get(homeNumber);
            boolean exists = (actualName != null && !actualName.isBlank());

            Map<String, String> ph = baseHomePlaceholders(homeNumber, page, pages, maxHomes);
            ph.put("%home_name%", exists ? actualName : "");

            if (exists) {
                inv.setItem(bedSlot, config.buildItem(savedBedTpl, ph));
                inv.setItem(actionSlot, config.buildItem(deleteActionTpl, ph));
            } else {
                inv.setItem(bedSlot, config.buildItem(emptyBedTpl, ph));
                inv.setItem(actionSlot, config.buildItem(emptyActionTpl, ph));
            }
        }

        if (holder.navEnabled()) {
            int prevPage = Math.max(1, page);
            int nextPage = Math.min(pages, page + 2);

            Map<String, String> navPh = new HashMap<>();
            navPh.put("%page%", String.valueOf(page + 1));
            navPh.put("%pages%", String.valueOf(pages));
            navPh.put("%max_homes%", String.valueOf(maxHomes));
            navPh.put("%prev_page%", String.valueOf(prevPage));
            navPh.put("%next_page%", String.valueOf(nextPage));

            int prevSlot = clamp(holder.navPrevSlot(), 0, inv.getSize() - 1);
            int pageSlot = clamp(holder.navPageSlot(), 0, inv.getSize() - 1);
            int nextSlot = clamp(holder.navNextSlot(), 0, inv.getSize() - 1);
            int closeSlot = clamp(holder.navCloseSlot(), 0, inv.getSize() - 1);

            if (page > 0) inv.setItem(prevSlot, config.buildItem(config.homesNavPrevItem(), navPh));
            inv.setItem(pageSlot, config.buildItem(config.homesNavPageItem(), navPh));
            if (page < pages - 1) inv.setItem(nextSlot, config.buildItem(config.homesNavNextItem(), navPh));
            inv.setItem(closeSlot, config.buildItem(config.homesNavCloseItem(), navPh));
        }
    }

    // ------------------------------------------------------------
    // Click handling (ANTI SPAM + API-first refresh)
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof HomesHolder holder)) return;

        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        // Nav
        if (holder.navEnabled()) {
            if (slot == clamp(holder.navCloseSlot(), 0, top.getSize() - 1)) {
                p.closeInventory();
                return;
            }
            if (slot == clamp(holder.navPrevSlot(), 0, top.getSize() - 1) && top.getItem(slot) != null) {
                open(p, Math.max(0, holder.page() - 1));
                return;
            }
            if (slot == clamp(holder.navNextSlot(), 0, top.getSize() - 1) && top.getItem(slot) != null) {
                open(p, holder.page() + 1);
                return;
            }
        }

        Layout l = holder.layout();
        HomeSlotRef ref = resolveHomeSlot(l, slot, holder.perPage());
        if (ref == null) return;

        int homeNumber = holder.page() * holder.perPage() + ref.indexInPage + 1;
        if (homeNumber < 1 || homeNumber > holder.maxHomes()) return;

        String actualName = holder.slotToActualHomeName().get(homeNumber);
        boolean exists = (actualName != null && !actualName.isBlank());

        ItemStack clicked = top.getItem(slot);
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Teleport click
        if (ref.kind == HomeSlotKind.TELEPORT) {
            Material expectedSavedBed = safeMaterial(config.homesTeleportItem().material(), Material.BLUE_BED);
            if (!exists) return;
            if (clicked.getType() != expectedSavedBed) return;

            try { Bukkit.dispatchCommand(p, "huskhomes:home " + actualName); } catch (Throwable ignored) {}
            p.closeInventory();
            return;
        }

        // Action click
        if (ref.kind == HomeSlotKind.ACTION) {

            // --- debounce / in-flight guard ---
            if (!beginAction(p.getUniqueId())) {
                return; // ignore spam clicks
            }

            Material createMat = safeMaterial(config.homesEmptyActionItem().material(), Material.GRAY_DYE);
            Material deleteMat = safeMaterial(config.homesDeleteActionItem().material(), Material.LIGHT_BLUE_DYE);

            // If the click isn't actually one of our action buttons, release lock immediately
            if (clicked.getType() != createMat && clicked.getType() != deleteMat) {
                endAction(p.getUniqueId());
                return;
            }

            // Visually disable the clicked action slot immediately (prevents double click)
            try {
                top.setItem(slot, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
                p.updateInventory();
            } catch (Throwable ignored) {}

            if (clicked.getType() == createMat) {
                String newHomeName = String.valueOf(homeNumber);

                doSetHomeApiFirst(p, newHomeName).whenComplete((ok, err) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try { refreshIfStillOpen(p); } finally { endAction(p.getUniqueId()); }
                        })
                );
                return;
            }

            if (clicked.getType() == deleteMat) {
                if (!exists) {
                    endAction(p.getUniqueId());
                    return;
                }

                final String deleteName = actualName; // snapshot
                doDeleteHomeApiFirst(p, deleteName).whenComplete((ok, err) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try { refreshIfStillOpen(p); } finally { endAction(p.getUniqueId()); }
                        })
                );
            }
        }
    }

    private boolean beginAction(UUID uuid) {
        if (uuid == null) return false;

        long now = System.currentTimeMillis();
        Long last = lastActionMs.get(uuid);
        if (last != null && (now - last) < clickCooldownMs) return false;

        // in-flight lock
        if (!inFlightActions.add(uuid)) return false;

        lastActionMs.put(uuid, now);
        return true;
    }

    private void endAction(UUID uuid) {
        if (uuid == null) return;
        inFlightActions.remove(uuid);
    }

    /**
     * Refreshes by re-fetching homes then re-rendering into the CURRENT open inventory (no flicker).
     */
    private void refreshIfStillOpen(Player p) {
        if (p == null || !p.isOnline()) return;

        Inventory top = p.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof HomesHolder holder)) return;

        final int maxHomes = getMaxHomes(p);

        final HuskHomesAPI api;
        try {
            api = HuskHomesAPI.getInstance();
        } catch (Throwable t) {
            return;
        }

        final OnlineUser user = api.adaptUser(p);

        api.getUserHomes(user).thenAccept(homeList ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;

                    int pages = Math.max(1, (int) Math.ceil(maxHomes / (double) holder.perPage()));
                    int page = Math.min(Math.max(0, holder.page()), pages - 1);

                    renderIntoInventory(top, holder, page, pages, maxHomes, homeList == null ? List.of() : homeList);
                    try { p.updateInventory(); } catch (Throwable ignored) {}
                })
        );
    }

    /**
     * API-first sethome; fallback to command if API method signature differs.
     */
    private CompletableFuture<Boolean> doSetHomeApiFirst(Player p, String homeName) {
        try {
            HuskHomesAPI api = HuskHomesAPI.getInstance();
            OnlineUser user = api.adaptUser(p);

            Object pos = null;
            try {
                Method adaptPos = api.getClass().getMethod("adaptPosition", org.bukkit.Location.class);
                pos = adaptPos.invoke(api, p.getLocation());
            } catch (Throwable ignored) {}

            if (pos != null) {
                for (Method m : api.getClass().getMethods()) {
                    if (!m.getName().equalsIgnoreCase("setHome")) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 3 && pt[1] == String.class && pt[2].isInstance(pos)) {
                        Object out = m.invoke(api, user, homeName, pos);
                        if (out instanceof CompletableFuture<?> cf) {
                            @SuppressWarnings("unchecked")
                            CompletableFuture<Object> raw = (CompletableFuture<Object>) cf;
                            return raw.handle((r, ex) -> ex == null);
                        }
                        return CompletableFuture.completedFuture(true);
                    }
                }
            }
        } catch (Throwable ignored) {}

        try { Bukkit.dispatchCommand(p, "huskhomes:sethome " + homeName); } catch (Throwable ignored) {}
        return completeAfterTicks(12L);
    }

    /**
     * API-first delete home; fallback to command if API method signature differs.
     */
    private CompletableFuture<Boolean> doDeleteHomeApiFirst(Player p, String homeName) {
        try {
            HuskHomesAPI api = HuskHomesAPI.getInstance();
            OnlineUser user = api.adaptUser(p);

            for (Method m : api.getClass().getMethods()) {
                if (!m.getName().equalsIgnoreCase("deleteHome") && !m.getName().equalsIgnoreCase("delHome")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[1] == String.class) {
                    Object out = m.invoke(api, user, homeName);
                    if (out instanceof CompletableFuture<?> cf) {
                        @SuppressWarnings("unchecked")
                        CompletableFuture<Object> raw = (CompletableFuture<Object>) cf;
                        return raw.handle((r, ex) -> ex == null);
                    }
                    return CompletableFuture.completedFuture(true);
                }
            }
        } catch (Throwable ignored) {}

        try { Bukkit.dispatchCommand(p, "huskhomes:delhome " + homeName); } catch (Throwable ignored) {}
        return completeAfterTicks(12L);
    }

    private CompletableFuture<Boolean> completeAfterTicks(long ticks) {
        CompletableFuture<Boolean> cf = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(plugin, () -> cf.complete(true), ticks);
        return cf;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof HomesHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof HomesHolder holder) {
            openMenus.remove(holder.owner());
        }
        // ensure any in-flight lock clears if they close mid-action
        if (e.getPlayer() instanceof Player p) {
            endAction(p.getUniqueId());
        }
    }

    private enum HomeSlotKind { TELEPORT, ACTION }

    private static final class HomeSlotRef {
        final int indexInPage;
        final HomeSlotKind kind;

        private HomeSlotRef(int indexInPage, HomeSlotKind kind) {
            this.indexInPage = indexInPage;
            this.kind = kind;
        }
    }

    private HomeSlotRef resolveHomeSlot(Layout l, int clickedSlot, int perPage) {
        int cols = l.columns;
        for (int idx = 0; idx < perPage; idx++) {
            int line = idx / cols;
            int col = idx % cols;

            int bedSlot = slotForTeleport(l, line, col);
            if (bedSlot == clickedSlot) return new HomeSlotRef(idx, HomeSlotKind.TELEPORT);

            int actionSlot = slotForAction(l, line, col);
            if (actionSlot == clickedSlot) return new HomeSlotRef(idx, HomeSlotKind.ACTION);
        }
        return null;
    }

    private Material safeMaterial(Material configured, Material def) {
        return configured == null ? def : configured;
    }

    // ------------------------------------------------------------
    // Mapping + layout helpers
    // ------------------------------------------------------------

    private Map<Integer, String> buildSlotToActualHomeNameMap(List<Home> homes, int maxHomes) {
        Map<Integer, String> out = new HashMap<>();
        if (homes == null || homes.isEmpty()) return out;

        List<String> names = new ArrayList<>();
        for (Home h : homes) {
            if (h == null) continue;
            String n = h.getName();
            if (n == null) continue;
            n = n.trim();
            if (!n.isBlank()) names.add(n);
        }

        Set<String> usedNames = new HashSet<>();

        for (String n : names) {
            Integer idx = parseHomeIndex(n);
            if (idx == null) continue;
            if (idx < 1 || idx > maxHomes) continue;
            out.putIfAbsent(idx, n);
            usedNames.add(n);
        }

        List<String> leftovers = new ArrayList<>();
        for (String n : names) if (!usedNames.contains(n)) leftovers.add(n);
        leftovers.sort(String.CASE_INSENSITIVE_ORDER);

        int slot = 1;
        for (String n : leftovers) {
            while (slot <= maxHomes && out.containsKey(slot)) slot++;
            if (slot > maxHomes) break;
            out.put(slot, n);
            slot++;
        }

        return out;
    }

    private Integer parseHomeIndex(String name) {
        if (name == null) return null;
        Matcher m = HOME_INDEX_PATTERN.matcher(name);
        if (!m.matches()) return null;
        try {
            int n = Integer.parseInt(m.group(1));
            return n > 0 ? n : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int computeLinesThatFit(Layout l) {
        int invRows = l.rows;
        int teleportStartRow = l.teleportStartSlot / 9;
        int actionStartRow = l.actionStartSlot / 9;

        int lines = 0;
        for (int line = 0; line < 100; line++) {
            int tRow = teleportStartRow + (line * l.lineStrideRows);
            int aRow = actionStartRow + (line * l.lineStrideRows);

            int bedSlot = slotForTeleport(l, line, 0);
            int actionSlot = slotForAction(l, line, 0);

            if (!slotInInventory(bedSlot, invRows * 9) || !slotInInventory(actionSlot, invRows * 9)) break;

            int bedBaseCol = l.teleportStartSlot % 9;
            int actionBaseCol = l.actionStartSlot % 9;
            if (bedBaseCol + (l.columns - 1) > 8) break;
            if (actionBaseCol + (l.columns - 1) > 8) break;

            if (tRow < 0 || tRow >= invRows) break;
            if (aRow < 0 || aRow >= invRows) break;

            lines++;
        }
        return Math.max(1, lines);
    }

    private int slotForTeleport(Layout l, int line, int col) {
        int baseRow = (l.teleportStartSlot / 9) + (line * l.lineStrideRows);
        int baseCol = (l.teleportStartSlot % 9);
        return baseRow * 9 + (baseCol + col);
    }

    private int slotForAction(Layout l, int line, int col) {
        int teleportBaseRow = (l.teleportStartSlot / 9) + (line * l.lineStrideRows);
        int actionRow = teleportBaseRow + l.actionOffsetRows;

        int baseCol = (l.actionStartSlot % 9);
        return actionRow * 9 + (baseCol + col);
    }

    private boolean slotInInventory(int slot, int size) {
        return slot >= 0 && slot < size;
    }

    private Map<String, String> baseHomePlaceholders(int homeNumber, int page, int pages, int maxHomes) {
        Map<String, String> ph = new HashMap<>();
        ph.put("%home%", String.valueOf(homeNumber));
        ph.put("%page%", String.valueOf(page + 1));
        ph.put("%pages%", String.valueOf(pages));
        ph.put("%max_homes%", String.valueOf(maxHomes));
        ph.put("%prev_page%", String.valueOf(Math.max(1, page)));
        ph.put("%next_page%", String.valueOf(Math.min(pages, page + 2)));
        return ph;
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------
    // Max homes logic (unchanged)
    // ------------------------------------------------------------

    private int getMaxHomes(Player p) {
        int base = readHuskHomesMaxHomesFromConfig();
        int permMax = readMaxHomesFromPermissions(p);
        int out = Math.max(base, permMax);
        return Math.max(1, out);
    }

    private int readHuskHomesMaxHomesFromConfig() {
        try {
            File file = new File(Bukkit.getPluginsFolder(), "HuskHomes/config.yml");
            if (!file.exists()) return 10;

            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            int v = yml.getInt("general.max_homes", 10);
            return Math.max(1, v);
        } catch (Throwable t) {
            return 10;
        }
    }

    private int readMaxHomesFromPermissions(Player p) {
        int best = 0;
        for (var info : p.getEffectivePermissions()) {
            if (info == null || !info.getValue()) continue;
            String perm = info.getPermission();
            if (perm == null) continue;

            String lower = perm.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("huskhomes.max_homes.")) continue;

            String tail = lower.substring("huskhomes.max_homes.".length());
            try {
                int n = Integer.parseInt(tail);
                if (n > best) best = n;
            } catch (NumberFormatException ignored) {}
        }
        return Math.max(0, best);
    }
}
