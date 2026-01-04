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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

public final class WarpsCommandInterceptListener implements Listener {

    private final WarpsMenu warpsMenu;
    private final ToggleManager toggles;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public WarpsCommandInterceptListener(WarpsMenu warpsMenu, ToggleManager toggles, HHMConfig config) {
        this.warpsMenu = warpsMenu;
        this.toggles = toggles;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        final Player p = e.getPlayer();

        // If menu disabled in config, do nothing (let normal command flow happen)
        if (config != null && !config.isEnabled("menus.warps.enabled", true)) return;

        // If player disabled warp menu, do nothing
        if (toggles != null && !toggles.isWarpMenuOn(p)) return;

        final String msg = e.getMessage();
        if (msg == null) return;

        final String trimmed = msg.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return;

        final String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return;

        String first = parts[0].substring(1);
        if (first.isBlank()) return;

        // Do NOT intercept namespaced commands like /huskhomes:warp
        if (first.contains(":")) return;

        final String cmd = first.toLowerCase(Locale.ROOT);

        // Only care about /warp and /warps
        if (!(cmd.equals("warp") || cmd.equals("warps"))) return;

        // Only intercept "/warp" and "/warps" with NO args
        if (parts.length != 1) return;

        // ✅ Permission gate should match HuskHomes permissions, not huskhomesmenus.*
        // /warp -> huskhomes.command.warp
        // /warps -> huskhomes.command.warplist
        if ("warp".equals(cmd) && !p.hasPermission("huskhomes.command.warp")) return;
        if ("warps".equals(cmd) && !p.hasPermission("huskhomes.command.warplist")) return;

        e.setCancelled(true);

        if (warpsMenu == null) {
            p.sendMessage(AMP.deserialize((config != null ? config.prefix() : "") + "&cWarp menu is not available."));
            return;
        }

        try {
            // ✅ Folia-safe: open inventory on the player's region thread
            Sched.run(p, () -> warpsMenu.open(p));
        } catch (Throwable t) {
            p.sendMessage(AMP.deserialize((config != null ? config.prefix() : "") + "&cWarp menu failed to open."));
            if (config != null && config.debug()) t.printStackTrace();
        }
    }
}
