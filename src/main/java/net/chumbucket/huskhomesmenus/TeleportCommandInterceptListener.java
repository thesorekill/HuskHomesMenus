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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

public final class TeleportCommandInterceptListener implements Listener {

    private final ConfirmRequestMenu menu;
    private final HHMConfig config;
    private final ToggleManager toggles;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public TeleportCommandInterceptListener(ConfirmRequestMenu menu, HHMConfig config, ToggleManager toggles) {
        this.menu = menu;
        this.config = config;
        this.toggles = toggles;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        final Player p = e.getPlayer();
        if (p == null) return;

        // If we're bypassing, don't intercept
        if (PendingRequests.isBypassActive(p.getUniqueId())) return;

        // If menu feature disabled, do nothing
        if (!isConfirmMenuEnabled()) return;

        // If player disabled TP menu, do not intercept /tpaccept or /tpdeny
        if (toggles != null && !toglesTpMenuOnSafe(p)) return;

        final String msg = e.getMessage();
        if (msg == null) return;

        final String trimmed = msg.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return;

        final String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return;

        String first = parts[0];
        if (first.length() < 2) return; // "/"
        first = first.substring(1);     // remove leading "/"
        if (first.isBlank()) return;

        // Strip namespace (e.g. "huskhomes:tpaccept")
        String label = first;
        if (label.contains(":")) label = label.split(":", 2)[1];

        String action = label.toLowerCase(Locale.ROOT);
        int argIndex = 1;

        // Support "/huskhomes tpaccept" and "/hh tpaccept"
        if (action.equals("huskhomes") || action.equals("hh")) {
            if (parts.length < 2) return;
            action = safeLower(parts[1]);
            argIndex = 2;
        }

        boolean isAccept = action.equals("tpaccept");
        boolean isDeny = action.equals("tpdeny") || action.equals("tpdecline");

        // Handle things like "/tpaccept:foo" or "/tpdeny:bar" etc.
        if (!isAccept && !isDeny) {
            if (action.startsWith("tpaccept")) isAccept = true;
            if (action.startsWith("tpdeny") || action.startsWith("tpdecline")) isDeny = true;
        }

        if (!isAccept && !isDeny) return;

        // We're handling it (GUI), cancel the command
        e.setCancelled(true);

        // Determine requester name (may be null)
        String requesterName = (parts.length > argIndex) ? parts[argIndex] : null;
        if (requesterName != null) requesterName = requesterName.trim();

        // If no arg provided, fall back to "last pending"
        if (requesterName == null || requesterName.isBlank()) {
            PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
            if (pending == null) {
                // Folia-safe: run message on player's scheduler where possible
                Sched.run(p, () -> p.sendMessage(
                        AMP.deserialize(config.prefix())
                                .append(AMP.deserialize("&cYou have no pending teleport requests."))
                ));
                return;
            }
            requesterName = pending.senderName();
        }

        // Resolve request type from pending map
        ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA;

        PendingRequests.Pending byName = PendingRequests.get(p.getUniqueId(), requesterName);
        if (byName != null && byName.type() != null) {
            type = byName.type();
            requesterName = byName.senderName(); // preserve correct casing
        } else {
            PendingRequests.Pending last = PendingRequests.get(p.getUniqueId());
            if (last != null && last.type() != null) type = last.type();
        }

        final String finalRequester = requesterName;
        final ConfirmRequestMenu.RequestType finalType = type;

        // Folia-safe: menu open should run on correct scheduler
        Sched.run(p, () -> menu.open(p, finalRequester, finalType));
    }

    private boolean toglesTpMenuOnSafe(Player p) {
        try {
            return toggles.isTpMenuOn(p);
        } catch (Throwable ignored) {
            // safest behavior: don't intercept if we can't check
            return false;
        }
    }

    private boolean isConfirmMenuEnabled() {
        try {
            ConfigurationSection sec = config.section("menus.confirm_request");
            if (sec == null) return true;
            return sec.getBoolean("enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String safeLower(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
