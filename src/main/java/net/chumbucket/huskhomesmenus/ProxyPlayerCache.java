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
    private final Map<String, String> playerToServer = new ConcurrentHashMap<>(); // will remain empty in fast mode

    private volatile long lastRefreshMs = 0L;
    private volatile long negativeUntilMs = 0L;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    public ProxyPlayerCache(JavaPlugin plugin, HHMConfig config, OptionalProxyMessenger messenger) {
        this.plugin = plugin;
        this.config = config;
        this.messenger = messenger;
    }

    public void start() {
        // You can drop this lower in config.yml to make autocomplete feel instant (e.g., 5)
        int seconds = Math.max(2, plugin.getConfig().getInt("cache.refresh_interval_seconds", 10));
        long periodTicks = seconds * 20L;

        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAsyncish, 20L, periodTicks);
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAsyncish, 20L);
    }

    public List<String> getCached() {
        if (isStale()) refreshAsyncish();
        return cached;
    }

    public String getServerFor(String playerName) {
        // Fast mode does not populate server mapping (PlayerList ALL doesn't provide it)
        if (playerName == null) return null;
        if (isStale()) refreshAsyncish();
        return playerToServer.get(playerName);
    }

    private boolean isStale() {
        int seconds = Math.max(2, plugin.getConfig().getInt("cache.refresh_interval_seconds", 10));
        long maxAgeMs = seconds * 1000L;
        return (System.currentTimeMillis() - lastRefreshMs) > maxAgeMs;
    }

    private void refreshAsyncish() {
        if (!config.proxyEnabled()) return;
        if (!messenger.isEnabled()) return;
        if (!config.isEnabled("commands.allow_proxy_targeting", true)) return;

        long now = System.currentTimeMillis();
        if (now < negativeUntilMs) return;

        if (!refreshInFlight.compareAndSet(false, true)) return;

        // ✅ Fast path: single request to the proxy for ALL players
        boolean sentAll = messenger.requestProxyPlayerList(names ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    cached = (names == null) ? List.of() : new ArrayList<>(new LinkedHashSet<>(names));
                    playerToServer.clear(); // no server mapping in ALL mode
                    lastRefreshMs = System.currentTimeMillis();
                    refreshInFlight.set(false);
                })
        );

        if (!sentAll) {
            long negMs = Math.max(500L, plugin.getConfig().getLong("cache.negative_cache_millis", 2000L));
            negativeUntilMs = System.currentTimeMillis() + negMs;
            refreshInFlight.set(false);
        }
    }
}
