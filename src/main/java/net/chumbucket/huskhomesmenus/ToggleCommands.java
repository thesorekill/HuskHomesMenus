package net.chumbucket.huskhomesmenus;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class ToggleCommands implements CommandExecutor {

    private final ToggleManager toggles;

    public ToggleCommands(ToggleManager toggles) {
        this.toggles = toggles;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "tpatoggle" -> {
                boolean on = toggles.toggleTpa(p);
                p.sendMessage(color(on ? "&aTPA requests: &lON" : "&cTPA requests: &lOFF"));
                return true;
            }
            case "tpaheretoggle" -> {
                boolean on = toggles.toggleTpahere(p);
                p.sendMessage(color(on ? "&aTPAHere requests: &lON" : "&cTPAHere requests: &lOFF"));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
