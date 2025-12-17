package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class OptionalProxyMessenger implements PluginMessageListener {

    // Support both (different setups expect different channel IDs)
    public static final String CHANNEL_MODERN = "bungeecord:main";
    public static final String CHANNEL_LEGACY = "BungeeCord";

    // Used by forwardTo(UUID,msg) (TeleportRequestToggleListener)
    public static final String SUBCHANNEL = "HuskHomesMenus:Msg";

    private final JavaPlugin plugin;
    private final HHMConfig config;
    private boolean enabled = false;

    // --- Callbacks for proxy queries (region cache) ---
    private final List<Consumer<List<String>>> getServersCallbacks = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<String, List<String>>> playerListServerCallbacks = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<String>>> playerListAllCallbacks = new CopyOnWriteArrayList<>();

    // prevent double-processing when both channels deliver same response
    private volatile long lastReceiveMs = 0L;

    public OptionalProxyMessenger(JavaPlugin plugin, HHMConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void tryEnable() {
        if (!config.proxyEnabled()) {
            enabled = false;
            plugin.getLogger().info("Proxy messaging disabled in config.yml (proxy.enabled=false).");
            return;
        }

        try {
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_MODERN, this);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_LEGACY, this);

            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_MODERN);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_LEGACY);

            enabled = true;
            plugin.getLogger().info("Optional proxy messaging enabled (channels: " + CHANNEL_MODERN + " & " + CHANNEL_LEGACY + ")");
        } catch (Throwable t) {
            enabled = false;
            plugin.getLogger().warning("Optional proxy messaging could not be enabled: " + t.getMessage());
        }
    }

    // =========================================================
    // ✅ Existing API used by TeleportRequestToggleListener
    // =========================================================

    /** Proxy "Message" subchannel (sends chat message to player by name). */
    public boolean messagePlayer(String playerName, String message) {
        if (!enabled) return false;
        if (playerName == null || playerName.isBlank()) return false;

        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            byte[] payload = buildMessagePacket(playerName, message);
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, payload);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, payload);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to proxy-message '" + playerName + "': " + t.getMessage());
            return false;
        }
    }

    /** Proxy "ForwardToPlayer" to deliver data to target backend (SUBCHANNEL). */
    public boolean forwardTo(UUID targetUuid, String message) {
        if (!enabled) return false;
        if (targetUuid == null) return false;

        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            ByteArrayOutputStream msgBytes = new ByteArrayOutputStream();
            DataOutputStream msgOut = new DataOutputStream(msgBytes);
            msgOut.writeUTF(message == null ? "" : message);

            byte[] forward = buildForwardToPlayerPacket(targetUuid.toString(), SUBCHANNEL, msgBytes.toByteArray());
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, forward);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, forward);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to proxy-forward '" + targetUuid + "': " + t.getMessage());
            return false;
        }
    }

    // =========================================================
    // ✅ New API used by ProxyPlayerCache (REGION mapping)
    // =========================================================

    /** GetServers -> returns list of server IDs (proxy side names). */
    public boolean requestProxyServers(Consumer<List<String>> callback) {
        if (!enabled) return false;
        if (callback != null) getServersCallbacks.add(callback);

        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            byte[] payload = buildGetServersPacket();
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, payload);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, payload);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to request proxy servers: " + t.getMessage());
            return false;
        }
    }

    /** PlayerList <server> -> returns players on that specific server. */
    public boolean requestPlayerListForServer(String server, BiConsumer<String, List<String>> callback) {
        if (!enabled) return false;
        if (server == null || server.isBlank()) return false;
        if (callback != null) playerListServerCallbacks.add(callback);

        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            byte[] payload = buildPlayerListPacket(server);
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, payload);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, payload);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to request PlayerList for server " + server + ": " + t.getMessage());
            return false;
        }
    }

    /** PlayerList ALL -> returns players across proxy. (Optional; may be used elsewhere.) */
    public boolean requestProxyPlayerList(Consumer<List<String>> callback) {
        if (!enabled) return false;
        if (callback != null) playerListAllCallbacks.add(callback);

        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            byte[] payload = buildPlayerListPacket("ALL");
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, payload);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, payload);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to request PlayerList ALL: " + t.getMessage());
            return false;
        }
    }

    // =========================================================
    // Packet builders
    // =========================================================

    private byte[] buildMessagePacket(String playerName, String message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("Message");
        out.writeUTF(playerName);
        out.writeUTF(message == null ? "" : message);
        return bytes.toByteArray();
    }

    private byte[] buildForwardToPlayerPacket(String playerUuid, String subchannel, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("ForwardToPlayer");
        out.writeUTF(playerUuid);
        out.writeUTF(subchannel);
        out.writeShort(data.length);
        out.write(data);
        return bytes.toByteArray();
    }

    private byte[] buildGetServersPacket() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("GetServers");
        return bytes.toByteArray();
    }

    private byte[] buildPlayerListPacket(String server) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("PlayerList");
        out.writeUTF(server);
        return bytes.toByteArray();
    }

    private Player anyOnlinePlayer() {
        for (Player p : Bukkit.getOnlinePlayers()) return p;
        return null;
    }

    // =========================================================
    // Incoming responses for GetServers / PlayerList
    // =========================================================

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!enabled) return;
        if (message == null || message.length == 0) return;

        // ignore duplicates from modern+legacy back-to-back
        long now = System.currentTimeMillis();
        if (now - lastReceiveMs < 75) return;
        lastReceiveMs = now;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String sub = in.readUTF();
            if (sub == null || sub.isBlank()) return;

            if ("GetServers".equalsIgnoreCase(sub)) {
                String csv = in.readUTF();
                List<String> servers = parseCsv(csv);
                for (var cb : getServersCallbacks) {
                    try { cb.accept(servers); } catch (Throwable ignored) {}
                }
                getServersCallbacks.clear();
                return;
            }

            if ("PlayerList".equalsIgnoreCase(sub)) {
                String server = in.readUTF(); // "ALL" or specific server
                String csv = in.readUTF();
                List<String> names = parseCsv(csv);

                if ("ALL".equalsIgnoreCase(server)) {
                    for (var cb : playerListAllCallbacks) {
                        try { cb.accept(names); } catch (Throwable ignored) {}
                    }
                    playerListAllCallbacks.clear();
                } else {
                    for (var cb : playerListServerCallbacks) {
                        try { cb.accept(server, names); } catch (Throwable ignored) {}
                    }
                    playerListServerCallbacks.clear();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private List<String> parseCsv(String csv) {
        if (csv == null) return List.of();
        csv = csv.trim();
        if (csv.isEmpty()) return List.of();

        String[] parts = csv.split(",\\s*");
        ArrayList<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
