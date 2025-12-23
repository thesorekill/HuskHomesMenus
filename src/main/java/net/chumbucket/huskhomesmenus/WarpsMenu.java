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

import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.position.Warp;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class WarpsMenu implements Listener {

    private final HuskHomesMenus plugin;
    private final HHMConfig config;

    // Track last opened page per player
    private final Map<UUID, Integer> lastPage = new ConcurrentHashMap<>();

    private enum WarpPermMode {
        PER_WARP, // show only warps with huskhomes.warp.<warpname> or huskhomes.warp.*
        SHOW_ALL  // if player has no per-warp permissions at all, show all warps
    }

    public WarpsMenu(HuskHomesMenus plugin, HHMConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void open(Player player) {
        open(player, lastPage.getOrDefault(player.getUniqueId(), 1));
    }

    public void open(Player player, int page) {
        if (player == null) return;

        if (!config.warpsMenuEnabled()) {
            player.sendMessage(config.msgWithPrefix("messages.warps.disabled", "&cWarps menu is disabled."));
            return;
        }

        final int rows = Math.max(1, config.warpsRows());
        final int size = rows * 9;
        final int safePage = Math.max(1, page);

        final HuskHomesAPI api;
        try {
            api = HuskHomesAPI.getInstance();
        } catch (Throwable t) {
            player.sendMessage(config.msgWithPrefix("messages.warps.load_failed", "&cFailed to load warps."));
            return;
        }

        api.getWarps().thenAccept(warps -> {
            final List<Warp> raw = (warps == null) ? List.of() : warps;

            // Normalize + sort by name (stable ordering)
            final List<Warp> sorted = new ArrayList<>();
            for (Warp w : raw) {
                String n = safeWarpName(w);
                if (n == null || n.isBlank()) continue;
                sorted.add(w);
            }
            sorted.sort(Comparator.comparing(w -> {
                String n = safeWarpName(w);
                return n == null ? "" : n.toLowerCase(Locale.ROOT);
            }));

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                List<Integer> itemSlots = config.warpsItemSlots(rows);
                if (itemSlots == null || itemSlots.isEmpty()) itemSlots = defaultWarpSlots(size);

                // Decide permission mode for this viewer
                final WarpPermMode mode = determineMode(player, sorted);

                // Visible list:
                // - PER_WARP: ONLY warps the player has huskhomes.warp.<warpname> (or huskhomes.warp.*)
                // - SHOW_ALL: player has NO per-warp permissions at all -> show all warps
                final List<Warp> visible = new ArrayList<>();
                for (Warp w : sorted) {
                    String name = safeWarpName(w);
                    if (name == null || name.isBlank()) continue;

                    if (mode == WarpPermMode.SHOW_ALL) {
                        visible.add(w);
                    } else {
                        if (canUseWarp(player, name)) visible.add(w);
                    }
                }

                final int perPage = Math.max(1, itemSlots.size());
                final int pages = Math.max(1, (int) Math.ceil(visible.size() / (double) perPage));
                final int clampedPage = Math.max(1, Math.min(pages, safePage));

                Inventory inv = Bukkit.createInventory(
                        new WarpsHolder(player.getUniqueId(), clampedPage),
                        size,
                        config.colorComponent(config.warpsTitle())
                );

                // filler
                if (config.warpsUseFiller()) {
                    ItemStack filler = config.buildItem(config.warpsFillerItem(), Map.of());
                    if (filler != null) {
                        for (int i = 0; i < size; i++) inv.setItem(i, filler);
                    }
                }

                // place items
                int startIndex = (clampedPage - 1) * perPage;
                int endIndex = Math.min(visible.size(), startIndex + perPage);

                int slotCursor = 0;
                for (int i = startIndex; i < endIndex && slotCursor < itemSlots.size(); i++) {
                    Warp warp = visible.get(i);
                    int slot = itemSlots.get(slotCursor++);

                    String warpName = safeWarpName(warp);
                    if (warpName == null || warpName.isBlank()) continue;

                    // In SHOW_ALL mode, everything is displayed as usable.
                    boolean canUse = (mode == WarpPermMode.SHOW_ALL) || canUseWarp(player, warpName);

                    ItemStack item = buildWarpItem(player, warp, canUse);
                    if (item != null) inv.setItem(slot, item);
                }

                // empty state
                if (visible.isEmpty() && itemSlots != null && !itemSlots.isEmpty()) {
                    ItemStack empty = config.buildItem(config.warpsEmptyItem(), Map.of());
                    if (empty != null) inv.setItem(itemSlots.get(0), empty);
                }

                // nav
                if (config.warpsNavEnabled()) {
                    placeNav(inv, clampedPage, pages, perPage, visible.size());
                }

                lastPage.put(player.getUniqueId(), clampedPage);
                player.openInventory(inv);
            });
        }).exceptionally(err -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(config.msgWithPrefix("messages.warps.load_failed", "&cFailed to load warps."));
                }
            });
            return null;
        });
    }

    // -------------------------
    // Rendering
    // -------------------------

    private void placeNav(Inventory inv, int page, int pages, int perPage, int total) {
        Map<String, String> ph = new HashMap<>();
        ph.put("%page%", String.valueOf(page));
        ph.put("%pages%", String.valueOf(pages));
        ph.put("%prev_page%", String.valueOf(Math.max(1, page - 1)));
        ph.put("%next_page%", String.valueOf(Math.min(pages, page + 1)));
        ph.put("%per_page%", String.valueOf(perPage));
        ph.put("%total_warps%", String.valueOf(total));

        if (page > 1) inv.setItem(config.warpsNavPrevSlot(), config.buildItem(config.warpsNavPrevItem(), ph));
        inv.setItem(config.warpsNavPageSlot(), config.buildItem(config.warpsNavPageItem(), ph));
        if (page < pages) inv.setItem(config.warpsNavNextSlot(), config.buildItem(config.warpsNavNextItem(), ph));
        inv.setItem(config.warpsNavCloseSlot(), config.buildItem(config.warpsNavCloseItem(), ph));
    }

    private ItemStack buildWarpItem(Player viewer, Warp warp, boolean canUse) {
        final String warpName = safeWarpName(warp);
        if (warpName == null || warpName.isBlank()) return null;

        // choose template (teleport vs locked)
        HHMConfig.MenuItemTemplate base = canUse ? config.warpsTeleportItem() : config.warpsLockedItem();

        // per-warp override item ONLY for usable warps
        ConfigurationSection override = config.warpOverrideSection(warpName);
        if (canUse && override != null && override.isConfigurationSection("item")) {
            base = HHMConfig.MenuItemTemplate.fromSection(override.getConfigurationSection("item"), base);
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("%warp_name%", warpName);
        ph.put("%warp_description%", safeWarpDescription(warp));

        // ✅ HuskHomes-derived placeholders (best-effort, no object @hash)
        ph.put("%warp_server%", safeWarpServer(warp));
        ph.put("%warp_world%", safeWarpWorld(warp));
        ph.put("%warp_dimension%", safeWarpDimension(warp));
        ph.put("%warp_coords%", safeWarpCoords(warp));

        ph.put("%warp_permission%", "huskhomes.warp." + warpName.toLowerCase(Locale.ROOT));

        return config.buildItem(base, ph);
    }

    // -------------------------
    // Click handling
    // -------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof WarpsHolder holder)) return;

        e.setCancelled(true);

        int rawSlot = e.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) return;

        // nav clicks
        if (config.warpsNavEnabled()) {
            if (rawSlot == config.warpsNavCloseSlot()) {
                playSound(p, config.warpsNavClickSound(), config.warpsTeleportClickSound());
                p.closeInventory();
                return;
            }
            if (rawSlot == config.warpsNavPrevSlot()) {
                playSound(p, config.warpsNavClickSound(), config.warpsTeleportClickSound());
                open(p, holder.page() - 1);
                return;
            }
            if (rawSlot == config.warpsNavNextSlot()) {
                playSound(p, config.warpsNavClickSound(), config.warpsTeleportClickSound());
                open(p, holder.page() + 1);
                return;
            }
            if (rawSlot == config.warpsNavPageSlot()) {
                playSound(p, config.warpsNavClickSound(), config.warpsTeleportClickSound());
                return;
            }
        }

        ItemStack clicked = top.getItem(rawSlot);
        if (clicked == null || clicked.getType() == Material.AIR) return;

        final HuskHomesAPI api;
        try {
            api = HuskHomesAPI.getInstance();
        } catch (Throwable t) {
            p.sendMessage(config.msgWithPrefix("messages.warps.load_failed", "&cFailed to load warps."));
            return;
        }

        final int page = holder.page();
        final int rows = top.getSize() / 9;

        List<Integer> itemSlots = config.warpsItemSlots(rows);
        if (itemSlots == null || itemSlots.isEmpty()) itemSlots = defaultWarpSlots(top.getSize());

        int slotIndex = itemSlots.indexOf(rawSlot);
        if (slotIndex < 0) return;

        final int perPage = Math.max(1, itemSlots.size());
        final int start = (Math.max(1, page) - 1) * perPage;

        api.getWarps().thenAccept(warps -> {
            final List<Warp> raw = (warps == null) ? List.of() : warps;

            // sort consistent with open()
            final List<Warp> sorted = new ArrayList<>();
            for (Warp w : raw) {
                String n = safeWarpName(w);
                if (n == null || n.isBlank()) continue;
                sorted.add(w);
            }
            sorted.sort(Comparator.comparing(w -> {
                String n = safeWarpName(w);
                return n == null ? "" : n.toLowerCase(Locale.ROOT);
            }));

            final WarpPermMode mode = determineMode(p, sorted);

            // visible consistent with open()
            final List<Warp> visible = new ArrayList<>();
            for (Warp w : sorted) {
                String name = safeWarpName(w);
                if (name == null || name.isBlank()) continue;

                if (mode == WarpPermMode.SHOW_ALL) {
                    visible.add(w);
                } else {
                    if (canUseWarp(p, name)) visible.add(w);
                }
            }

            int idx = start + slotIndex;
            if (idx < 0 || idx >= visible.size()) return;

            Warp targetWarp = visible.get(idx);
            String warpName = safeWarpName(targetWarp);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                if (warpName == null || warpName.isBlank()) return;

                // In SHOW_ALL mode, allow attempt (HH may still deny; we'll show teleport_failed if it throws)
                boolean canUse = (mode == WarpPermMode.SHOW_ALL) || canUseWarp(p, warpName);

                if (!canUse) {
                    playSound(p, config.warpsLockedClickSound(), config.warpsTeleportClickSound());
                    if (config.warpsLockedCloseOnClick()) {
                        p.closeInventory();
                    }
                    return;
                }

                try {
                    OnlineUser user = api.adaptUser(p);

                    api.teleportBuilder()
                            .teleporter(user)
                            .target(targetWarp)
                            .toTimedTeleport()
                            .execute();

                    playSound(p, config.warpsTeleportClickSound(), null);

                    if (config.warpsTeleportCloseOnClick()) {
                        p.closeInventory();
                    }
                } catch (Throwable t) {
                    p.sendMessage(config.msgWithPrefix("messages.warps.teleport_failed", "&cTeleport failed."));
                    if (config.debug()) t.printStackTrace();
                }
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    p.sendMessage(config.msgWithPrefix("messages.warps.load_failed", "&cFailed to load warps."));
                }
            });
            return null;
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof WarpsHolder)) return;
        // nothing needed
    }

    // -------------------------
    // Permission rules
    // -------------------------

    /**
     * STRICT per-warp rule:
     * player must have huskhomes.warp.<name> (or huskhomes.warp.*)
     */
    private boolean canUseWarp(Player p, String warpName) {
        if (p == null) return false;
        if (warpName == null || warpName.isBlank()) return false;

        String node = "huskhomes.warp." + warpName.toLowerCase(Locale.ROOT);
        return p.hasPermission("huskhomes.warp.*") || p.hasPermission(node);
    }

    /**
     * Mode selection:
     * - If player has ANY per-warp permission (huskhomes.warp.* OR huskhomes.warp.<any existing warp>),
     *   then we enforce PER_WARP filtering in the menu.
     * - If they have NONE of those, we SHOW_ALL warps in the menu.
     */
    private WarpPermMode determineMode(Player p, List<Warp> sortedWarps) {
        if (p == null) return WarpPermMode.SHOW_ALL;

        if (p.hasPermission("huskhomes.warp.*")) return WarpPermMode.PER_WARP;

        // Check if they have at least one huskhomes.warp.<warpname> for any existing warp
        if (sortedWarps != null) {
            for (Warp w : sortedWarps) {
                String name = safeWarpName(w);
                if (name == null || name.isBlank()) continue;
                String node = "huskhomes.warp." + name.toLowerCase(Locale.ROOT);
                if (p.hasPermission(node)) return WarpPermMode.PER_WARP;
            }
        }

        return WarpPermMode.SHOW_ALL;
    }

    // -------------------------
    // Helpers
    // -------------------------

    private List<Integer> defaultWarpSlots(int size) {
        Set<Integer> blocked = new HashSet<>();
        if (config.warpsNavEnabled()) {
            blocked.add(config.warpsNavPrevSlot());
            blocked.add(config.warpsNavPageSlot());
            blocked.add(config.warpsNavNextSlot());
            blocked.add(config.warpsNavCloseSlot());
        }

        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (!blocked.contains(i)) out.add(i);
        }
        return out;
    }

    private void playSound(Player p, String preferred, String fallback) {
        String soundName = (preferred != null && !preferred.isBlank()) ? preferred : fallback;
        if (soundName == null || soundName.isBlank()) return;
        try {
            Sound s = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
            p.playSound(p.getLocation(), s, 1f, 1f);
        } catch (Throwable ignored) {}
    }

    // -------------------------
    // Best-effort meta access (HH-compatible)
    // -------------------------

    private String safeWarpName(Warp w) {
        if (w == null) return null;
        try {
            Object meta = w.getClass().getField("meta").get(w);
            if (meta != null) {
                Object name = meta.getClass().getField("name").get(meta);
                if (name != null) return String.valueOf(name);
            }
        } catch (Throwable ignored) {}
        try {
            Method m = w.getClass().getMethod("getName");
            Object o = m.invoke(w);
            return o == null ? null : String.valueOf(o);
        } catch (Throwable ignored) {}
        return null;
    }

    private String safeWarpDescription(Warp w) {
        if (w == null) return "";
        try {
            Object meta = w.getClass().getField("meta").get(w);
            if (meta != null) {
                Object desc = meta.getClass().getField("description").get(meta);
                return desc == null ? "" : String.valueOf(desc);
            }
        } catch (Throwable ignored) {}
        try {
            Method m = w.getClass().getMethod("getDescription");
            Object o = m.invoke(w);
            return o == null ? "" : String.valueOf(o);
        } catch (Throwable ignored) {}
        return "";
    }

    private String safeWarpServer(Warp w) {
        if (w == null) return "";

        // Try getServer()
        try {
            Method m = w.getClass().getMethod("getServer");
            Object serverObj = m.invoke(w);
            String name = extractNameLike(serverObj);
            if (!name.isBlank()) return name;
        } catch (Throwable ignored) {}

        // Try meta.server
        try {
            Object meta = w.getClass().getField("meta").get(w);
            if (meta != null) {
                Object serverObj = meta.getClass().getField("server").get(meta);
                String name = extractNameLike(serverObj);
                if (!name.isBlank()) return name;
            }
        } catch (Throwable ignored) {}

        return "";
    }

    private String safeWarpWorld(Warp w) {
        if (w == null) return "";

        Object worldObj = null;

        // Try getWorld()
        try {
            Method m = w.getClass().getMethod("getWorld");
            worldObj = m.invoke(w);
        } catch (Throwable ignored) {}

        // Try meta.world
        if (worldObj == null) {
            try {
                Object meta = w.getClass().getField("meta").get(w);
                if (meta != null) worldObj = meta.getClass().getField("world").get(meta);
            } catch (Throwable ignored) {}
        }

        String name = extractNameLike(worldObj);
        return name == null ? "" : name;
    }

    private String safeWarpDimension(Warp w) {
        if (w == null) return "";

        // 1) Try getDimension()
        try {
            Method m = w.getClass().getMethod("getDimension");
            Object o = m.invoke(w);
            String s = (o == null) ? "" : String.valueOf(o);
            if (!s.isBlank() && !looksLikeObjectToString(s)) return s;
        } catch (Throwable ignored) {}

        // 2) Try meta.dimension or meta.environment
        try {
            Object meta = w.getClass().getField("meta").get(w);
            if (meta != null) {
                Object dim = null;
                try { dim = meta.getClass().getField("dimension").get(meta); } catch (Throwable ignored2) {}
                if (dim == null) {
                    try { dim = meta.getClass().getField("environment").get(meta); } catch (Throwable ignored2) {}
                }
                String s = extractNameLike(dim);
                if (!s.isBlank()) return s;
            }
        } catch (Throwable ignored) {}

        // 3) Fallback to Bukkit env by world name
        String worldName = safeWarpWorld(w);
        if (worldName == null || worldName.isBlank()) return "";

        World bw = Bukkit.getWorld(worldName);
        if (bw == null) return "";

        return switch (bw.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            default -> "Overworld";
        };
    }

    // ✅ Rounded nearest whole number for x/y/z
    private String safeWarpCoords(Warp w) {
        if (w == null) return "";

        Double x = null, y = null, z = null;

        // Try getX/Y/Z
        try {
            Method mx = w.getClass().getMethod("getX");
            Method my = w.getClass().getMethod("getY");
            Method mz = w.getClass().getMethod("getZ");
            Object ox = mx.invoke(w);
            Object oy = my.invoke(w);
            Object oz = mz.invoke(w);

            if (ox instanceof Number nx) x = nx.doubleValue();
            if (oy instanceof Number ny) y = ny.doubleValue();
            if (oz instanceof Number nz) z = nz.doubleValue();
        } catch (Throwable ignored) {}

        // Try meta.x/y/z if needed
        if (x == null || y == null || z == null) {
            try {
                Object meta = w.getClass().getField("meta").get(w);
                if (meta != null) {
                    Object ox = meta.getClass().getField("x").get(meta);
                    Object oy = meta.getClass().getField("y").get(meta);
                    Object oz = meta.getClass().getField("z").get(meta);

                    if (x == null && ox instanceof Number nx) x = nx.doubleValue();
                    if (y == null && oy instanceof Number ny) y = ny.doubleValue();
                    if (z == null && oz instanceof Number nz) z = nz.doubleValue();
                }
            } catch (Throwable ignored) {}
        }

        if (x == null || y == null || z == null) return "";

        long rx = Math.round(x);
        long ry = Math.round(y);
        long rz = Math.round(z);

        return rx + ", " + ry + ", " + rz;
    }

    /**
     * Extract a human-friendly name from HH objects (World/Server/etc).
     * Tries: getName(), name field, toString (only if not object-ish)
     */
    private String extractNameLike(Object obj) {
        if (obj == null) return "";
        if (obj instanceof String s) return s;

        try {
            Method m = obj.getClass().getMethod("getName");
            Object o = m.invoke(obj);
            if (o != null) return String.valueOf(o);
        } catch (Throwable ignored) {}

        try {
            Object o = obj.getClass().getField("name").get(obj);
            if (o != null) return String.valueOf(o);
        } catch (Throwable ignored) {}

        String s = String.valueOf(obj);
        return looksLikeObjectToString(s) ? "" : s;
    }

    private boolean looksLikeObjectToString(String s) {
        // Heuristic: "some.package.Class@deadbeef"
        return s != null && s.contains("@") && s.contains(".");
    }

    // -------------------------
    // Holder
    // -------------------------

    public static final class WarpsHolder implements InventoryHolder {
        private final UUID viewer;
        private final int page;

        public WarpsHolder(UUID viewer, int page) {
            this.viewer = viewer;
            this.page = page;
        }

        public UUID viewer() { return viewer; }
        public int page() { return page; }

        @Override
        public Inventory getInventory() {
            return null; // Bukkit ignores this for custom holders
        }
    }
}