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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyPlayerCache {

    private final JavaPlugin plugin;
    private final HHMConfig config;
    private final OptionalProxyMessenger messenger;

    private volatile List<String> cached = List.of();
    private final Map<String, String> playerToServer = new ConcurrentHashMap<>();

    // ✅ dimension cache
    private final Map<String, String> playerToDimension = new ConcurrentHashMap<>();
    private final Map<String, Long> dimUpdatedMs = new ConcurrentHashMap<>();

    // request tracking
    private final Map<String, Long> dimReqMs = new ConcurrentHashMap<>();
    private final Map<String, Long> dimLatestRequestId = new ConcurrentHashMap<>();

    private volatile long lastRefreshMs = 0L;
    private volatile long negativeUntilMs = 0L;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    public ProxyPlayerCache(JavaPlugin plugin, HHMConfig config, OptionalProxyMessenger messenger) {
        this.plugin = plugin;
        this.config = config;
        this.messenger = messenger;

        // ✅ hook DIM_RESP into this cache
        if (this.messenger != null) {
            this.messenger.setDimensionSink(this::onDimResponse);
        }
    }

    public void start() {
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAsyncish, 20L);
    }

    public List<String> getCached() {
        if (isStale()) refreshAsyncish();
        return cached;
    }

    public String getServerFor(String playerName) {
        if (playerName == null) return null;
        if (isStale()) refreshAsyncish();
        return playerToServer.get(normalize(playerName));
    }

    public String getServerForFresh(String playerName) {
        if (playerName == null) return null;

        long now = System.currentTimeMillis();
        long ttlMs = Math.max(2000L, plugin.getConfig().getLong("cache.region_ttl_millis", 15000L));

        if (lastRefreshMs > 0 && (now - lastRefreshMs) <= ttlMs) {
            return getServerFor(playerName);
        }

        refreshAsyncish();
        return null;
    }

    // =========================================================
    // ✅ Dimension API (nonce-guarded)
    // =========================================================

    private void onDimResponse(OptionalProxyMessenger.DimResponse resp) {
        if (resp == null) return;

        String key = normalize(resp.playerName);
        if (key == null) return;

        long incomingId = resp.requestId;
        Long latest = dimLatestRequestId.get(key);

        // If we have a latest requestId, ignore out-of-order replies.
        // If requestId==0 (old backend), accept it.
        if (incomingId != 0L && latest != null && incomingId != latest) {
            return;
        }

        String dim = (resp.dimension == null || resp.dimension.isBlank()) ? "Unknown" : resp.dimension.trim();
        playerToDimension.put(key, dim);
        dimUpdatedMs.put(key, System.currentTimeMillis());
    }

    public String getOrRequestDimension(String subjectName, String requesterName) {
        if (!config.proxyEnabled()) return "Unknown";
        if (messenger == null || !messenger.isEnabled()) return "Unknown";
        if (subjectName == null || subjectName.isBlank()) return "Unknown";
        if (requesterName == null || requesterName.isBlank()) return "Unknown";

        String key = normalize(subjectName);
        if (key == null) return "Unknown";

        long now = System.currentTimeMillis();

        long ttlMs = Math.max(500L, plugin.getConfig().getLong("cache.dimension_ttl_millis", 3000L));
        Long updated = dimUpdatedMs.get(key);

        if (updated != null && (now - updated) <= ttlMs) {
            String dim = playerToDimension.get(key);
            if (dim != null && !dim.isBlank() && !"Unknown".equalsIgnoreCase(dim)) return dim;
        }

        return requestAndLoading(subjectName, requesterName, key, now);
    }

    private String requestAndLoading(String subjectName, String requesterName, String key, long now) {
        long cooldownMs = Math.max(200L, plugin.getConfig().getLong("cache.dimension_request_cooldown_millis", 500L));
        Long lastReq = dimReqMs.get(key);

        if (lastReq == null || (now - lastReq) >= cooldownMs) {
            dimReqMs.put(key, now);

            // ✅ new nonce per request; store as “latest”
            long requestId = (System.nanoTime() ^ (now << 1)) & Long.MAX_VALUE;
            dimLatestRequestId.put(key, requestId);

            messenger.requestDimensionByName(subjectName, requesterName, requestId);
        }

        return "Loading...";
    }

    private String normalize(String s) {
        if (s == null) return null;
        String out = s.trim().toLowerCase(Locale.ROOT);
        return out.isEmpty() ? null : out;
    }

    // =========================================================
    // Existing region cache logic
    // =========================================================

    private boolean isStale() {
        int seconds = Math.max(5, plugin.getConfig().getInt("cache.refresh_interval_seconds", 30));
        long maxAgeMs = seconds * 1000L;
        return (System.currentTimeMillis() - lastRefreshMs) > maxAgeMs;
    }

    private void refreshAsyncish() {
        if (!config.proxyEnabled()) return;
        if (messenger == null || !messenger.isEnabled()) return;

        long now = System.currentTimeMillis();
        if (now < negativeUntilMs) return;

        if (!refreshInFlight.compareAndSet(false, true)) return;

        boolean sent = messenger.requestProxyServers(servers -> {
            try {
                if (servers == null || servers.isEmpty()) {
                    refreshInFlight.set(false);
                    return;
                }

                Set<String> addrs = new LinkedHashSet<>();
                Map<String, String> map = new HashMap<>();

                Runnable next = new Runnable() {
                    private int i = 0;

                    @Override
                    public void run() {
                        if (i >= servers.size()) {
                            cached = new ArrayList<>(addrs);
                            playerToServer.clear();
                            playerToServer.putAll(map);
                            lastRefreshMs = System.currentTimeMillis();
                            refreshInFlight.set(false);
                            return;
                        }

                        String srv = servers.get(i++);
                        messenger.requestPlayerListForServer(srv, (server, names) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (names != null) {
                                for (String name : names) {
                                    if (name == null || name.isBlank()) continue;
                                    addrs.add(name);
                                    map.put(normalize(name), srv);
                                }
                            }
                            Bukkit.getScheduler().runTask(plugin, this);
                        }));
                    }
                };

                Bukkit.getScheduler().runTask(plugin, next);

            } catch (Throwable t) {
                refreshInFlight.set(false);
            }
        });

        if (!sent) {
            long negMs = Math.max(1000L, plugin.getConfig().getLong("cache.negative_cache_millis", 10000L));
            negativeUntilMs = System.currentTimeMillis() + negMs;
            refreshInFlight.set(false);
        }
    }
}