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
        // ensures config.yml exists on disk (must be included in your jar resources)
        saveDefaultConfig();

        this.config = new HHMConfig(this);
        this.toggleManager = new ToggleManager(this);

        // Velocity / proxy messaging (config-driven)
        this.messenger = new OptionalProxyMessenger(this, config);
        this.messenger.tryEnable();

        // Teleport request commands (wrappers around HuskHomes)
        safeSetExecutor("tpa", new TpaCommand(toggleManager, config));
        safeSetExecutor("tpahere", new TpaHereCommand(toggleManager, config));
        safeSetExecutor("tpaccept", new TpAcceptCommand());
        safeSetExecutor("tpdeny", new TpDenyCommand());

        // Toggle commands
        ToggleCommands toggleCommands = new ToggleCommands(toggleManager, config);
        safeSetExecutor("tpatoggle", toggleCommands);
        safeSetExecutor("tpaheretoggle", toggleCommands);

        // Listener (blocks on target backend; messages requester local or cross-backend)
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
}
