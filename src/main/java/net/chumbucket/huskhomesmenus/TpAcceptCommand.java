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

        // If HuskHomes ran /tpaccept <name>, we already know the sender
        if (args.length == 1) {
            menu.open(p, args[0], ConfirmRequestMenu.RequestType.TPA); // type unknown; GUI still works
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
