package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class HuskHomesMenus extends JavaPlugin {

    private HuskHomesHook huskHomes;
    private ToggleManager toggleManager;
    private OptionalProxyMessenger proxyMessenger;

    @Override
    public void onEnable() {
        // Defensive: plugin.yml should depend on HuskHomes
        this.huskHomes = new HuskHomesHook(this);
        if (!huskHomes.isReady()) {
            getLogger().severe("HuskHomes not found or API not ready. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.toggleManager = new ToggleManager(this);

        // Optional proxy messenger (works single-server without it; enables cross-server messages when available)
        this.proxyMessenger = new OptionalProxyMessenger(this);
        this.proxyMessenger.tryEnable();

        // Commands: delegate to HuskHomes namespaced commands so cross-server requests work
        ToggleCommands togglesCmd = new ToggleCommands(toggleManager);
        getCommand("tpatoggle").setExecutor(togglesCmd);
        getCommand("tpaheretoggle").setExecutor(togglesCmd);

        getCommand("tpa").setExecutor(new TpaCommand());
        getCommand("tpahere").setExecutor(new TpaHereCommand());
        getCommand("tpaccept").setExecutor(new TpAcceptCommand());
        getCommand("tpdeny").setExecutor(new TpDenyCommand());

        // Enforce per-type toggles on the RECEIVING server
        Bukkit.getPluginManager().registerEvents(
                new TeleportRequestToggleListener(toggleManager, proxyMessenger),
                this
        );

        // Optional PlaceholderAPI
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new Placeholders(this, toggleManager).register();
            getLogger().info("Registered PlaceholderAPI placeholders.");
        }

        getLogger().info("HuskHomesMenus enabled (cross-server /tpa wrappers + per-type toggle enforcement).");
    }
}
