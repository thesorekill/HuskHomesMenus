package net.chumbucket.huskhomesmenus;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ToggleManager {

    private final NamespacedKey keyTpa;
    private final NamespacedKey keyTpahere;

    public ToggleManager(JavaPlugin plugin) {
        this.keyTpa = new NamespacedKey(plugin, "tpa_on");
        this.keyTpahere = new NamespacedKey(plugin, "tpahere_on");
    }

    public boolean isTpaOn(Player p) {
        return getFlagDefaultTrue(p, keyTpa);
    }

    public boolean isTpahereOn(Player p) {
        return getFlagDefaultTrue(p, keyTpahere);
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
