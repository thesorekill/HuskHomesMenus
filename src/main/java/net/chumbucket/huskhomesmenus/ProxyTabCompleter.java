package net.chumbucket.huskhomesmenus;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class ProxyTabCompleter implements TabCompleter {

    private final ProxyPlayerCache cache;
    private final boolean oneArgRequired; // true for /tpa, /tpahere; false for /tpaccept, /tpdeny

    public ProxyTabCompleter(ProxyPlayerCache cache, boolean oneArgRequired) {
        this.cache = cache;
        this.oneArgRequired = oneArgRequired;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!(sender instanceof Player p)) return List.of();

        // /tpa <player> or /tpahere <player>
        if (oneArgRequired) {
            if (args.length != 1) return List.of();
        } else {
            // /tpaccept [player] or /tpdeny [player]
            if (args.length > 1) return List.of();
            if (args.length == 0) return List.of();
        }

        String prefix = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
        String self = p.getName();

        LinkedHashSet<String> out = new LinkedHashSet<>();

        // local players first (nice UX)
        for (Player online : Bukkit.getOnlinePlayers()) {
            String name = online.getName();
            if (name.equalsIgnoreCase(self)) continue;
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(name);
        }

        // proxy cache
        for (String name : cache.getCached()) {
            if (name == null) continue;
            if (name.equalsIgnoreCase(self)) continue; // remove current player's name
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(name);
        }

        return new ArrayList<>(out);
    }
}
