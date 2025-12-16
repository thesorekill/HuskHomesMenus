package net.chumbucket.huskhomesmenus;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class Placeholders extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final ToggleManager toggles;

    public Placeholders(JavaPlugin plugin, ToggleManager toggles) {
        this.plugin = plugin;
        this.toggles = toggles;
    }

    @Override
    public String getIdentifier() {
        return "huskhomesmenus";
    }

    @Override
    public String getAuthor() {
        return "chumbucket";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null || params == null) return null;

        if (params.equalsIgnoreCase("tpa_pretty")) {
            return pretty(toggles.isTpaOn(player));
        }
        if (params.equalsIgnoreCase("tpahere_pretty")) {
            return pretty(toggles.isTpahereOn(player));
        }

        return null;
    }

    private String pretty(boolean on) {
        return ChatColor.translateAlternateColorCodes('&', on ? "&a&lON" : "&c&lOFF");
    }
}
