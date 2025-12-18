package net.chumbucket.huskhomesmenus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingRequests {

    public record Pending(String senderName, UUID senderUuid, ConfirmRequestMenu.RequestType type) {}

    // All pending requests per target: target -> (senderLower -> Pending)
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Pending>> PENDING = new ConcurrentHashMap<>();

    // Last request per target (for /tpaccept with no args fallback)
    private static final ConcurrentHashMap<UUID, Pending> LAST = new ConcurrentHashMap<>();

    // bypass expires at a given millis timestamp
    private static final ConcurrentHashMap<UUID, Long> BYPASS_UNTIL = new ConcurrentHashMap<>();

    private PendingRequests() {}

    /** Backwards-compatible: used by your listener. Now it ADDS instead of overwriting. */
    public static void set(UUID target, String senderName, ConfirmRequestMenu.RequestType type) {
        add(target, senderName, null, type);
    }

    /** New: include sender UUID when available (for proxy dimension lookups). */
    public static void set(UUID target, String senderName, UUID senderUuid, ConfirmRequestMenu.RequestType type) {
        add(target, senderName, senderUuid, type);
    }

    /** Add (or update) a pending request for target from sender. */
    public static void add(UUID target, String senderName, UUID senderUuid, ConfirmRequestMenu.RequestType type) {
        if (target == null || senderName == null || senderName.isBlank() || type == null) return;

        String key = senderName.toLowerCase(Locale.ROOT);
        PENDING.computeIfAbsent(target, u -> new ConcurrentHashMap<>())
                .put(key, new Pending(senderName, senderUuid, type));

        // update "last" pointer
        LAST.put(target, new Pending(senderName, senderUuid, type));
    }

    /** Returns the last remembered pending request (for no-arg /tpaccept fallback). */
    public static Pending get(UUID target) {
        return target == null ? null : LAST.get(target);
    }

    /** Get all pending sender names for a target (for tab-complete). */
    public static List<String> getSenders(UUID target) {
        if (target == null) return List.of();
        Map<String, Pending> map = PENDING.get(target);
        if (map == null || map.isEmpty()) return List.of();

        // Keep insertion-ish order stable enough for UX
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Pending p : map.values()) {
            if (p != null && p.senderName() != null && !p.senderName().isBlank()) out.add(p.senderName());
        }
        return new ArrayList<>(out);
    }

    /** Remove one pending request (after accept/deny). */
    public static void remove(UUID target, String senderName) {
        if (target == null || senderName == null || senderName.isBlank()) return;

        Map<String, Pending> map = PENDING.get(target);
        if (map != null) {
            map.remove(senderName.toLowerCase(Locale.ROOT));
            if (map.isEmpty()) PENDING.remove(target);
        }

        // If LAST was this sender, repoint LAST to another pending (or clear)
        Pending last = LAST.get(target);
        if (last != null && last.senderName() != null && last.senderName().equalsIgnoreCase(senderName)) {
            Pending newLast = null;
            Map<String, Pending> remain = PENDING.get(target);
            if (remain != null && !remain.isEmpty()) {
                // pick any remaining (good enough)
                newLast = remain.values().iterator().next();
            }
            if (newLast == null) LAST.remove(target);
            else LAST.put(target, newLast);
        }
    }

    /** Clear ALL pending requests for a target. */
    public static void clear(UUID target) {
        if (target == null) return;
        PENDING.remove(target);
        LAST.remove(target);
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

    
    /** Get the sender UUID for a specific pending request (best effort). */
    public static UUID getSenderUuid(UUID target, String senderName) {
        if (target == null || senderName == null || senderName.isBlank()) return null;
        String key = senderName.toLowerCase(Locale.ROOT);
        var map = PENDING.get(target);
        if (map != null) {
            Pending p = map.get(key);
            if (p != null) return p.senderUuid();
        }
        Pending last = LAST.get(target);
        if (last != null && last.senderName() != null && last.senderName().equalsIgnoreCase(senderName)) {
            return last.senderUuid();
        }
        return null;
    }

public static Pending get(UUID target, String senderName) {
        if (target == null || senderName == null || senderName.isBlank()) return null;
        Pending p = LAST.get(target);
        if (p == null || p.senderName() == null) return null;
        return p.senderName().equalsIgnoreCase(senderName) ? p : null;
    }
}
