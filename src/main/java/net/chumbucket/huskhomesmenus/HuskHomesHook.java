package net.chumbucket.huskhomesmenus;

import net.william278.huskhomes.api.HuskHomesAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class HuskHomesHook {

    private final JavaPlugin plugin;
    private HuskHomesAPI api; // NOT final (prevents "might already have been assigned")

    public HuskHomesHook(JavaPlugin plugin) {
        this.plugin = plugin;
        this.api = null;

        Plugin hh = Bukkit.getPluginManager().getPlugin("HuskHomes");
        if (hh == null) {
            return;
        }

        try {
            this.api = HuskHomesAPI.getInstance();
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to get HuskHomes API instance: " + t.getMessage());
            this.api = null;
        }
    }

    public boolean isReady() {
        return api != null;
    }

    public HuskHomesAPI api() {
        if (api == null) throw new IllegalStateException("HuskHomes API not ready");
        return api;
    }

    public JavaPlugin plugin() {
        return plugin;
    }
}
