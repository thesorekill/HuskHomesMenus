package net.chumbucket.huskhomesmenus;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class TeleportCommandInterceptListener implements Listener {

    private final ConfirmRequestMenu menu;
    private final HHMConfig config;

    public TeleportCommandInterceptListener(ConfirmRequestMenu menu, HHMConfig config) {
        this.menu = menu;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();

        // If the GUI just triggered a real HuskHomes command, let it through (prevents loop)
        if (PendingRequests.isBypassActive(p.getUniqueId())) return;

        // If the confirm menu is disabled, DO NOT intercept; let HuskHomes handle commands normally
        if (!isConfirmMenuEnabled()) return;

        String msg = e.getMessage();
        if (msg == null) return;

        String trimmed = msg.trim();
        if (trimmed.isBlank() || !trimmed.startsWith("/")) return;

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return;

        String first = parts[0].substring(1);
        if (first.isBlank()) return;

        // Strip namespace: "huskhomes:tpaccept" -> "tpaccept"
        String label = first;
        if (label.contains(":")) label = label.split(":", 2)[1];

        // Handle "/huskhomes tpaccept" and "/hh tpaccept"
        String action = label.toLowerCase();
        int argIndex = 1;

        if (action.equals("huskhomes") || action.equals("hh")) {
            if (parts.length < 2) return;
            action = parts[1].toLowerCase();
            argIndex = 2;
        }

        boolean isAccept = action.equals("tpaccept");
        boolean isDeny = action.equals("tpdeny") || action.equals("tpdecline");

        // also allow namespaced variants that some setups pass through as full strings
        if (!isAccept && !isDeny) {
            if (action.startsWith("tpaccept")) isAccept = true;
            if (action.startsWith("tpdeny") || action.startsWith("tpdecline")) isDeny = true;
        }

        if (!isAccept && !isDeny) return;

        // Cancel HuskHomes handling; open our menu instead
        e.setCancelled(true);

        // Optional arg (requester name)
        String requesterName = (parts.length > argIndex) ? parts[argIndex] : null;

        // Fall back to last remembered request for missing arg
        if (requesterName == null || requesterName.isBlank()) {
            PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
            if (pending == null) {
                p.sendMessage(ChatColor.RED + "You have no pending teleport requests.");
                return;
            }
            requesterName = pending.senderName();
        }

        // ✅ Correct type resolution:
        // 1) If command included a requester name, prefer the pending entry for that requester
        // 2) Otherwise fall back to the "last" pending entry
        ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA;

        PendingRequests.Pending byName = PendingRequests.get(p.getUniqueId(), requesterName);
        if (byName != null && byName.type() != null) {
            type = byName.type();
            requesterName = byName.senderName(); // keep canonical casing if stored
        } else {
            PendingRequests.Pending last = PendingRequests.get(p.getUniqueId());
            if (last != null && last.type() != null) {
                type = last.type();
            }
        }

        menu.open(p, requesterName, type);
    }

    private boolean isConfirmMenuEnabled() {
        // uses config path menus.confirm_request.enabled (default true if missing)
        ConfigurationSection sec = config.section("menus.confirm_request");
        if (sec == null) return true;
        return sec.getBoolean("enabled", true);
    }
}
