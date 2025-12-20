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

public final class TpAcceptCommand implements CommandExecutor {

    private final ConfirmRequestMenu menu;
    private final ToggleManager toggles;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public TpAcceptCommand(ConfirmRequestMenu menu, ToggleManager toggles) {
        this.menu = menu;
        this.toggles = toggles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        // NEW: If player disabled GUI menu, delegate to HuskHomes /tpaccept
        if (toggles != null && !toggles.isTpMenuOn(p)) {
            PendingRequests.bypassForMs(p.getUniqueId(), 1500L);
            String command = (args.length == 1) ? "huskhomes:tpaccept " + args[0] : "huskhomes:tpaccept";
            boolean handled = Bukkit.dispatchCommand(p, command);
            if (!handled) {
                p.sendMessage(AMP.deserialize("&cFailed to run HuskHomes /tpaccept (huskhomes:tpaccept)."));
            }
            return true;
        }

        // If HuskHomes ran /tpaccept <name>, we know the sender name; lookup the request type if possible
        if (args.length == 1) {
            String requester = args[0];

            ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA; // default fallback
            PendingRequests.Pending byName = PendingRequests.get(p.getUniqueId(), requester);
            if (byName != null && byName.type() != null) {
                type = byName.type();
                // use the canonical stored name (keeps capitalization consistent)
                requester = byName.senderName();
            }

            menu.open(p, requester, type);
            return true;
        }

        // Otherwise use last remembered request
        PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
        if (pending == null) {
            p.sendMessage(AMP.deserialize("&cYou have no pending teleport requests."));
            return true;
        }

        menu.open(p, pending.senderName(), pending.type());
        return true;
    }
}