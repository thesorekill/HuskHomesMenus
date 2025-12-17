package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TpaCommand implements CommandExecutor {

    private final ToggleManager toggles;
    private final HHMConfig config;

    public TpaCommand(ToggleManager toggles, HHMConfig config) {
        this.toggles = toggles;
        this.config = config;
    }

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

        // If target is on this backend, enforce toggle immediately
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && !toggles.isTpaOn(target)) {
            if (config.isEnabled("messages.sender.tpa_off.enabled", true)) {
                player.sendMessage(config.msgWithPrefix("messages.sender.tpa_off.text",
                        "&cThat player has &lTPA&r &crequests off."));
            }
            return true;
        }

        boolean handled = Bukkit.dispatchCommand(player, "huskhomes:tpa " + targetName);
        if (!handled) {
            player.sendMessage(config.prefix() + ChatColor.RED + "Failed to run HuskHomes /tpa.");
        }
        return true;
    }
}
