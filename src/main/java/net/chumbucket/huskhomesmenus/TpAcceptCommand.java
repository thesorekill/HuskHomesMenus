package net.chumbucket.huskhomesmenus;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class TpAcceptCommand implements CommandExecutor {

    private final ConfirmRequestMenu menu;

    public TpAcceptCommand(ConfirmRequestMenu menu) {
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        // If HuskHomes ran /tpaccept <name>, we know the sender name; lookup the request type if possible
        if (args.length == 1) {
            String requester = args[0];

            ConfirmRequestMenu.RequestType type = ConfirmRequestMenu.RequestType.TPA; // default fallback
            PendingRequests.Pending byName = PendingRequests.get(p.getUniqueId(), requester);
            if (byName != null && byName.type() != null) {
                type = byName.type();
                // use the canonical stored name (keeps capitalization consistent)
                requester = byName.senderName();
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
