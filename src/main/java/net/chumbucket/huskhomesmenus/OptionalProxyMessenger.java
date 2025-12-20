package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class OptionalProxyMessenger implements PluginMessageListener {

    public static final String CHANNEL_MODERN = "bungeecord:main";
    public static final String CHANNEL_LEGACY = "BungeeCord";

    public static final String SUBCHANNEL = "HuskHomesMenus:Msg";

    private final JavaPlugin plugin;
    private final HHMConfig config;
    private boolean enabled = false;

    // One-shot GetServers callbacks
    private final List<Consumer<List<String>>> getServersCallbacks = Collections.synchronizedList(new ArrayList<>());

    // Per-server one-shot callbacks (serverLower -> callback)
    private final ConcurrentHashMap<String, BiConsumer<String, List<String>>> perServerPlayerList = new ConcurrentHashMap<>();

    // One-shot PlayerList ALL callback(s)
    private final List<Consumer<List<String>>> playerListAllCallbacks = Collections.synchronizedList(new ArrayList<>());

    // =========================================================
    // ✅ Dimension response sink
    // =========================================================

    public static final class DimResponse {
        public final String playerName;
        public final String dimension;
        public final long requestId;

        public DimResponse(String playerName, String dimension, long requestId) {
            this.playerName = playerName;
            this.dimension = dimension;
            this.requestId = requestId;
        }
    }

    private volatile Consumer<DimResponse> dimensionSink;

    // =========================================================
    // ✅ Skin response sink (optional)
    // =========================================================

    public static final class SkinResponse {
        public final UUID targetUuid;       // viewer/target
        public final String playerName;     // subject (sender)
        public final UUID playerUuid;       // subject uuid
        public final String value;
        public final String signature;
        public final long requestId;

        public SkinResponse(UUID targetUuid, String playerName, UUID playerUuid, String value, String signature, long requestId) {
            this.targetUuid = targetUuid;
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.value = value;
            this.signature = signature;
            this.requestId = requestId;
        }
    }

    private volatile Consumer<SkinResponse> skinSink;

    public OptionalProxyMessenger(JavaPlugin plugin, HHMConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setDimensionSink(Consumer<DimResponse> sink) {
        this.dimensionSink = sink;
    }

    public void setSkinSink(Consumer<SkinResponse> sink) {
        this.skinSink = sink;
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
    // Existing API
    // =========================================================

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

    // =========================================================
    // ForwardToPlayer by NAME
    // =========================================================

    public boolean forwardSubchannelToPlayerName(String targetPlayerName, String subchannel, byte[] data) {
        if (!enabled) return false;
        if (targetPlayerName == null || targetPlayerName.isBlank()) return false;
        if (subchannel == null || subchannel.isBlank()) return false;
        if (data == null) data = new byte[0];

        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            byte[] forward = buildForwardToPlayerPacket(targetPlayerName, subchannel, data);
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, forward);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, forward);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to proxy-forward to '" + targetPlayerName + "': " + t.getMessage());
            return false;
        }
    }

    /**
     * Ask a (possibly remote) player what dimension they are in.
     * The remote backend replies back to requesterName with DIM_RESP(subjectName, dimension, requestId).
     */
    public boolean requestDimensionByName(String remotePlayerName, String requesterName, long requestId) {
        if (!enabled) return false;
        if (remotePlayerName == null || remotePlayerName.isBlank()) return false;
        if (requesterName == null || requesterName.isBlank()) return false;

        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(payloadBytes);
            out.writeUTF("DIM_REQ");
            out.writeUTF(requesterName);
            out.writeLong(requestId);

            return forwardSubchannelToPlayerName(remotePlayerName, SUBCHANNEL, payloadBytes.toByteArray());
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to request dimension from " + remotePlayerName + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * ✅ Ask a (possibly remote) player for their skin textures.
     * Remote backend replies to requesterName with:
     * SKIN_RESP(targetUuid, subjectName, subjectUuid, texturesValue, texturesSignature, requestId)
     */
    public boolean requestSkinByName(String remotePlayerName, String requesterName, UUID targetUuid, long requestId) {
        if (!enabled) return false;
        if (remotePlayerName == null || remotePlayerName.isBlank()) return false;
        if (requesterName == null || requesterName.isBlank()) return false;
        if (targetUuid == null) return false;

        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(payloadBytes);
            out.writeUTF("SKIN_REQ");
            out.writeUTF(requesterName);
            out.writeUTF(targetUuid.toString());
            out.writeLong(requestId);

            return forwardSubchannelToPlayerName(remotePlayerName, SUBCHANNEL, payloadBytes.toByteArray());
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to request skin from " + remotePlayerName + ": " + t.getMessage());
            return false;
        }
    }

    // =========================================================
    // API used by ProxyPlayerCache
    // =========================================================

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

    public boolean requestPlayerListForServer(String server, BiConsumer<String, List<String>> callback) {
        if (!enabled) return false;
        if (server == null || server.isBlank()) return false;

        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            if (callback != null) {
                perServerPlayerList.put(server.toLowerCase(Locale.ROOT), callback);
            }

            byte[] payload = buildPlayerListPacket(server);
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, payload);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, payload);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to request PlayerList for server " + server + ": " + t.getMessage());
            return false;
        }
    }

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

    private byte[] buildForwardToPlayerPacket(String playerName, String subchannel, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("ForwardToPlayer");
        out.writeUTF(playerName);
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

    private String resolveDimension(Player p) {
        if (p == null) return "Unknown";
        World w = p.getWorld();
        if (w == null) return "Unknown";

        World.Environment env = w.getEnvironment();
        if (env == World.Environment.NORMAL) return "Overworld";
        if (env == World.Environment.NETHER) return "Nether";
        if (env == World.Environment.THE_END) return "The End";

        String name = w.getName();
        return (name == null || name.isBlank()) ? "Unknown" : name;
    }

    // =========================================================
    // ✅ Extract textures from a local Player (remote backend side)
    //    FIXED: supports modern PlayerProfile AND legacy GameProfile
    // =========================================================
    private PendingRequests.Skin extractTexturesFromPlayer(Player p) {
        if (p == null) return null;

        // -------- Strategy A: Modern Bukkit/Paper PlayerProfile API --------
        // Player#getPlayerProfile() -> org.bukkit.profile.PlayerProfile
        try {
            Method getPlayerProfile = p.getClass().getMethod("getPlayerProfile");
            Object profile = getPlayerProfile.invoke(p);
            if (profile != null) {
                // profile.getProperties() -> Collection<ProfileProperty>
                Method getProps = profile.getClass().getMethod("getProperties");
                Object propsObj = getProps.invoke(profile);

                if (propsObj instanceof Iterable<?> props) {
                    for (Object prop : props) {
                        if (prop == null) continue;

                        String name = null;
                        try {
                            Method getName = prop.getClass().getMethod("getName");
                            name = String.valueOf(getName.invoke(prop));
                        } catch (Throwable ignored) {}

                        if (name == null || !name.equalsIgnoreCase("textures")) continue;

                        String value = null;
                        String sig = null;

                        try {
                            Method getValue = prop.getClass().getMethod("getValue");
                            value = (String) getValue.invoke(prop);
                        } catch (Throwable ignored) {}

                        try {
                            Method getSignature = prop.getClass().getMethod("getSignature");
                            Object s = getSignature.invoke(prop);
                            sig = (s == null) ? null : String.valueOf(s);
                        } catch (Throwable ignored) {}

                        if (value != null && !value.isBlank()) {
                            if (config.debug()) plugin.getLogger().info("extractTextures: PlayerProfile OK for " + p.getName() + " valueLen=" + value.length());
                            return new PendingRequests.Skin(value, (sig == null || sig.isBlank()) ? null : sig);
                        }
                    }
                }
            }
        } catch (NoSuchMethodException ignored) {
            // server doesn't have getPlayerProfile
        } catch (Throwable t) {
            if (config.debug()) plugin.getLogger().info("extractTextures: PlayerProfile failed for " + p.getName() + " err=" + t.getClass().getSimpleName());
        }

        // -------- Strategy B: Legacy CraftPlayer#getProfile() -> GameProfile --------
        try {
            Method getProfile = p.getClass().getMethod("getProfile");
            Object gp = getProfile.invoke(p);
            if (gp == null) return null;

            Method getProps = gp.getClass().getMethod("getProperties");
            Object props = getProps.invoke(gp);
            if (props == null) return null;

            Method get = null;
            for (Method m : props.getClass().getMethods()) {
                if (m.getName().equals("get") && m.getParameterCount() == 1) {
                    get = m;
                    break;
                }
            }
            if (get == null) return null;

            Object texturesObj = get.invoke(props, "textures");
            if (!(texturesObj instanceof Collection<?> col) || col.isEmpty()) return null;

            Object prop = col.iterator().next();
            if (prop == null) return null;

            Method getValue = prop.getClass().getMethod("getValue");
            String value = (String) getValue.invoke(prop);

            String sig = null;
            try {
                Method getSignature = prop.getClass().getMethod("getSignature");
                sig = (String) getSignature.invoke(prop);
            } catch (Throwable ignored2) {}

            if (value == null || value.isBlank()) return null;

            if (config.debug()) plugin.getLogger().info("extractTextures: GameProfile OK for " + p.getName() + " valueLen=" + value.length());
            return new PendingRequests.Skin(value, (sig == null || sig.isBlank()) ? null : sig);

        } catch (NoSuchMethodException ignored) {
            // getProfile not present on this server build
        } catch (Throwable t) {
            if (config.debug()) plugin.getLogger().info("extractTextures: GameProfile failed for " + p.getName() + " err=" + t.getClass().getSimpleName());
        }

        return null;
    }

    // =========================================================
    // Incoming responses
    // =========================================================

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!enabled) return;
        if (message == null || message.length == 0) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String sub = in.readUTF();
            if (sub == null || sub.isBlank()) return;

            if (SUBCHANNEL.equalsIgnoreCase(sub)) {
                short len = in.readShort();
                if (len <= 0) return;
                byte[] data = new byte[len];
                in.readFully(data);

                try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(data))) {
                    String cmd = din.readUTF();
                    if (cmd == null || cmd.isBlank()) return;

                    // =================================================
                    // DIM
                    // =================================================
                    if ("DIM_REQ".equalsIgnoreCase(cmd)) {
                        String requesterName = din.readUTF();
                        if (requesterName == null || requesterName.isBlank()) return;

                        long requestId = 0L;
                        try { requestId = din.readLong(); } catch (EOFException ignored) {}

                        String dim = resolveDimension(player);

                        ByteArrayOutputStream bout = new ByteArrayOutputStream();
                        DataOutputStream dout = new DataOutputStream(bout);
                        dout.writeUTF("DIM_RESP");
                        dout.writeUTF(player != null ? player.getName() : "");
                        dout.writeUTF(dim);
                        dout.writeLong(requestId);

                        forwardSubchannelToPlayerName(requesterName, SUBCHANNEL, bout.toByteArray());
                        return;
                    }

                    if ("DIM_RESP".equalsIgnoreCase(cmd)) {
                        String name = din.readUTF();
                        String dim = din.readUTF();

                        long requestId = 0L;
                        try { requestId = din.readLong(); } catch (EOFException ignored) {}

                        Consumer<DimResponse> sink = this.dimensionSink;
                        if (sink != null && name != null && !name.isBlank()) {
                            try { sink.accept(new DimResponse(name, dim, requestId)); } catch (Throwable ignored) {}
                        }
                        return;
                    }

                    // =================================================
                    // ✅ SKIN
                    // =================================================
                    if ("SKIN_REQ".equalsIgnoreCase(cmd)) {
                        String requesterName = din.readUTF();
                        if (requesterName == null || requesterName.isBlank()) return;

                        String targetUuidStr = din.readUTF();
                        if (targetUuidStr == null || targetUuidStr.isBlank()) return;

                        UUID targetUuid;
                        try { targetUuid = UUID.fromString(targetUuidStr); }
                        catch (Throwable t) { return; }

                        long requestId = 0L;
                        try { requestId = din.readLong(); } catch (EOFException ignored) {}

                        if (config.debug()) {
                            plugin.getLogger().info("SKIN_REQ received: subject=" + (player != null ? player.getName() : "null")
                                    + " requester=" + requesterName + " target=" + targetUuidStr);
                        }

                        PendingRequests.Skin skin = extractTexturesFromPlayer(player);
                        if (skin == null || skin.value() == null || skin.value().isBlank()) {
                            if (config.debug()) {
                                plugin.getLogger().info("SKIN_REQ: no textures available for subject=" + (player != null ? player.getName() : "null"));
                            }
                            return;
                        }

                        UUID subjectUuid = null;
                        try { subjectUuid = (player != null ? player.getUniqueId() : null); } catch (Throwable ignored) {}

                        ByteArrayOutputStream bout = new ByteArrayOutputStream();
                        DataOutputStream dout = new DataOutputStream(bout);
                        dout.writeUTF("SKIN_RESP");
                        dout.writeUTF(targetUuid.toString());
                        dout.writeUTF(player != null ? player.getName() : "");
                        dout.writeUTF(subjectUuid != null ? subjectUuid.toString() : "");
                        dout.writeUTF(skin.value());
                        dout.writeUTF(skin.signature() == null ? "" : skin.signature());
                        dout.writeLong(requestId);

                        if (config.debug()) {
                            plugin.getLogger().info("Sending SKIN_RESP -> requester=" + requesterName
                                    + " subject=" + (player != null ? player.getName() : "null")
                                    + " valueLen=" + skin.value().length());
                        }

                        forwardSubchannelToPlayerName(requesterName, SUBCHANNEL, bout.toByteArray());
                        return;
                    }

                    if ("SKIN_RESP".equalsIgnoreCase(cmd)) {
                        String targetUuidStr = din.readUTF();
                        String subjectName = din.readUTF();
                        String subjectUuidStr = din.readUTF();
                        String value = din.readUTF();
                        String sig = din.readUTF();

                        // ✅ DEBUG LINE YOU REQUESTED
                        if (config.debug()) {
                            plugin.getLogger().info("SKIN_RESP for target=" + targetUuidStr + " subject=" + subjectName
                                    + " valueLen=" + (value == null ? 0 : value.length()));
                        }

                        long requestId = 0L;
                        try { requestId = din.readLong(); } catch (EOFException ignored) {}

                        UUID targetUuid;
                        try { targetUuid = UUID.fromString(targetUuidStr); }
                        catch (Throwable t) { return; }

                        UUID subjectUuid = null;
                        if (subjectUuidStr != null && !subjectUuidStr.isBlank()) {
                            try { subjectUuid = UUID.fromString(subjectUuidStr); } catch (Throwable ignored) {}
                        }

                        if (value == null || value.isBlank() || subjectName == null || subjectName.isBlank()) return;

                        PendingRequests.setSkin(targetUuid, subjectName, subjectUuid, value,
                                (sig == null || sig.isBlank()) ? null : sig);

                        Consumer<SkinResponse> sink = this.skinSink;
                        if (sink != null) {
                            try { sink.accept(new SkinResponse(targetUuid, subjectName, subjectUuid, value, sig, requestId)); }
                            catch (Throwable ignored) {}
                        }
                        return;
                    }

                } catch (Throwable ignored) {}
                return;
            }

            if ("GetServers".equalsIgnoreCase(sub)) {
                String csv = in.readUTF();
                List<String> servers = parseCsv(csv);

                List<Consumer<List<String>>> copy;
                synchronized (getServersCallbacks) {
                    copy = new ArrayList<>(getServersCallbacks);
                    getServersCallbacks.clear();
                }

                for (var cb : copy) {
                    try { cb.accept(servers); } catch (Throwable ignored) {}
                }
                return;
            }

            if ("PlayerList".equalsIgnoreCase(sub)) {
                String server = in.readUTF();
                String csv = in.readUTF();
                List<String> names = parseCsv(csv);

                if ("ALL".equalsIgnoreCase(server)) {
                    List<Consumer<List<String>>> copy;
                    synchronized (playerListAllCallbacks) {
                        copy = new ArrayList<>(playerListAllCallbacks);
                        playerListAllCallbacks.clear();
                    }
                    for (var cb : copy) {
                        try { cb.accept(names); } catch (Throwable ignored) {}
                    }
                    return;
                }

                String key = server.toLowerCase(Locale.ROOT);
                BiConsumer<String, List<String>> cb = perServerPlayerList.remove(key);
                if (cb != null) {
                    try { cb.accept(server, names); } catch (Throwable ignored) {}
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
