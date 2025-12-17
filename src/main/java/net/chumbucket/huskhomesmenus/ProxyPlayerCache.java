package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyPlayerCache {

    private final JavaPlugin plugin;
    private final HHMConfig config;
    private final OptionalProxyMessenger messenger;

    private volatile List<String> cached = List.of();
    private volatile long lastRefreshMs = 0L;
    private volatile long negativeUntilMs = 0L;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    public ProxyPlayerCache(JavaPlugin plugin, HHMConfig config, OptionalProxyMessenger messenger) {
        this.plugin = plugin;
        this.config = config;
        this.messenger = messenger;
    }

    public void start() {
        int seconds = Math.max(5, plugin.getConfig().getInt("cache.refresh_interval_seconds", 30));
        long periodTicks = seconds * 20L;

        // periodic refresh
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAsyncish, 20L, periodTicks);
        // quick initial attempt
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAsyncish, 20L);
    }

    public List<String> getCached() {
        // If stale, kick a refresh (non-blocking)
        if (isStale()) refreshAsyncish();
        return cached;
    }

    private boolean isStale() {
        int seconds = Math.max(5, plugin.getConfig().getInt("cache.refresh_interval_seconds", 30));
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

        boolean sent = messenger.requestProxyPlayerList(names -> {
            try {
                // Filter empties + de-dupe while keeping order
                LinkedHashSet<String> set = new LinkedHashSet<>();
                for (String n : names) {
                    if (n == null) continue;
                    String s = n.trim();
                    if (!s.isEmpty()) set.add(s);
                }
                cached = new ArrayList<>(set);
                lastRefreshMs = System.currentTimeMillis();
            } finally {
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
