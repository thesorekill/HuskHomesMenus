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

        final boolean isHomes = cmd.getName().equalsIgnoreCase("homes") || label.equalsIgnoreCase("homes");
        final String base = isHomes ? "huskhomes:homes" : "huskhomes:home";

        // If player used args, forward to HuskHomes so /home <name> still works
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
                ok = Bukkit.dispatchCommand(p, forward);
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
        boolean menuOn = true;
        try {
            menuOn = toggles.isHomeMenuOn(p); // <-- your ToggleManager method
        } catch (Throwable ignored) {
            // If toggles breaks for some reason, safest behavior is: forward to HuskHomes.
            menuOn = false;
        }

        if (!menuOn) {
            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(p, base);
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
