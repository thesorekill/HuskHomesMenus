package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Cross-server /tpaccept wrapper. Delegates to HuskHomes.
 */
public final class TpAcceptCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        if (args.length > 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /tpaccept [player]");
            return true;
        }

        String command = (args.length == 1)
                ? "huskhomes:tpaccept " + args[0]
                : "huskhomes:tpaccept";

        boolean handled = Bukkit.dispatchCommand(player, command);
        if (!handled) {
            player.sendMessage(ChatColor.RED + "Failed to run HuskHomes /tpaccept (huskhomes:tpaccept).");
        }
        return true;
    }
}
