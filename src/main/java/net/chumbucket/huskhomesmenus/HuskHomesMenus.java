package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class HuskHomesMenus extends JavaPlugin {

    private HuskHomesHook huskHomes;
    private ToggleManager toggleManager;

    @Override
    public void onEnable() {
        // Hard depend in plugin.yml, but still defensive.
        this.huskHomes = new HuskHomesHook(this);
        if (!huskHomes.isReady()) {
            getLogger().severe("HuskHomes not found or API not ready. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.toggleManager = new ToggleManager(this);

        // Commands (all delegate to HuskHomes namespaced commands for cross-server support)
        ToggleCommands togglesCmd = new ToggleCommands(toggleManager);
        getCommand("tpatoggle").setExecutor(togglesCmd);
        getCommand("tpaheretoggle").setExecutor(togglesCmd);

        getCommand("tpa").setExecutor(new TpaCommand());
        getCommand("tpahere").setExecutor(new TpaHereCommand());
        getCommand("tpaccept").setExecutor(new TpAcceptCommand());
        getCommand("tpdeny").setExecutor(new TpDenyCommand());

        // Enforce toggles on the receiving server
        Bukkit.getPluginManager().registerEvents(new TeleportRequestToggleListener(toggleManager), this);

        // Optional PlaceholderAPI
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new Placeholders(this, toggleManager).register();
            getLogger().info("Registered PlaceholderAPI placeholders.");
        }

        getLogger().info("HuskHomesMenus enabled (cross-server /tpa + /tpahere wrappers + per-type toggle enforcement).");
    }
}
