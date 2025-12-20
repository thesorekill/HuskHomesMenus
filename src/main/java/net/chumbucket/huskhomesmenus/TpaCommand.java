package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TpaCommand implements CommandExecutor {

    private final ToggleManager toggles;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public TpaCommand(ToggleManager toggles, HHMConfig config) {
        this.toggles = toggles;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(AMP.deserialize("&cPlayers only."));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(AMP.deserialize("&eUsage: /tpa <player>"));
            return true;
        }

        String targetName = args[0];

        // If target is on this backend, enforce toggle immediately
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null) {
            if (!toggles.isTpaOn(target) && !toggles.isTpahereOn(target)) {
                if (config.isEnabled("messages.sender.both_off.enabled", true)) {
                    player.sendMessage(
                        AMP.deserialize(
                            config.msgWithPrefix(
                                "messages.sender.both_off.text",
                                "&cThat player has teleport requests off."
                            )
                        )
                    );
                }
                return true;
            } else if (!toggles.isTpaOn(target)) {
                if (config.isEnabled("messages.sender.tpa_off.enabled", true)) {
                    player.sendMessage(
                        AMP.deserialize(
                            config.msgWithPrefix(
                                "messages.sender.tpa_off.text",
                                "&cThat player has &lTPA&r &crequests off."
                            )
                        )
                    );
                }
                return true;
            }
        }

        boolean handled = Bukkit.dispatchCommand(player, "huskhomes:tpa " + targetName);
        if (!handled) {
            player.sendMessage(
                AMP.deserialize(config.prefix())
                    .append(AMP.deserialize("&cFailed to run HuskHomes /tpa."))
            );
        }
        return true;
    }
}