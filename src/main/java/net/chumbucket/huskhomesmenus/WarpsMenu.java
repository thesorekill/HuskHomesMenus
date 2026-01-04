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

            // ✅ Folia-safe: hop back onto the player's thread via Sched
            Sched.run(player, () -> {
                if (!player.isOnline()) return;

                List<Integer> itemSlots = config.warpsItemSlots(rows);
                if (itemSlots == null || itemSlots.isEmpty()) itemSlots = defaultWarpSlots(size);

                // Decide permission mode for this viewer
                final WarpPermMode mode = determineMode(player, sorted);

                // Visible list:
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
            // ✅ Folia-safe: message on player's thread
            Sched.run(player, () -> {
                if (player != null && player.isOnline()) {
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

        // -------------------------
        // NAV clicks + configured commands
        // -------------------------
        if (config.warpsNavEnabled()) {
            if (rawSlot == config.warpsNavCloseSlot()) {
                playSound(p, config.warpsNavClickSound(), config.warpsTeleportClickSound());

                // ✅ run commands from your config:
                // menus.warps.navigation.close_item.click.player_commands / console_commands
                Map<String, String> navPh = new HashMap<>();
                navPh.put("%player%", p.getName());
                navPh.put("%page%", String.valueOf(holder.page()));

                runPlayerCommands(p, plugin.getConfig().getStringList("menus.warps.navigation.close_item.click.player_commands"), navPh);
                runConsoleCommands(plugin.getConfig().getStringList("menus.warps.navigation.close_item.click.console_commands"), navPh);

                boolean close = plugin.getConfig().getBoolean("menus.warps.navigation.close_item.click.close_menu", true);
                if (close) p.closeInventory();
                return;
            }

            if (rawSlot == config.warpsNavPrevSlot()) {
                playSound(p, config.warpsNavClickSound(), config.warpsTeleportClickSound());

                Map<String, String> navPh = new HashMap<>();
                navPh.put("%player%", p.getName());
                navPh.put("%page%", String.valueOf(holder.page()));

                runPlayerCommands(p, plugin.getConfig().getStringList("menus.warps.navigation.prev_item.click.player_commands"), navPh);
                runConsoleCommands(plugin.getConfig().getStringList("menus.warps.navigation.prev_item.click.console_commands"), navPh);

                open(p, holder.page() - 1);
                return;
            }

            if (rawSlot == config.warpsNavNextSlot()) {
                playSound(p, config.warpsNavClickSound(), config.warpsTeleportClickSound());

                Map<String, String> navPh = new HashMap<>();
                navPh.put("%player%", p.getName());
                navPh.put("%page%", String.valueOf(holder.page()));

                runPlayerCommands(p, plugin.getConfig().getStringList("menus.warps.navigation.next_item.click.player_commands"), navPh);
                runConsoleCommands(plugin.getConfig().getStringList("menus.warps.navigation.next_item.click.console_commands"), navPh);

                open(p, holder.page() + 1);
                return;
            }

            if (rawSlot == config.warpsNavPageSlot()) {
                playSound(p, config.warpsNavClickSound(), config.warpsTeleportClickSound());

                Map<String, String> navPh = new HashMap<>();
                navPh.put("%player%", p.getName());
                navPh.put("%page%", String.valueOf(holder.page()));

                runPlayerCommands(p, plugin.getConfig().getStringList("menus.warps.navigation.page_item.click.player_commands"), navPh);
                runConsoleCommands(plugin.getConfig().getStringList("menus.warps.navigation.page_item.click.console_commands"), navPh);

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

            // ✅ Folia-safe: hop back onto the player's thread via Sched
            Sched.run(p, () -> {
                if (!p.isOnline()) return;
                if (warpName == null || warpName.isBlank()) return;

                Map<String, String> ph = new HashMap<>();
                ph.put("%player%", p.getName());
                ph.put("%warp_name%", warpName);
                ph.put("%warp_description%", safeWarpDescription(targetWarp));
                ph.put("%warp_server%", safeWarpServer(targetWarp));
                ph.put("%warp_world%", safeWarpWorld(targetWarp));
                ph.put("%warp_dimension%", safeWarpDimension(targetWarp));
                ph.put("%warp_coords%", safeWarpCoords(targetWarp));
                ph.put("%warp_permission%", "huskhomes.warp." + warpName.toLowerCase(Locale.ROOT));

                boolean canUse = (mode == WarpPermMode.SHOW_ALL) || canUseWarp(p, warpName);

                if (!canUse) {
                    playSound(p, config.warpsLockedClickSound(), config.warpsTeleportClickSound());

                    // ✅ LOCKED click actions (override first, then global)
                    // global: menus.warps.warp_items.locked.click.*
                    runClickActions(p, warpName, "menus.warps.warp_items.locked.click", ph);

                    boolean close = plugin.getConfig().getBoolean(
                            "menus.warps.warp_items.locked.click.close_menu", false);
                    if (close) p.closeInventory();
                    return;
                }

                try {
                    // ✅ TELEPORT click actions (override first, then global)
                    // global: menus.warps.warp_items.teleport.click.*
                    runClickActions(p, warpName, "menus.warps.warp_items.teleport.click", ph);

                    OnlineUser user = api.adaptUser(p);
                    api.teleportBuilder()
                            .teleporter(user)
                            .target(targetWarp)
                            .toTimedTeleport()
                            .execute();

                    playSound(p, config.warpsTeleportClickSound(), null);

                    boolean close = plugin.getConfig().getBoolean(
                            "menus.warps.warp_items.teleport.click.close_menu", true);
                    if (close) p.closeInventory();

                } catch (Throwable t) {
                    p.sendMessage(config.msgWithPrefix("messages.warps.teleport_failed", "&cTeleport failed."));
                    if (config.debug()) t.printStackTrace();
                }
            });
        }).exceptionally(ex -> {
            // ✅ Folia-safe: message on player's thread
            Sched.run(p, () -> {
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
    // ✅ Click actions (player/console) + per-warp override support
    // -------------------------

    private void runClickActions(Player p, String warpName, String globalClickBasePath, Map<String, String> ph) {
        if (p == null || globalClickBasePath == null) return;

        // 1) per-warp override click path (only if present)
        // menus.warps.warp_overrides.<warp>.item.click.player_commands
        if (warpName != null && !warpName.isBlank()) {
            String key = warpName.toLowerCase(Locale.ROOT);
            String overrideBase = "menus.warps.warp_overrides." + key + ".item.click";
            runPlayerCommands(p, plugin.getConfig().getStringList(overrideBase + ".player_commands"), ph);
            runConsoleCommands(plugin.getConfig().getStringList(overrideBase + ".console_commands"), ph);
        }

        // 2) global click path
        runPlayerCommands(p, plugin.getConfig().getStringList(globalClickBasePath + ".player_commands"), ph);
        runConsoleCommands(plugin.getConfig().getStringList(globalClickBasePath + ".console_commands"), ph);
    }

    private void runPlayerCommands(Player p, List<String> cmds, Map<String, String> ph) {
        if (p == null || cmds == null || cmds.isEmpty()) return;

        for (String raw : cmds) {
            if (raw == null) continue;
            String cmd = applyPlaceholders(raw, ph);
            if (cmd == null) continue;

            cmd = cmd.trim();
            if (cmd.isEmpty()) continue;

            if (cmd.startsWith("/")) cmd = cmd.substring(1);

            try {
                Bukkit.dispatchCommand(p, cmd);
            } catch (Throwable ignored) { }
        }
    }

    private void runConsoleCommands(List<String> cmds, Map<String, String> ph) {
        if (cmds == null || cmds.isEmpty()) return;

        for (String raw : cmds) {
            if (raw == null) continue;
            String cmd = applyPlaceholders(raw, ph);
            if (cmd == null) continue;

            cmd = cmd.trim();
            if (cmd.isEmpty()) continue;

            if (cmd.startsWith("/")) cmd = cmd.substring(1);

            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable ignored) { }
        }
    }

    private String applyPlaceholders(String s, Map<String, String> ph) {
        if (s == null) return null;
        String out = s;

        if (ph != null && !ph.isEmpty()) {
            for (Map.Entry<String, String> e : ph.entrySet()) {
                if (e.getKey() == null) continue;
                out = out.replace(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }

        return out;
    }

    // -------------------------
    // Permission rules
    // -------------------------

    private boolean canUseWarp(Player p, String warpName) {
        if (p == null) return false;
        if (warpName == null || warpName.isBlank()) return false;

        String node = "huskhomes.warp." + warpName.toLowerCase(Locale.ROOT);
        return p.hasPermission("huskhomes.warp.*") || p.hasPermission(node);
    }

    private WarpPermMode determineMode(Player p, List<Warp> sortedWarps) {
        if (p == null) return WarpPermMode.SHOW_ALL;

        if (p.hasPermission("huskhomes.warp.*")) return WarpPermMode.PER_WARP;

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

        try {
            Method m = w.getClass().getMethod("getServer");
            Object serverObj = m.invoke(w);
            String name = extractNameLike(serverObj);
            if (!name.isBlank()) return name;
        } catch (Throwable ignored) {}

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

        try {
            Method m = w.getClass().getMethod("getWorld");
            worldObj = m.invoke(w);
        } catch (Throwable ignored) {}

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

        try {
            Method m = w.getClass().getMethod("getDimension");
            Object o = m.invoke(w);
            String s = (o == null) ? "" : String.valueOf(o);
            if (!s.isBlank() && !looksLikeObjectToString(s)) return s;
        } catch (Throwable ignored) {}

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

    private String safeWarpCoords(Warp w) {
        if (w == null) return "";

        Double x = null, y = null, z = null;

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
