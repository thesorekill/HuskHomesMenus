package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Cross-server /tpa wrapper that delegates to HuskHomes.
 * We do NOT use Bukkit.getPlayer checks (they fail cross-server).
 */
public final class TpaCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /tpa <player>");
            return true;
        }

        final String targetName = args[0];
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + "You can't request yourself.");
            return true;
        }

        // Delegate to HuskHomes namespaced command (works cross-server)
        boolean handled = Bukkit.dispatchCommand(player, "huskhomes:tpa " + targetName);

        // If the command isn't found/handled, tell the player (prevents "silent nothing happened")
        if (!handled) {
            player.sendMessage(ChatColor.RED + "Failed to run HuskHomes /tpa (huskhomes:tpa).");
            player.sendMessage(ChatColor.GRAY + "Check HuskHomes is installed + command isn't disabled.");
        }

        return true;
    }
}
