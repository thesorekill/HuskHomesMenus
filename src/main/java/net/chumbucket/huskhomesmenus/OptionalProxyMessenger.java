package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.util.UUID;

public final class OptionalProxyMessenger implements PluginMessageListener {

    // Support both (different setups expect different channel IDs)
    public static final String CHANNEL_MODERN = "bungeecord:main";
    public static final String CHANNEL_LEGACY = "BungeeCord";

    // Custom forward subchannel name
    public static final String SUBCHANNEL = "HuskHomesMenus:Msg";

    private final JavaPlugin plugin;
    private boolean enabled = false;

    public OptionalProxyMessenger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void tryEnable() {
        enabled = false;

        try {
            // Register BOTH channels. No harm if one is unused.
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_MODERN);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_MODERN, this);

            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_LEGACY);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_LEGACY, this);

            enabled = true;
            plugin.getLogger().info("Optional proxy messaging enabled (channels=" + CHANNEL_MODERN + " & " + CHANNEL_LEGACY + ", sub=" + SUBCHANNEL + ")");
        } catch (Throwable t) {
            enabled = false;
            plugin.getLogger().warning("Optional proxy messaging could not be enabled: " + t.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Ask the proxy to send a chat message to a player by NAME (works cross-server).
     * Carrier must be online on THIS backend.
     *
     * NOTE: This uses the proxy "Message" subchannel.
     */
    public boolean messagePlayer(String playerName, String message, Player carrier) {
        if (!enabled || carrier == null || playerName == null || playerName.isBlank() || message == null) return false;

        try {
            byte[] data = buildProxyMessagePacket(playerName, message);

            // Send on both channels to be compatible with proxy expectations
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, data);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, data);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to proxy-message '" + playerName + "': " + t.getMessage());
            return false;
        }
    }

    private byte[] buildProxyMessagePacket(String playerName, String message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("Message");
        out.writeUTF(playerName);
        out.writeUTF(message);
        return bytes.toByteArray();
    }

    /**
     * Forward a custom payload to ALL servers; the server where targetUuid is online will deliver it.
     * Carrier must be online on THIS backend.
     *
     * NOTE: This uses the proxy "Forward" subchannel.
     */
    public boolean forwardTo(UUID targetUuid, String message, Player carrier) {
        if (!enabled || carrier == null || targetUuid == null || message == null) return false;

        try {
            byte[] innerPayload = packInner(targetUuid, message);
            byte[] packet = buildForwardPacket(innerPayload);

            // Send on both channels to be compatible with proxy expectations
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, packet);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, packet);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to forward proxy message: " + t.getMessage());
            return false;
        }
    }

    private byte[] buildForwardPacket(byte[] innerPayload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);

        out.writeUTF("Forward");
        out.writeUTF("ALL");
        out.writeUTF(SUBCHANNEL);
        out.writeShort(innerPayload.length);
        out.write(innerPayload);

        return bytes.toByteArray();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!enabled) return;

        // Only accept from our known channels
        if (!CHANNEL_MODERN.equalsIgnoreCase(channel) && !CHANNEL_LEGACY.equalsIgnoreCase(channel)) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            // Forwarded messages arrive as:
            // <SUBCHANNEL UTF> <LEN SHORT> <PAYLOAD BYTES>
            String sub = in.readUTF();
            if (!SUBCHANNEL.equals(sub)) return;

            int len = in.readUnsignedShort();
            byte[] payload = new byte[len];
            in.readFully(payload);

            unpackAndDeliver(payload);
        } catch (Throwable ignored) {
        }
    }

    private byte[] packInner(UUID uuid, String message) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeUTF(uuid.toString());
        out.writeUTF(message);
        return b.toByteArray();
    }

    private void unpackAndDeliver(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            UUID uuid = UUID.fromString(in.readUTF());
            String chat = in.readUTF();

            Player target = Bukkit.getPlayer(uuid);
            if (target != null) {
                target.sendMessage(chat);
            }
        } catch (IllegalArgumentException ignored) {
            // bad UUID; ignore
        }
    }
}
