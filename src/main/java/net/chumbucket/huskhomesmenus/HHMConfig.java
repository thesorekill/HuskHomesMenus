package net.chumbucket.huskhomesmenus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Lightweight config wrapper for HuskHomesMenus.
 * Only exposes settings that are currently used by the codebase.
 */
public final class HHMConfig {

    private final JavaPlugin plugin;

    // Supports legacy & color codes (e.g., "&cHello")
    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

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

    /**
     * Prefix as a legacy-colored String (kept for backwards compatibility).
     */
    public String prefix() {
        return color(plugin.getConfig().getString("messages.prefix", ""));
    }

    /**
     * Prefix as a Component (preferred for sendMessage(Component)).
     */
    public Component prefixComponent() {
        return AMP.deserialize(plugin.getConfig().getString("messages.prefix", ""));
    }

    public boolean isEnabled(String enabledPath, boolean def) {
        return plugin.getConfig().getBoolean(enabledPath, def);
    }

    public String raw(String path, String def) {
        return plugin.getConfig().getString(path, def);
    }

    /**
     * Message with prefix as a legacy-colored String (kept for backwards compatibility).
     */
    public String msgWithPrefix(String path, String def) {
        return prefix() + color(plugin.getConfig().getString(path, def));
    }

    /**
     * Message with prefix as a Component (preferred for sendMessage(Component)).
     */
    public Component msgWithPrefixComponent(String path, String def) {
        Component pfx = prefixComponent();
        Component msg = AMP.deserialize(plugin.getConfig().getString(path, def));
        return pfx.append(msg);
    }

    /**
     * Converts legacy &-colored text into a normalized legacy-colored String.
     * (Useful when older parts of the code still build Strings.)
     */
    public String color(String s) {
        if (s == null) return "";
        return AMP.serialize(AMP.deserialize(s));
    }

    /**
     * Converts legacy &-colored text into a Component.
     */
    public Component colorComponent(String s) {
        if (s == null) return Component.empty();
        return AMP.deserialize(s);
    }

    public ConfigurationSection section(String path) {
        return plugin.getConfig().getConfigurationSection(path);
    }
}