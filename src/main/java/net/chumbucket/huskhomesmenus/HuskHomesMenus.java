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
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class HuskHomesMenus extends JavaPlugin {

    private ToggleManager toggleManager;
    private OptionalProxyMessenger messenger;
    private HHMConfig config;

    private ProxyPlayerCache playerCache;
    private ConfirmRequestMenu confirmMenu;

    // Keep references so we can unregister cleanly on reload
    private TeleportCommandInterceptListener interceptListener;
    private TeleportRequestToggleListener toggleListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initRuntime();
        getLogger().info("HuskHomesMenus enabled. Proxy messenger enabled=" + (messenger != null && messenger.isEnabled()));
    }

    @Override
    public void onDisable() {
        teardownRuntime();
    }

    /**
     * Full "plugin-style" reload:
     * - reload config
     * - cancel tasks
     * - unregister listeners
     * - rebuild runtime objects (messenger/cache/menu/listeners)
     * - rebind commands/tab completers
     */
    public boolean fullReload() {
        try {
            // Close first, then rebuild fresh
            teardownRuntime();

            // Reload config.yml
            reloadConfig();

            // Re-init everything
            initRuntime();

            getLogger().info("HuskHomesMenus reloaded. Proxy messenger enabled=" + (messenger != null && messenger.isEnabled()));
            return true;
        } catch (Throwable t) {
            getLogger().severe("HuskHomesMenus reload failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    // ------------------------------------------------------------
    // Runtime init/teardown
    // ------------------------------------------------------------
    private void closeOpenConfirmMenus() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) continue;

                Inventory top = null;
                try {
                    top = p.getOpenInventory().getTopInventory();
                } catch (Throwable ignored) {}

                if (top == null) continue;

                InventoryHolder holder = top.getHolder();
                if (holder instanceof ConfirmRequestMenu.ConfirmHolder) {
                    try {
                        p.closeInventory();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) { }
    }

    private void initRuntime() {
        // Fresh wrappers/managers
        this.config = new HHMConfig(this);
        this.toggleManager = new ToggleManager(this);

        // Proxy messaging
        this.messenger = new OptionalProxyMessenger(this, config);
        this.messenger.tryEnable();

        // Proxy cache (tab completion + region + dimension)
        this.playerCache = new ProxyPlayerCache(this, config, messenger);
        this.playerCache.start();

        // Menu
        this.confirmMenu = new ConfirmRequestMenu(this, config, playerCache);

        // Listeners
        Bukkit.getPluginManager().registerEvents(confirmMenu, this);

        this.interceptListener = new TeleportCommandInterceptListener(confirmMenu, config, toggleManager);
        Bukkit.getPluginManager().registerEvents(interceptListener, this);

        this.toggleListener = new TeleportRequestToggleListener(this, toggleManager, messenger, config);
        Bukkit.getPluginManager().registerEvents(toggleListener, this);

        // Commands
        safeSetExecutor("tpa", new TpaCommand(toggleManager, config));
        safeSetExecutor("tpahere", new TpaHereCommand(toggleManager, config));
        safeSetExecutor("tpaccept", new TpAcceptCommand(confirmMenu, toggleManager));
        safeSetExecutor("tpdeny", new TpDenyCommand(confirmMenu, toggleManager));

        ToggleCommands toggleCommands = new ToggleCommands(toggleManager, config);
        safeSetExecutor("tpatoggle", toggleCommands);
        safeSetExecutor("tpaheretoggle", toggleCommands);
        safeSetExecutor("tpmenu", toggleCommands);
        safeSetExecutor("tpauto", toggleCommands);

        // Admin command
        safeSetExecutor("hhm", new HHMCommand(this, config));

        // Tab completion
        safeSetTabCompleter("tpa", new ProxyTabCompleter(playerCache, true));
        safeSetTabCompleter("tpahere", new ProxyTabCompleter(playerCache, true));
        safeSetTabCompleter("tpaccept", new ProxyTabCompleter(playerCache, false));
        safeSetTabCompleter("tpdeny", new ProxyTabCompleter(playerCache, false));

        // PlaceholderAPI: only register once per server run to avoid duplicates
        // (PlaceholderExpansion.persist() keeps it loaded across plugin reloads.)
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                // If it's already registered, PlaceholderAPI ignores or throws depending on version.
                // This guard avoids log spam/double-register attempts.
                new Placeholders(this, toggleManager, config).register();
                getLogger().info("PlaceholderAPI detected; placeholders registered.");
            } catch (Throwable ignored) {
                // already registered; fine
            }
        }
    }

    private void teardownRuntime() {
        // Cancel all tasks scheduled by this plugin (includes ProxyPlayerCache timers, refresh tasks, etc.)
        try {
            Bukkit.getScheduler().cancelTasks(this);
        } catch (Throwable ignored) { }

        // Unregister our listeners
        try {
            if (confirmMenu != null) HandlerList.unregisterAll(confirmMenu);
        } catch (Throwable ignored) { }

        try {
            if (interceptListener != null) HandlerList.unregisterAll(interceptListener);
        } catch (Throwable ignored) { }

        try {
            if (toggleListener != null) HandlerList.unregisterAll(toggleListener);
        } catch (Throwable ignored) { }

        // ✅ Close any open ConfirmRequestMenu inventories (now safe: no auto-deny will run)
        closeOpenConfirmMenus();

        // Disable proxy messaging (unregister channels)
        try {
            if (messenger != null) messenger.disable();
        } catch (Throwable ignored) { }

        // Optional: clear static caches so reload starts “clean”
        try {
            PendingRequests.clearGlobalSkins();
        } catch (Throwable ignored) { }

        // Drop references
        this.playerCache = null;
        this.confirmMenu = null;
        this.interceptListener = null;
        this.toggleListener = null;
        this.messenger = null;
        this.toggleManager = null;
        this.config = null;
    }

    // ------------------------------------------------------------
    // Safe command binding
    // ------------------------------------------------------------

    private void safeSetExecutor(String cmd, org.bukkit.command.CommandExecutor exec) {
        PluginCommand pc = getCommand(cmd);
        if (pc == null) {
            getLogger().warning("Command '/" + cmd + "' not found in plugin.yml (executor not set).");
            return;
        }
        pc.setExecutor(exec);
    }

    private void safeSetTabCompleter(String cmd, org.bukkit.command.TabCompleter completer) {
        PluginCommand pc = getCommand(cmd);
        if (pc == null) {
            getLogger().warning("Command '/" + cmd + "' not found in plugin.yml (tab completer not set).");
            return;
        }
        pc.setTabCompleter(completer);
    }
}
