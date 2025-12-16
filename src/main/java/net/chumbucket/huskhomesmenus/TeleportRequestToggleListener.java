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

    public TeleportRequestToggleListener(ToggleManager toggles) {
        this.toggles = toggles;
    }

    private enum ReqType { TPA, TPAHERE }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReceive(ReceiveTeleportRequestEvent event) {
        final Player target = getTargetPlayer(event);
        if (target == null) return;

        final ReqType type = getRequestType(event);

        final boolean allowed = switch (type) {
            case TPA -> toggles.isTpaOn(target);
            case TPAHERE -> toggles.isTpahereOn(target);
        };

        if (allowed) return;

        // Block it
        event.setCancelled(true);

        // IMPORTANT: do NOT message the target (your requirement)

        final String msg = ChatColor.RED + "That player has "
                + (type == ReqType.TPA ? "TPA" : "TPAHere")
                + " requests turned off.";

        // Try to message requester cross-server via HuskHomes event objects
        boolean sent = sendToRequester(event, msg);

        if (!sent) {
            // Last-resort fallback: if requester happens to be local, message via Bukkit
            UUID requesterUuid = getRequesterUuid(event);
            if (requesterUuid != null) {
                Player localRequester = Bukkit.getPlayer(requesterUuid);
                if (localRequester != null) {
                    localRequester.sendMessage(msg);
                    sent = true;
                }
            }
        }

        // Debug logging (remove later if you want)
        Bukkit.getLogger().info("[HuskHomesMenus] Blocked "
                + (type == ReqType.TPA ? "TPA" : "TPAHere")
                + " request to " + target.getName()
                + " | requesterMsgSent=" + sent);
    }

    /**
     * Attempts to message the requester using whatever HuskHomes exposes on the event.
     * This is the key to making the message show up even when requester is on another server.
     */
    private boolean sendToRequester(ReceiveTeleportRequestEvent event, String message) {
        // Common patterns across HuskHomes versions:
        // event.getRequester() / getSender() / getRequesterUser() etc.
        Object requesterUser = invoke(event,
                "getRequester", "getSender", "getRequesterUser", "getRequesterOnlineUser", "getRequestSender");

        if (requesterUser == null) {
            return false;
        }

        // 1) sendMessage(String)
        if (invokeMethod(requesterUser, "sendMessage", new Class<?>[]{String.class}, new Object[]{message})) {
            return true;
        }

        // 2) sendMessage(Component) (Adventure)
        Object component = makeAdventureTextComponent(message);
        if (component != null) {
            Class<?> componentClass = component.getClass();
            if (invokeMethod(requesterUser, "sendMessage", new Class<?>[]{componentClass}, new Object[]{component})) {
                return true;
            }
            // Some APIs accept supertype net.kyori.adventure.text.Component, not the impl class
            Class<?> componentInterface = tryClass("net.kyori.adventure.text.Component");
            if (componentInterface != null
                    && componentInterface.isAssignableFrom(componentClass)
                    && invokeMethod(requesterUser, "sendMessage", new Class<?>[]{componentInterface}, new Object[]{component})) {
                return true;
            }
        }

        // 3) requesterUser.getPlayer() -> Bukkit Player then message
        Object playerObj = invoke(requesterUser, "getPlayer", "asPlayer");
        if (playerObj instanceof Player p) {
            p.sendMessage(message);
            return true;
        }

        // 4) requesterUser.getUuid() -> Bukkit local lookup
        UUID uuid = getUuidFromUserLike(requesterUser);
        if (uuid != null) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(message);
                return true;
            }
        }

        return false;
    }

    private Object makeAdventureTextComponent(String message) {
        try {
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
            Method text = componentClass.getMethod("text", String.class);
            return text.invoke(null, ChatColor.stripColor(message));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Class<?> tryClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean invokeMethod(Object target, String methodName, Class<?>[] sig, Object[] args) {
        try {
            Method m = target.getClass().getMethod(methodName, sig);
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
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
