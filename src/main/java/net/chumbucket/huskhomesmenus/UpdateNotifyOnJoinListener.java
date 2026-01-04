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

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateNotifyOnJoinListener implements Listener {

    private final HuskHomesMenus plugin;

    public UpdateNotifyOnJoinListener(HuskHomesMenus plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("update_checker.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("update_checker.notify_on_join", true)) return;

        final UpdateChecker checker = plugin.getUpdateChecker();
        if (checker == null) return;

        final Player p = e.getPlayer();

        // ✅ Check async, then notify on the correct thread for Folia/Paper/Spigot via Sched
        checker.checkIfNeededAsync().thenAccept(result -> {
            if (result == null) return;

            Sched.run(p, () -> {
                if (!p.isOnline()) return;
                checker.notifyPlayerIfOutdated(p, "huskhomesmenus.admin");
            });
        });
    }
}
