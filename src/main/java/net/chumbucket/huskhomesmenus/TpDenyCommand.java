package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Cross-server /tpdeny wrapper.
 * - If TPMenu is ON: open the confirm menu (with Deny button).
 * - If TPMenu is OFF: delegate to HuskHomes /tpdeny normally.
 */
public final class TpDenyCommand implements CommandExecutor {

    private final ConfirmRequestMenu menu;
    private final ToggleManager toggles;

    public TpDenyCommand(ConfirmRequestMenu menu, ToggleManager toggles) {
        this.menu = menu;
        this.toggles = toggles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        if (args.length > 1) {
            p.sendMessage(ChatColor.YELLOW + "Usage: /tpdeny [player]");
            return true;
        }

        // If menu is OFF, delegate to HuskHomes directly
        if (toggles != null && !toggles.isTpMenuOn(p)) {
            // safety: prevent intercept/loop if anything else tries to catch the forwarded command
            PendingRequests.bypassForMs(p.getUniqueId(), 1500L);

            String command = (args.length == 1)
                    ? "huskhomes:tpdeny " + args[0]
                    : "huskhomes:tpdeny";

            boolean handled = Bukkit.dispatchCommand(p, command);
            if (!handled) {
                p.sendMessage(ChatColor.RED + "Failed to run HuskHomes /tpdeny (huskhomes:tpdeny).");
            }
            return true;
        }

        // Menu is ON -> open our confirm menu so they can click Deny

        // If /tpdeny <name>, prefer that request type/casing if we have it
        if (args.length == 1) {
            String requester = args[0];

            ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA; // fallback
            PendingRequests.Pending byName = PendingRequests.get(p.getUniqueId(), requester);
            if (byName != null && byName.type() != null) {
                type = byName.type();
                requester = byName.senderName(); // canonical casing
            }

            menu.open(p, requester, type);
            return true;
        }

        // Otherwise use last remembered request
        PendingRequests.Pending pending = PendingRequests.get(p.getUniqueId());
        if (pending == null) {
            p.sendMessage(ChatColor.RED + "You have no pending teleport requests.");
            return true;
        }

        menu.open(p, pending.senderName(), pending.type());
        return true;
    }
}
