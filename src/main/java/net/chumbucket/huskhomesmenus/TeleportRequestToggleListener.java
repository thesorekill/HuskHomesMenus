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

/**
 * Enforce per-type toggles on the server where the TARGET is online.
 *
 * Messaging behavior:
 * - If the requester is on the same backend: message them directly (single-server support).
 * - If requester is on another backend AND proxy messaging is available: forward message via proxy.
 * - Never message the target when blocked.
 */
public final class TeleportRequestToggleListener implements Listener {

    private final ToggleManager toggles;
    private final OptionalProxyMessenger proxyMessenger;

    public TeleportRequestToggleListener(ToggleManager toggles, OptionalProxyMessenger proxyMessenger) {
        this.toggles = toggles;
        this.proxyMessenger = proxyMessenger;
    }

    private enum ReqType { TPA, TPAHERE }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReceive(ReceiveTeleportRequestEvent event) {
        Player target = getTargetPlayer(event);
        if (target == null) return;

        ReqType type = getRequestType(event);

        boolean allowed = switch (type) {
            case TPA -> toggles.isTpaOn(target);
            case TPAHERE -> toggles.isTpahereOn(target);
        };

        if (allowed) return;

        // Block request
        event.setCancelled(true);

        // Build requester message (exact phrasing you requested)
        String msg = ChatColor.RED + "That player has "
                + (type == ReqType.TPA ? "TPA" : "TPAHere")
                + " requests turned off.";

        UUID requesterUuid = getRequesterUuid(event);
        if (requesterUuid == null) return;

        // Single-server / same-backend
        Player localRequester = Bukkit.getPlayer(requesterUuid);
        if (localRequester != null) {
            localRequester.sendMessage(msg);
            return;
        }

        // Cross-server (optional): forward via proxy messaging (requires plugin installed on BOTH servers)
        if (proxyMessenger != null && proxyMessenger.isEnabled()) {
            proxyMessenger.forwardTo(requesterUuid, msg, target);
        }
    }

    private Player getTargetPlayer(ReceiveTeleportRequestEvent event) {
        UUID uuid = getUuidFromUserLike(invoke(event,
                "getTarget", "getRecipient", "getUser", "getTargetUser", "getReceiver"));
        if (uuid == null) return null;
        return Bukkit.getPlayer(uuid);
    }

    private UUID getRequesterUuid(ReceiveTeleportRequestEvent event) {
        return getUuidFromUserLike(invoke(event,
                "getRequester", "getSender", "getRequesterUser", "getRequesterOnlineUser", "getRequestSender"));
    }

    private ReqType getRequestType(ReceiveTeleportRequestEvent event) {
        Object typeObj = invoke(event, "getRequestType", "getType");
        if (typeObj == null) {
            Object requestObj = invoke(event, "getTeleportRequest", "getRequest");
            if (requestObj != null) {
                typeObj = invoke(requestObj, "getType", "getRequestType");
            }
        }

        String name = (typeObj == null) ? "" : typeObj.toString().toUpperCase(Locale.ROOT);
        return name.contains("HERE") ? ReqType.TPAHERE : ReqType.TPA;
    }

    private Object invoke(Object target, String... methodNames) {
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private UUID getUuidFromUserLike(Object userLike) {
        if (userLike == null) return null;

        if (userLike instanceof UUID u) return u;

        Object uuidObj = invoke(userLike, "getUuid", "getUUID", "getUniqueId", "getUniqueID", "uuid", "uniqueId");
        if (uuidObj instanceof UUID u) return u;

        if (uuidObj instanceof String s) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {}
        }

        return null;
    }
}
