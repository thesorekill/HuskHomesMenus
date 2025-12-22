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
import java.util.*;
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

    // Frozen layout snapshot used for build + click calculations
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

    public void closeAllOpenHomesMenus() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null) continue;
            Inventory top;
            try { top = p.getOpenInventory().getTopInventory(); }
            catch (Throwable ignored) { continue; }

            if (top == null) continue;
            if (top.getHolder() instanceof HomesHolder) {
                try { p.closeInventory(); } catch (Throwable ignored) {}
            }
        }
        openMenus.clear();
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
    // Build (config-customizable)
    // ------------------------------------------------------------

    private void buildAndOpen(Player player, int requestedPage, int maxHomes, List<Home> homes) {

        // Read layout from config
        final int rows = clamp(config.homesRows(), 1, 6);
        final int cols = clamp(config.homesLayoutColumns(), 1, 9);

        final int teleportStart = clamp(config.homesTeleportRowStartSlot(), 0, rows * 9 - 1);
        final int actionStart = clamp(config.homesActionRowStartSlot(), 0, rows * 9 - 1);

        final int actionOffsetRows = Math.max(1, config.homesActionRowOffsetRows());
        final int lineStrideRows = Math.max(1, config.homesLineStrideRows());

        final boolean useFiller = config.homesUseFiller();

        Layout layout = new Layout(rows, cols, teleportStart, actionStart, actionOffsetRows, lineStrideRows, useFiller);

        // Determine how many "lines" fit
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

        String title = config.homesTitle();
        Inventory inv = Bukkit.createInventory(holder, rows * 9, AMP.deserialize(title));

        // Optional filler
        if (useFiller) {
            ItemStack filler = config.buildItem(config.homesFillerItem(), Map.of());
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, filler);
            }
        }

        // Build slotNumber -> actual home name mapping
        Map<Integer, String> slotMap = buildSlotToActualHomeNameMap(homes, maxHomes);
        holder.slotToActualHomeName().clear();
        holder.slotToActualHomeName().putAll(slotMap);

        // Templates
        HHMConfig.MenuItemTemplate savedBedTpl = config.homesTeleportItem();     // should be BLUE_BED by default
        HHMConfig.MenuItemTemplate emptyBedTpl = config.homesEmptyBedItem();    // WHITE_BED by default
        HHMConfig.MenuItemTemplate emptyActionTpl = config.homesEmptyActionItem(); // GRAY_DYE by default
        HHMConfig.MenuItemTemplate deleteActionTpl = config.homesDeleteActionItem();

        int startHome = page * perPage + 1;
        int endHome = Math.min(maxHomes, startHome + perPage - 1);

        for (int homeNumber = startHome; homeNumber <= endHome; homeNumber++) {
            int idx = homeNumber - startHome;
            int line = idx / cols;
            int col = idx % cols;

            int bedSlot = slotForTeleport(layout, line, col);
            int actionSlot = slotForAction(layout, line, col);

            if (!slotInInventory(bedSlot, inv.getSize()) || !slotInInventory(actionSlot, inv.getSize())) continue;

            String actualName = slotMap.get(homeNumber);
            boolean exists = (actualName != null && !actualName.isBlank());

            Map<String, String> ph = baseHomePlaceholders(homeNumber, page, pages, maxHomes);
            // show REAL home name in display; fallback to the slot number for empty slots if you want
            ph.put("%home_name%", exists ? actualName : ("Home " + homeNumber));

            if (exists) {
                // BLUE bed (configurable via savedBedTpl) with home name
                inv.setItem(bedSlot, config.buildItem(savedBedTpl, ph));
                inv.setItem(actionSlot, config.buildItem(deleteActionTpl, ph));
            } else {
                // WHITE bed (configurable) above the create action
                inv.setItem(bedSlot, config.buildItem(emptyBedTpl, ph));
                inv.setItem(actionSlot, config.buildItem(emptyActionTpl, ph));
            }
        }

        // Nav items
        if (navEnabled) {
            int prevPage = Math.max(1, page);
            int nextPage = Math.min(pages, page + 2);

            Map<String, String> navPh = new HashMap<>();
            navPh.put("%page%", String.valueOf(page + 1));
            navPh.put("%pages%", String.valueOf(pages));
            navPh.put("%max_homes%", String.valueOf(maxHomes));
            navPh.put("%prev_page%", String.valueOf(prevPage));
            navPh.put("%next_page%", String.valueOf(nextPage));

            int prevSlot = clamp(config.homesNavPrevSlot(), 0, inv.getSize() - 1);
            int pageSlot = clamp(config.homesNavPageSlot(), 0, inv.getSize() - 1);
            int nextSlot = clamp(config.homesNavNextSlot(), 0, inv.getSize() - 1);
            int closeSlot = clamp(config.homesNavCloseSlot(), 0, inv.getSize() - 1);

            if (page > 0) inv.setItem(prevSlot, config.buildItem(config.homesNavPrevItem(), navPh));
            inv.setItem(pageSlot, config.buildItem(config.homesNavPageItem(), navPh));
            if (page < pages - 1) inv.setItem(nextSlot, config.buildItem(config.homesNavNextItem(), navPh));
            inv.setItem(closeSlot, config.buildItem(config.homesNavCloseItem(), navPh));
        }

        player.openInventory(inv);
        openMenus.add(player.getUniqueId());
    }

    /**
     * Build slot -> actual home name map.
     *
     * Priority:
     *  1) If a home name looks like "1" / "Home 1" / "home_1" etc., map it to that slot index.
     *  2) Any remaining homes (e.g., "Base", "Spawn") are assigned in alphabetical order
     *     to the first empty slot numbers (1..maxHomes).
     */
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

        // 1) numeric-ish mapping
        for (String n : names) {
            Integer idx = parseHomeIndex(n);
            if (idx == null) continue;
            if (idx < 1 || idx > maxHomes) continue;
            out.putIfAbsent(idx, n);
            usedNames.add(n);
        }

        // 2) fill leftovers
        List<String> leftovers = new ArrayList<>();
        for (String n : names) {
            if (!usedNames.contains(n)) leftovers.add(n);
        }
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

    // Compute how many "lines" can fit based on start slots and stride/offset
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
    // Click handling + refresh after set/delete
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof HomesHolder holder)) return;

        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        // Navigation clicks
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

        if (ref.kind == HomeSlotKind.TELEPORT) {
            // Teleport only when home exists AND clicked is the "saved home bed" type
            Material expectedSavedBed = safeMaterial(config.homesTeleportItem().material(), Material.BLUE_BED);
            if (!exists) return;
            if (clicked.getType() != expectedSavedBed) return;

            try { Bukkit.dispatchCommand(p, "huskhomes:home " + actualName); } catch (Throwable ignored) {}
            p.closeInventory();
            return;
        }

        if (ref.kind == HomeSlotKind.ACTION) {
            Material createMat = safeMaterial(config.homesEmptyActionItem().material(), Material.GRAY_DYE);
            Material deleteMat = safeMaterial(config.homesDeleteActionItem().material(), Material.LIGHT_BLUE_DYE);

            if (clicked.getType() == createMat) {
                // IMPORTANT: sethome to the SLOT NUMBER so the menu always has stable slots.
                try { Bukkit.dispatchCommand(p, "huskhomes:sethome " + homeNumber); } catch (Throwable ignored) {}

                // Refresh AFTER HuskHomes processes the command (1 tick delay)
                Bukkit.getScheduler().runTaskLater(plugin, () -> open(p, holder.page()), 1L);
                return;
            }

            if (clicked.getType() == deleteMat) {
                if (!exists) return;
                try { Bukkit.dispatchCommand(p, "huskhomes:delhome " + actualName); } catch (Throwable ignored) {}

                // Refresh AFTER HuskHomes processes the command (1 tick delay)
                Bukkit.getScheduler().runTaskLater(plugin, () -> open(p, holder.page()), 1L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof HomesHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof HomesHolder holder) {
            openMenus.remove(holder.owner());
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
