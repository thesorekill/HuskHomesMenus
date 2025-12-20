package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TpaHereCommand implements CommandExecutor {

    private final ToggleManager toggles;
    private final HHMConfig config;

    public TpaHereCommand(ToggleManager toggles, HHMConfig config) {
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
            player.sendMessage(ChatColor.YELLOW + "Usage: /tpahere <player>");
            return true;
        }

        String targetName = args[0];

        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null) {
            if (!toggles.isTpaOn(target) && !toggles.isTpahereOn(target)) {
                if (config.isEnabled("messages.sender.both_off.enabled", true)) {
                    player.sendMessage(config.msgWithPrefix("messages.sender.both_off.text",
                        "&cThat player has teleport requests off."));
                }
                return true;
            } else if (!toggles.isTpahereOn(target)) {
                if (config.isEnabled("messages.sender.tpa_off.enabled", true)) {
                    player.sendMessage(config.msgWithPrefix("messages.sender.tpa_off.text",
                        "&cThat player has &lTPAHere&r &crequests off."));

                }
                return true;
            } 
        }

        boolean handled = Bukkit.dispatchCommand(player, "huskhomes:tpahere " + targetName);
        if (!handled) {
            player.sendMessage(config.prefix() + ChatColor.RED + "Failed to run HuskHomes /tpahere.");
        }
        return true;
    }
}
