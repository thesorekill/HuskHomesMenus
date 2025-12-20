package net.chumbucket.huskhomesmenus;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class HHMCommand implements CommandExecutor {

    private final HuskHomesMenus plugin;
    private final HHMConfig config;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    public HHMCommand(HuskHomesMenus plugin, HHMConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("huskhomesmenus.reload")) {
                sender.sendMessage(
                        AMP.deserialize(config.prefix())
                                .append(AMP.deserialize("&cYou do not have permission to do that."))
                );
                return true;
            }

            boolean ok = plugin.fullReload();
            sender.sendMessage(
                    AMP.deserialize(config.prefix())
                            .append(AMP.deserialize(ok ? "&aHuskHomesMenus reloaded." : "&cReload failed. Check console."))
            );
            return true;
        }

        sender.sendMessage(
                AMP.deserialize(config.prefix())
                        .append(AMP.deserialize("&eUsage: /hhm reload"))
        );
        return true;
    }
}