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
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

public final class HuskHomesMenus extends JavaPlugin {

    private ToggleManager toggleManager;
    private OptionalProxyMessenger messenger;
    private HHMConfig config;

    private ProxyPlayerCache playerCache;
    private ConfirmRequestMenu confirmMenu;
    private HomesMenu homesMenu;

    // ✅ Warps Menu
    private WarpsMenu warpsMenu;

    // ✅ Update checker
    private UpdateChecker updateChecker;
    private UpdateNotifyOnJoinListener updateNotifyOnJoinListener;

    // Keep references so we can unregister cleanly on reload
    private TeleportCommandInterceptListener interceptListener;
    private TeleportRequestToggleListener toggleListener;
    private HomesCommandInterceptListener homesInterceptListener;

    // ✅ Warps intercept listener (toggle-respecting)
    private WarpsCommandInterceptListener warpsInterceptListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // ✅ Folia/Paper/Spigot/Purpur scheduling shim
        // (safe no-op on non-Folia)
        Sched.init(this);

        initRuntime();
        getLogger().info("HuskHomesMenus enabled. Proxy messenger enabled=" + (messenger != null && messenger.isEnabled()));
    }

    @Override
    public void onDisable() {
        teardownRuntime();
    }

    public boolean fullReload() {
        try {
            teardownRuntime();
            reloadConfig();

            // keep shim bound (in case someone calls reload before enable completes)
            Sched.init(this);

            initRuntime();

            getLogger().info("HuskHomesMenus reloaded. Proxy messenger enabled=" + (messenger != null && messenger.isEnabled()));
            return true;
        } catch (Throwable t) {
            getLogger().severe("HuskHomesMenus reload failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    // ------------------------------------------------------------
    // Runtime init/teardown
    // ------------------------------------------------------------

    private void closeOpenConfirmMenus() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) continue;

                Inventory top = null;
                try { top = p.getOpenInventory().getTopInventory(); }
                catch (Throwable ignored) {}

                if (top == null) continue;

                InventoryHolder holder = top.getHolder();
                if (holder instanceof ConfirmRequestMenu.ConfirmHolder) {
                    try { p.closeInventory(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) { }
    }

    private void closeOpenHomesMenus() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) continue;

                Inventory top = null;
                try { top = p.getOpenInventory().getTopInventory(); }
                catch (Throwable ignored) {}

                if (top == null) continue;

                InventoryHolder holder = top.getHolder();
                if (holder instanceof HomesMenu.HomesHolder || holder instanceof HomesMenu.DeleteConfirmHolder) {
                    try { p.closeInventory(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) { }
    }

    private void closeOpenWarpsMenus() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) continue;

                Inventory top = null;
                try { top = p.getOpenInventory().getTopInventory(); }
                catch (Throwable ignored) {}

                if (top == null) continue;

                InventoryHolder holder = top.getHolder();
                if (holder instanceof WarpsMenu.WarpsHolder) {
                    try { p.closeInventory(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) { }
    }

    private void initRuntime() {
        this.config = new HHMConfig(this);
        this.toggleManager = new ToggleManager(this);

        // Proxy messaging
        this.messenger = new OptionalProxyMessenger(this, config);
        this.messenger.tryEnable();

        // Proxy cache
        this.playerCache = new ProxyPlayerCache(this, config, messenger);
        this.playerCache.start();

        // Menus
        this.confirmMenu = new ConfirmRequestMenu(this, config, playerCache);
        this.homesMenu = new HomesMenu(this, config);
        this.warpsMenu = new WarpsMenu(this, config);

        // Register menu listeners
        Bukkit.getPluginManager().registerEvents(confirmMenu, this);
        Bukkit.getPluginManager().registerEvents(homesMenu, this);
        Bukkit.getPluginManager().registerEvents(warpsMenu, this);

        // Intercepts
        this.interceptListener = new TeleportCommandInterceptListener(confirmMenu, config, toggleManager);
        Bukkit.getPluginManager().registerEvents(interceptListener, this);

        this.homesInterceptListener = new HomesCommandInterceptListener(homesMenu, toggleManager);
        Bukkit.getPluginManager().registerEvents(homesInterceptListener, this);

        // ✅ Warps intercept listener (toggle ON/OFF behavior)
        this.warpsInterceptListener = new WarpsCommandInterceptListener(warpsMenu, toggleManager, config);
        Bukkit.getPluginManager().registerEvents(warpsInterceptListener, this);

        // ✅ FIX: remove unused plugin reference from TeleportRequestToggleListener
        this.toggleListener = new TeleportRequestToggleListener(toggleManager, messenger, config);
        Bukkit.getPluginManager().registerEvents(toggleListener, this);

        // Update checker
        try {
            if (getConfig().getBoolean("update_checker.enabled", true)) {
                this.updateChecker = new UpdateChecker(this, config, 130925);

                // ✅ No Bukkit scheduling needed here — just logging (thread-safe)
                if (getConfig().getBoolean("update_checker.notify_console", true)) {
                    this.updateChecker.checkNowAsync().thenAccept(result -> {
                        if (result == null) return;

                        final String spigotUrl = "https://www.spigotmc.org/resources/huskhomesmenus-1-21-x.130925/";

                        if (result.status() == UpdateChecker.Status.OUTDATED) {
                            getLogger().warning("A new version of HuskHomesMenus is available! "
                                    + "Current: " + result.currentVersion()
                                    + " Latest: " + result.latestVersion()
                                    + " (" + spigotUrl + ")");
                        } else if (result.status() == UpdateChecker.Status.UP_TO_DATE) {
                            getLogger().info("HuskHomesMenus is up to date. "
                                    + "Current: " + result.currentVersion()
                                    + " Latest: " + result.latestVersion());
                        } else {
                            getLogger().warning("HuskHomesMenus update check: could not determine latest version right now. "
                                    + "Current: " + result.currentVersion()
                                    + (result.latestVersion() != null && !result.latestVersion().isBlank()
                                    ? " CachedLatest: " + result.latestVersion()
                                    : ""));
                        }
                    });
                }

                if (getConfig().getBoolean("update_checker.notify_on_join", true)) {
                    this.updateNotifyOnJoinListener = new UpdateNotifyOnJoinListener(this);
                    Bukkit.getPluginManager().registerEvents(updateNotifyOnJoinListener, this);
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Update checker failed to initialize: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }

        // Commands
        safeSetExecutor("tpa", new TpaCommand(toggleManager, config));
        safeSetExecutor("tpahere", new TpaHereCommand(toggleManager, config));
        safeSetExecutor("tpaccept", new TpAcceptCommand(confirmMenu, toggleManager));
        safeSetExecutor("tpdeny", new TpDenyCommand(confirmMenu, toggleManager));
        safeSetExecutor("home", new HomesCommand(homesMenu, config, toggleManager));
        safeSetExecutor("homes", new HomesCommand(homesMenu, config, toggleManager));

        // ✅ Warp commands are in your plugin.yml, so bind them
        WarpsCommand warpsCommand = new WarpsCommand(warpsMenu, config, toggleManager);
        safeSetExecutor("warp", warpsCommand);
        safeSetExecutor("warps", warpsCommand);

        ToggleCommands toggleCommands = new ToggleCommands(toggleManager, config);
        safeSetExecutor("tpatoggle", toggleCommands);
        safeSetExecutor("tpaheretoggle", toggleCommands);
        safeSetExecutor("tpmenu", toggleCommands);
        safeSetExecutor("tpauto", toggleCommands);
        safeSetExecutor("homemenu", toggleCommands);
        safeSetExecutor("warpmenu", toggleCommands);

        // Admin command
        safeSetExecutor("hhm", new HHMCommand(this, config));

        // Tab completion
        safeSetTabCompleter("tpa", new ProxyTabCompleter(playerCache, true));
        safeSetTabCompleter("tpahere", new ProxyTabCompleter(playerCache, true));
        safeSetTabCompleter("tpaccept", new ProxyTabCompleter(playerCache, false));
        safeSetTabCompleter("tpdeny", new ProxyTabCompleter(playerCache, false));

        HomesTabCompleter homesTab = new HomesTabCompleter(this, config, toggleManager);
        safeSetTabCompleter("home", homesTab);
        safeSetTabCompleter("homes", homesTab);

        // PlaceholderAPI
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new Placeholders(this, toggleManager, config).register();
                getLogger().info("PlaceholderAPI detected; placeholders registered.");
            } catch (Throwable ignored) { }
        }
    }

    private void teardownRuntime() {
        // ✅ Folia note:
        // cancelTasks(JavaPlugin) exists on Folia builds too; if it ever throws, we just ignore.
        try { Bukkit.getScheduler().cancelTasks(this); } catch (Throwable ignored) { }

        try { if (confirmMenu != null) HandlerList.unregisterAll(confirmMenu); } catch (Throwable ignored) { }
        try { if (interceptListener != null) HandlerList.unregisterAll(interceptListener); } catch (Throwable ignored) { }
        try { if (toggleListener != null) HandlerList.unregisterAll(toggleListener); } catch (Throwable ignored) { }

        try { if (homesMenu != null) HandlerList.unregisterAll(homesMenu); } catch (Throwable ignored) { }
        try { if (homesInterceptListener != null) HandlerList.unregisterAll(homesInterceptListener); } catch (Throwable ignored) { }

        try { if (warpsMenu != null) HandlerList.unregisterAll(warpsMenu); } catch (Throwable ignored) { }
        try { if (warpsInterceptListener != null) HandlerList.unregisterAll(warpsInterceptListener); } catch (Throwable ignored) { }

        try { if (updateNotifyOnJoinListener != null) HandlerList.unregisterAll(updateNotifyOnJoinListener); } catch (Throwable ignored) { }

        // Close open inventories (best-effort)
        closeOpenConfirmMenus();
        closeOpenHomesMenus();
        closeOpenWarpsMenus();

        try { if (messenger != null) messenger.disable(); } catch (Throwable ignored) { }
        try { PendingRequests.clearGlobalSkins(); } catch (Throwable ignored) { }

        this.playerCache = null;
        this.confirmMenu = null;
        this.interceptListener = null;
        this.toggleListener = null;
        this.messenger = null;
        this.toggleManager = null;
        this.config = null;

        this.homesMenu = null;
        this.homesInterceptListener = null;

        this.warpsMenu = null;
        this.warpsInterceptListener = null;

        this.updateChecker = null;
        this.updateNotifyOnJoinListener = null;
    }

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
