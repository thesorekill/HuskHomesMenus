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

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TpaCommand implements CommandExecutor {

    private final ToggleManager toggles;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public TpaCommand(ToggleManager toggles, HHMConfig config) {
        this.toggles = toggles;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        // Folia-safe: run command logic on the correct scheduler for this player
        Sched.run(player, () -> handle(player, args));
        return true;
    }

    private void handle(Player player, String[] args) {
        if (args == null || args.length != 1 || args[0] == null || args[0].isBlank()) {
            player.sendMessage(AMP.deserialize("&eUsage: /tpa <player>"));
            return;
        }

        final String targetName = args[0].trim();

        // If target is on this backend, enforce toggle immediately
        Player target = null;
        try {
            target = Bukkit.getPlayerExact(targetName);
        } catch (Throwable ignored) { }

        if (target != null) {
            boolean tpaOn = true;
            boolean tpahereOn = true;

            try { tpaOn = toggles.isTpaOn(target); } catch (Throwable ignored) { }
            try { tpahereOn = toggles.isTpahereOn(target); } catch (Throwable ignored) { }

            if (!tpaOn && !tpahereOn) {
                if (config.isEnabled("messages.sender.both_off.enabled", true)) {
                    player.sendMessage(AMP.deserialize(
                            config.msgWithPrefix(
                                    "messages.sender.both_off.text",
                                    "&cThat player has teleport requests off."
                            )
                    ));
                }
                return;
            } else if (!tpaOn) {
                if (config.isEnabled("messages.sender.tpa_off.enabled", true)) {
                    player.sendMessage(AMP.deserialize(
                            config.msgWithPrefix(
                                    "messages.sender.tpa_off.text",
                                    "&cThat player has &lTPA&r &crequests off."
                            )
                    ));
                }
                return;
            }
        }

        boolean handled;
        try {
            handled = Bukkit.dispatchCommand(player, "huskhomes:tpa " + targetName);
        } catch (Throwable t) {
            handled = false;
        }

        if (!handled) {
            player.sendMessage(
                    AMP.deserialize(config.prefix())
                            .append(AMP.deserialize("&cFailed to run HuskHomes /tpa."))
            );
        }
    }
}
