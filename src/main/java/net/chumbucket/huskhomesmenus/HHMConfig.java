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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration; // ✅ NEW
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Lightweight config wrapper for HuskHomesMenus.
 * Only exposes settings that are currently used by the codebase.
 */
public final class HHMConfig {

    private final JavaPlugin plugin;

    // Supports legacy & color codes (e.g., "&cHello")
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public HHMConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean debug() {
        return plugin.getConfig().getBoolean("debug", false);
    }

    public boolean proxyEnabled() {
        // ✅ default false to match your config.yml
        return plugin.getConfig().getBoolean("proxy.enabled", false);
    }

    public String backendName() {
        return plugin.getConfig().getString("backend_name", "Loading...");
    }

    /**
     * Prefix as a legacy-colored String (kept for backwards compatibility).
     */
    public String prefix() {
        return color(plugin.getConfig().getString("messages.prefix", ""));
    }

    /**
     * Prefix as a Component (preferred for sendMessage(Component)).
     */
    public Component prefixComponent() {
        return AMP.deserialize(plugin.getConfig().getString("messages.prefix", ""));
    }

    public boolean isEnabled(String enabledPath, boolean def) {
        return plugin.getConfig().getBoolean(enabledPath, def);
    }

    public String raw(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    /**
     * Message with prefix as a legacy-colored String (kept for backwards compatibility).
     */
    public String msgWithPrefix(String path, String def) {
        return prefix() + color(plugin.getConfig().getString(path, def));
    }

    /**
     * Message with prefix as a Component (preferred for sendMessage(Component)).
     */
    public Component msgWithPrefixComponent(String path, String def) {
        Component pfx = prefixComponent();
        Component msg = AMP.deserialize(plugin.getConfig().getString(path, def));
        return pfx.append(msg);
    }

    /**
     * Converts legacy &-colored text into a normalized legacy-colored String.
     * (Useful when older parts of the code still build Strings.)
     */
    public String color(String s) {
        if (s == null) return "";
        return AMP.serialize(AMP.deserialize(s));
    }

    /**
     * Converts legacy &-colored text into a Component.
     */
    public Component colorComponent(String s) {
        if (s == null) return Component.empty();
        return AMP.deserialize(s);
    }

    public ConfigurationSection section(String path) {
        return plugin.getConfig().getConfigurationSection(path);
    }

    // ---------------------------------------------------------------------
    // Homes menu config helpers
    // ---------------------------------------------------------------------

    public boolean homesMenuEnabled() {
        return plugin.getConfig().getBoolean("menus.homes.enabled", true);
    }

    public String homesTitle() {
        return plugin.getConfig().getString("menus.homes.title", "&7HOMES");
    }

    public int homesRows() {
        int r = plugin.getConfig().getInt("menus.homes.rows", 4);
        return Math.max(1, Math.min(6, r));
    }

    public boolean homesUseFiller() {
        return plugin.getConfig().getBoolean("menus.homes.use_filler", false);
    }

    public MenuItemTemplate homesFillerItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.filler"),
                new MenuItemTemplate(Material.GRAY_STAINED_GLASS_PANE, " ", Collections.emptyList(), false, 0)
        );
    }

    // Layout
    public int homesLayoutColumns() {
        int c = plugin.getConfig().getInt("menus.homes.layout.columns", 5);
        return Math.max(1, Math.min(9, c));
    }

    public int homesTeleportRowStartSlot() {
        return plugin.getConfig().getInt("menus.homes.layout.teleport_row_start_slot", 12);
    }

    public int homesActionRowStartSlot() {
        return plugin.getConfig().getInt("menus.homes.layout.action_row_start_slot", 21);
    }

    public int homesActionRowOffsetRows() {
        int off = plugin.getConfig().getInt("menus.homes.layout.action_row_offset_rows", 1);
        return Math.max(1, off);
    }

    public int homesLineStrideRows() {
        int stride = plugin.getConfig().getInt("menus.homes.layout.line_stride_rows", 2);
        return Math.max(1, stride);
    }

    // Home item templates

    /** Bed shown when a home EXISTS (click to teleport). */
    public MenuItemTemplate homesTeleportItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.home_items.teleport"),
                new MenuItemTemplate(Material.BLUE_BED, "&f%home_name%", List.of("&7Click to teleport"), false, 0)
        );
    }

    /** Bed shown when a home does NOT exist (above the empty_action item). */
    public MenuItemTemplate homesEmptyBedItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.home_items.empty_bed"),
                new MenuItemTemplate(Material.WHITE_BED, " ", Collections.emptyList(), false, 0)
        );
    }

    /** Action shown when NO home exists (save location). */
    public MenuItemTemplate homesEmptyActionItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.home_items.empty_action"),
                new MenuItemTemplate(Material.GRAY_DYE, "&fNO HOME SET", List.of("&7- Click to save your location"), false, 0)
        );
    }

    /** Action shown when home exists (delete). */
    public MenuItemTemplate homesDeleteActionItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.home_items.delete_action"),
                new MenuItemTemplate(Material.LIGHT_BLUE_DYE, "&b%home_name%", List.of("&fClick to delete %home_name%"), false, 0)
        );
    }

    // Navigation
    public boolean homesNavEnabled() {
        return plugin.getConfig().getBoolean("menus.homes.navigation.enabled", true);
    }

    public int homesNavPrevSlot() { return plugin.getConfig().getInt("menus.homes.navigation.prev_slot", 29); }
    public int homesNavPageSlot() { return plugin.getConfig().getInt("menus.homes.navigation.page_slot", 31); }
    public int homesNavNextSlot() { return plugin.getConfig().getInt("menus.homes.navigation.next_slot", 33); }
    public int homesNavCloseSlot() { return plugin.getConfig().getInt("menus.homes.navigation.close_slot", 35); }

    public MenuItemTemplate homesNavPrevItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.navigation.prev_item"),
                new MenuItemTemplate(Material.ARROW, "&ePrevious", List.of("&7Go to page %prev_page%"), false, 0)
        );
    }

    public MenuItemTemplate homesNavPageItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.navigation.page_item"),
                new MenuItemTemplate(Material.PAPER, "&fPage &a%page%&f/&a%pages%", List.of("&7Max homes: &f%max_homes%"), false, 0)
        );
    }

    public MenuItemTemplate homesNavNextItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.navigation.next_item"),
                new MenuItemTemplate(Material.ARROW, "&eNext", List.of("&7Go to page %next_page%"), false, 0)
        );
    }

    public MenuItemTemplate homesNavCloseItem() {
        return MenuItemTemplate.fromSection(
                plugin.getConfig().getConfigurationSection("menus.homes.navigation.close_item"),
                new MenuItemTemplate(Material.BARRIER, "&cClose", List.of("&7Close this menu"), false, 0)
        );
    }

    // ---------------------------------------------------------------------
    // ✅ NO-ITALICS FIX (the real fix)
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

    /**
     * Build an ItemStack from a template + placeholders.
     * Placeholders are applied to name and lore.
     */
    public ItemStack buildItem(MenuItemTemplate t, Map<String, String> placeholders) {
        if (t == null) return null;

        final Material mat = (t.material() == null) ? Material.STONE : t.material();
        final ItemStack item = new ItemStack(mat);

        final ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        final String name = applyPlaceholders(t.name(), placeholders);
        final String safeName = (name == null || name.isBlank()) ? " " : name;

        meta.displayName(deitalicize(AMP.deserialize(safeName)));

        final List<String> rawLore = (t.lore() == null) ? Collections.emptyList() : t.lore();
        if (!rawLore.isEmpty()) {
            final List<Component> loreComponents = new ArrayList<>(rawLore.size());
            for (String line : rawLore) {
                String applied = applyPlaceholders(line, placeholders);
                if (applied != null && !applied.isBlank()) {
                    loreComponents.add(deitalicize(AMP.deserialize(applied)));
                }
            }
            if (!loreComponents.isEmpty()) meta.lore(loreComponents);
        }

        if (t.customModelData() > 0) {
            try { meta.setCustomModelData(t.customModelData()); } catch (Throwable ignored) {}
        }

        if (t.glow()) applyGlow(meta);

        try { meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES); } catch (Throwable ignored) {}

        item.setItemMeta(meta);
        return item;
    }

    private void applyGlow(ItemMeta meta) {
        try {
            Enchantment ench = resolveGlowEnchantNoDeprecation();
            if (ench == null) return;

            meta.addEnchant(ench, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } catch (Throwable ignored) {}
    }

    /**
     * Resolve an enchantment for glow WITHOUT referencing deprecated methods/fields at compile time.
     */
    private Enchantment resolveGlowEnchantNoDeprecation() {
        try {
            Field f = Enchantment.class.getField("UNBREAKING");
            Object o = f.get(null);
            if (o instanceof Enchantment e) return e;
        } catch (Throwable ignored) {}

        try {
            Method m = Enchantment.class.getMethod("getByKey", NamespacedKey.class);
            Object o = m.invoke(null, NamespacedKey.minecraft("unbreaking"));
            if (o instanceof Enchantment e) return e;
        } catch (Throwable ignored) {}

        try {
            Method m = Enchantment.class.getMethod("getByName", String.class);
            Object o = m.invoke(null, "UNBREAKING");
            if (o instanceof Enchantment e) return e;
        } catch (Throwable ignored) {}

        return null;
    }

    private String applyPlaceholders(String s, Map<String, String> ph) {
        if (s == null) return "";
        String out = s;
        if (ph != null && !ph.isEmpty()) {
            for (Map.Entry<String, String> e : ph.entrySet()) {
                String k = e.getKey();
                if (k == null) continue;
                String v = e.getValue();
                out = out.replace(k, v == null ? "" : v);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Template
    // ---------------------------------------------------------------------

    public static final class MenuItemTemplate {
        private final Material material;
        private final String name;
        private final List<String> lore;
        private final boolean glow;
        private final int customModelData;

        public MenuItemTemplate(Material material, String name, List<String> lore, boolean glow, int customModelData) {
            this.material = material;
            this.name = name == null ? "" : name;
            this.lore = lore == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(lore));
            this.glow = glow;
            this.customModelData = customModelData;
        }

        public Material material() { return material; }
        public String name() { return name; }
        public List<String> lore() { return lore; }
        public boolean glow() { return glow; }
        public int customModelData() { return customModelData; }

        public static MenuItemTemplate fromSection(ConfigurationSection sec, MenuItemTemplate def) {
            if (sec == null) return def;

            Material mat = def.material;
            String matName = sec.getString("material", null);
            if (matName != null && !matName.isBlank()) {
                try { mat = Material.valueOf(matName.toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) {}
            }

            String name = sec.getString("name", def.name);

            List<String> lore = sec.getStringList("lore");
            if (lore == null || lore.isEmpty()) lore = def.lore;

            boolean glow = sec.getBoolean("glow", def.glow);
            int cmd = sec.getInt("custom_model_data", def.customModelData);

            return new MenuItemTemplate(mat, name, lore, glow, cmd);
        }
    }
}
