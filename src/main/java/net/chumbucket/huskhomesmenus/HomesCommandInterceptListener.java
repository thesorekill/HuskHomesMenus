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

import net.william278.huskhomes.api.HuskHomesAPI;
import net.william278.huskhomes.position.Home;
import net.william278.huskhomes.user.OnlineUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.Locale;

public final class HomesCommandInterceptListener implements Listener {

    private final JavaPlugin plugin;
    private final HomesMenu homesMenu;
    private final ToggleManager toggles;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    /**
     * Backwards-compatible constructor (so you don't have to change HuskHomesMenus.java)
     */
    public HomesCommandInterceptListener(HomesMenu homesMenu, ToggleManager toggles) {
        this(JavaPlugin.getProvidingPlugin(HomesCommandInterceptListener.class), homesMenu, toggles,
                new HHMConfig(JavaPlugin.getProvidingPlugin(HomesCommandInterceptListener.class)));
    }

    /**
     * Full constructor if you prefer explicit injection later
     */
    public HomesCommandInterceptListener(JavaPlugin plugin, HomesMenu homesMenu, ToggleManager toggles, HHMConfig config) {
        this.plugin = plugin;
        this.homesMenu = homesMenu;
        this.toggles = toggles;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        final Player p = e.getPlayer();

        // ✅ if player disabled home menu, do nothing (let HuskHomes handle /home normally)
        if (toggles != null && !toggles.isHomeMenuOn(p)) return;

        String msg = e.getMessage();
        if (msg == null) return;

        String trimmed = msg.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) return;

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return;

        String first = parts[0].substring(1);
        if (first.isBlank()) return;

        String label = first;
        if (label.contains(":")) label = label.split(":", 2)[1];

        String cmd = label.toLowerCase(Locale.ROOT);

        // Only care about /home and /homes
        if (!(cmd.equals("home") || cmd.equals("homes"))) return;

        // Optional permission gate (so people without permission still use HuskHomes)
        if (!p.hasPermission("huskhomesmenus.home")) return;

        // /home or /homes with NO args -> open GUI
        if (parts.length == 1) {
            e.setCancelled(true);
            homesMenu.open(p);
            return;
        }

        // Only intercept typed "/home <name>" (NOT "/homes ..." pagination/etc)
        if (!cmd.equals("home")) return;

        final String homeName = parts[1];
        if (homeName == null || homeName.isBlank()) return;

        e.setCancelled(true);

        final HuskHomesAPI api;
        try {
            api = HuskHomesAPI.getInstance();
        } catch (Throwable t) {
            p.sendMessage(AMP.deserialize(config.prefix() + "&cHuskHomes API not available."));
            return;
        }

        final OnlineUser user = api.adaptUser(p);

        // 4.9.9: resolve by fetching homes list, then filtering
        api.getUserHomes(user).thenAccept(homes -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;

                List<Home> list = (homes == null) ? List.of() : homes;

                Home match = null;
                for (Home h : list) {
                    if (h == null) continue;
                    String n = h.getName();
                    if (n != null && n.equalsIgnoreCase(homeName)) {
                        match = h;
                        break;
                    }
                }

                if (match == null) {
                    // Your message (so we don't spam HuskHomes messages)
                    p.sendMessage(AMP.deserialize(config.prefix() + "&cThat home doesn’t exist."));
                    return;
                }

                try {
                    // Respect warmups/etc
                    api.teleportBuilder()
                            .teleporter(user)
                            .target(match)
                            .toTimedTeleport()
                            .execute();
                } catch (Throwable t) {
                    // Keep this generic; different HH versions throw different exception types
                    p.sendMessage(AMP.deserialize(config.prefix() + "&cTeleport failed."));
                    if (config.debug()) t.printStackTrace();
                }
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    p.sendMessage(AMP.deserialize(config.prefix() + "&cFailed to load that home."));
                }
            });
            if (config.debug()) {
                plugin.getLogger().warning("getUserHomes failed: " + ex.getMessage());
            }
            return null;
        });
    }
}
