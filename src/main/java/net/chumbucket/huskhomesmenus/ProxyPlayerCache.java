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

        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAsyncish, 20L, periodTicks);
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAsyncish, 20L);
    }

    public List<String> getCached() {
        if (isStale()) refreshAsyncish();
        return cached;
    }

    public String getServerFor(String playerName) {
        if (playerName == null) return null;
        if (isStale()) refreshAsyncish();
        return playerToServer.get(playerName);
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

        // Mapping-first refresh: GetServers -> PlayerList <server> (for player->server map)
        boolean sent = messenger.requestProxyServers(servers -> {
            try {
                if (servers == null || servers.isEmpty()) {
                    // Fallback: still populate cached names if proxy won't return servers list
                    boolean sentAll = messenger.requestProxyPlayerList(names ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                cached = (names == null) ? List.of() : new ArrayList<>(new LinkedHashSet<>(names));
                                // no server mapping possible here
                                playerToServer.clear();
                                lastRefreshMs = System.currentTimeMillis();
                                refreshInFlight.set(false);
                            })
                    );

                    if (!sentAll) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            cached = List.of();
                            playerToServer.clear();
                            lastRefreshMs = System.currentTimeMillis();
                            refreshInFlight.set(false);
                        });
                    }
                    return;
                }

                LinkedHashSet<String> allPlayers = new LinkedHashSet<>();
                HashMap<String, String> map = new HashMap<>();

                Iterator<String> it = servers.iterator();

                Runnable next = new Runnable() {
                    @Override
                    public void run() {
                        if (!it.hasNext()) {
                            cached = new ArrayList<>(allPlayers);
                            playerToServer.clear();
                            playerToServer.putAll(map);
                            lastRefreshMs = System.currentTimeMillis();
                            refreshInFlight.set(false);
                            return;
                        }

                        String server = it.next();
                        messenger.requestPlayerListForServer(server, (srv, names) -> {
                            if (names != null) {
                                for (String n : names) {
                                    if (n == null) continue;
                                    String name = n.trim();
                                    if (name.isEmpty()) continue;
                                    allPlayers.add(name);
                                    map.put(name, srv);
                                }
                            }
                            Bukkit.getScheduler().runTask(plugin, this);
                        });
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
