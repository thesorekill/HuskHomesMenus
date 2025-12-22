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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    // --- Anti-spam / in-flight guard (fixes "clicked too quickly") ---
    private final Set<UUID> inFlightActions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastActionMs = new ConcurrentHashMap<>();
    private final long clickCooldownMs = 250L; // small debounce

    // ---------------------------------------------------------------------
    // ✅ Sign input sessions (for naming a home)
    // ---------------------------------------------------------------------
    private final Map<UUID, SignSession> signSessions = new ConcurrentHashMap<>();

    private static final class SignSession {
        final int returnPage;
        final int homeNumber;

        final Location signLoc;
        final Material originalType;
        final BlockData originalData;

        SignSession(int returnPage, int homeNumber,
                    Location signLoc, Material originalType, BlockData originalData) {
            this.returnPage = returnPage;
            this.homeNumber = homeNumber;
            this.signLoc = signLoc;
            this.originalType = originalType;
            this.originalData = originalData;
        }
    }

    public HomesMenu(JavaPlugin plugin, HHMConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean isHomesMenuOpen(Player p) {
        return p != null && openMenus.contains(p.getUniqueId());
    }

    // ---------------------------------------------------------------------
    // ✅ NO-ITALICS FIX: force ITALIC=false on name + lore components
    // ---------------------------------------------------------------------
    private Component deitalicize(Component c) {
        if (c == null) return Component.empty();

        Component out = c.decoration(TextDecoration.ITALIC, false);

        if (!out.children().isEmpty()) {
            List<Component> kids = new ArrayList<>(out.children().size());
            for (Component child : out.children()) kids.add(deitalicize(child));
            out = out.children(kids);
        }

        return out;
    }

    private ItemStack buildNoItalics(HHMConfig.MenuItemTemplate tpl, Map<String, String> placeholders) {
        if (tpl == null) return new ItemStack(Material.AIR);

        ItemStack item = config.buildItem(tpl, placeholders == null ? Map.of() : placeholders);
        if (item == null || item.getType() == Material.AIR) return item;

        try {
            var meta = item.getItemMeta();
            if (meta == null) return item;

            Component name = meta.displayName();
            if (name == null) name = AMP.deserialize(" ");

            meta.displayName(deitalicize(name));

            List<Component> lore = meta.lore();
            if (lore != null && !lore.isEmpty()) {
                List<Component> fixed = new ArrayList<>(lore.size());
                for (Component line : lore) fixed.add(deitalicize(line));
                meta.lore(fixed);
            }

            item.setItemMeta(meta);
        } catch (Throwable ignored) { }

        return item;
    }

    // ---------------------------------------------------------------------
    // Holders
    // ---------------------------------------------------------------------

    public static final class HomesHolder implements InventoryHolder {
        private final UUID owner;
        private final int page;       // 0-based
        private final int maxHomes;   // HuskHomes max
        private final int perPage;    // homes per page
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

    public static final class DeleteConfirmHolder implements InventoryHolder {
        private final UUID owner;
        private final int returnPage;        // the page to return to in homes menu
        private final int homeNumber;        // slot number (1..maxHomes)
        private final String actualHomeName; // actual HuskHomes name to delete

        private final int rows;
        private final boolean useFiller;

        private final int cancelSlot;
        private final int homeSlot;
        private final int confirmSlot;

        private DeleteConfirmHolder(UUID owner, int returnPage, int homeNumber, String actualHomeName,
                                    int rows, boolean useFiller,
                                    int cancelSlot, int homeSlot, int confirmSlot) {
            this.owner = owner;
            this.returnPage = returnPage;
            this.homeNumber = homeNumber;
            this.actualHomeName = actualHomeName;
            this.rows = rows;
            this.useFiller = useFiller;
            this.cancelSlot = cancelSlot;
            this.homeSlot = homeSlot;
            this.confirmSlot = confirmSlot;
        }

        public UUID owner() { return owner; }
        public int returnPage() { return returnPage; }
        public int homeNumber() { return homeNumber; }
        public String actualHomeName() { return actualHomeName; }

        public int rows() { return rows; }
        public boolean useFiller() { return useFiller; }

        public int cancelSlot() { return cancelSlot; }
        public int homeSlot() { return homeSlot; }
        public int confirmSlot() { return confirmSlot; }

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

        Layout(int rows, int columns, int teleportStartSlot, int actionStartSlot,
               int actionOffsetRows, int lineStrideRows, boolean useFiller) {
            this.rows = rows;
            this.columns = columns;
            this.teleportStartSlot = teleportStartSlot;
            this.actionStartSlot = actionStartSlot;
            this.actionOffsetRows = actionOffsetRows;
            this.lineStrideRows = lineStrideRows;
            this.useFiller = useFiller;
        }
    }

    // ---------------------------------------------------------------------
    // Open homes menu (fetch homes async)
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Build / Render homes menu
    // ---------------------------------------------------------------------

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
     * Re-renders the homes menu into an existing inventory (no flicker).
     */
    private void renderIntoInventory(Inventory inv, HomesHolder holder, int page, int pages, int maxHomes, List<Home> homes) {
        Layout layout = holder.layout();
        boolean useFiller = layout.useFiller;

        inv.clear();

        if (useFiller) {
            ItemStack filler = buildNoItalics(config.homesFillerItem(), Map.of());
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        // name -> Home object
        Map<String, Home> homeByName = new HashMap<>();
        if (homes != null) {
            for (Home h : homes) {
                if (h == null) continue;
                try {
                    String n = h.getName();
                    if (n != null && !n.isBlank()) homeByName.put(n.trim(), h);
                } catch (Throwable ignored) {}
            }
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

            // ✅ NEW placeholders (dimension/server/world/coords)
            if (exists) {
                Home h = homeByName.get(actualName);

                String worldName = resolveHomeWorldName(h);
                String serverId = resolveHomeServerId(h);

                String shownServer = "";
                if (config.proxyEnabled()) {
                    shownServer = (serverId == null) ? "" : serverId;
                }

                String dimension = resolveHomeDimensionBestEffort(worldName, shownServer);

                // coords (best-effort across HuskHomes versions)
                String x = resolveHomeCoordInt(h, "getX");
                String y = resolveHomeCoordInt(h, "getY");
                String z = resolveHomeCoordInt(h, "getZ");
                String coords = (!x.isBlank() && !y.isBlank() && !z.isBlank()) ? (x + ", " + y + ", " + z) : "";

                ph.put("%home_world%", worldName == null ? "" : worldName);
                ph.put("%home_server%", shownServer == null ? "" : shownServer);
                ph.put("%home_dimension%", dimension == null ? "" : dimension);

                ph.put("%home_x%", x);
                ph.put("%home_y%", y);
                ph.put("%home_z%", z);
                ph.put("%home_coords%", coords);
            } else {
                ph.put("%home_world%", "");
                ph.put("%home_server%", "");
                ph.put("%home_dimension%", "");

                ph.put("%home_x%", "");
                ph.put("%home_y%", "");
                ph.put("%home_z%", "");
                ph.put("%home_coords%", "");
            }

            if (exists) {
                inv.setItem(bedSlot, buildNoItalics(savedBedTpl, ph));
                inv.setItem(actionSlot, buildNoItalics(deleteActionTpl, ph));
            } else {
                inv.setItem(bedSlot, buildNoItalics(emptyBedTpl, ph));
                inv.setItem(actionSlot, buildNoItalics(emptyActionTpl, ph));
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

            if (page > 0) inv.setItem(prevSlot, buildNoItalics(config.homesNavPrevItem(), navPh));
            inv.setItem(pageSlot, buildNoItalics(config.homesNavPageItem(), navPh));
            if (page < pages - 1) inv.setItem(nextSlot, buildNoItalics(config.homesNavNextItem(), navPh));
            inv.setItem(closeSlot, buildNoItalics(config.homesNavCloseItem(), navPh));
        }
    }

    // ---------------------------------------------------------------------
    // ✅ Configurable create-home sign prompt (menus.homes.create_home_sign)
    // ---------------------------------------------------------------------

    private boolean createHomeSignEnabled() {
        return plugin.getConfig().getBoolean("menus.homes.create_home_sign.enabled", true);
    }

    private Material readCreateHomeSignMaterial() {
        String matName = plugin.getConfig().getString("menus.homes.create_home_sign.material", "OAK_SIGN");
        if (matName == null) return Material.OAK_SIGN;

        Material m = Material.matchMaterial(matName.trim());
        if (m == null) return Material.OAK_SIGN;

        // Ensure it's a sign item/block type
        if (!m.name().endsWith("_SIGN")) return Material.OAK_SIGN;

        return m;
    }

    private String[] readCreateHomeSignLines(Player p, int returnPage, int homeNumber) {
        List<String> cfg = plugin.getConfig().getStringList("menus.homes.create_home_sign.lines");

        String l1 = cfg.size() > 0 ? cfg.get(0) : "&7^^^^^^^^^^^^^^^";
        String l2 = cfg.size() > 1 ? cfg.get(1) : "&bName your home";
        String l3 = cfg.size() > 2 ? cfg.get(2) : "";
        String l4 = cfg.size() > 3 ? cfg.get(3) : "";

        Map<String, String> ph = new HashMap<>();
        ph.put("%home%", String.valueOf(homeNumber));
        ph.put("%player%", (p == null ? "" : p.getName()));
        ph.put("%page%", String.valueOf(Math.max(1, returnPage + 1)));

        l1 = applyPlaceholders(l1, ph);
        l2 = applyPlaceholders(l2, ph);
        l3 = applyPlaceholders(l3, ph);
        l4 = applyPlaceholders(l4, ph);

        return new String[]{l1, l2, l3, l4};
    }

    private String applyPlaceholders(String s, Map<String, String> ph) {
        if (s == null) return "";
        String out = s;
        if (ph != null) {
            for (var e : ph.entrySet()) {
                if (e.getKey() == null) continue;
                out = out.replace(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // ✅ Sign UI: open editor + capture name
    // ---------------------------------------------------------------------

    private void openHomeNameSign(Player p, int returnPage, int homeNumber) {
        if (p == null || !p.isOnline()) return;

        // If disabled in config, fallback to numeric name
        if (!createHomeSignEnabled()) {
            doSetHomeApiFirst(p, String.valueOf(homeNumber)).whenComplete((ok, err) ->
                    Bukkit.getScheduler().runTask(plugin, () -> open(p, Math.max(0, returnPage)))
            );
            return;
        }

        // Find an air block near the player (a couple blocks above head)
        Location base = p.getLocation().clone();
        Location spot = findSignSpot(base);

        if (spot == null) {
            p.sendMessage(AMP.deserialize(config.prefix()).append(AMP.deserialize("&cCouldn't open name prompt here.")));
            return;
        }

        Block b = spot.getBlock();
        Material oldType = b.getType();
        BlockData oldData = b.getBlockData();

        // Place a temporary sign (configurable type)
        Material signMat = readCreateHomeSignMaterial();
        try {
            b.setType(signMat, false);
        } catch (Throwable t) {
            p.sendMessage(AMP.deserialize(config.prefix()).append(AMP.deserialize("&cCouldn't place sign.")));
            return;
        }

        // Prepare sign text (from config)
        final String[] lines = readCreateHomeSignLines(p, returnPage, homeNumber);

        try {
            if (b.getState() instanceof Sign sign) {
                setSignLinesBestEffort(sign, lines[0], lines[1], lines[2], lines[3]);

                // ✅ push to world/client so editor doesn't open blank
                try { sign.update(true, false); } catch (Throwable ignored) {}

                // ✅ additionally push preview straight to THIS player (fixes blank editor race)
                sendSignPreviewToPlayerBestEffort(p, sign.getLocation(), lines);
            }
        } catch (Throwable ignored) {}

        // Store session (overwrites any previous)
        SignSession session = new SignSession(
                Math.max(0, returnPage),
                homeNumber,
                spot.clone(),
                oldType,
                oldData
        );
        signSessions.put(p.getUniqueId(), session);

        // Timeout cleanup (if player ESC's the sign editor, no event fires)
        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanupSignSession(p.getUniqueId(), true), 20L * 60L); // 60s

        // ✅ open editor after tiny delay so client has sign text
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;

            Block sb = session.signLoc.getBlock();
            if (!(sb.getState() instanceof Sign sign)) {
                cleanupSignSession(p.getUniqueId(), true);
                return;
            }

            // one last push for safety
            sendSignPreviewToPlayerBestEffort(p, sign.getLocation(), lines);

            openSignEditorBestEffort(p, sign);
        }, 2L);
    }

    private Location findSignSpot(Location playerLoc) {
        if (playerLoc == null || playerLoc.getWorld() == null) return null;

        // try y+2..y+5 above player, same x/z
        for (int dy = 2; dy <= 5; dy++) {
            Location loc = playerLoc.clone().add(0, dy, 0);
            Block b = loc.getBlock();
            if (b.getType().isAir()) return b.getLocation();
        }

        // try a small ring around (in case above is blocked)
        for (int dy = 2; dy <= 5; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Location loc = playerLoc.clone().add(dx, dy, dz);
                    Block b = loc.getBlock();
                    if (b.getType().isAir()) return b.getLocation();
                }
            }
        }

        return null;
    }

    private void cleanupSignSession(UUID playerId, boolean restoreBlock) {
        if (playerId == null) return;
        SignSession session = signSessions.remove(playerId);
        if (session == null) return;

        if (restoreBlock) {
            try {
                Block b = session.signLoc.getBlock();
                b.setType(session.originalType, false);
                try {
                    if (session.originalData != null) {
                        b.setBlockData(session.originalData, false);
                    }
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        }
    }

    // ✅ Writes to FRONT (mirrors BACK when possible) without unchecked Enum warnings
    private void setSignLinesBestEffort(Sign sign, String l1, String l2, String l3, String l4) {
        if (sign == null) return;

        String[] raw = new String[]{
                l1 == null ? "" : l1,
                l2 == null ? "" : l2,
                l3 == null ? "" : l3,
                l4 == null ? "" : l4
        };

        // --- Modern (1.20+): set FRONT (and BACK) sign sides explicitly ---
        try {
            Class<?> sideClass = Class.forName("org.bukkit.block.sign.Side");

            Object front = null;
            Object back = null;

            Object[] constants = sideClass.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    if (c instanceof Enum<?> en) {
                        String n = en.name();
                        if ("FRONT".equals(n)) front = c;
                        else if ("BACK".equals(n)) back = c;
                    }
                }
            }
            if (front == null) throw new IllegalStateException("Side.FRONT not found");

            Method getSide = sign.getClass().getMethod("getSide", sideClass);
            Object frontSide = getSide.invoke(sign, front);
            Object backSide = (back != null) ? getSide.invoke(sign, back) : null;

            Method lineMethod = frontSide.getClass().getMethod("line", int.class, Component.class);

            for (int i = 0; i < 4; i++) {
                // NOTE: sign editor does not render colors in the same way; keep it clean
                String plain = stripLegacyColor(raw[i]);
                Component c = AMP.deserialize(plain).decoration(TextDecoration.ITALIC, false);

                lineMethod.invoke(frontSide, i, c);
                if (backSide != null) {
                    try { lineMethod.invoke(backSide, i, c); } catch (Throwable ignored) {}
                }
            }
            return;

        } catch (Throwable ignored) {
            // fall back below
        }

        // --- Older Bukkit: setLine(int, String) ---
        try {
            Method setLine = sign.getClass().getMethod("setLine", int.class, String.class);
            setLine.invoke(sign, 0, stripLegacyColor(raw[0]));
            setLine.invoke(sign, 1, stripLegacyColor(raw[1]));
            setLine.invoke(sign, 2, stripLegacyColor(raw[2]));
            setLine.invoke(sign, 3, stripLegacyColor(raw[3]));
        } catch (Throwable ignored) {}
    }

    // ✅ Sends sign text directly to the player (best-effort) to prevent blank editor
    private void sendSignPreviewToPlayerBestEffort(Player p, Location signLoc, String[] lines) {
        if (p == null || signLoc == null || lines == null || lines.length < 4) return;

        try {
            // Prefer Paper/Spigot API: Player#sendSignChange(Location, String[])
            Method m = p.getClass().getMethod("sendSignChange", Location.class, String[].class);
            String[] plain = new String[] {
                    stripLegacyColor(lines[0]),
                    stripLegacyColor(lines[1]),
                    stripLegacyColor(lines[2]),
                    stripLegacyColor(lines[3])
            };
            m.invoke(p, signLoc, (Object) plain);
            return;
        } catch (Throwable ignored) {}

        try {
            // Some newer APIs: sendSignChange(Location, Component[])
            Method m = p.getClass().getMethod("sendSignChange", Location.class, Component[].class);
            Component[] comps = new Component[] {
                    AMP.deserialize(stripLegacyColor(lines[0])).decoration(TextDecoration.ITALIC, false),
                    AMP.deserialize(stripLegacyColor(lines[1])).decoration(TextDecoration.ITALIC, false),
                    AMP.deserialize(stripLegacyColor(lines[2])).decoration(TextDecoration.ITALIC, false),
                    AMP.deserialize(stripLegacyColor(lines[3])).decoration(TextDecoration.ITALIC, false),
            };
            m.invoke(p, signLoc, (Object) comps);
        } catch (Throwable ignored) {}
    }

    private void openSignEditorBestEffort(Player p, Sign sign) {
        try {
            Method m = p.getClass().getMethod("openSign", Sign.class);
            m.invoke(p, sign);
            return;
        } catch (Throwable ignored) {}

        try {
            Method m = p.getClass().getMethod("openSign", Sign.class, boolean.class);
            m.invoke(p, sign, true);
        } catch (Throwable ignored) {}
    }

    private String stripLegacyColor(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)§[0-9A-FK-OR]", "").replaceAll("(?i)&[0-9A-FK-OR]", "");
    }

    private String sanitizeHomeName(String raw, int fallbackHomeNumber) {
        String s = raw == null ? "" : raw.trim();

        // remove color codes (both & and §)
        s = stripLegacyColor(s).trim();

        // Convert spaces -> underscore
        s = s.replaceAll("\\s+", "_").trim();

        // Only keep safe characters
        s = s.replaceAll("[^A-Za-z0-9_\\-]", "");

        // Limit length
        if (s.length() > 32) s = s.substring(0, 32);

        if (s.isBlank()) s = String.valueOf(fallbackHomeNumber);
        return s;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent e) {
        Player p = e.getPlayer();
        if (p == null) return;

        SignSession session = signSessions.get(p.getUniqueId());
        if (session == null) return;

        // Make sure it's OUR temporary sign
        try {
            Location loc = e.getBlock().getLocation();
            if (loc.getWorld() == null || session.signLoc.getWorld() == null) return;
            if (!loc.getWorld().equals(session.signLoc.getWorld())) return;
            if (loc.getBlockX() != session.signLoc.getBlockX()
                    || loc.getBlockY() != session.signLoc.getBlockY()
                    || loc.getBlockZ() != session.signLoc.getBlockZ()) return;
        } catch (Throwable t) {
            return;
        }

        // Your config has players typing on line 0 (top line).
        // ✅ If line 0 is blank -> default to numeric home name (same behavior as disabled).
        String rawLine0 = safeSignLine(e, 0);
        String typed = sanitizeHomeName(rawLine0, session.homeNumber);

        final String homeName;
        if (typed == null || typed.isBlank() || typed.equals(String.valueOf(session.homeNumber))) {
            // Important: treat blank (or effectively blank) as "use number"
            // If the player typed nothing (or only junk stripped by sanitize), name = home number
            if (rawLine0 == null || rawLine0.trim().isEmpty() || stripLegacyColor(rawLine0).trim().isEmpty()) {
                homeName = String.valueOf(session.homeNumber);
            } else {
                // They typed something but it sanitized down to the fallback number; keep it
                homeName = typed;
            }
        } else {
            homeName = typed;
        }

        int returnPage = session.returnPage;

        // Restore the block immediately
        cleanupSignSession(p.getUniqueId(), true);

        // Perform sethome, then reopen menu
        doSetHomeApiFirst(p, homeName).whenComplete((ok, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> open(p, returnPage))
        );
    }

    private String safeSignLine(SignChangeEvent e, int idx) {
        try {
            Method m = e.getClass().getMethod("line", int.class);
            Object out = m.invoke(e, idx);
            if (out instanceof Component c) {
                return AMP.serialize(c);
            }
        } catch (Throwable ignored) {}

        try {
            Method m2 = e.getClass().getMethod("getLine", int.class);
            Object out2 = m2.invoke(e, idx);
            if (out2 instanceof String s) return s;
        } catch (Throwable ignored) {}

        return "";
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (e == null || e.getPlayer() == null) return;
        cleanupSignSession(e.getPlayer().getUniqueId(), true);
        endAction(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------------
    // ✅ HuskHomes Home -> (world/server/dimension/coords) placeholder helpers
    // ---------------------------------------------------------------------

    private String resolveHomeWorldName(Home h) {
        if (h == null) return "";

        Object worldObj = callNoArg(h, "getWorld");
        if (worldObj == null) worldObj = callNoArg(h, "world");
        if (worldObj == null) return "";

        Object name = callNoArg(worldObj, "getName");
        if (name == null) name = callNoArg(worldObj, "name");
        if (name instanceof String s) return s;
        return "";
    }

    private String resolveHomeServerId(Home h) {
        if (h == null) return "";

        Object server = callNoArg(h, "getServer");
        if (server == null) server = callNoArg(h, "server");
        if (server == null) server = callNoArg(h, "getServerName");
        if (server == null) server = callNoArg(h, "getServerId");
        if (server == null) server = callNoArg(h, "getServerID");

        if (server instanceof String s) return s;
        return "";
    }

    private String resolveHomeDimensionBestEffort(String worldName, String homeServerShown) {
        boolean localish = !config.proxyEnabled()
                || homeServerShown == null
                || homeServerShown.isBlank()
                || equalsIgnoreCaseTrim(homeServerShown, config.backendName());

        String wn = (worldName == null) ? "" : worldName.trim();

        if (localish && !wn.isBlank()) {
            try {
                World bw = Bukkit.getWorld(wn);
                if (bw != null) {
                    return switch (bw.getEnvironment()) {
                        case NORMAL -> "Overworld";
                        case NETHER -> "Nether";
                        case THE_END -> "The End";
                        default -> bw.getEnvironment().name();
                    };
                }
            } catch (Throwable ignored) {}
        }

        String lower = wn.toLowerCase(Locale.ROOT);
        if (lower.contains("nether")) return "Nether";
        if (lower.contains("the_end") || lower.endsWith("_end") || lower.contains(" end")) return "The End";
        if (!wn.isBlank()) return "Overworld";

        return "";
    }

    private String resolveHomeCoordInt(Home h, String getterName) {
        if (h == null || getterName == null) return "";

        Object v = callNoArg(h, getterName);

        if (v == null) {
            Object pos = callNoArg(h, "getPosition");
            if (pos == null) pos = callNoArg(h, "position");
            if (pos != null) v = callNoArg(pos, getterName);
        }

        if (v instanceof Number n) return String.valueOf(n.intValue());
        if (v instanceof String s) return s.trim();

        return "";
    }

    private boolean equalsIgnoreCaseTrim(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private Object callNoArg(Object target, String methodName) {
        if (target == null || methodName == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Delete confirmation menu (configurable under menus.homes.delete_confirm)
    // ---------------------------------------------------------------------

    private boolean deleteConfirmEnabled() {
        return plugin.getConfig().getBoolean("menus.homes.delete_confirm.enabled", true);
    }

    private void openDeleteConfirm(Player p, int returnPage, int homeNumber, String actualHomeName) {
        if (p == null || !p.isOnline()) return;

        final String basePath = "menus.homes.delete_confirm";
        final String title = plugin.getConfig().getString(basePath + ".title", "&7Confirm Delete");
        final int rows = clamp(plugin.getConfig().getInt(basePath + ".rows", 3), 1, 6);
        final boolean useFiller = plugin.getConfig().getBoolean(basePath + ".use_filler", true);

        HHMConfig.MenuItemTemplate fillerTpl = HHMConfig.MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection(basePath + ".filler"),
                new HHMConfig.MenuItemTemplate(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false, 0)
        );

        HHMConfig.MenuItemTemplate cancelTpl = HHMConfig.MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection(basePath + ".items.cancel"),
                new HHMConfig.MenuItemTemplate(Material.RED_STAINED_GLASS_PANE, "&cCANCEL",
                        List.of("&7Click to cancel!"), false, 0)
        );

        HHMConfig.MenuItemTemplate homeTpl = HHMConfig.MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection(basePath + ".items.home"),
                new HHMConfig.MenuItemTemplate(Material.LIGHT_BLUE_DYE, "&bHOME %home_name%",
                        List.of(), false, 0)
        );

        HHMConfig.MenuItemTemplate confirmTpl = HHMConfig.MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection(basePath + ".items.confirm"),
                new HHMConfig.MenuItemTemplate(Material.LIME_STAINED_GLASS_PANE, "&aCONFIRM",
                        List.of("&7Click to delete"), false, 0)
        );

        int invSize = rows * 9;

        int cancelSlot = clamp(plugin.getConfig().getInt(basePath + ".items.cancel.slot", 11), 0, invSize - 1);
        int homeSlot = clamp(plugin.getConfig().getInt(basePath + ".items.home.slot", 13), 0, invSize - 1);
        int confirmSlot = clamp(plugin.getConfig().getInt(basePath + ".items.confirm.slot", 15), 0, invSize - 1);

        DeleteConfirmHolder holder = new DeleteConfirmHolder(
                p.getUniqueId(),
                Math.max(0, returnPage),
                homeNumber,
                actualHomeName,
                rows,
                useFiller,
                cancelSlot,
                homeSlot,
                confirmSlot
        );

        Inventory inv = Bukkit.createInventory(holder, invSize, AMP.deserialize(title));

        if (useFiller) {
            ItemStack filler = buildNoItalics(fillerTpl, Map.of());
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("%home%", String.valueOf(homeNumber));
        ph.put("%home_name%", actualHomeName == null ? "" : actualHomeName);

        inv.setItem(cancelSlot, buildNoItalics(cancelTpl, ph));
        inv.setItem(homeSlot, buildNoItalics(homeTpl, ph));
        inv.setItem(confirmSlot, buildNoItalics(confirmTpl, ph));

        p.openInventory(inv);
        openMenus.add(p.getUniqueId());
    }

    // ---------------------------------------------------------------------
    // Click handling (Homes + DeleteConfirm)
    // ---------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        InventoryHolder rawHolder = top.getHolder();

        // Delete confirm clicks
        if (rawHolder instanceof DeleteConfirmHolder dch) {
            e.setCancelled(true);

            int slot = e.getRawSlot();
            if (slot < 0 || slot >= top.getSize()) return;

            if (!beginAction(p.getUniqueId())) return;

            if (slot == dch.cancelSlot()) {
                try { open(p, dch.returnPage()); }
                finally { endAction(p.getUniqueId()); }
                return;
            }

            if (slot == dch.confirmSlot()) {
                try {
                    top.setItem(slot, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
                    p.updateInventory();
                } catch (Throwable ignored) {}

                final String deleteName = dch.actualHomeName();
                doDeleteHomeApiFirst(p, deleteName).whenComplete((ok, err) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try { open(p, dch.returnPage()); }
                            finally { endAction(p.getUniqueId()); }
                        })
                );
                return;
            }

            endAction(p.getUniqueId());
            return;
        }

        // Homes menu clicks
        if (!(rawHolder instanceof HomesHolder holder)) return;

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

        if (ref.kind == HomeSlotKind.TELEPORT) {
            Material expectedSavedBed = safeMaterial(config.homesTeleportItem().material(), Material.BLUE_BED);
            if (!exists) return;
            if (clicked.getType() != expectedSavedBed) return;

            try { Bukkit.dispatchCommand(p, "huskhomes:home " + actualName); } catch (Throwable ignored) {}
            p.closeInventory();
            return;
        }

        if (ref.kind == HomeSlotKind.ACTION) {
            if (!beginAction(p.getUniqueId())) return;

            Material createMat = safeMaterial(config.homesEmptyActionItem().material(), Material.GRAY_DYE);
            Material deleteMat = safeMaterial(config.homesDeleteActionItem().material(), Material.LIGHT_BLUE_DYE);

            if (clicked.getType() != createMat && clicked.getType() != deleteMat) {
                endAction(p.getUniqueId());
                return;
            }

            try {
                top.setItem(slot, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
                p.updateInventory();
            } catch (Throwable ignored) {}

            if (clicked.getType() == createMat) {
                int returnPage = holder.page();
                p.closeInventory();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        openHomeNameSign(p, returnPage, homeNumber);
                    } finally {
                        endAction(p.getUniqueId());
                    }
                });
                return;
            }

            if (clicked.getType() == deleteMat) {
                if (!exists) {
                    endAction(p.getUniqueId());
                    return;
                }

                if (deleteConfirmEnabled()) {
                    try { openDeleteConfirm(p, holder.page(), homeNumber, actualName); }
                    finally { endAction(p.getUniqueId()); }
                    return;
                }

                final String deleteName = actualName;
                doDeleteHomeApiFirst(p, deleteName).whenComplete((ok, err) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try { refreshIfStillOpen(p); }
                            finally { endAction(p.getUniqueId()); }
                        })
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof HomesHolder || top.getHolder() instanceof DeleteConfirmHolder) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder();

        if (h instanceof HomesHolder hh) openMenus.remove(hh.owner());
        if (h instanceof DeleteConfirmHolder dh) openMenus.remove(dh.owner());

        if (e.getPlayer() instanceof Player p) {
            endAction(p.getUniqueId());
        }
    }

    // ---------------------------------------------------------------------
    // Action guard
    // ---------------------------------------------------------------------

    private boolean beginAction(UUID uuid) {
        if (uuid == null) return false;

        long now = System.currentTimeMillis();
        Long last = lastActionMs.get(uuid);
        if (last != null && (now - last) < clickCooldownMs) return false;

        if (!inFlightActions.add(uuid)) return false;

        lastActionMs.put(uuid, now);
        return true;
    }

    private void endAction(UUID uuid) {
        if (uuid == null) return;
        inFlightActions.remove(uuid);
    }

    // ---------------------------------------------------------------------
    // Refresh homes menu (no flicker)
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // HuskHomes API-first actions
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Slot resolve helpers
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Mapping + layout helpers
    // ---------------------------------------------------------------------

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

        int lines = 0;
        for (int line = 0; line < 100; line++) {
            int bedSlot = slotForTeleport(l, line, 0);
            int actionSlot = slotForAction(l, line, 0);

            if (!slotInInventory(bedSlot, invRows * 9) || !slotInInventory(actionSlot, invRows * 9)) break;

            int bedBaseCol = l.teleportStartSlot % 9;
            int actionBaseCol = l.actionStartSlot % 9;
            if (bedBaseCol + (l.columns - 1) > 8) break;
            if (actionBaseCol + (l.columns - 1) > 8) break;

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

    // ---------------------------------------------------------------------
    // Max homes logic (unchanged)
    // ---------------------------------------------------------------------

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
