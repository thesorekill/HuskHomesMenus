package net.chumbucket.huskhomesmenus;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ToggleManager {

    private final NamespacedKey keyTpa;
    private final NamespacedKey keyTpahere;

    // NEW: toggle for whether to use the GUI menu for TP requests
    private final NamespacedKey keyTpMenu;

    public ToggleManager(JavaPlugin plugin) {
        this.keyTpa = new NamespacedKey(plugin, "tpa_on");
        this.keyTpahere = new NamespacedKey(plugin, "tpahere_on");

        // NEW
        this.keyTpMenu = new NamespacedKey(plugin, "tpmenu_on");
    }

    public boolean isTpaOn(Player p) {
        return getFlagDefaultTrue(p, keyTpa);
    }

    public boolean isTpahereOn(Player p) {
        return getFlagDefaultTrue(p, keyTpahere);
    }

    // NEW: default ON
    public boolean isTpMenuOn(Player p) {
        return getFlagDefaultTrue(p, keyTpMenu);
    }

    public boolean toggleTpa(Player p) {
        boolean now = !isTpaOn(p);
        setFlag(p, keyTpa, now);
        return now;
    }

    public boolean toggleTpahere(Player p) {
        boolean now = !isTpahereOn(p);
        setFlag(p, keyTpahere, now);
        return now;
    }

    // NEW
    public boolean toggleTpMenu(Player p) {
        boolean now = !isTpMenuOn(p);
        setFlag(p, keyTpMenu, now);
        return now;
    }

    // NEW: helper setter (optional but useful for future /tpmenu on|off)
    public void setTpMenuOn(Player p, boolean on) {
        setFlag(p, keyTpMenu, on);
    }

    private boolean getFlagDefaultTrue(Player p, NamespacedKey key) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Byte stored = pdc.get(key, PersistentDataType.BYTE);
        if (stored == null) return true; // default ON
        return stored == (byte) 1;
    }

    private void setFlag(Player p, NamespacedKey key, boolean value) {
        p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, value ? (byte) 1 : (byte) 0);
    }
}
