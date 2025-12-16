package net.chumbucket.huskhomesmenus;

import net.william278.huskhomes.event.ReceiveTeleportRequestEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

public final class TeleportRequestToggleListener implements Listener {

    private final ToggleManager toggles;
    private final OptionalProxyMessenger messenger;

    // Flip to true while testing, then set back to false
    private static final boolean DEBUG = true;

    public TeleportRequestToggleListener(ToggleManager toggles, OptionalProxyMessenger messenger) {
        this.toggles = toggles;
        this.messenger = messenger;
    }

    private enum ReqType { TPA, TPAHERE }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onReceive(ReceiveTeleportRequestEvent event) {
        final Player target = resolveTargetPlayer(event);
        if (target == null) {
            if (DEBUG) Bukkit.getLogger().info("[HHM] target == null; cannot enforce toggle");
            return;
        }

        final ReqType type = resolveRequestType(event);

        final boolean allowed = (type == ReqType.TPA)
                ? toggles.isTpaOn(target)
                : toggles.isTpahereOn(target);

        if (allowed) return;

        // Cancel always
        event.setCancelled(true);

        final String msg = ChatColor.RED + "That player has "
                + (type == ReqType.TPA ? "TPA" : "TPAHere")
                + " requests turned off.";

        // Local requester? send directly
        final Player requester = resolveRequesterPlayer(event);
        if (requester != null) {
            requester.sendMessage(msg);
            if (DEBUG) Bukkit.getLogger().info("[HHM] Sent local denial to " + requester.getName());
            return;
        }

        // Cross-server messaging requires messenger enabled
        if (messenger == null || !messenger.isEnabled()) {
            if (DEBUG) Bukkit.getLogger().info("[HHM] messenger disabled; cannot send cross-server denial");
            return;
        }

        // IMPORTANT: carrier must be an ONLINE Player on THIS backend
        final Player carrier = pickCarrier(target);
        if (carrier == null) {
            if (DEBUG) Bukkit.getLogger().info("[HHM] No carrier player online; cannot send plugin message");
            return;
        }

        // 1) Best: message by NAME through proxy
        String requesterName = resolveRequesterName(event);

        // If TPAHERE, be extra aggressive about resolving requester (some HH versions wrap it differently)
        if ((requesterName == null || requesterName.isBlank()) && type == ReqType.TPAHERE) {
            requesterName = resolveRequesterNameAggressive(event);
        }

        if (DEBUG) {
            Bukkit.getLogger().info("[HHM] Blocked " + type
                    + " target=" + target.getName()
                    + " requesterName=" + requesterName
                    + " carrier=" + carrier.getName());
        }

        if (requesterName != null && !requesterName.isBlank()) {
            boolean ok = messenger.messagePlayer(requesterName, msg, carrier);
            if (DEBUG) Bukkit.getLogger().info("[HHM] messenger.messagePlayer -> " + ok);
            if (ok) return;
        } else {
            if (DEBUG) Bukkit.getLogger().info("[HHM] requesterName unresolved; cannot use messagePlayer");
        }

        // 2) Fallback: forward by UUID (custom forward receiver must be correct)
        UUID requesterUuid = resolveRequesterUuid(event);

        // If TPAHERE, be extra aggressive about resolving requester UUID too
        if (requesterUuid == null && type == ReqType.TPAHERE) {
            requesterUuid = resolveRequesterUuidAggressive(event);
        }

        if (requesterUuid != null) {
            boolean ok = messenger.forwardTo(requesterUuid, msg, carrier);
            if (DEBUG) Bukkit.getLogger().info("[HHM] messenger.forwardTo -> " + ok);
        } else {
            if (DEBUG) Bukkit.getLogger().info("[HHM] requesterUuid unresolved; cannot forward denial");
        }
    }

