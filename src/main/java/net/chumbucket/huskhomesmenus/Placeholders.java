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

    /**
     * Placeholders provided by this expansion:
     *
     * Pretty (colored):
     *  - %huskhomesmenus_tpa_pretty%
     *  - %huskhomesmenus_tpahere_pretty%
     *  - %huskhomesmenus_requests_pretty%
     *
     * Raw booleans:
     *  - %huskhomesmenus_tpa%
     *  - %huskhomesmenus_tpahere%
     *
     * Plain text:
     *  - %huskhomesmenus_tpa_text%
     *  - %huskhomesmenus_tpahere_text%
     *
     * Icons:
     *  - %huskhomesmenus_tpa_icon%
     *  - %huskhomesmenus_tpahere_icon%
     */
    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null || params == null) {
            return null;
        }

        final boolean tpa = toggles.isTpaOn(player);
        final boolean tpahere = toggles.isTpahereOn(player);

        switch (params.toLowerCase()) {
            // Pretty (colored + bold)
            case "tpa_pretty":
                return pretty(tpa);
            case "tpahere_pretty":
                return pretty(tpahere);

            // Raw booleans (useful for conditional formatting in some plugins)
            case "tpa":
                return String.valueOf(tpa);
            case "tpahere":
                return String.valueOf(tpahere);

            // Plain text (no colors)
            case "tpa_text":
                return tpa ? "ON" : "OFF";
            case "tpahere_text":
                return tpahere ? "ON" : "OFF";

            // Icons (scoreboard-friendly)
            case "tpa_icon":
                return tpa ? "✔" : "✘";
            case "tpahere_icon":
                return tpahere ? "✔" : "✘";

            // Combined status
            case "requests_pretty":
                return color("&fTPA: " + (tpa ? "&a&lON" : "&c&lOFF")
                        + " &7| &fTPAHERE: " + (tpahere ? "&a&lON" : "&c&lOFF"));

            default:
                return null;
        }
    }

    private String pretty(boolean on) {
        return color(on ? "&a&lON" : "&c&lOFF");
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
