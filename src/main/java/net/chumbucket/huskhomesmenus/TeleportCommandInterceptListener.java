package net.chumbucket.huskhomesmenus;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class TeleportCommandInterceptListener implements Listener {

    private final ConfirmRequestMenu menu;

    public TeleportCommandInterceptListener(ConfirmRequestMenu menu) {
        this.menu = menu;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();

        // If the GUI just triggered a real HuskHomes command, let it through (prevents loop)
        if (PendingRequests.isBypassActive(p.getUniqueId())) return;

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

        boolean isAccept = action.equals("tpaccept") || action.startsWith("tpaccept");
        boolean isDeny =
                action.equals("tpdeny")
                        || action.equals("tpdecline")
                        || action.equals("tpdenyrequest")
                        || action.equals("tpdeclinerequest")
                        || action.startsWith("tpdeny")
                        || action.startsWith("tpdecline");

        if (!isAccept && !isDeny) return;

        // Cancel HuskHomes handling; open our menu instead
        e.setCancelled(true);

        // Optional arg (requester name)
        String requesterName = (parts.length > argIndex) ? parts[argIndex] : null;

        // Fall back to last remembered request
        if (requesterName == null || requesterName.isBlank()) {
            PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
            if (pending == null) {
                p.sendMessage(ChatColor.RED + "You have no pending teleport requests.");
                return;
            }
            requesterName = pending.senderName();
        }

        // Use remembered request type if possible
        ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA;
        PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
        if (pending != null && pending.type() != null) {
            type = pending.type();
        }

        menu.open(p, requesterName, type);
    }
}
