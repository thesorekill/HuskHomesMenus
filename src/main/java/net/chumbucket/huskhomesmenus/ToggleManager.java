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

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ToggleManager {

    private final NamespacedKey keyTpa;
    private final NamespacedKey keyTpahere;

    private final NamespacedKey keyTpMenu;
    private final NamespacedKey keyTpAuto;

    // ✅ toggle for whether to use the Homes GUI intercept (default ON)
    private final NamespacedKey keyHomeMenu;

    // ✅ toggle for whether to use the Warps GUI intercept (default ON)
    private final NamespacedKey keyWarpMenu;

    public ToggleManager(JavaPlugin plugin) {
        // ultra-safe: plugin should never be null, but avoid NPE if something calls this wrong
        if (plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null");
        }

        this.keyTpa = new NamespacedKey(plugin, "tpa_on");
        this.keyTpahere = new NamespacedKey(plugin, "tpahere_on");
        this.keyTpMenu = new NamespacedKey(plugin, "tpmenu_on");
        this.keyTpAuto = new NamespacedKey(plugin, "tpauto_on");
        this.keyHomeMenu = new NamespacedKey(plugin, "homemenu_on");
        this.keyWarpMenu = new NamespacedKey(plugin, "warpmenu_on");
    }

    // -------------------------
    // Reads
    // -------------------------

    public boolean isTpaOn(Player p) {
        return getFlag(p, keyTpa, true);
    }

    public boolean isTpahereOn(Player p) {
        return getFlag(p, keyTpahere, true);
    }

    public boolean isTpMenuOn(Player p) {
        return getFlag(p, keyTpMenu, true);
    }

    public boolean isTpAutoOn(Player p) {
        return getFlag(p, keyTpAuto, false);
    }

    public boolean isHomeMenuOn(Player p) {
        return getFlag(p, keyHomeMenu, true);
    }

    public boolean isWarpMenuOn(Player p) {
        return getFlag(p, keyWarpMenu, true);
    }

    // -------------------------
    // Toggles
    // -------------------------

    public boolean toggleTpa(Player p) {
        boolean now = !isTpaOn(p);
        setFlag(p, keyTpa, now);
        return now;
    }

    public boolean toggleTpahere(Player p) {
        boolean now = !isTpahereOn(p);
        setFlag(p, keyTpahere, now);
        return now;
    }

    public boolean toggleTpMenu(Player p) {
        boolean now = !isTpMenuOn(p);
        setFlag(p, keyTpMenu, now);
        return now;
    }

    public boolean toggleTpAuto(Player p) {
        boolean now = !isTpAutoOn(p);
        setFlag(p, keyTpAuto, now);
        return now;
    }

    public boolean toggleHomeMenu(Player p) {
        boolean now = !isHomeMenuOn(p);
        setFlag(p, keyHomeMenu, now);
        return now;
    }

    public boolean toggleWarpMenu(Player p) {
        boolean now = !isWarpMenuOn(p);
        setFlag(p, keyWarpMenu, now);
        return now;
    }

    // -------------------------
    // Setters
    // -------------------------

    public void setTpMenuOn(Player p, boolean on) {
        setFlag(p, keyTpMenu, on);
    }

    public void setTpAutoOn(Player p, boolean on) {
        setFlag(p, keyTpAuto, on);
    }

    public void setHomeMenuOn(Player p, boolean on) {
        setFlag(p, keyHomeMenu, on);
    }

    public void setWarpMenuOn(Player p, boolean on) {
        setFlag(p, keyWarpMenu, on);
    }

    // -------------------------
    // Internal helpers
    // -------------------------

    private boolean getFlag(Player p, NamespacedKey key, boolean defaultValue) {
        if (p == null || key == null) return defaultValue;

        final PersistentDataContainer pdc = p.getPersistentDataContainer();
        final Byte stored = pdc.get(key, PersistentDataType.BYTE);

        if (stored == null) return defaultValue;
        return stored == (byte) 1;
    }

    private void setFlag(Player p, NamespacedKey key, boolean value) {
        if (p == null || key == null) return;
        p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, value ? (byte) 1 : (byte) 0);
    }
}