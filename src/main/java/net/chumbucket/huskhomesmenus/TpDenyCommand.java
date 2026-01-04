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

/**
 * Cross-server /tpdeny wrapper.
 * - If TPMenu is ON: open the confirm menu (with Deny button).
 * - If TPMenu is OFF: delegate to HuskHomes /tpdeny normally.
 */
public final class TpDenyCommand implements CommandExecutor {

    private final ConfirmRequestMenu menu;
    private final ToggleManager toggles;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public TpDenyCommand(ConfirmRequestMenu menu, ToggleManager toggles) {
        this.menu = menu;
        this.toggles = toggles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        // Folia-safe: run command logic on the correct scheduler for this player
        Sched.run(p, () -> handle(p, args));
        return true;
    }

    private void handle(Player p, String[] args) {
        if (args != null && args.length > 1) {
            p.sendMessage(AMP.deserialize("&eUsage: /tpdeny [player]"));
            return;
        }

        // If menu is OFF, delegate to HuskHomes directly
        if (toggles != null) {
            boolean tpMenuOn = true;
            try { tpMenuOn = toggles.isTpMenuOn(p); } catch (Throwable ignored) { }
            if (!tpMenuOn) {
                // safety: prevent intercept/loop if anything else tries to catch the forwarded command
                PendingRequests.bypassForMs(p.getUniqueId(), 1500L);

                String command;
                if (args != null && args.length == 1 && args[0] != null && !args[0].isBlank()) {
                    command = "huskhomes:tpdeny " + args[0].trim();
                } else {
                    command = "huskhomes:tpdeny";
                }

                boolean handled;
                try {
                    handled = Bukkit.dispatchCommand(p, command);
                } catch (Throwable t) {
                    handled = false;
                }

                if (!handled) {
                    p.sendMessage(AMP.deserialize("&cFailed to run HuskHomes /tpdeny (huskhomes:tpdeny)."));
                }
                return;
            }
        }

        // Menu is ON -> open our confirm menu so they can click Deny

        // If /tpdeny <name>, prefer that request type/casing if we have it
        if (args != null && args.length == 1 && args[0] != null && !args[0].isBlank()) {
            String requester = args[0].trim();

            ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA; // fallback
            PendingRequests.Pending byName = PendingRequests.get(p.getUniqueId(), requester);
            if (byName != null && byName.type() != null) {
                type = byName.type();
                requester = byName.senderName(); // canonical casing
            }

            menu.open(p, requester, type);
            return;
        }

        // Otherwise use last remembered request
        PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
        if (pending == null) {
            p.sendMessage(AMP.deserialize("&cYou have no pending teleport requests."));
            return;
        }

        menu.open(p, pending.senderName(), pending.type());
    }
}
