package net.chumbucket.huskhomesmenus;

import net.william278.huskhomes.event.ReceiveTeleportRequestEvent;
import org.bukkit.Bukkit;
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
    private final HHMConfig config;
    private final boolean debug;

    public TeleportRequestToggleListener(ToggleManager toggles, OptionalProxyMessenger messenger, HHMConfig config) {
        this.toggles = toggles;
        this.messenger = messenger;
        this.config = config;
        this.debug = config.debug();
    }

    private enum ReqType { TPA, TPAHERE }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onReceive(ReceiveTeleportRequestEvent event) {
        final Player target = resolveTargetPlayer(event);
        if (target == null) {
            if (debug) Bukkit.getLogger().info("target == null; cannot enforce toggle");
            return;
        }

        final ReqType type = resolveRequestType(event);

        final boolean tpaOn = toggles.isTpaOn(target);
        final boolean tpahereOn = toggles.isTpahereOn(target);

        final boolean allowed = (type == ReqType.TPA) ? tpaOn : tpahereOn;
        if (allowed) {
            String requesterName = resolveRequesterName(event);
            ConfirmRequestMenu.RequestType rt = (type == ReqType.TPA)
                    ? ConfirmRequestMenu.RequestType.TPA
                    : ConfirmRequestMenu.RequestType.TPAHERE;

            UUID requesterUuid = resolveRequesterUuid(event);

            if (requesterName != null && !requesterName.isBlank()) {
                PendingRequests.set(target.getUniqueId(), requesterName, requesterUuid, rt);
            }

            // ✅ SKIN HANDSHAKE: if requester is remote, ask their backend for textures and store for our menu head
            if (config.proxyEnabled()
                    && messenger != null
                    && messenger.isEnabled()
                    && requesterName != null
                    && !requesterName.isBlank()
                    && target.isOnline()) {

                Player requesterLocal = resolveRequesterPlayer(event);
                boolean requesterIsLocal = (requesterLocal != null);

                if (!requesterIsLocal) {
                    long reqId = (System.nanoTime() ^ (System.currentTimeMillis() << 1)) & Long.MAX_VALUE;
                    boolean ok = messenger.requestSkinByName(requesterName, target.getName(), target.getUniqueId(), reqId);

                    if (debug) Bukkit.getLogger().info("requestSkinByName(" + requesterName + " -> " + target.getName() + ") ok=" + ok);
                }
            }

            // ✅ TPMENU TOGGLE: if target disabled the GUI menu, let HuskHomes handle it normally (do NOT cancel)
            // (No other logic removed; we simply early-return before any menu opening code elsewhere)
            if (!toggles.isTpMenuOn(target)) {
                if (debug) Bukkit.getLogger().info("tpmenu is OFF for " + target.getName() + "; skipping menu");
                return;
            }

            return;
        }

        event.setCancelled(true);

        if (config.isEnabled("messages.target.blocked_notice.enabled", false)) {
            String requesterName = resolveRequesterName(event);
            if (requesterName == null || requesterName.isBlank()) requesterName = "someone";
            String notice = config.msgWithPrefix("messages.target.blocked_notice.text",
                    "&7You blocked a teleport request from &f%sender%&7.")
                    .replace("%sender%", requesterName);
            target.sendMessage(notice);
        }

        String msg = null;
        if (!tpaOn && !tpahereOn) {
            if (config.isEnabled("messages.sender.both_off.enabled", true)) {
                msg = config.msgWithPrefix("messages.sender.both_off.text",
                        "&cThat player has teleport requests off.");
            }
        } else if (type == ReqType.TPA) {
            if (config.isEnabled("messages.sender.tpa_off.enabled", true)) {
                msg = config.msgWithPrefix("messages.sender.tpa_off.text",
                        "&cThat player has &lTPA&r &crequests off.");
            }
        } else {
            if (config.isEnabled("messages.sender.tpahere_off.enabled", true)) {
                msg = config.msgWithPrefix("messages.sender.tpahere_off.text",
                        "&cThat player has &lTPAHere&r &crequests off.");
            }
        }

        if (msg == null) msg = "";

        final Player requester = resolveRequesterPlayer(event);
        if (requester != null) {
            if (!msg.isEmpty()) requester.sendMessage(msg);
            if (debug) Bukkit.getLogger().info("Sent local denial to " + requester.getName());
            return;
        }

        if (messenger == null || !messenger.isEnabled()) {
            if (debug) Bukkit.getLogger().info("messenger disabled; cannot send cross-server denial");
            return;
        }

        Player carrier = anyOnlinePlayer();
        if (carrier == null) {
            if (debug) Bukkit.getLogger().info("No carrier player online; cannot send plugin message");
            return;
        }

        final String requesterName = resolveRequesterName(event);
        if (requesterName != null && !requesterName.isBlank() && !msg.isEmpty()) {
            boolean ok = messenger.messagePlayer(requesterName, msg);
            if (debug) Bukkit.getLogger().info("messenger.messagePlayer -> " + ok);
            if (ok) return;
        } else {
            if (debug) Bukkit.getLogger().info("requesterName unresolved; cannot use messagePlayer");
        }

        final UUID requesterUuid = resolveRequesterUuid(event);
        if (requesterUuid != null && !msg.isEmpty()) {
            boolean ok = tryForwardToIfPresent(messenger, requesterUuid, msg);
            if (debug) Bukkit.getLogger().info("messenger.forwardTo (reflective) -> " + ok);
        } else {
            if (debug) Bukkit.getLogger().info("requesterUuid unresolved; cannot forward denial");
        }
    }

    private boolean tryForwardToIfPresent(OptionalProxyMessenger messenger, UUID uuid, String msg) {
        try {
            Method m = messenger.getClass().getMethod("forwardTo", UUID.class, String.class);
            Object r = m.invoke(messenger, uuid, msg);
            if (r instanceof Boolean b) return b;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Player anyOnlinePlayer() {
        for (Player p : Bukkit.getOnlinePlayers()) return p;
        return null;
    }

    private ReqType resolveRequestType(ReceiveTeleportRequestEvent event) {
        String s = event.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (s.contains("here")) return ReqType.TPAHERE;

        Object requestObj = getPrimaryRequestObject(event);
        if (requestObj != null) {
            Object type = invokeAny(requestObj, "getType", "type", "getRequestType", "requestType");
            if (type != null && type.toString().toLowerCase(Locale.ROOT).contains("here")) {
                return ReqType.TPAHERE;
            }
        }
        return ReqType.TPA;
    }

    private Player resolveTargetPlayer(ReceiveTeleportRequestEvent event) {
        Object targetObj = invokeAny(event, "getTarget", "target", "getRecipient", "recipient", "getUser", "user");
        if (targetObj instanceof Player p) return p;

        Object maybeUuid = invokeAny(targetObj, "getUuid", "uuid", "getUniqueId", "uniqueId");
        if (maybeUuid instanceof UUID uuid) return Bukkit.getPlayer(uuid);

        return null;
    }

    private Player resolveRequesterPlayer(ReceiveTeleportRequestEvent event) {
        UUID uuid = resolveRequesterUuid(event);
        if (uuid == null) return null;
        return Bukkit.getPlayer(uuid);
    }

    private UUID resolveRequesterUuid(ReceiveTeleportRequestEvent event) {
        Object requesterObj = invokeAny(event, "getRequester", "requester", "getSender", "sender");
        Object uuid1 = invokeAny(requesterObj, "getUuid", "uuid", "getUniqueId", "uniqueId");
        if (uuid1 instanceof UUID u) return u;

        Object requestObj = getPrimaryRequestObject(event);
        Object uuid2 = invokeAny(requestObj, "getRequesterUuid", "getSenderUuid", "requesterUuid", "senderUuid");
        if (uuid2 instanceof UUID u2) return u2;

        Object requester2 = invokeAny(requestObj, "getRequester", "getSender", "requester", "sender");
        Object uuid3 = invokeAny(requester2, "getUuid", "uuid", "getUniqueId", "uniqueId");
        if (uuid3 instanceof UUID u3) return u3;

        return null;
    }

    private String resolveRequesterName(ReceiveTeleportRequestEvent event) {
        Object requesterObj = invokeAny(event, "getRequester", "requester", "getSender", "sender");
        Object maybeName = invokeAny(requesterObj, "getUsername", "getName", "username", "name", "getPlayerName", "playerName");
        if (maybeName instanceof String s && !s.isBlank()) return s;

        Object requestObj = getPrimaryRequestObject(event);
        if (requestObj != null) {
            Object n2 = invokeAny(requestObj, "getRequesterName", "getSenderName", "getRequesterUsername",
                    "getSenderUsername", "requesterName", "senderName", "requesterUsername", "senderUsername");
            if (n2 instanceof String s2 && !s2.isBlank()) return s2;

            Object ro = invokeAny(requestObj, "getRequester", "getSender", "requester", "sender");
            Object n3 = invokeAny(ro, "getUsername", "getName", "username", "name", "getPlayerName", "playerName");
            if (n3 instanceof String s3 && !s3.isBlank()) return s3;
        }

        return null;
    }

    private Object getPrimaryRequestObject(ReceiveTeleportRequestEvent event) {
        return invokeAny(event, "getRequest", "request", "getTeleportRequest", "teleportRequest");
    }

    private Object invokeAny(Object target, String... methodNames) {
        if (target == null) return null;
        for (String name : methodNames) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) { }
        }
        return null;
    }
}
