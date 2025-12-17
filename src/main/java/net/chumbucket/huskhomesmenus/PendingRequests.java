package net.chumbucket.huskhomesmenus;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingRequests {

    public record Pending(String senderName, ConfirmRequestMenu.RequestType type) {}

    private static final ConcurrentHashMap<UUID, Pending> LAST = new ConcurrentHashMap<>();

    // bypass expires at a given millis timestamp
    private static final ConcurrentHashMap<UUID, Long> BYPASS_UNTIL = new ConcurrentHashMap<>();

    private PendingRequests() {}

    public static void set(UUID target, String senderName, ConfirmRequestMenu.RequestType type) {
        if (target == null || senderName == null || senderName.isBlank() || type == null) return;
        LAST.put(target, new Pending(senderName, type));
    }

    public static Pending get(UUID target) {
        return target == null ? null : LAST.get(target);
    }

    public static void clear(UUID target) {
        if (target != null) LAST.remove(target);
    }

    /** Allow huskhomes accept/deny commands through for a short window (GUI execution). */
    public static void bypassForMs(UUID player, long ms) {
        if (player == null) return;
        BYPASS_UNTIL.put(player, System.currentTimeMillis() + Math.max(1, ms));
    }

    /** Clear bypass immediately (optional; belt & suspenders). */
    public static void clearBypass(UUID player) {
        if (player != null) BYPASS_UNTIL.remove(player);
    }

    /** True if bypass window is active (and DOES NOT consume it). */
    public static boolean isBypassActive(UUID player) {
        if (player == null) return false;
        Long until = BYPASS_UNTIL.get(player);
        if (until == null) return false;
        if (System.currentTimeMillis() <= until) return true;
        BYPASS_UNTIL.remove(player);
        return false;
    }
}
