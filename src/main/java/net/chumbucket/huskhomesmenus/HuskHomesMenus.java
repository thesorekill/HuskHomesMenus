package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HuskHomesMenus extends JavaPlugin {

    private ToggleManager toggleManager;
    private OptionalProxyMessenger messenger;

    @Override
    public void onEnable() {
        this.toggleManager = new ToggleManager(this);

        // Velocity / proxy messaging
        this.messenger = new OptionalProxyMessenger(this);
        this.messenger.tryEnable();

        // Teleport request commands (wrappers around HuskHomes)
        safeSetExecutor("tpa", new TpaCommand(toggleManager));
        safeSetExecutor("tpahere", new TpaHereCommand(toggleManager));
        safeSetExecutor("tpaccept", new TpAcceptCommand());
        safeSetExecutor("tpdeny", new TpDenyCommand());

        // Toggle commands
        ToggleCommands toggleCommands = new ToggleCommands(toggleManager);
        safeSetExecutor("tpatoggle", toggleCommands);
        safeSetExecutor("tpaheretoggle", toggleCommands);

        // Listener (blocks on target backend; messages requester local or cross-backend)
        Bukkit.getPluginManager().registerEvents(
                new TeleportRequestToggleListener(toggleManager, messenger),
                this
        );

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new Placeholders(this, toggleManager).register();
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
