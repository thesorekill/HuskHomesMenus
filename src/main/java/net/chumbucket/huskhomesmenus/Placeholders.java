/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package net.chumbucket.huskhomesmenus;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Placeholders extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final ToggleManager toggles;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public Placeholders(JavaPlugin plugin, ToggleManager toggles, HHMConfig config) {
        this.plugin = plugin;
        this.toggles = toggles;
    }

    @Override
    public String getIdentifier() {
        return "huskhomesmenus";
    }

    @Override
    public String getAuthor() {
        return "Chumbucket";
    }

    @Override
    public String getVersion() {
        // ✅ avoids deprecated JavaPlugin#getDescription()
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null || params == null) {
            return null;
        }

        final boolean tpa = toggles.isTpaOn(player);
        final boolean tpahere = toggles.isTpahereOn(player);

        // ✅ NEW
        final boolean tpmenu = toggles.isTpMenuOn(player);
        final boolean tpauto = toggles.isTpAutoOn(player);

        switch (params.toLowerCase()) {
            // -------------------------
            // Pretty (colored + bold)
            // -------------------------
            case "tpa_pretty":
                return pretty(tpa);
            case "tpahere_pretty":
                return pretty(tpahere);
            case "tpmenu_pretty":
                return pretty(tpmenu);
            case "tpauto_pretty":
                return pretty(tpauto);

            // -------------------------
            // Raw booleans
            // -------------------------
            case "tpa":
                return String.valueOf(tpa);
            case "tpahere":
                return String.valueOf(tpahere);
            case "tpmenu":
                return String.valueOf(tpmenu);
            case "tpauto":
                return String.valueOf(tpauto);

            // -------------------------
            // Text
            // -------------------------
            case "tpa_text":
                return tpa ? "ON" : "OFF";
            case "tpahere_text":
                return tpahere ? "ON" : "OFF";
            case "tpmenu_text":
                return tpmenu ? "ON" : "OFF";
            case "tpauto_text":
                return tpauto ? "ON" : "OFF";

            // -------------------------
            // Icons
            // -------------------------
            case "tpa_icon":
                return tpa ? "✔" : "✘";
            case "tpahere_icon":
                return tpahere ? "✔" : "✘";
            case "tpmenu_icon":
                return tpmenu ? "✔" : "✘";
            case "tpauto_icon":
                return tpauto ? "✔" : "✘";

            // -------------------------
            // Combined status lines
            // -------------------------
            case "requests_pretty":
                return color("&fTPA: " + (tpa ? "&a&lON" : "&c&lOFF")
                        + " &7| &fTPAHERE: " + (tpahere ? "&a&lON" : "&c&lOFF"));

            case "all_pretty":
                return color("&fTPA: " + (tpa ? "&a&lON" : "&c&lOFF")
                        + " &7| &fTPAHERE: " + (tpahere ? "&a&lON" : "&c&lOFF")
                        + " &7| &fTPMENU: " + (tpmenu ? "&a&lON" : "&c&lOFF")
                        + " &7| &fTPAUTO: " + (tpauto ? "&a&lON" : "&c&lOFF"));

            default:
                return null;
        }
    }

    private String pretty(boolean on) {
        return color(on ? "&a&lON" : "&c&lOFF");
    }

    /**
     * PlaceholderAPI wants a String back, so we keep this as legacy formatting.
     * (Your scoreboard/plugin consuming this will interpret &-codes.)
     */
    private String color(String s) {
        if (s == null) return "";
        return AMP.serialize(AMP.deserialize(s));
    }
}