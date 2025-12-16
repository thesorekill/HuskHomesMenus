package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Cross-server compatible /tpdeny wrapper.
 * Delegates to HuskHomes' namespaced command so HuskHomes can deny the request across servers.
 */
public final class TpDenyCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        if (args.length > 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /tpdeny [player]");
            return true;
        }

        final String cmd = (args.length == 1)
                ? ("huskhomes:tpdeny " + args[0])
                : "huskhomes:tpdeny";

        Bukkit.dispatchCommand(player, cmd);
        return true;
    }
}
