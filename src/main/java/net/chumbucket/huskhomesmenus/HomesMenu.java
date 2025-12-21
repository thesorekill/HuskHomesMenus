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
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.position.Home;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HomesMenu implements Listener {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final HHMConfig config;

    // For quickly verifying “is this our GUI?” on close/click
    public static final class HomesHolder implements InventoryHolder {
        private final UUID owner;
        private final int page;
        private final int maxHomes;
        private final Map<Integer, String> slotToHomeName = new HashMap<>();

        private HomesHolder(UUID owner, int page, int maxHomes) {
            this.owner = owner;
            this.page = page;
            this.maxHomes = maxHomes;
        }

        public UUID owner() { return owner; }
        public int page() { return page; }
        public int maxHomes() { return maxHomes; }
        public Map<Integer, String> slotToHomeName() { return slotToHomeName; }

        @Override
        public Inventory getInventory() { return null; } // not used
    }

    // Track open menus so we can close them on reload if desired
    private final Set<UUID> openMenus = ConcurrentHashMap.newKeySet();

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

        // Get max homes (HuskHomes config + permission override)
        final int maxHomes = getMaxHomes(player);

        // HuskHomes API homes fetch is async (CompletableFuture)
        final HuskHomesAPI api;
        try {
            api = HuskHomesAPI.getInstance();
        } catch (Throwable t) {
            player.sendMessage(AMP.deserialize(config.prefix()).append(AMP.deserialize("&cHuskHomes API not available.")));
            return;
        }

        final OnlineUser user = api.adaptUser(player);

        api.getUserHomes(user).thenAccept(homeList -> {
            // Switch back to main thread before touching Bukkit inventories
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                buildAndOpen(player, page, maxHomes, homeList == null ? List.of() : homeList);
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(AMP.deserialize(config.prefix()).append(AMP.deserialize("&cFailed to load homes.")));
                }
            });
            if (config.debug()) {
                plugin.getLogger().warning("Failed to load homes: " + ex.getMessage());
            }
            return null;
        });
    }

    // ------------------------------------------------------------
    // Inventory build
    // ------------------------------------------------------------

    private void buildAndOpen(Player player, int page, int maxHomes, List<Home> homes) {
        // Sort homes by name for consistent ordering
        List<Home> sorted = new ArrayList<>(homes);
        sorted.sort(Comparator.comparing(h -> safeLower(h.getName())));

        // Capacity per page: if maxHomes is small, we can size dynamically
        // We reserve 1 bottom row for controls when we need paging or want a consistent UX.
        final int pageCapacity = 45; // 5 rows
        final boolean needsPaging = maxHomes > 45 || sorted.size() > 45;

        final int size;
        if (needsPaging) {
            size = 54; // 6 rows (5 content + 1 controls)
        } else {
            // Build size based on maxHomes, plus 1 control row.
            int rowsForHomes = (int) Math.ceil(Math.max(1, maxHomes) / 9.0);
            rowsForHomes = Math.max(1, rowsForHomes);
            int rowsTotal = Math.min(6, rowsForHomes + 1); // +1 controls
            size = rowsTotal * 9;
        }

        HomesHolder holder = new HomesHolder(player.getUniqueId(), Math.max(0, page), maxHomes);

        String title = "&aHomes &7(" + (sorted.size()) + "/" + maxHomes + ")";
        Inventory inv = Bukkit.createInventory(holder, size, AMP.deserialize(title));

        // Figure out which slice of homes to show for this page
        int cap = needsPaging ? pageCapacity : (size - 9);
        cap = Math.max(1, cap);

        int maxPages = Math.max(1, (int) Math.ceil(Math.max(0, maxHomes) / (double) cap));
        int safePage = Math.min(holder.page(), maxPages - 1);

        int start = safePage * cap;
        int end = Math.min(start + cap, sorted.size());

        // Place home items
        int slot = 0;
        for (int i = start; i < end && slot < cap; i++) {
            Home h = sorted.get(i);
            String name = h.getName();

            ItemStack item = new ItemStack(Material.WHITE_BED);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(AMP.deserialize("&a" + name));
            List<String> lore = new ArrayList<>();
            lore.add("&7Click to teleport");
            lore.add("&8/huskhomes:home " + name);
            meta.lore(lore.stream().map(AMP::deserialize).toList());
            item.setItemMeta(meta);

            inv.setItem(slot, item);
            holder.slotToHomeName().put(slot, name);
            slot++;
        }

        // Fill remaining allowed slots (up to maxHomes) with “Empty”
        // Only fill empties on this page.
        int totalSlotsThisPage = Math.min(cap, Math.max(0, maxHomes - start));
        for (int s = slot; s < totalSlotsThisPage; s++) {
            inv.setItem(s, emptySlotItem());
        }

        // Controls row (bottom row)
        int lastRowStart = size - 9;

        // Prev/Next if paging
        if (needsPaging) {
            if (safePage > 0) inv.setItem(lastRowStart + 2, navItem(Material.ARROW, "&ePrevious Page", "&7Go to page " + safePage));
            if (safePage < maxPages - 1) inv.setItem(lastRowStart + 6, navItem(Material.ARROW, "&eNext Page", "&7Go to page " + (safePage + 2)));
            inv.setItem(lastRowStart + 4, navItem(Material.PAPER, "&fPage &a" + (safePage + 1) + "&f/&a" + maxPages,
                    "&7Max homes: &f" + maxHomes));
        }

        inv.setItem(lastRowStart + 8, navItem(Material.BARRIER, "&cClose", "&7Close this menu"));

        player.openInventory(inv);
        openMenus.add(player.getUniqueId());
    }

    private ItemStack emptySlotItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(AMP.deserialize("&7Empty Home Slot"));
        meta.lore(List.of(
                AMP.deserialize("&8Create one with:"),
                AMP.deserialize("&f/sethome <name>")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack navItem(Material mat, String name, String loreLine) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(AMP.deserialize(name));
        meta.lore(List.of(AMP.deserialize(loreLine)));
        item.setItemMeta(meta);
        return item;
    }

    private String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------
    // Click handling
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof HomesHolder holder)) return;

        // Hard-cancel all interaction in our GUI
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;

        // Close
        if (slot == top.getSize() - 1) {
            p.closeInventory();
            return;
        }

        // Paging controls (only if they exist)
        int lastRowStart = top.getSize() - 9;
        if (slot == lastRowStart + 2 && top.getItem(slot) != null) {
            open(p, Math.max(0, holder.page() - 1));
            return;
        }
        if (slot == lastRowStart + 6 && top.getItem(slot) != null) {
            open(p, holder.page() + 1);
            return;
        }

        // Home click
        String homeName = holder.slotToHomeName().get(slot);
        if (homeName == null || homeName.isBlank()) return;

        // Run HuskHomes namespaced command so we never conflict
        try {
            Bukkit.dispatchCommand(p, "huskhomes:home " + homeName);
        } catch (Throwable ignored) {}
        p.closeInventory();
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

    // ------------------------------------------------------------
    // Max homes logic: read HuskHomes config + permission override
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
            // As you referenced:
            // general:
            //   max_homes: 10
            int v = yml.getInt("general.max_homes", 10);
            return Math.max(1, v);
        } catch (Throwable t) {
            return 10;
        }
    }

    private int readMaxHomesFromPermissions(Player p) {
        int best = 0;
        try {
            p.getEffectivePermissions().forEach(perm -> {
                // no-op here; lambda needs effectively final if we want to store
            });
        } catch (Throwable ignored) {}

        // Simple, safe scan
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
