package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.*;
import java.util.UUID;

/**
 * Optional cross-server messenger using the legacy "BungeeCord" plugin messaging channel.
 *
 * - Single server: not required (we'll message locally).
 * - Multi-server behind Velocity/Bungee: enables sending a message back to the requester on their server.
 *
 * Note: Requires proxy support for the BungeeCord plugin messaging channel.
 */
public final class OptionalProxyMessenger implements PluginMessageListener {

    private static final String CHANNEL = "BungeeCord";
    private static final String SUBCHANNEL = "HuskHomesMenus:Msg";

    private final JavaPlugin plugin;
    private boolean enabled = false;

    public OptionalProxyMessenger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void tryEnable() {
        try {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
            enabled = true;
            plugin.getLogger().info("Optional proxy messaging registered on channel " + CHANNEL);
        } catch (Throwable t) {
            enabled = false;
            plugin.getLogger().warning("Proxy messaging not available: " + t.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Forward a message to ALL servers; only the server that has the UUID online will deliver it.
     *
     * @param targetUuid player UUID to receive the message
     * @param message chat message
     * @param carrier an online player on THIS backend to carry the plugin message to the proxy
     */
    public void forwardTo(UUID targetUuid, String message, Player carrier) {
        if (!enabled || carrier == null) return;

        try {
            byte[] inner = packInner(targetUuid, message);

            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);

            out.writeUTF("Forward");
            out.writeUTF("ALL");
            out.writeUTF(SUBCHANNEL);
            out.writeShort(inner.length);
            out.write(inner);

            carrier.sendPluginMessage(plugin, CHANNEL, b.toByteArray());
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (!CHANNEL.equals(channel)) return;

        // Handle the common "Forward" format:
        // Forward -> subchannel -> len -> payload
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String header = in.readUTF();
            if (!"Forward".equalsIgnoreCase(header)) {
                return;
            }

            String sub = in.readUTF();
            if (!SUBCHANNEL.equals(sub)) {
                return;
            }

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
            UUID uuid;
            try {
                uuid = UUID.fromString(in.readUTF());
            } catch (IllegalArgumentException e) {
                return;
            }
            String chat = in.readUTF();

            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(chat);
            }
        }
    }
}
