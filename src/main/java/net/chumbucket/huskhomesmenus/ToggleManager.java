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

    // toggle for whether to use the GUI menu for TP requests (default ON)
    private final NamespacedKey keyTpMenu;

    // ✅ auto-accept incoming /tpa requests (default OFF)
    private final NamespacedKey keyTpAuto;

    public ToggleManager(JavaPlugin plugin) {
        this.keyTpa = new NamespacedKey(plugin, "tpa_on");
        this.keyTpahere = new NamespacedKey(plugin, "tpahere_on");
        this.keyTpMenu = new NamespacedKey(plugin, "tpmenu_on");
        this.keyTpAuto = new NamespacedKey(plugin, "tpauto_on");
    }

    public boolean isTpaOn(Player p) {
        return getFlagDefaultTrue(p, keyTpa);
    }

    public boolean isTpahereOn(Player p) {
        return getFlagDefaultTrue(p, keyTpahere);
    }

    // default ON (your current behavior)
    public boolean isTpMenuOn(Player p) {
        return getFlagDefaultTrue(p, keyTpMenu);
    }

    // ✅ default OFF
    public boolean isTpAutoOn(Player p) {
        return getFlagDefaultFalse(p, keyTpAuto);
    }

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

    public void setTpMenuOn(Player p, boolean on) {
        setFlag(p, keyTpMenu, on);
    }

    public void setTpAutoOn(Player p, boolean on) {
        setFlag(p, keyTpAuto, on);
    }

    private boolean getFlagDefaultTrue(Player p, NamespacedKey key) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Byte stored = pdc.get(key, PersistentDataType.BYTE);
        if (stored == null) return true; // default ON
        return stored == (byte) 1;
    }

    private boolean getFlagDefaultFalse(Player p, NamespacedKey key) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Byte stored = pdc.get(key, PersistentDataType.BYTE);
        if (stored == null) return false; // ✅ default OFF
        return stored == (byte) 1;
    }

    private void setFlag(Player p, NamespacedKey key, boolean value) {
        p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, value ? (byte) 1 : (byte) 0);
    }
}