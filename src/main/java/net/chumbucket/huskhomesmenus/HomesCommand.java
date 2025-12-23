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

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class HomesCommand implements CommandExecutor {

    private final HomesMenu homesMenu;
    private final HHMConfig config;
    private final ToggleManager toggles;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public HomesCommand(HomesMenu homesMenu, HHMConfig config, ToggleManager toggles) {
        this.homesMenu = homesMenu;
        this.config = config;
        this.toggles = toggles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        // Use cmd.getName() (canonical), not label (can be alias)
        final boolean isHomes = cmd.getName().equalsIgnoreCase("homes");
        final String base = isHomes ? "huskhomes:homes" : "huskhomes:home";

        // If player used args, forward to HuskHomes so /home <name> works
        if (args.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String a : args) {
                if (a == null || a.isBlank()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(a);
            }

            final String forward = sb.length() > 0 ? (base + " " + sb) : base;

            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(sender, forward);
            } catch (Throwable t) {
                ok = false;
            }

            if (!ok) {
                p.sendMessage(AMP.deserialize(config.prefix() + "&cFailed to run HuskHomes /" + (isHomes ? "homes" : "home") + "."));
            }
            return true;
        }

        // No args:
        // If HomeMenu toggle is OFF, do NOT open GUI — forward to HuskHomes base command instead.
        boolean menuOn;
        try {
            menuOn = toggles.isHomeMenuOn(p);
        } catch (Throwable ignored) {
            // safest behavior: forward to HuskHomes
            menuOn = false;
        }

        if (!menuOn) {
            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(sender, base);
            } catch (Throwable t) {
                ok = false;
            }

            if (!ok) {
                p.sendMessage(AMP.deserialize(config.prefix() + "&cFailed to run HuskHomes /" + (isHomes ? "homes" : "home") + "."));
            }
            return true;
        }

        // GUI path (permission-gated)
        if (!p.hasPermission("huskhomesmenus.home")) {
            p.sendMessage(AMP.deserialize(config.prefix() + "&cYou don’t have permission."));
            return true;
        }

        homesMenu.open(p, 0);
        return true;
    }
}