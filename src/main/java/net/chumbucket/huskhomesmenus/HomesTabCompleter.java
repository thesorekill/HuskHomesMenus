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
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HomesTabCompleter implements TabCompleter {

    private final JavaPlugin plugin;
    private final HHMConfig config;

    // player UUID -> cached home names
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    // keep it short so it stays fresh
    private static final long CACHE_MS = 1500L;

    public HomesTabCompleter(JavaPlugin plugin, HHMConfig config, ToggleManager toggles) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!(sender instanceof Player p)) return List.of();

        // Only complete the FIRST argument: /home <name>
        // (If HuskHomes supports more args, we don't interfere.)
        if (args.length != 1) return List.of();

        // If HomesMenu is ON and player typed /home with no args,
        // your command opens GUI. But tab completion is only relevant when args[0] exists.
        // Still: we always provide home-name suggestions.
        String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);

        // Trigger async refresh if stale
        warmCacheAsync(p);

        // Return cached results immediately
        List<String> homes = getCachedHomes(p.getUniqueId());
        if (homes.isEmpty()) return List.of();

        if (prefix.isBlank()) return homes;

        List<String> filtered = new ArrayList<>();
        for (String h : homes) {
            if (h.toLowerCase(Locale.ROOT).startsWith(prefix)) filtered.add(h);
        }
        return filtered;
    }

    private List<String> getCachedHomes(UUID uuid) {
        CacheEntry e = cache.get(uuid);
        if (e == null) return List.of();
        return e.homes;
    }

    private void warmCacheAsync(Player p) {
        UUID uuid = p.getUniqueId();

        CacheEntry existing = cache.get(uuid);
        long now = System.currentTimeMillis();
        if (existing != null && (now - existing.timeMs) <= CACHE_MS) return;

        HuskHomesAPI api;
        try {
            api = HuskHomesAPI.getInstance();
        } catch (Throwable t) {
            return;
        }

        OnlineUser user;
        try {
            user = api.adaptUser(p);
        } catch (Throwable t) {
            return;
        }

        api.getUserHomes(user).thenAccept(list -> {
            List<String> names = new ArrayList<>();
            if (list != null) {
                for (Home h : list) {
                    if (h == null) continue;
                    String n = h.getName();
                    if (n == null) continue;
                    n = n.trim();
                    if (!n.isBlank()) names.add(n);
                }
            }

            // stable, nice ordering
            names.sort(String.CASE_INSENSITIVE_ORDER);

            cache.put(uuid, new CacheEntry(Collections.unmodifiableList(names), System.currentTimeMillis()));
        }).exceptionally(ex -> {
            if (config.debug()) plugin.getLogger().warning("HomesTabCompleter failed to load homes: " + ex.getMessage());
            return null;
        });
    }

    private record CacheEntry(List<String> homes, long timeMs) {}
}
