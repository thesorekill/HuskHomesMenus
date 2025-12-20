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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PendingRequests {

    /** Holds value+signature for skull textures (signature may be null/blank on some setups). */
    public record Skin(String value, String signature) {}

    public record Pending(String senderName, UUID senderUuid, ConfirmRequestMenu.RequestType type) {}

    // All pending requests per target: target -> (senderLower -> Pending)
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Pending>> PENDING = new ConcurrentHashMap<>();

    // Per-target skin cache: target -> (senderLower -> Skin)
    private static final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Skin>> SKINS = new ConcurrentHashMap<>();

    // GLOBAL skin caches (helps if timing/target mapping differs)
    private static final ConcurrentHashMap<String, Skin> GLOBAL_SKINS_BY_NAME = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Skin> GLOBAL_SKINS_BY_UUID = new ConcurrentHashMap<>();

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

        LAST.put(target, new Pending(senderName, senderUuid, type));
    }

    // --------------------------------------------------------------------
    // Skin caching for cross-backend player heads
    // --------------------------------------------------------------------

    public static void setSkin(UUID target, String senderName, String texturesValue, String texturesSignature) {
        setSkin(target, senderName, null, texturesValue, texturesSignature);
    }

    public static void setSkin(UUID target, String senderName, UUID senderUuid, String texturesValue, String texturesSignature) {
        if (senderName == null || senderName.isBlank()) return;
        if (texturesValue == null || texturesValue.isBlank()) return;

        String key = senderName.toLowerCase(Locale.ROOT);
        Skin skin = new Skin(texturesValue, texturesSignature);

        // per-target
        if (target != null) {
            SKINS.computeIfAbsent(target, u -> new ConcurrentHashMap<>()).put(key, skin);
        }

        // global
        GLOBAL_SKINS_BY_NAME.put(key, skin);
        if (senderUuid != null) GLOBAL_SKINS_BY_UUID.put(senderUuid, skin);

        // best-effort: if senderUuid not passed, try to discover from pending
        if (senderUuid == null && target != null) {
            Pending p = getPendingInternal(target, senderName);
            if (p != null && p.senderUuid() != null) GLOBAL_SKINS_BY_UUID.put(p.senderUuid(), skin);
        }
    }

    public static Skin getSkin(UUID target, String senderName) {
        if (senderName == null || senderName.isBlank()) return null;
        String key = senderName.toLowerCase(Locale.ROOT);

        // 1) per-target
        if (target != null) {
            var map = SKINS.get(target);
            if (map != null) {
                Skin s = map.get(key);
                if (s != null && s.value() != null && !s.value().isBlank()) return s;
            }
        }

        // 2) global by uuid
        if (target != null) {
            UUID senderUuid = getSenderUuid(target, senderName);
            if (senderUuid != null) {
                Skin s = GLOBAL_SKINS_BY_UUID.get(senderUuid);
                if (s != null && s.value() != null && !s.value().isBlank()) return s;
            }
        }

        // 3) global by name
        Skin s = GLOBAL_SKINS_BY_NAME.get(key);
        if (s != null && s.value() != null && !s.value().isBlank()) return s;

        return null;
    }

    public static void removeSkin(UUID target, String senderName) {
        if (target == null || senderName == null || senderName.isBlank()) return;
        var map = SKINS.get(target);
        if (map != null) {
            map.remove(senderName.toLowerCase(Locale.ROOT));
            if (map.isEmpty()) SKINS.remove(target);
        }
    }

    public static void clearSkins(UUID target) {
        if (target == null) return;
        SKINS.remove(target);
    }

    public static void clearGlobalSkins() {
        GLOBAL_SKINS_BY_NAME.clear();
        GLOBAL_SKINS_BY_UUID.clear();
    }

    // --------------------------------------------------------------------

    public static Pending get(UUID target) {
        return target == null ? null : LAST.get(target);
    }

    public static List<String> getSenders(UUID target) {
        if (target == null) return List.of();
        Map<String, Pending> map = PENDING.get(target);
        if (map == null || map.isEmpty()) return List.of();

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Pending p : map.values()) {
            if (p != null && p.senderName() != null && !p.senderName().isBlank()) out.add(p.senderName());
        }
        return new ArrayList<>(out);
    }

    public static void remove(UUID target, String senderName) {
        if (target == null || senderName == null || senderName.isBlank()) return;

        String key = senderName.toLowerCase(Locale.ROOT);

        Map<String, Pending> map = PENDING.get(target);
        if (map != null) {
            map.remove(key);
            if (map.isEmpty()) PENDING.remove(target);
        }

        removeSkin(target, senderName);

        Pending last = LAST.get(target);
        if (last != null && last.senderName() != null && last.senderName().equalsIgnoreCase(senderName)) {
            Pending newLast = null;
            Map<String, Pending> remain = PENDING.get(target);
            if (remain != null && !remain.isEmpty()) newLast = remain.values().iterator().next();
            if (newLast == null) LAST.remove(target);
            else LAST.put(target, newLast);
        }
    }

    public static void clear(UUID target) {
        if (target == null) return;
        PENDING.remove(target);
        LAST.remove(target);
        SKINS.remove(target);
    }

    public static void bypassForMs(UUID player, long ms) {
        if (player == null) return;
        BYPASS_UNTIL.put(player, System.currentTimeMillis() + Math.max(1, ms));
    }

    public static void clearBypass(UUID player) {
        if (player != null) BYPASS_UNTIL.remove(player);
    }

    public static boolean isBypassActive(UUID player) {
        if (player == null) return false;
        Long until = BYPASS_UNTIL.get(player);
        if (until == null) return false;
        if (System.currentTimeMillis() <= until) return true;
        BYPASS_UNTIL.remove(player);
        return false;
    }

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

        String key = senderName.toLowerCase(Locale.ROOT);

        var map = PENDING.get(target);
        if (map != null) {
            Pending p = map.get(key);
            if (p != null) return p;
        }

        Pending last = LAST.get(target);
        if (last == null || last.senderName() == null) return null;
        return last.senderName().equalsIgnoreCase(senderName) ? last : null;
    }

    private static Pending getPendingInternal(UUID target, String senderName) {
        if (target == null || senderName == null || senderName.isBlank()) return null;
        var map = PENDING.get(target);
        if (map == null) return null;
        return map.get(senderName.toLowerCase(Locale.ROOT));
    }
}