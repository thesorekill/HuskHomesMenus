package net.chumbucket.huskhomesmenus;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TpaRequestManager {

    public enum RequestType { TPA, TPAHERE }

    public static final class Request {
        public final UUID requester;
        public final UUID target;
        public final RequestType type;
        public final long createdAt;

        public Request(UUID requester, UUID target, RequestType type, long createdAt) {
            this.requester = requester;
            this.target = target;
            this.type = type;
            this.createdAt = createdAt;
        }
    }

    private final JavaPlugin plugin;
    private final ToggleManager toggles;

    // target -> (requester -> request)
    private final Map<UUID, LinkedHashMap<UUID, Request>> inbox = new ConcurrentHashMap<>();

    // configurable expiry (ms)
    private final long expiryMillis;

    public TpaRequestManager(JavaPlugin plugin, ToggleManager toggles) {
        this.plugin = plugin;
        this.toggles = toggles;
        this.expiryMillis = 120_000L; // 120s default
    }

    public boolean canReceive(Player target, RequestType type) {
        return switch (type) {
            case TPA -> toggles.isTpaOn(target);
            case TPAHERE -> toggles.isTpahereOn(target);
        };
    }

    public void addRequest(Player requester, Player target, RequestType type) {
        cleanupExpired(target.getUniqueId());

        inbox.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>())
                .put(requester.getUniqueId(), new Request(
                        requester.getUniqueId(),
                        target.getUniqueId(),
                        type,
                        System.currentTimeMillis()
                ));
    }

    public Optional<Request> getLatestRequest(UUID target) {
        cleanupExpired(target);
        LinkedHashMap<UUID, Request> map = inbox.get(target);
        if (map == null || map.isEmpty()) return Optional.empty();
        // last inserted
        Request latest = null;
        for (Request r : map.values()) latest = r;
        return Optional.ofNullable(latest);
    }

    public Optional<Request> getRequest(UUID target, UUID requester) {
        cleanupExpired(target);
        LinkedHashMap<UUID, Request> map = inbox.get(target);
        if (map == null) return Optional.empty();
        return Optional.ofNullable(map.get(requester));
    }

    public void removeRequest(UUID target, UUID requester) {
        LinkedHashMap<UUID, Request> map = inbox.get(target);
        if (map == null) return;
        map.remove(requester);
        if (map.isEmpty()) inbox.remove(target);
    }

    public void clearAll() {
        inbox.clear();
    }

    private void cleanupExpired(UUID target) {
        LinkedHashMap<UUID, Request> map = inbox.get(target);
        if (map == null || map.isEmpty()) return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Request>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Request> e = it.next();
            if (now - e.getValue().createdAt > expiryMillis) {
                it.remove();
            }
        }
        if (map.isEmpty()) inbox.remove(target);
    }

    public void sendRequestMessages(Player requester, Player target, RequestType type) {
        String prettyType = (type == RequestType.TPA) ? "TPA" : "TPAHere";

        requester.sendMessage(color("&aSent a " + prettyType + " request to &f" + target.getName() + "&a."));
        target.sendMessage(color("&eYou received a " + prettyType + " request from &f" + requester.getName() + "&e."));
        target.sendMessage(color("&7Type &f/tpaccept " + requester.getName() + " &7to accept, or &f/tpdeny " + requester.getName() + " &7to deny."));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
