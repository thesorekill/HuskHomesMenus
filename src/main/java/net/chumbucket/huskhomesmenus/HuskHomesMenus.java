package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HuskHomesMenus extends JavaPlugin {

    private ToggleManager toggleManager;
    private OptionalProxyMessenger messenger;
    private HHMConfig config;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.config = new HHMConfig(this);
        this.toggleManager = new ToggleManager(this);

        // Proxy messaging
        this.messenger = new OptionalProxyMessenger(this, config);
        this.messenger.tryEnable();

        // Proxy cache (tab completion + REGION mapping + dimension cache)
        ProxyPlayerCache playerCache = new ProxyPlayerCache(this, config, messenger);
        playerCache.start();

        // ✅ Wire remote dimension responses into the cache
        this.messenger.setDimensionSink(playerCache::setRemoteDimension);

        // Menu
        ConfirmRequestMenu confirmMenu = new ConfirmRequestMenu(this, config, playerCache);
        Bukkit.getPluginManager().registerEvents(confirmMenu, this);
        Bukkit.getPluginManager().registerEvents(new TeleportCommandInterceptListener(confirmMenu, config), this);

        // Commands
        safeSetExecutor("tpa", new TpaCommand(toggleManager, config));
        safeSetExecutor("tpahere", new TpaHereCommand(toggleManager, config));
        safeSetExecutor("tpaccept", new TpAcceptCommand(confirmMenu));
        safeSetExecutor("tpdeny", new TpDenyCommand());

        // ✅ Tab completion across proxy + remove own name
        safeSetTabCompleter("tpa", new ProxyTabCompleter(playerCache, true));
        safeSetTabCompleter("tpahere", new ProxyTabCompleter(playerCache, true));
        safeSetTabCompleter("tpaccept", new ProxyTabCompleter(playerCache, false));
        safeSetTabCompleter("tpdeny", new ProxyTabCompleter(playerCache, false));

        // Toggle commands
        ToggleCommands toggleCommands = new ToggleCommands(toggleManager, config);
        safeSetExecutor("tpatoggle", toggleCommands);
        safeSetExecutor("tpaheretoggle", toggleCommands);

        // Toggle enforcement (and cross-server denial messaging)
        Bukkit.getPluginManager().registerEvents(
                new TeleportRequestToggleListener(toggleManager, messenger, config),
                this
        );

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new Placeholders(this, toggleManager, config).register();
            getLogger().info("PlaceholderAPI detected; placeholders registered.");
        }

        getLogger().info("HuskHomesMenus enabled. Proxy messenger enabled=" + messenger.isEnabled());
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