    /**
     * Carrier MUST be an online player on this backend, or plugin messages will silently drop.
     */
    private Player pickCarrier(Player preferred) {
        try {
            if (preferred != null && preferred.isOnline()) return preferred;
        } catch (Throwable ignored) { }

        // Fallback: any online player
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != null && p.isOnline()) return p;
        }
        return null;
    }

    private Player resolveTargetPlayer(ReceiveTeleportRequestEvent event) {
        // direct Player getters
        Object direct = invokeAny(event, "getTargetPlayer", "getRecipientPlayer", "getTarget", "getRecipient", "getUser");
        if (direct instanceof Player p) return p;

        // try nested request object
        Object requestObj = getPrimaryRequestObject(event);
        Object targetObj = invokeAny(event,
                "getTarget", "getRecipient", "getUser", "getTargetUser", "getReceiver"
        );
        if (targetObj == null && requestObj != null) {
            targetObj = invokeAny(requestObj, "getTarget", "getRecipient", "getReceiver", "getTargetUser", "target", "recipient", "receiver");
        }

        UUID uuid = uuidFrom(targetObj);
        if (uuid != null) return Bukkit.getPlayer(uuid);

        Object nameObj = invokeAny(event, "getTargetName", "getRecipientName", "getTargetUsername", "getRecipientUsername");
        if (nameObj instanceof String name && !name.isBlank()) return Bukkit.getPlayerExact(name);

        if (requestObj != null) {
            Object n2 = invokeAny(requestObj, "getTargetName", "getRecipientName", "getTargetUsername", "getRecipientUsername",
                    "targetName", "recipientName", "targetUsername", "recipientUsername");
            if (n2 instanceof String name && !name.isBlank()) return Bukkit.getPlayerExact(name);
        }

        return null;
    }

    private Player resolveRequesterPlayer(ReceiveTeleportRequestEvent event) {
        Object direct = invokeAny(event, "getRequesterPlayer", "getSenderPlayer", "getRequester", "getSender");
        if (direct instanceof Player p) return p;

        Object requesterObj = getRequesterObject(event);
        UUID uuid = uuidFrom(requesterObj);
        if (uuid != null) return Bukkit.getPlayer(uuid);

        Object nameObj = invokeAny(event, "getRequesterName", "getSenderName", "getRequesterUsername",
                "getSenderUsername", "getRequesterPlayerName");
        if (nameObj instanceof String name && !name.isBlank()) return Bukkit.getPlayerExact(name);

        // try nested request object name
        Object requestObj = getPrimaryRequestObject(event);
        if (requestObj != null) {
            Object n2 = invokeAny(requestObj, "getRequesterName", "getSenderName", "getRequesterUsername",
                    "getSenderUsername", "requesterName", "senderName", "requesterUsername", "senderUsername");
            if (n2 instanceof String name && !name.isBlank()) return Bukkit.getPlayerExact(name);
        }

        return null;
    }

    private UUID resolveRequesterUuid(ReceiveTeleportRequestEvent event) {
        Object requesterObj = getRequesterObject(event);
        UUID uuid = uuidFrom(requesterObj);
        if (uuid != null) return uuid;

        // direct player (only works if local)
        Object direct = invokeAny(event, "getRequesterPlayer", "getSenderPlayer");
        uuid = uuidFrom(direct);
        if (uuid != null) return uuid;

        // try nested request object
        Object requestObj = getPrimaryRequestObject(event);
        if (requestObj != null) {
            Object ro = invokeAny(requestObj, "getRequester", "getSender", "requester", "sender");
            uuid = uuidFrom(ro);
            if (uuid != null) return uuid;
        }

        return null;
    }

    private String resolveRequesterName(ReceiveTeleportRequestEvent event) {
        Object nameObj = invokeAny(event, "getRequesterName", "getSenderName", "getRequesterUsername",
                "getSenderUsername", "getRequesterPlayerName");
        if (nameObj instanceof String s && !s.isBlank()) return s;

        Object requesterObj = getRequesterObject(event);
        Object maybeName = invokeAny(requesterObj, "getUsername", "getName", "username", "name", "getPlayerName", "playerName");
        if (maybeName instanceof String s2 && !s2.isBlank()) return s2;

        // try nested request object
        Object requestObj = getPrimaryRequestObject(event);
        if (requestObj != null) {
            Object n2 = invokeAny(requestObj, "getRequesterName", "getSenderName", "getRequesterUsername",
                    "getSenderUsername", "requesterName", "senderName", "requesterUsername", "senderUsername");
            if (n2 instanceof String s3 && !s3.isBlank()) return s3;

            Object ro = invokeAny(requestObj, "getRequester", "getSender", "requester", "sender");
            Object n3 = invokeAny(ro, "getUsername", "getName", "username", "name", "getPlayerName", "playerName");
            if (n3 instanceof String s4 && !s4.isBlank()) return s4;
        }

        return null;
    }

    /**
     * Extra resolver for TPAHERE: some versions store sender/requester on a different nested object.
     */
    private String resolveRequesterNameAggressive(ReceiveTeleportRequestEvent event) {
        for (Object candidate : getAllRequestCandidates(event)) {
            if (candidate == null) continue;

            Object n1 = invokeAny(candidate,
                    "getRequesterName", "getSenderName", "getRequesterUsername", "getSenderUsername",
                    "requesterName", "senderName", "requesterUsername", "senderUsername"
            );
            if (n1 instanceof String s && !s.isBlank()) return s;

            Object ro = invokeAny(candidate, "getRequester", "getSender", "requester", "sender");
            Object n2 = invokeAny(ro, "getUsername", "getName", "username", "name", "getPlayerName", "playerName");
            if (n2 instanceof String s2 && !s2.isBlank()) return s2;
        }
        return null;
    }

    private UUID resolveRequesterUuidAggressive(ReceiveTeleportRequestEvent event) {
        for (Object candidate : getAllRequestCandidates(event)) {
            if (candidate == null) continue;

            Object ro = invokeAny(candidate, "getRequester", "getSender", "requester", "sender");
            UUID uuid = uuidFrom(ro);
            if (uuid != null) return uuid;

            // sometimes requester uuid is directly on the request object
            uuid = uuidFrom(candidate);
            if (uuid != null) return uuid;
        }
        return null;
    }

    private Object getRequesterObject(ReceiveTeleportRequestEvent event) {
        Object requesterObj = invokeAny(event,
                "getRequester", "getSender", "getRequesterUser", "getRequesterOnlineUser", "getRequestSender",
                // also support no-get style
                "requester", "sender", "requesterUser", "requesterOnlineUser", "requestSender"
        );

        if (requesterObj != null) return requesterObj;

        Object requestObj = getPrimaryRequestObject(event);
        if (requestObj != null) {
            requesterObj = invokeAny(requestObj, "getRequester", "getSender", "requester", "sender");
        }

        return requesterObj;
    }

    private ReqType resolveRequestType(ReceiveTeleportRequestEvent event) {
        Object typeObj = invokeAny(event, "getRequestType", "getType", "requestType", "type");
        if (typeObj == null) {
            Object requestObj = getPrimaryRequestObject(event);
            if (requestObj != null) {
                typeObj = invokeAny(requestObj, "getType", "getRequestType", "type", "requestType");
            }
        }

        String name = (typeObj == null) ? "" : typeObj.toString().toUpperCase(Locale.ROOT);
        return name.contains("HERE") ? ReqType.TPAHERE : ReqType.TPA;
    }

    private UUID uuidFrom(Object obj) {
        if (obj == null) return null;
        if (obj instanceof UUID u) return u;
        if (obj instanceof Player p) return p.getUniqueId();

        // common UUID getters + common no-get styles seen in APIs
        Object u = invokeAny(obj,
                "getUuid", "getUUID", "getUniqueId", "getUniqueID",
                "uuid", "UUID", "uniqueId", "uniqueID",
                "getId", "id"
        );

        if (u instanceof UUID uuid) return uuid;
        if (u instanceof String s) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    /**
     * The "normal" request object we already used before.
     */
    private Object getPrimaryRequestObject(ReceiveTeleportRequestEvent event) {
        return invokeAny(event, "getTeleportRequest", "getRequest");
    }

    /**
     * Candidates to try for TPAHERE where sender/requester might live.
     */
    private Object[] getAllRequestCandidates(ReceiveTeleportRequestEvent event) {
        Object req1 = invokeAny(event, "getTeleportRequest", "getRequest");
        Object req2 = invokeAny(event, "getHereRequest", "getTpahereRequest", "getTeleportHereRequest");
        Object req3 = invokeAny(event, "getTeleport", "getTeleportAction");
        Object req4 = invokeAny(req1, "getInnerRequest", "getRequest", "request", "innerRequest");
        return new Object[] { req1, req2, req3, req4 };
    }

    /**
     * Tries both exact name and also a "getX" fallback if you pass "x".
     * Example: passing "uuid" will also try "getUuid".
     */
    private Object invokeAny(Object target, String... methodNames) {
        if (target == null) return null;

        for (String raw : methodNames) {
            if (raw == null || raw.isBlank()) continue;

            // try as-is
            Object v = invoke0(target, raw);
            if (v != null) return v;

            // if not already a getter, try getX
            if (!raw.startsWith("get") && raw.length() > 1) {
                String getter = "get" + Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
                v = invoke0(target, getter);
                if (v != null) return v;
            }
        }
        return null;
    }

    private Object invoke0(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
