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

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class HHMCommand implements CommandExecutor {

    private final HuskHomesMenus plugin;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public HHMCommand(HuskHomesMenus plugin, HHMConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        // /hhm reload
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("huskhomesmenus.reload")) {
                sender.sendMessage(
                        AMP.deserialize(config.prefix())
                                .append(AMP.deserialize("&cYou do not have permission to do that."))
                );
                return true;
            }

            boolean ok = plugin.fullReload();
            sender.sendMessage(
                    AMP.deserialize(config.prefix())
                            .append(AMP.deserialize(ok ? "&aHuskHomesMenus reloaded." : "&cReload failed. Check console."))
            );
            return true;
        }

        // ✅ /hhm version
        if (args.length == 1 && args[0].equalsIgnoreCase("version")) {
            if (!sender.hasPermission("huskhomesmenus.admin")) {
                sender.sendMessage(
                        AMP.deserialize(config.prefix())
                                .append(AMP.deserialize("&cYou do not have permission to do that."))
                );
                return true;
            }

            if (!plugin.getConfig().getBoolean("update_checker.enabled", true)) {
                sender.sendMessage(
                        AMP.deserialize(config.prefix())
                                .append(AMP.deserialize("&cUpdate checker is disabled in config.yml."))
                );
                return true;
            }

            UpdateChecker checker = plugin.getUpdateChecker();
            if (checker == null) {
                sender.sendMessage(
                        AMP.deserialize(config.prefix())
                                .append(AMP.deserialize("&cUpdate checker not initialized."))
                );
                return true;
            }

            sender.sendMessage(
                    AMP.deserialize(config.prefix())
                            .append(AMP.deserialize("&7Checking for updates..."))
            );

            checker.checkNowAsync().thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (result == null) {
                    sender.sendMessage(
                            AMP.deserialize(config.prefix())
                                    .append(AMP.deserialize("&cCould not check for updates."))
                    );
                    return;
                }

                String current = safe(result.currentVersion());
                String latest = safe(result.latestVersion());

                switch (result.status()) {
                    case UP_TO_DATE -> sender.sendMessage(
                            AMP.deserialize(config.prefix())
                                    .append(AMP.deserialize("&aHuskHomesMenus is up to date. &7(Current: &f" + current + "&7)"))
                    );

                    case OUTDATED -> {
                        sender.sendMessage(
                                AMP.deserialize(config.prefix())
                                        .append(AMP.deserialize("&eA new HuskHomesMenus update is available!"))
                        );
                        sender.sendMessage(
                                AMP.deserialize(config.prefix())
                                        .append(AMP.deserialize("&7Current: &f" + current + " &7→ Latest: &a" + latest))
                        );
                        sender.sendMessage(
                                AMP.deserialize(config.prefix())
                                        .append(AMP.deserialize("&7Spigot: &fhttps://www.spigotmc.org/resources/huskhomesmenus-1-21-x.130925/"))
                        );
                    }

                    default -> {
                        sender.sendMessage(
                                AMP.deserialize(config.prefix())
                                        .append(AMP.deserialize("&cCould not determine latest version right now."))
                        );
                        sender.sendMessage(
                                AMP.deserialize(config.prefix())
                                        .append(AMP.deserialize("&7Current: &f" + current))
                        );
                    }
                }
            }));

            return true;
        }

        sender.sendMessage(
                AMP.deserialize(config.prefix())
                        .append(AMP.deserialize("&eUsage: /hhm reload &7| &e/hhm version"))
        );
        return true;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
