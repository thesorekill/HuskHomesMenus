package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Cross-server /tpa wrapper. Delegates to HuskHomes' namespaced command.
 * Do NOT check Bukkit.getPlayer(...) here; it fails cross-server.
 */
public final class TpaCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /tpa <player>");
            return true;
        }

        String targetName = args[0];
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + "You can't request yourself.");
            return true;
        }

        boolean handled = Bukkit.dispatchCommand(player, "huskhomes:tpa " + targetName);
        if (!handled) {
            player.sendMessage(ChatColor.RED + "Failed to run HuskHomes /tpa (huskhomes:tpa).");
        }
        return true;
    }
}
