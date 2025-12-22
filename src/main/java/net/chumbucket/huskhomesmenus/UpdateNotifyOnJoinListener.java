package net.chumbucket.huskhomesmenus;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class UpdateNotifyOnJoinListener implements Listener {

    private final HuskHomesMenus plugin;

    public UpdateNotifyOnJoinListener(HuskHomesMenus plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("update_checker.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("update_checker.notify_on_join", true)) return;

        UpdateChecker checker = plugin.getUpdateChecker();
        if (checker == null) return;

        // notify admins only (change permission if you want)
        checker.notifyPlayerIfOutdated(e.getPlayer(), "huskhomesmenus.*");
    }
}
