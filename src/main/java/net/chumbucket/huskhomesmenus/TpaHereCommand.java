package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TpaHereCommand implements CommandExecutor {

    private final ToggleManager toggles;

    public TpaHereCommand(ToggleManager toggles) {
        this.toggles = toggles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /tpahere <player>");
            return true;
        }

        String targetName = args[0];
        if (targetName.equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + "You can't request yourself.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && !toggles.isTpahereOn(target)) {
            player.sendMessage(ChatColor.RED + "That player has TPAHere requests turned off.");
            return true;
        }

        boolean handled = Bukkit.dispatchCommand(player, "huskhomes:tpahere " + targetName);
        if (!handled) {
            player.sendMessage(ChatColor.RED + "Failed to run HuskHomes /tpahere.");
        }
        return true;
    }
}
