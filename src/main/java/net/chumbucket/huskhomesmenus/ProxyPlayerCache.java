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

    // ✅ dimension cache (normalizedName -> "Overworld"/"Nether"/"The End"/etc)
    private final Map<String, String> playerToDimension = new ConcurrentHashMap<>();
    private final Map<String, Long> dimUpdatedMs = new ConcurrentHashMap<>();
    private final Map<String, Long> dimReqMs = new ConcurrentHashMap<>();

    private volatile long lastRefreshMs = 0L;
    private volatile long negativeUntilMs = 0L;
    private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

    public ProxyPlayerCache(JavaPlugin plugin, HHMConfig config, OptionalProxyMessenger messenger) {
        this.plugin = plugin;
        this.config = config;
        this.messenger = messenger;
    }

    public void start() {
        // prime quickly
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

    // =========================================================
    // ✅ Dimension API
    // =========================================================

    public void setRemoteDimension(String playerName, String dimension) {
        String key = normalize(playerName);
        if (key == null) return;

        String dim = (dimension == null || dimension.isBlank()) ? "Unknown" : dimension.trim();
        playerToDimension.put(key, dim);
        dimUpdatedMs.put(key, System.currentTimeMillis());
    }

    public String getDimensionFor(String playerName) {
        String key = normalize(playerName);
        if (key == null) return null;
        return playerToDimension.get(key);
    }

    /**
     * Returns a cached dimension if present; otherwise triggers a remote request and returns "Loading...".
     * requesterName is the local viewer who will receive DIM_RESP back.
     */
    public String getOrRequestDimension(String subjectName, String requesterName) {
        if (!config.proxyEnabled()) return "Unknown";
        if (messenger == null || !messenger.isEnabled()) return "Unknown";
        if (subjectName == null || subjectName.isBlank()) return "Unknown";
        if (requesterName == null || requesterName.isBlank()) return "Unknown";

        String key = normalize(subjectName);
        if (key == null) return "Unknown";

        long now = System.currentTimeMillis();

        // TTL for dimension cache (default 15s)
        long ttlMs = Math.max(2000L, plugin.getConfig().getLong("cache.dimension_ttl_millis", 15000L));
        Long updated = dimUpdatedMs.get(key);
        if (updated != null && (now - updated) <= ttlMs) {
            String dim = playerToDimension.get(key);
            return (dim == null || dim.isBlank()) ? "Unknown" : dim;
        }

        // request cooldown per subject (default 1.5s)
        long cooldownMs = Math.max(500L, plugin.getConfig().getLong("cache.dimension_request_cooldown_millis", 1500L));
        Long lastReq = dimReqMs.get(key);
        if (lastReq == null || (now - lastReq) >= cooldownMs) {
            dimReqMs.put(key, now);
            messenger.requestDimensionByName(subjectName, requesterName);
        }

        return "Loading...";
    }

    private String normalize(String s) {
        if (s == null) return null;
        String out = s.trim().toLowerCase(Locale.ROOT);
        return out.isEmpty() ? null : out;
    }

    // =========================================================
    // Existing region cache logic (unchanged)
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
