package net.chumbucket.huskhomesmenus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TeleportCommandInterceptListener implements Listener {

    private final ConfirmRequestMenu menu;
    private final HHMConfig config;
    private final ToggleManager toggles;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public TeleportCommandInterceptListener(ConfirmRequestMenu menu, HHMConfig config, ToggleManager toggles) {
        this.menu = menu;
        this.config = config;
        this.toggles = toggles;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();

        if (PendingRequests.isBypassActive(p.getUniqueId())) return;
        if (!isConfirmMenuEnabled()) return;

        // if player disabled menu, DO NOT intercept /tpaccept or /tpdeny
        if (toggles != null && !toggles.isTpMenuOn(p)) return;

        String msg = e.getMessage();
        if (msg == null) return;

        String trimmed = msg.trim();
        if (trimmed.isBlank() || !trimmed.startsWith("/")) return;

        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return;

        String first = parts[0].substring(1);
        if (first.isBlank()) return;

        String label = first;
        if (label.contains(":")) label = label.split(":", 2)[1];

        String action = label.toLowerCase();
        int argIndex = 1;

        if (action.equals("huskhomes") || action.equals("hh")) {
            if (parts.length < 2) return;
            action = parts[1].toLowerCase();
            argIndex = 2;
        }

        boolean isAccept = action.equals("tpaccept");
        boolean isDeny = action.equals("tpdeny") || action.equals("tpdecline");

        if (!isAccept && !isDeny) {
            if (action.startsWith("tpaccept")) isAccept = true;
            if (action.startsWith("tpdeny") || action.startsWith("tpdecline")) isDeny = true;
        }

        if (!isAccept && !isDeny) return;

        e.setCancelled(true);

        String requesterName = (parts.length > argIndex) ? parts[argIndex] : null;

        if (requesterName == null || requesterName.isBlank()) {
            PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
            if (pending == null) {
                // ✅ no ChatColor; use Adventure + legacy & codes
                p.sendMessage(
                        AMP.deserialize(config.prefix())
                                .append(AMP.deserialize("&cYou have no pending teleport requests."))
                );
                return;
            }
            requesterName = pending.senderName();
        }

        ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA;

        PendingRequests.Pending byName = PendingRequests.get(p.getUniqueId(), requesterName);
        if (byName != null && byName.type() != null) {
            type = byName.type();
            requesterName = byName.senderName();
        } else {
            PendingRequests.Pending last = PendingRequests.get(p.getUniqueId());
            if (last != null && last.type() != null) {
                type = last.type();
            }
        }

        menu.open(p, requesterName, type);
    }

    private boolean isConfirmMenuEnabled() {
        ConfigurationSection sec = config.section("menus.confirm_request");
        if (sec == null) return true;
        return sec.getBoolean("enabled", true);
    }
}