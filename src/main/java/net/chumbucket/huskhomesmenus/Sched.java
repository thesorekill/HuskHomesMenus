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

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Tiny scheduling shim:
 * - Paper/Spigot/Purpur: uses BukkitScheduler (main thread)
 * - Folia: uses PlayerScheduler / RegionScheduler / GlobalRegionScheduler (region-safe)
 *
 * Uses reflection so this compiles on non-Folia.
 */
public final class Sched {

    private static volatile Plugin PLUGIN;

    private Sched() {}

    /**
     * Call once during onEnable.
     */
    public static void init(JavaPlugin plugin) {
        PLUGIN = Objects.requireNonNull(plugin, "plugin");
    }

    private static Plugin plugin() {
        Plugin p = PLUGIN;
        if (p == null) {
            // Fallback: try to grab a plugin instance by scanning (best-effort)
            try {
                Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
                if (plugins.length > 0) p = plugins[0];
            } catch (Throwable ignored) {}
        }
        if (p == null) {
            throw new IllegalStateException("Sched.init(plugin) was not called");
        }
        return p;
    }

    // =========================================================
    // ✅ Run now
    // =========================================================

    /**
     * Run a task on the correct thread for this player.
     * Folia: player scheduler
     * Non-Folia: main thread
     */
    public static void run(Player player, Runnable task) {
        if (task == null) return;

        if (player == null) {
            run(task);
            return;
        }

        // Folia path (reflection): player.getScheduler().run(plugin, consumerTask, null)
        if (tryRunOnPlayerScheduler(player, task)) return;

        // Bukkit path
        try {
            Bukkit.getScheduler().runTask(plugin(), task);
        } catch (Throwable t) {
            plugin().getLogger().log(Level.WARNING, "Sched.run(player) failed, running inline", t);
            try { task.run(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run a task on the global/main thread.
     * Folia: GlobalRegionScheduler
     * Non-Folia: main thread
     */
    public static void run(Runnable task) {
        if (task == null) return;

        if (tryRunOnGlobalScheduler(task)) return;

        try {
            Bukkit.getScheduler().runTask(plugin(), task);
        } catch (Throwable t) {
            plugin().getLogger().log(Level.WARNING, "Sched.run failed, running inline", t);
            try { task.run(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Run a task on the correct region thread for a location (chunk/region ownership).
     * Folia: RegionScheduler
     * Non-Folia: main thread
     */
    public static void runAt(Location loc, Runnable task) {
        if (task == null) return;

        if (loc == null || loc.getWorld() == null) {
            run(task);
            return;
        }

        if (tryRunOnRegionScheduler(loc, task)) return;

        // Bukkit path (main)
        try {
            Bukkit.getScheduler().runTask(plugin(), task);
        } catch (Throwable t) {
            plugin().getLogger().log(Level.WARNING, "Sched.runAt failed, running inline", t);
            try { task.run(); } catch (Throwable ignored) {}
        }
    }

    // =========================================================
    // ✅ Run later (ticks)
    // =========================================================

    /**
     * Run later (in ticks) on the correct thread for this player.
     */
    public static void later(Player player, long delayTicks, Runnable task) {
        if (task == null) return;

        if (delayTicks <= 0) {
            run(player, task);
            return;
        }

        if (player == null) {
            later(delayTicks, task);
            return;
        }

        // Folia path: player.getScheduler().runDelayed(plugin, consumerTask, null, delayTicks)
        if (tryRunDelayedOnPlayerScheduler(player, delayTicks, task)) return;

        // Bukkit path
        try {
            Bukkit.getScheduler().runTaskLater(plugin(), task, delayTicks);
        } catch (Throwable t) {
            plugin().getLogger().log(Level.WARNING, "Sched.later(player) failed, falling back to run()", t);
            run(player, task);
        }
    }

    /**
     * Run later (in ticks) on the global/main thread.
     * Folia: GlobalRegionScheduler
     * Non-Folia: main thread
     */
    public static void later(long delayTicks, Runnable task) {
        if (task == null) return;

        if (delayTicks <= 0) {
            run(task);
            return;
        }

        // Folia path: Bukkit.getGlobalRegionScheduler().runDelayed(plugin, consumerTask, delayTicks)
        if (tryRunDelayedOnGlobalScheduler(delayTicks, task)) return;

        // Bukkit path
        try {
            Bukkit.getScheduler().runTaskLater(plugin(), task, delayTicks);
        } catch (Throwable t) {
            plugin().getLogger().log(Level.WARNING, "Sched.later failed, falling back to run()", t);
            run(task);
        }
    }

    /**
     * Run later (in ticks) on a location's region thread.
     * Folia: RegionScheduler
     * Non-Folia: main thread
     */
    public static void laterAt(Location loc, long delayTicks, Runnable task) {
        if (task == null) return;

        if (delayTicks <= 0) {
            runAt(loc, task);
            return;
        }

        if (loc == null || loc.getWorld() == null) {
            later(delayTicks, task);
            return;
        }

        if (tryRunDelayedOnRegionScheduler(loc, delayTicks, task)) return;

        // Bukkit path
        try {
            Bukkit.getScheduler().runTaskLater(plugin(), task, delayTicks);
        } catch (Throwable t) {
            plugin().getLogger().log(Level.WARNING, "Sched.laterAt failed, falling back to later()", t);
            later(delayTicks, task);
        }
    }

    /**
     * Explicit alias for clarity (HomesMenu uses this for pure timers).
     */
    public static void laterGlobal(long delayTicks, Runnable task) {
        later(delayTicks, task);
    }

    // =========================================================
    // Folia reflection helpers
    // =========================================================

    private static boolean tryRunOnPlayerScheduler(Player player, Runnable task) {
        try {
            Method getScheduler = player.getClass().getMethod("getScheduler");
            Object scheduler = getScheduler.invoke(player);
            if (scheduler == null) return false;

            // PlayerScheduler#run(Plugin, Consumer<ScheduledTask>, Runnable)
            Method run = findMethod(scheduler.getClass(), "run", 3);
            if (run == null) return false;

            Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    scheduler.getClass().getClassLoader(),
                    new Class[]{run.getParameterTypes()[1]},
                    (proxy, method, args) -> {
                        try { task.run(); } catch (Throwable ignored) {}
                        return null;
                    }
            );

            run.invoke(scheduler, plugin(), consumer, null);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false; // not folia
        } catch (Throwable t) {
            plugin().getLogger().log(Level.FINEST, "Folia player scheduler run failed", t);
            return false;
        }
    }

    private static boolean tryRunDelayedOnPlayerScheduler(Player player, long delayTicks, Runnable task) {
        try {
            Method getScheduler = player.getClass().getMethod("getScheduler");
            Object scheduler = getScheduler.invoke(player);
            if (scheduler == null) return false;

            // PlayerScheduler#runDelayed(Plugin, Consumer<ScheduledTask>, Runnable, long)
            Method runDelayed = findMethod(scheduler.getClass(), "runDelayed", 4);
            if (runDelayed == null) return false;

            Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    scheduler.getClass().getClassLoader(),
                    new Class[]{runDelayed.getParameterTypes()[1]},
                    (proxy, method, args) -> {
                        try { task.run(); } catch (Throwable ignored) {}
                        return null;
                    }
            );

            runDelayed.invoke(scheduler, plugin(), consumer, null, delayTicks);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false; // not folia
        } catch (Throwable t) {
            plugin().getLogger().log(Level.FINEST, "Folia player scheduler runDelayed failed", t);
            return false;
        }
    }

    private static boolean tryRunOnGlobalScheduler(Runnable task) {
        try {
            Method getGlobal = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object global = getGlobal.invoke(null);
            if (global == null) return false;

            // GlobalRegionScheduler#run(Plugin, Consumer<ScheduledTask>)
            Method run = findMethod(global.getClass(), "run", 2);
            if (run == null) return false;

            Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    global.getClass().getClassLoader(),
                    new Class[]{run.getParameterTypes()[1]},
                    (proxy, method, args) -> {
                        try { task.run(); } catch (Throwable ignored) {}
                        return null;
                    }
            );

            run.invoke(global, plugin(), consumer);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false; // not folia
        } catch (Throwable t) {
            plugin().getLogger().log(Level.FINEST, "Folia global scheduler run failed", t);
            return false;
        }
    }

    private static boolean tryRunDelayedOnGlobalScheduler(long delayTicks, Runnable task) {
        try {
            Method getGlobal = Bukkit.class.getMethod("getGlobalRegionScheduler");
            Object global = getGlobal.invoke(null);
            if (global == null) return false;

            // GlobalRegionScheduler#runDelayed(Plugin, Consumer<ScheduledTask>, long)
            Method runDelayed = findMethod(global.getClass(), "runDelayed", 3);
            if (runDelayed == null) return false;

            Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    global.getClass().getClassLoader(),
                    new Class[]{runDelayed.getParameterTypes()[1]},
                    (proxy, method, args) -> {
                        try { task.run(); } catch (Throwable ignored) {}
                        return null;
                    }
            );

            runDelayed.invoke(global, plugin(), consumer, delayTicks);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false; // not folia
        } catch (Throwable t) {
            plugin().getLogger().log(Level.FINEST, "Folia global scheduler runDelayed failed", t);
            return false;
        }
    }

    private static boolean tryRunOnRegionScheduler(Location loc, Runnable task) {
        try {
            Method getRegion = Bukkit.class.getMethod("getRegionScheduler");
            Object region = getRegion.invoke(null);
            if (region == null) return false;

            // RegionScheduler#run(Plugin, Location, Consumer<ScheduledTask>)
            Method run = findMethod(region.getClass(), "run", 3);
            if (run == null) return false;

            Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    region.getClass().getClassLoader(),
                    new Class[]{run.getParameterTypes()[2]},
                    (proxy, method, args) -> {
                        try { task.run(); } catch (Throwable ignored) {}
                        return null;
                    }
            );

            run.invoke(region, plugin(), loc, consumer);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false; // not folia
        } catch (Throwable t) {
            plugin().getLogger().log(Level.FINEST, "Folia region scheduler run failed", t);
            return false;
        }
    }

    private static boolean tryRunDelayedOnRegionScheduler(Location loc, long delayTicks, Runnable task) {
        try {
            Method getRegion = Bukkit.class.getMethod("getRegionScheduler");
            Object region = getRegion.invoke(null);
            if (region == null) return false;

            // RegionScheduler#runDelayed(Plugin, Location, Consumer<ScheduledTask>, long)
            Method runDelayed = findMethod(region.getClass(), "runDelayed", 4);
            if (runDelayed == null) return false;

            Object consumer = java.lang.reflect.Proxy.newProxyInstance(
                    region.getClass().getClassLoader(),
                    new Class[]{runDelayed.getParameterTypes()[2]},
                    (proxy, method, args) -> {
                        try { task.run(); } catch (Throwable ignored) {}
                        return null;
                    }
            );

            runDelayed.invoke(region, plugin(), loc, consumer, delayTicks);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false; // not folia
        } catch (Throwable t) {
            plugin().getLogger().log(Level.FINEST, "Folia region scheduler runDelayed failed", t);
            return false;
        }
    }

    private static Method findMethod(Class<?> type, String name, int paramCount) {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != paramCount) continue;
            return m;
        }
        return null;
    }
}
