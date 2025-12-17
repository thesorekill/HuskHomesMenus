package net.chumbucket.huskhomesmenus;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public final class ConfirmRequestMenu implements Listener {

    public enum RequestType { TPA, TPAHERE }

    private final HuskHomesMenus plugin;
    private final HHMConfig config;

    public ConfirmRequestMenu(HuskHomesMenus plugin, HHMConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void open(Player target, String senderName, RequestType type) {
        if (!config.isEnabled("menus.confirm_request.enabled", true)) return;

        ConfigurationSection menu = config.section("menus.confirm_request");
        if (menu == null) return;

        int rows = Math.max(1, Math.min(6, menu.getInt("rows", 3)));
        String titleKey = (type == RequestType.TPA) ? "title_tpa" : "title_tpahere";
        String title = config.color(menu.getString(titleKey, menu.getString("title", "&7CONFIRM REQUEST")));

        ConfirmHolder holder = new ConfirmHolder(senderName, type);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, title);

        // filler
        boolean useFiller = menu.getBoolean("use_filler", false);
        if (useFiller) {
            ConfigurationSection fill = menu.getConfigurationSection("filler");
            ItemStack filler = buildSimpleItem(fill,
                    Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false, 0,
                    Map.of("%sender%", safe(senderName), "%dimension_name%", "Unknown", "%region%", "Unknown"));
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        // items
        List<Map<?, ?>> items = menu.getMapList("items");
        for (Map<?, ?> itemMap : items) {
            int slot = mapGetInt(itemMap, "slot", -1);
            if (slot < 0 || slot >= inv.getSize()) continue;

            String itemType = mapGetString(itemMap, "type", "ITEM");
            boolean requiresProxy = mapGetBool(itemMap, "requires_proxy", false);
            if (requiresProxy && !config.proxyEnabled()) continue;

            ItemStack built = switch (itemType.toUpperCase(Locale.ROOT)) {
                case "PLAYER_HEAD" -> buildSenderHead(itemMap, senderName);
                case "DIMENSION_ITEM" -> buildDimensionItem(itemMap, senderName);
                default -> buildGenericItem(itemMap, senderName);
            };

            inv.setItem(slot, built);
        }

        // deny (CANCEL)
        ConfigurationSection deny = menu.getConfigurationSection("deny");
        if (deny != null) {
            int slot = deny.getInt("slot", -1);
            ItemStack item = buildSimpleItem(deny.getConfigurationSection("item"),
                    Material.RED_STAINED_GLASS_PANE, "&c&lCANCEL",
                    List.of("&7Click to cancel the teleport"), false, 0,
                    Map.of("%sender%", safe(senderName), "%dimension_name%", "Unknown", "%region%", "Unknown"));
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, item);
        }

        // accept (CONFIRM)
        ConfigurationSection accept = menu.getConfigurationSection("accept");
        if (accept != null) {
            int slot = accept.getInt("slot", -1);
            ItemStack item = buildSimpleItem(accept.getConfigurationSection("item"),
                    Material.LIME_STAINED_GLASS_PANE, "&a&lCONFIRM",
                    List.of("&7Click to accept %sender%'s request"), false, 0,
                    Map.of("%sender%", safe(senderName), "%dimension_name%", "Unknown", "%region%", "Unknown"));
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, item);
        }

        target.openInventory(inv);
    }

    // ---------------------------
    // Click + Drag handling (prevents taking items)
    // ---------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof ConfirmHolder holder)) return;

        // Stop ALL movement/taking items
        e.setCancelled(true);

        // Only respond to clicks in TOP inventory
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;

        ConfigurationSection menu = config.section("menus.confirm_request");
        if (menu == null) return;

        int rawSlot = e.getRawSlot();
        if (rawSlot < 0 || rawSlot >= e.getView().getTopInventory().getSize()) return;

        ConfigurationSection deny = menu.getConfigurationSection("deny");
        ConfigurationSection accept = menu.getConfigurationSection("accept");

        int denySlot = (deny != null) ? deny.getInt("slot", -1) : -1;
        int acceptSlot = (accept != null) ? accept.getInt("slot", -1) : -1;

        if (rawSlot == denySlot) {
            handleButton(p, deny, holder, false);
            return;
        }

        if (rawSlot == acceptSlot) {
            handleButton(p, accept, holder, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof ConfirmHolder)) return;

        int topSize = e.getView().getTopInventory().getSize();
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private void handleButton(Player p, ConfigurationSection section, ConfirmHolder holder, boolean doAccept) {
        if (section == null) return;

        ConfigurationSection click = section.getConfigurationSection("click");
        if (click != null) {
            String snd = click.getString("sound", "");
            if (snd != null && !snd.isBlank()) {
                try { p.playSound(p.getLocation(), Sound.valueOf(snd), 1f, 1f); }
                catch (Throwable ignored) { }
            }
        }

        boolean close = (click == null) || click.getBoolean("close_menu", true);

        // Close immediately (client-side)
        if (close) p.closeInventory();

        final String sender = holder.senderName;
        final RequestType type = holder.type;

        // IMPORTANT: allow the next huskhomes accept/deny command through interception
        PendingRequests.bypassForMs(p.getUniqueId(), 1200);

        // Run 1 tick later so close always sticks + command executes cleanly
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (doAccept) {
                runAccept(p, sender, type);
            } else {
                runDeny(p, sender, type);
            }

            PendingRequests.clear(p.getUniqueId());
            PendingRequests.clearBypass(p.getUniqueId());

            // Belt + suspenders: close again after running
            if (close) p.closeInventory();
        }, 1L);
    }

    // ---------------------------
    // ✅ Command execution (ONLY commands you said exist)
    // Prefer tpaccept/tpdecline/tpdeny first; check tpyes/tpno last.
    // ---------------------------
    private boolean dispatchFirstSuccessful(Player p, String... cmdsNoSlash) {
        for (String cmd : cmdsNoSlash) {
            if (cmd == null || cmd.isBlank()) continue;
            try {
                if (Bukkit.dispatchCommand(p, cmd)) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private void runAccept(Player p, String sender, RequestType type) {
        boolean hasSender = sender != null && !sender.isBlank();

        boolean ok = dispatchFirstSuccessful(
                p,
                hasSender ? "huskhomes:tpaccept " + sender : "huskhomes:tpaccept",
                "huskhomes:tpaccept",
                // check last
                "huskhomes:tpyes"
        );

        if (!ok) {
            p.sendMessage(config.prefix() + ChatColor.RED + "Could not accept the request.");
        }
    }

    private void runDeny(Player p, String sender, RequestType type) {
        boolean hasSender = sender != null && !sender.isBlank();

        // Prefer decline, then deny; check tpno last
        boolean ok = dispatchFirstSuccessful(
                p,
                hasSender ? "huskhomes:tpdecline " + sender : "huskhomes:tpdecline",
                hasSender ? "huskhomes:tpdeny " + sender : "huskhomes:tpdeny",
                "huskhomes:tpdecline",
                "huskhomes:tpdeny",
                // check last
                "huskhomes:tpno"
        );

        if (!ok) {
            p.sendMessage(config.prefix() + ChatColor.RED + "Could not decline the request.");
        }
    }

    // ---------------------------
    // Item builders
    // ---------------------------
    private ItemStack buildSenderHead(Map<?, ?> itemMap, String senderName) {
        String name = config.color(mapGetString(itemMap, "name", "&a&lPLAYER"));
        List<String> lore = mapGetStringList(itemMap, "lore");

        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta0 = skull.getItemMeta();
        if (meta0 instanceof SkullMeta meta) {
            Player sender = (senderName != null) ? Bukkit.getPlayerExact(senderName) : null;
            if (sender != null) meta.setOwningPlayer(sender);
            meta.setDisplayName(apply(name, senderName, dimensionOf(sender), regionOf(sender)));
            meta.setLore(colorLore(applyAll(lore, senderName, dimensionOf(sender), regionOf(sender))));
            skull.setItemMeta(meta);
        }
        return skull;
    }

    private ItemStack buildDimensionItem(Map<?, ?> itemMap, String senderName) {
        Player sender = (senderName != null) ? Bukkit.getPlayerExact(senderName) : null;

        String overworldMat = mapGetString(itemMap, "overworld_material", "GRASS_BLOCK");
        String netherMat = mapGetString(itemMap, "nether_material", "NETHERRACK");
        String endMat = mapGetString(itemMap, "end_material", "END_STONE");

        Material mat;
        if (sender != null) {
            World.Environment env = sender.getWorld().getEnvironment();
            if (env == World.Environment.NETHER) mat = materialOr(Material.NETHERRACK, netherMat);
            else if (env == World.Environment.THE_END) mat = materialOr(Material.END_STONE, endMat);
            else mat = materialOr(Material.GRASS_BLOCK, overworldMat);
        } else {
            mat = materialOr(Material.GRASS_BLOCK, overworldMat);
        }

        String name = config.color(mapGetString(itemMap, "name", "&a&lLOCATION"));
        List<String> lore = mapGetStringList(itemMap, "lore");

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(apply(name, senderName, dimensionOf(sender), regionOf(sender)));
            meta.setLore(colorLore(applyAll(lore, senderName, dimensionOf(sender), regionOf(sender))));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack buildGenericItem(Map<?, ?> itemMap, String senderName) {
        Player sender = (senderName != null) ? Bukkit.getPlayerExact(senderName) : null;

        Material mat = materialOr(Material.PAPER, mapGetString(itemMap, "material", "PAPER"));
        String name = config.color(mapGetString(itemMap, "name", "&a&lINFO"));
        List<String> lore = mapGetStringList(itemMap, "lore");

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(apply(name, senderName, dimensionOf(sender), regionOf(sender)));
            meta.setLore(colorLore(applyAll(lore, senderName, dimensionOf(sender), regionOf(sender))));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack buildSimpleItem(ConfigurationSection sec,
                                      Material fallbackMat,
                                      String fallbackName,
                                      List<String> fallbackLore,
                                      boolean fallbackGlow,
                                      int fallbackCmd,
                                      Map<String, String> repl) {

        if (sec == null) {
            return simple(fallbackMat, fallbackName, fallbackLore, fallbackGlow, fallbackCmd, repl);
        }

        Material mat = materialOr(fallbackMat, sec.getString("material", fallbackMat.name()));
        String name = sec.getString("name", fallbackName);
        List<String> lore = sec.getStringList("lore");
        boolean glow = sec.getBoolean("glow", fallbackGlow);
        int cmd = sec.getInt("custom_model_data", fallbackCmd);

        return simple(mat, name, lore, glow, cmd, repl);
    }

    private ItemStack simple(Material mat, String name, List<String> lore, boolean glow, int cmd, Map<String, String> repl) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(config.color(replaceAll(name, repl)));

            List<String> outLore = new ArrayList<>();
            for (String l : lore) outLore.add(config.color(replaceAll(l, repl)));
            if (!outLore.isEmpty()) meta.setLore(outLore);

            if (cmd > 0) meta.setCustomModelData(cmd);

            if (glow) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }

            it.setItemMeta(meta);
        }
        return it;
    }

    // ---------------------------
    // Placeholder helpers
    // ---------------------------
    private String apply(String s, String sender, String dimension, String region) {
        return config.color(s)
                .replace("%sender%", safe(sender))
                .replace("%dimension_name%", safe(dimension))
                .replace("%region%", safe(region));
    }

    private List<String> applyAll(List<String> lore, String sender, String dimension, String region) {
        List<String> out = new ArrayList<>();
        for (String l : lore) out.add(apply(l, sender, dimension, region));
        return out;
    }

    private List<String> colorLore(List<String> lore) {
        List<String> out = new ArrayList<>();
        for (String l : lore) out.add(config.color(l));
        return out;
    }

    private String dimensionOf(Player sender) {
        if (sender == null) return "Unknown";
        World.Environment env = sender.getWorld().getEnvironment();
        return switch (env) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            default -> "Overworld";
        };
    }

    private String regionOf(Player sender) {
        if (sender == null) return (config.proxyEnabled() ? "Remote" : "Unknown");
        return "Local";
    }

    private String safe(String s) {
        return (s == null) ? "Unknown" : s;
    }

    private static Material materialOr(Material fallback, String name) {
        if (name == null) return fallback;
        try { return Material.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (Throwable t) { return fallback; }
    }

    // ---------------------------
    // Map<?, ?> safe helpers
    // ---------------------------
    private static Object mapGet(Map<?, ?> map, String key) {
        if (map == null) return null;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (key.equals(String.valueOf(e.getKey()))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String mapGetString(Map<?, ?> map, String key, String def) {
        Object v = mapGet(map, key);
        return (v == null) ? def : String.valueOf(v);
    }

    private static boolean mapGetBool(Map<?, ?> map, String key, boolean def) {
        Object v = mapGet(map, key);
        if (v instanceof Boolean b) return b;
        if (v == null) return def;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static int mapGetInt(Map<?, ?> map, String key, int def) {
        Object v = mapGet(map, key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Throwable t) { return def; }
    }

    private static List<String> mapGetStringList(Map<?, ?> map, String key) {
        Object v = mapGet(map, key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object x : list) out.add(String.valueOf(x));
            return out;
        }
        return List.of();
    }

    private static String replaceAll(String s, Map<String, String> repl) {
        String out = (s == null) ? "" : s;
        for (var e : repl.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    public static final class ConfirmHolder implements InventoryHolder {
        private final String senderName;
        private final RequestType type;

        public ConfirmHolder(String senderName, RequestType type) {
            this.senderName = senderName;
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
