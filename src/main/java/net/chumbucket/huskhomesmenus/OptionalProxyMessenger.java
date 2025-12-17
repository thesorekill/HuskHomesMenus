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
    private final HHMConfig config;
    private boolean enabled = false;

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
            // register incoming listeners
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_MODERN, this);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL_LEGACY, this);

            // register outgoing channels
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_MODERN);
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL_LEGACY);

            enabled = true;
            plugin.getLogger().info("Optional proxy messaging enabled (channels: " + CHANNEL_MODERN + " & " + CHANNEL_LEGACY + ", sub=" + SUBCHANNEL + ")");
        } catch (Throwable t) {
            enabled = false;
            plugin.getLogger().warning("Optional proxy messaging could not be enabled: " + t.getMessage());
        }
    }

    /**
     * Send a plain chat message to a player name (works only if proxy supports Message subchannel)
     */
    public boolean messagePlayer(String playerName, String message) {
        if (!enabled) return false;
        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            byte[] payload = buildProxyMessagePacket(playerName, message);
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, payload);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, payload);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to proxy-message '" + playerName + "': " + t.getMessage());
            return false;
        }
    }

    /**
     * Forward a custom payload to the target's backend via ForwardToPlayer.
     * Receiver must listen for SUBCHANNEL on Bukkit side.
     */
    public boolean forwardTo(UUID targetUuid, String message) {
        if (!enabled) return false;
        try {
            Player carrier = anyOnlinePlayer();
            if (carrier == null) return false;

            ByteArrayOutputStream msgBytes = new ByteArrayOutputStream();
            DataOutputStream msgOut = new DataOutputStream(msgBytes);
            msgOut.writeUTF(message);

            byte[] forward = buildForwardToPlayer(targetUuid.toString(), SUBCHANNEL, msgBytes.toByteArray());
            carrier.sendPluginMessage(plugin, CHANNEL_MODERN, forward);
            carrier.sendPluginMessage(plugin, CHANNEL_LEGACY, forward);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to proxy-forward '" + targetUuid + "': " + t.getMessage());
            return false;
        }
    }

    private Player anyOnlinePlayer() {
        for (Player p : Bukkit.getOnlinePlayers()) return p;
        return null;
    }

    private byte[] buildProxyMessagePacket(String playerName, String message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("Message");
        out.writeUTF(playerName);
        out.writeUTF(message);
        return bytes.toByteArray();
    }

    private byte[] buildForwardToPlayer(String player, String subchannel, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("ForwardToPlayer");
        out.writeUTF(player);
        out.writeUTF(subchannel);
        out.writeShort(data.length);
        out.write(data);
        return bytes.toByteArray();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        // If later you want cross-backend inbound handling, add it here.
    }
}
