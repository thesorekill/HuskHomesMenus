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

import java.util.Locale;

public final class WarpsCommand implements CommandExecutor {

    private final WarpsMenu warpsMenu;
    private final HHMConfig config;
    private final ToggleManager toggles;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public WarpsCommand(WarpsMenu warpsMenu, HHMConfig config, ToggleManager toggles) {
        this.warpsMenu = warpsMenu;
        this.config = config;
        this.toggles = toggles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        final String cmdName = (command == null || command.getName() == null)
                ? "warps"
                : command.getName().toLowerCase(Locale.ROOT); // "warp" or "warps"

        // ✅ HuskHomes command permissions (NOT huskhomesmenus.*)
        final boolean canWarp = p.hasPermission("huskhomes.command.warp");
        final boolean canWarpList = p.hasPermission("huskhomes.command.warplist");

        // If player used args, ALWAYS forward to HuskHomes — but ensure they have permission
        if (args != null && args.length > 0) {
            if ("warp".equals(cmdName) && !canWarp) {
                deny(p);
                return true;
            }
            if ("warps".equals(cmdName) && !canWarpList) {
                deny(p);
                return true;
            }

            forwardToHuskHomes(p, cmdName, args);
            return true;
        }

        // Determine whether we will open the GUI or forward
        final boolean menuEnabled = (config == null) || config.isEnabled("menus.warps.enabled", true);
        final boolean toggleOn = (toggles == null) || toggles.isWarpMenuOn(p);
        final boolean canOpenGui = menuEnabled && toggleOn && (warpsMenu != null);

        // ✅ If GUI is ON -> open it
        if (canOpenGui) {
            warpsMenu.open(p);
            return true;
        }

        // ✅ GUI is OFF -> now enforce that they can run the underlying command before dispatching it
        if ("warp".equals(cmdName) && !canWarp) {
            deny(p);
            return true;
        }
        if ("warps".equals(cmdName) && !canWarpList) {
            deny(p);
            return true;
        }

        forwardToHuskHomes(p, cmdName, args);
        return true;
    }

    private void deny(Player p) {
        String msg = (config != null)
                ? config.msgWithPrefix("messages.no_permission", "&cNo permission.")
                : "&cNo permission.";
        p.sendMessage(AMP.deserialize(msg));
    }

    private void forwardToHuskHomes(Player p, String cmdName, String[] args) {
        // Build "huskhomes:warp ..." or "huskhomes:warps ..."
        StringBuilder sb = new StringBuilder();
        sb.append("huskhomes:").append(cmdName);

        if (args != null && args.length > 0) {
            for (String a : args) {
                if (a == null || a.isBlank()) continue;
                sb.append(' ').append(a);
            }
        }

        final String namespaced = sb.toString();

        // ✅ For Folia compatibility, dispatch on the player's region thread
        Sched.run(p, () -> {
            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(p, namespaced);
            } catch (Throwable t) {
                ok = false;
            }

            // Fallback: try plain command if namespaced isn't available
            if (!ok) {
                try {
                    StringBuilder plain = new StringBuilder();
                    plain.append(cmdName);

                    if (args != null && args.length > 0) {
                        for (String a : args) {
                            if (a == null || a.isBlank()) continue;
                            plain.append(' ').append(a);
                        }
                    }

                    ok = Bukkit.dispatchCommand(p, plain.toString());
                } catch (Throwable ignored) {
                    ok = false;
                }
            }

            if (!ok) {
                String msg = (config != null)
                        ? config.msgWithPrefix("messages.warps.error", "&cCould not forward to HuskHomes warp command.")
                        : "&cCould not forward to HuskHomes warp command.";
                p.sendMessage(AMP.deserialize(msg));
            }
        });
    }
}
