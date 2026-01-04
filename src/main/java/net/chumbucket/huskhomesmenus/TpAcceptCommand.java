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

public final class TpAcceptCommand implements CommandExecutor {

    private final ConfirmRequestMenu menu;
    private final ToggleManager toggles;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public TpAcceptCommand(ConfirmRequestMenu menu, ToggleManager toggles) {
        this(menu, toggles, null);
    }

    public TpAcceptCommand(ConfirmRequestMenu menu, ToggleManager toggles, HHMConfig config) {
        this.menu = menu;
        this.toggles = toggles;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        // Folia-safe: do all player work on the correct scheduler
        Sched.run(p, () -> handlePlayerCommand(p, args));
        return true;
    }

    private void handlePlayerCommand(Player p, String[] args) {
        // If player disabled GUI menu, delegate to HuskHomes /tpaccept
        if (toggles != null && !toggles.isTpMenuOn(p)) {
            PendingRequests.bypassForMs(p.getUniqueId(), 1500L);

            final String command = (args != null && args.length == 1 && args[0] != null && !args[0].isBlank())
                    ? "huskhomes:tpaccept " + args[0]
                    : "huskhomes:tpaccept";

            boolean handled;
            try {
                handled = Bukkit.dispatchCommand(p, command);
            } catch (Throwable t) {
                handled = false;
            }

            if (!handled) {
                String msg = (config != null ? (config.prefix() + "&cFailed to run HuskHomes /tpaccept.") : "&cFailed to run HuskHomes /tpaccept.");
                p.sendMessage(AMP.deserialize(msg));
            }
            return;
        }

        // If /tpaccept <name>, open GUI for that sender (and keep type correct if we know it)
        if (args != null && args.length == 1 && args[0] != null && !args[0].isBlank()) {
            String requester = args[0];

            ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA; // fallback
            PendingRequests.Pending byName = PendingRequests.get(p.getUniqueId(), requester);
            if (byName != null && byName.type() != null) {
                type = byName.type();
                requester = byName.senderName(); // canonical capitalization
            }

            try {
                menu.open(p, requester, type);
            } catch (Throwable ignored) { }
            return;
        }

        // Otherwise use last remembered request
        PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
        if (pending == null) {
            String msg = (config != null ? (config.prefix() + "&cYou have no pending teleport requests.") : "&cYou have no pending teleport requests.");
            p.sendMessage(AMP.deserialize(msg));
            return;
        }

        try {
            menu.open(p, pending.senderName(), pending.type());
        } catch (Throwable ignored) { }
    }
}
