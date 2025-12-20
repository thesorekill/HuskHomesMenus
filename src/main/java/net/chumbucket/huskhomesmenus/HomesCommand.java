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
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class HomesCommand implements CommandExecutor {

    private final HomesMenu homesMenu;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public HomesCommand(HomesMenu homesMenu, HHMConfig config) {
        this.homesMenu = homesMenu;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        // If player used args, forward to HuskHomes so /home <name> still works
        if (args.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String a : args) {
                if (a == null || a.isBlank()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(a);
            }

            String base = cmd.getName().equalsIgnoreCase("homes") ? "huskhomes:homes" : "huskhomes:home";
            String forward = sb.length() > 0 ? (base + " " + sb) : base;

            try {
                Bukkit.dispatchCommand(p, forward);
            } catch (Throwable ignored) { }
            return true;
        }

        // No args -> open GUI (permission-gated)
        if (!p.hasPermission("huskhomesmenus.home")) {
            p.sendMessage(AMP.deserialize(config.prefix() + "&cYou don’t have permission."));
            return true;
        }

        homesMenu.open(p, 0);
        return true;
    }
}
