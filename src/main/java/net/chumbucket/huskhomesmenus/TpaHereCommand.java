package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Cross-server /tpahere wrapper that delegates to HuskHomes.
 */
public final class TpaHereCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /tpahere <player>");
            return true;
        }

        final String targetName = args[0];
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + "You can't request yourself.");
            return true;
        }

        boolean handled = Bukkit.dispatchCommand(player, "huskhomes:tpahere " + targetName);

        if (!handled) {
            player.sendMessage(ChatColor.RED + "Failed to run HuskHomes /tpahere (huskhomes:tpahere).");
            player.sendMessage(ChatColor.GRAY + "Check HuskHomes is installed + command isn't disabled.");
        }

        return true;
    }
}
