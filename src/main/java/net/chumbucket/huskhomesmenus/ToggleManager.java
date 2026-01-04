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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ToggleManager {

    private final NamespacedKey keyTpa;
    private final NamespacedKey keyTpahere;

    private final NamespacedKey keyTpMenu;
    private final NamespacedKey keyTpAuto;

    // ✅ toggle for whether to use the Homes GUI intercept (default ON)
    private final NamespacedKey keyHomeMenu;

    // ✅ toggle for whether to use the Warps GUI intercept (default ON)
    private final NamespacedKey keyWarpMenu;

    // ------------------------------------------------------------
    // ✅ Folia-safe cache (avoid touching PDC off-region-thread)
    // ------------------------------------------------------------
    private final Map<UUID, State> cache = new ConcurrentHashMap<>();

    private static final class State {
        volatile boolean tpa = true;
        volatile boolean tpahere = true;
        volatile boolean tpmenu = true;
        volatile boolean tpauto = false;
        volatile boolean homemenu = true;
        volatile boolean warpmenu = true;
    }

    public ToggleManager(JavaPlugin plugin) {
        if (plugin == null) throw new IllegalArgumentException("plugin cannot be null");

        this.keyTpa = new NamespacedKey(plugin, "tpa_on");
        this.keyTpahere = new NamespacedKey(plugin, "tpahere_on");
        this.keyTpMenu = new NamespacedKey(plugin, "tpmenu_on");
        this.keyTpAuto = new NamespacedKey(plugin, "tpauto_on");
        this.keyHomeMenu = new NamespacedKey(plugin, "homemenu_on");
        this.keyWarpMenu = new NamespacedKey(plugin, "warpmenu_on");
    }

    // -------------------------
    // Reads (cache-first)
    // -------------------------

    public boolean isTpaOn(Player p) {
        State s = state(p);
        if (s == null) return true;
        refreshAsync(p);
        return s.tpa;
    }

    public boolean isTpahereOn(Player p) {
        State s = state(p);
        if (s == null) return true;
        refreshAsync(p);
        return s.tpahere;
    }

    public boolean isTpMenuOn(Player p) {
        State s = state(p);
        if (s == null) return true;
        refreshAsync(p);
        return s.tpmenu;
    }

    public boolean isTpAutoOn(Player p) {
        State s = state(p);
        if (s == null) return false;
        refreshAsync(p);
        return s.tpauto;
    }

    public boolean isHomeMenuOn(Player p) {
        State s = state(p);
        if (s == null) return true;
        refreshAsync(p);
        return s.homemenu;
    }

    public boolean isWarpMenuOn(Player p) {
        State s = state(p);
        if (s == null) return true;
        refreshAsync(p);
        return s.warpmenu;
    }

    // -------------------------
    // Toggles (writes on player scheduler)
    // -------------------------

    public boolean toggleTpa(Player p) {
        State s = stateEnsure(p);
        boolean now = !s.tpa;
        s.tpa = now;
        writeAsync(p, keyTpa, now);
        return now;
    }

    public boolean toggleTpahere(Player p) {
        State s = stateEnsure(p);
        boolean now = !s.tpahere;
        s.tpahere = now;
        writeAsync(p, keyTpahere, now);
        return now;
    }

    public boolean toggleTpMenu(Player p) {
        State s = stateEnsure(p);
        boolean now = !s.tpmenu;
        s.tpmenu = now;
        writeAsync(p, keyTpMenu, now);
        return now;
    }

    public boolean toggleTpAuto(Player p) {
        State s = stateEnsure(p);
        boolean now = !s.tpauto;
        s.tpauto = now;
        writeAsync(p, keyTpAuto, now);
        return now;
    }

    public boolean toggleHomeMenu(Player p) {
        State s = stateEnsure(p);
        boolean now = !s.homemenu;
        s.homemenu = now;
        writeAsync(p, keyHomeMenu, now);
        return now;
    }

    public boolean toggleWarpMenu(Player p) {
        State s = stateEnsure(p);
        boolean now = !s.warpmenu;
        s.warpmenu = now;
        writeAsync(p, keyWarpMenu, now);
        return now;
    }

    // -------------------------
    // Setters (writes on player scheduler)
    // -------------------------

    public void setTpMenuOn(Player p, boolean on) {
        State s = stateEnsure(p);
        s.tpmenu = on;
        writeAsync(p, keyTpMenu, on);
    }

    public void setTpAutoOn(Player p, boolean on) {
        State s = stateEnsure(p);
        s.tpauto = on;
        writeAsync(p, keyTpAuto, on);
    }

    public void setHomeMenuOn(Player p, boolean on) {
        State s = stateEnsure(p);
        s.homemenu = on;
        writeAsync(p, keyHomeMenu, on);
    }

    public void setWarpMenuOn(Player p, boolean on) {
        State s = stateEnsure(p);
        s.warpmenu = on;
        writeAsync(p, keyWarpMenu, on);
    }

    // -------------------------
    // Optional: cleanup hooks
    // -------------------------

    public void forget(Player p) {
        if (p == null) return;
        cache.remove(p.getUniqueId());
    }

    // -------------------------
    // Internal helpers
    // -------------------------

    private State state(Player p) {
        if (p == null) return null;
        return cache.get(p.getUniqueId());
    }

    private State stateEnsure(Player p) {
        if (p == null) return new State();
        return cache.computeIfAbsent(p.getUniqueId(), u -> new State());
    }

    /**
     * Refresh cached values from PDC on the correct thread.
     * Safe to call frequently; it's just a scheduled read.
     */
    private void refreshAsync(Player p) {
        if (p == null) return;

        // Folia-safe: run on the player's scheduler (or main thread on non-Folia)
        Sched.run(p, () -> {
            State s = stateEnsure(p);
            s.tpa = readFlag(p, keyTpa, true);
            s.tpahere = readFlag(p, keyTpahere, true);
            s.tpmenu = readFlag(p, keyTpMenu, true);
            s.tpauto = readFlag(p, keyTpAuto, false);
            s.homemenu = readFlag(p, keyHomeMenu, true);
            s.warpmenu = readFlag(p, keyWarpMenu, true);
        });
    }

    private void writeAsync(Player p, NamespacedKey key, boolean value) {
        if (p == null || key == null) return;
        Sched.run(p, () -> writeFlag(p, key, value));
    }

    private boolean readFlag(Player p, NamespacedKey key, boolean defaultValue) {
        try {
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            Byte stored = pdc.get(key, PersistentDataType.BYTE);
            if (stored == null) return defaultValue;
            return stored == (byte) 1;
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    private void writeFlag(Player p, NamespacedKey key, boolean value) {
        try {
            p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, value ? (byte) 1 : (byte) 0);
        } catch (Throwable ignored) {
        }
    }
}
