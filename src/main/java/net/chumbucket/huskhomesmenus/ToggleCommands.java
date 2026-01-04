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

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class ToggleCommands implements CommandExecutor {

    private final ToggleManager toggles;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public ToggleCommands(ToggleManager toggles, HHMConfig config) {
        this.toggles = toggles;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        // Ultra-safe: avoid NPEs during weird reload timing
        if (command == null || toggles == null || config == null) {
            return true; // do nothing, but don't error
        }

        // Folia-safe: run the toggle + messaging on the player's scheduler
        Sched.run(p, () -> handle(p, command));
        return true;
    }

    private void handle(Player p, Command command) {
        final String name = command.getName().toLowerCase();

        switch (name) {
            case "tpatoggle" -> {
                boolean on = toggles.toggleTpa(p);
                sendToggleStatus(p, true, on);
            }
            case "tpaheretoggle" -> {
                boolean on = toggles.toggleTpahere(p);
                sendToggleStatus(p, false, on);
            }
            case "tpmenu" -> {
                boolean on = toggles.toggleTpMenu(p);
                sendTpMenuStatus(p, on);
            }
            case "tpauto" -> {
                boolean on = toggles.toggleTpAuto(p);
                sendTpAutoStatus(p, on);
            }
            case "homemenu" -> {
                boolean on = toggles.toggleHomeMenu(p);
                sendHomeMenuStatus(p, on);
            }
            case "warpmenu" -> {
                boolean on = toggles.toggleWarpMenu(p);
                sendWarpMenuStatus(p, on);
            }
            default -> {
                // Unknown executor binding; ignore.
            }
        }
    }

    private void sendToggleStatus(Player p, boolean isTpa, boolean on) {
        if (!config.isEnabled("messages.toggles.show_status_lines.enabled", true)) return;

        final String basePath = isTpa ? "messages.toggles.tpa_status_line" : "messages.toggles.tpahere_status_line";
        if (!config.isEnabled(basePath + ".enabled", true)) return;

        String template = config.raw(basePath + ".text",
                "%color%" + (isTpa ? "TPA" : "TPAHere") + " requests: %state%");

        template = template
                .replace("%color%", on ? "&a" : "&c")
                .replace("%state%", on ? "&lON" : "&lOFF");

        p.sendMessage(AMP.deserialize(config.prefix() + template));
    }

    private void sendTpMenuStatus(Player p, boolean on) {
        if (!config.isEnabled("messages.toggles.show_status_lines.enabled", true)) return;

        final String basePath = "messages.toggles.tpmenu_status_line";
        if (!config.isEnabled(basePath + ".enabled", true)) return;

        String template = config.raw(basePath + ".text", "%color%Teleport Menu: %state%");
        template = template
                .replace("%color%", on ? "&a" : "&c")
                .replace("%state%", on ? "&lON" : "&lOFF");

        p.sendMessage(AMP.deserialize(config.prefix() + template));
    }

    private void sendTpAutoStatus(Player p, boolean on) {
        if (!config.isEnabled("messages.toggles.show_status_lines.enabled", true)) return;

        final String basePath = "messages.toggles.tpauto_status_line";
        if (!config.isEnabled(basePath + ".enabled", true)) return;

        String template = config.raw(basePath + ".text", "%color%Auto Accept TPA: %state%");
        template = template
                .replace("%color%", on ? "&a" : "&c")
                .replace("%state%", on ? "&lON" : "&lOFF");

        p.sendMessage(AMP.deserialize(config.prefix() + template));
    }

    private void sendHomeMenuStatus(Player p, boolean on) {
        if (!config.isEnabled("messages.homes.show_status_lines.enabled", true)) return;

        final String basePath = "messages.homes.homemenu_status_line";
        if (!config.isEnabled(basePath + ".enabled", true)) return;

        String template = config.raw(basePath + ".text", "%color%Home Menu: %state%");
        template = template
                .replace("%color%", on ? "&a" : "&c")
                .replace("%state%", on ? "&lON" : "&lOFF");

        p.sendMessage(AMP.deserialize(config.prefix() + template));
    }

    private void sendWarpMenuStatus(Player p, boolean on) {
        if (!config.isEnabled("messages.warps.show_status_lines.enabled", true)) return;

        final String basePath = "messages.warps.warpmenu_status_line";
        if (!config.isEnabled(basePath + ".enabled", true)) return;

        String template = config.raw(basePath + ".text", "%color%Warp Menu: %state%");
        template = template
                .replace("%color%", on ? "&a" : "&c")
                .replace("%state%", on ? "&lON" : "&lOFF");

        p.sendMessage(AMP.deserialize(config.prefix() + template));
    }
}