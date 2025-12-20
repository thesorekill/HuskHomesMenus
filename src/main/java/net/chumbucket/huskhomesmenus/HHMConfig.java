package net.chumbucket.huskhomesmenus;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lightweight config wrapper for HuskHomesMenus.
 * Only exposes settings that are currently used by the codebase.
 */
public final class HHMConfig {

    private final JavaPlugin plugin;

    public HHMConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean debug() {
        return plugin.getConfig().getBoolean("debug", false);
    }

    public boolean proxyEnabled() {
        return plugin.getConfig().getBoolean("proxy.enabled", true);
    }

    public String backendName() {
        return plugin.getConfig().getString("backend_name", "Loading...");
    }

    public String prefix() {
        return color(plugin.getConfig().getString("messages.prefix", ""));
    }

    public boolean isEnabled(String enabledPath, boolean def) {
        return plugin.getConfig().getBoolean(enabledPath, def);
    }

    public String raw(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    public String msgWithPrefix(String path, String def) {
        return prefix() + color(plugin.getConfig().getString(path, def));
    }

    public String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public ConfigurationSection section(String path) {
        return plugin.getConfig().getConfigurationSection(path);
    }
}
