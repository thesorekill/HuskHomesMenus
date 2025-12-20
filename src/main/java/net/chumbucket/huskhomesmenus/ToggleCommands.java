package net.chumbucket.huskhomesmenus;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class ToggleCommands implements CommandExecutor {

    private final ToggleManager toggles;
    private final HHMConfig config;

    public ToggleCommands(ToggleManager toggles, HHMConfig config) {
        this.toggles = toggles;
        this.config = config;
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
                sendToggleStatus(p, true, on);
                return true;
            }
            case "tpaheretoggle" -> {
                boolean on = toggles.toggleTpahere(p);
                sendToggleStatus(p, false, on);
                return true;
            }
            case "tpmenu" -> {
                boolean on = toggles.toggleTpMenu(p);
                sendTpMenuStatus(p, on);
                return true;
            }
            case "tpauto" -> {
                boolean on = toggles.toggleTpAuto(p);
                sendTpAutoStatus(p, on);
                return true;
            }
            default -> {
                return false; // unchanged behavior
            }
        }
    }

    // Existing logic (unchanged)
    private void sendToggleStatus(Player p, boolean isTpa, boolean on) {
        if (!config.isEnabled("messages.toggles.show_status_lines.enabled", true)) return;

        final String basePath = isTpa ? "messages.toggles.tpa_status_line" : "messages.toggles.tpahere_status_line";
        if (!config.isEnabled(basePath + ".enabled", true)) return;

        String template = config.raw(basePath + ".text",
                "%color%" + (isTpa ? "TPA" : "TPAHere") + " requests: %state%");

        template = template
                .replace("%color%", on ? "&a" : "&c")
                .replace("%state%", on ? "&lON" : "&lOFF");

        p.sendMessage(config.prefix() + config.color(template));
    }

    // Existing TPMenu status line (unchanged)
    private void sendTpMenuStatus(Player p, boolean on) {
        if (!config.isEnabled("messages.toggles.show_status_lines.enabled", true)) return;

        final String basePath = "messages.toggles.tpmenu_status_line";
        if (!config.isEnabled(basePath + ".enabled", true)) return;

        String template = config.raw(basePath + ".text",
                "%color%Teleport Menu: %state%");

        template = template
                .replace("%color%", on ? "&a" : "&c")
                .replace("%state%", on ? "&lON" : "&lOFF");

        p.sendMessage(config.prefix() + config.color(template));
    }

    // ✅ NEW: TPAuto status line
    private void sendTpAutoStatus(Player p, boolean on) {
        if (!config.isEnabled("messages.toggles.show_status_lines.enabled", true)) return;

        final String basePath = "messages.toggles.tpauto_status_line";
        if (!config.isEnabled(basePath + ".enabled", true)) return;

        String template = config.raw(basePath + ".text",
                "%color%Auto Accept TPA: %state%");

        template = template
                .replace("%color%", on ? "&a" : "&c")
                .replace("%state%", on ? "&lON" : "&lOFF");

        p.sendMessage(config.prefix() + config.color(template));
    }

    // kept for compatibility with existing patterns
    @SuppressWarnings("unused")
    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
