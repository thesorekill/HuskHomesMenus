package net.chumbucket.huskhomesmenus;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class ConfirmRequestMenu implements Listener {

    public enum RequestType { TPA, TPAHERE }

    private final HuskHomesMenus plugin;
    private final HHMConfig config;
    private final ProxyPlayerCache playerCache;

    // ✅ PDC keys so we can swap material later after remote dimension arrives
    private final NamespacedKey KEY_DIM_ITEM;
    private final NamespacedKey KEY_DIM_OVERWORLD;
    private final NamespacedKey KEY_DIM_NETHER;
    private final NamespacedKey KEY_DIM_END;

    public ConfirmRequestMenu(HuskHomesMenus plugin, HHMConfig config, ProxyPlayerCache playerCache) {
        this.plugin = plugin;
        this.config = config;
        this.playerCache = playerCache;

        this.KEY_DIM_ITEM = new NamespacedKey(plugin, "hhm_dim_item");
        this.KEY_DIM_OVERWORLD = new NamespacedKey(plugin, "hhm_dim_overworld_mat");
        this.KEY_DIM_NETHER = new NamespacedKey(plugin, "hhm_dim_nether_mat");
        this.KEY_DIM_END = new NamespacedKey(plugin, "hhm_dim_end_mat");
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

        String region = resolveRegion(senderName);
        String dimension = resolveDimension(target, senderName);

        // filler
        boolean useFiller = menu.getBoolean("use_filler", false);
        if (useFiller) {
            ConfigurationSection fill = menu.getConfigurationSection("filler");
            ItemStack filler = buildSimpleItem(fill,
                    Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false, 0,
                    Map.of("%sender%", safe(senderName), "%dimension_name%", safe(dimension), "%region%", safe(region)));
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        }

        // items
        List<Integer> regionSlots = new ArrayList<>();
        List<Integer> dimensionSlots = new ArrayList<>();
        List<Map<?, ?>> items = menu.getMapList("items");
        for (Map<?, ?> itemMap : items) {
            int slot = mapGetInt(itemMap, "slot", -1);
            if (slot < 0 || slot >= inv.getSize()) continue;

            String itemType = mapGetString(itemMap, "type", "ITEM");

            if (containsRegionPlaceholder(itemMap)) regionSlots.add(slot);
            if (containsDimensionPlaceholder(itemMap)) dimensionSlots.add(slot);

            boolean requiresProxy = mapGetBool(itemMap, "requires_proxy", false);
            if (requiresProxy && !config.proxyEnabled()) continue;

            ItemStack built = switch (itemType.toUpperCase(Locale.ROOT)) {
                case "PLAYER_HEAD" -> buildPlayerHead(itemMap, target, senderName, dimension, region);
                case "DIMENSION_ITEM" -> buildDimensionItem(itemMap, senderName, dimension, region); // ✅ now stores PDC
                default -> buildGenericItem(itemMap, senderName, dimension, region);
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
                    Map.of("%sender%", safe(senderName), "%dimension_name%", safe(dimension), "%region%", safe(region)));
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, item);
        }

        // accept (CONFIRM)
        ConfigurationSection accept = menu.getConfigurationSection("accept");
        if (accept != null) {
            int slot = accept.getInt("slot", -1);
            ItemStack item = buildSimpleItem(accept.getConfigurationSection("item"),
                    Material.LIME_STAINED_GLASS_PANE, "&a&lCONFIRM",
                    List.of("&7Click to accept %sender%'s request"), false, 0,
                    Map.of("%sender%", safe(senderName), "%dimension_name%", safe(dimension), "%region%", safe(region)));
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, item);
        }

        target.openInventory(inv);

        // existing region refresh behavior
        if (config.proxyEnabled() && playerCache != null && !regionSlots.isEmpty() && "Loading...".equalsIgnoreCase(region)) {
            scheduleRegionRefresh(target, inv, senderName, regionSlots);
        }

        // ✅ dimension refresh
        if (config.proxyEnabled() && playerCache != null && !dimensionSlots.isEmpty() && "Loading...".equalsIgnoreCase(dimension)) {
            scheduleDimensionRefresh(target, inv, senderName, dimensionSlots);
        }
    }

    private boolean containsRegionPlaceholder(Map<?, ?> itemMap) {
        String name = mapGetString(itemMap, "name", "");
        if (name != null && name.contains("%region%")) return true;

        Object loreObj = itemMap.get("lore");
        if (loreObj instanceof List<?> loreList) {
            for (Object o : loreList) {
                if (o == null) continue;
                if (o.toString().contains("%region%")) return true;
            }
        }
        return false;
    }

    private boolean containsDimensionPlaceholder(Map<?, ?> itemMap) {
        if (itemMap == null) return false;

        String name = mapGetString(itemMap, "name", "");
        if (name != null && name.contains("%dimension_name%")) return true;

        Object loreObj = itemMap.get("lore");
        if (loreObj instanceof List<?> loreList) {
            for (Object o : loreList) {
                if (o == null) continue;
                if (o.toString().contains("%dimension_name%")) return true;
            }
        }
        return false;
    }

    private void scheduleRegionRefresh(Player viewer, Inventory inv, String senderName, List<Integer> regionSlots) {
        new BukkitRunnable() {
            int tries = 0;

            @Override
            public void run() {
                tries++;

                if (viewer == null || !viewer.isOnline()) { cancel(); return; }
                if (viewer.getOpenInventory() == null || viewer.getOpenInventory().getTopInventory() != inv) { cancel(); return; }

                String srv = playerCache.getServerFor(senderName);
                if (srv != null && !srv.isBlank()) {
                    updateRegionSlots(inv, regionSlots, srv.trim());
                    cancel();
                    return;
                }

                if (tries >= 20) {
                    updateRegionSlots(inv, regionSlots, "Offline");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    private void scheduleDimensionRefresh(Player viewer, Inventory inv, String senderName, List<Integer> dimensionSlots) {
        new BukkitRunnable() {
            int tries = 0;

            @Override
            public void run() {
                tries++;

                if (viewer == null || !viewer.isOnline()) { cancel(); return; }
                if (viewer.getOpenInventory() == null || viewer.getOpenInventory().getTopInventory() != inv) { cancel(); return; }

                String dim = playerCache.getDimensionFor(senderName);
                if (dim != null && !dim.isBlank()) {
                    updateDimensionSlots(inv, dimensionSlots, dim);
                    cancel();
                    return;
                }

                if (tries >= 20) {
                    updateDimensionSlots(inv, dimensionSlots, "Unknown");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    /**
     * ✅ Updates %dimension_name% and ALSO swaps the item material for DIMENSION_ITEMs
     * based on the three materials stored in PDC.
     */
    private void updateDimensionSlots(Inventory inv, List<Integer> slots, String dimensionValue) {
        for (Integer slot : slots) {
            if (slot == null) continue;
            if (slot < 0 || slot >= inv.getSize()) continue;

            ItemStack current = inv.getItem(slot);
            if (current == null) continue;

            ItemMeta curMeta = current.getItemMeta();
            if (curMeta == null) continue;

            // Replace placeholders in name/lore
            ItemStack updatedStack = current.clone();
            ItemMeta meta = updatedStack.getItemMeta();
            if (meta == null) continue;

            String safeDim = safe(dimensionValue);

            if (meta.hasDisplayName()) {
                meta.setDisplayName(config.color(meta.getDisplayName()
                        .replace("%dimension_name%", safeDim)
                        .replace("Loading...", safeDim)));
            }

            List<String> lore = meta.getLore();
            if (lore != null) {
                List<String> newLore = new ArrayList<>(lore.size());
                for (String line : lore) {
                    if (line == null) { newLore.add(null); continue; }
                    newLore.add(config.color(line
                            .replace("%dimension_name%", safeDim)
                            .replace("Loading...", safeDim)));
                }
                meta.setLore(newLore);
            }

            // ✅ If this is a DIMENSION_ITEM, swap material based on stored config materials
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            boolean isDimItem = pdc.has(KEY_DIM_ITEM, PersistentDataType.BYTE);

            if (isDimItem) {
                Material newMat = pickDimensionMaterialFromPdc(meta, safeDim);
                if (newMat != null && newMat != updatedStack.getType()) {
                    ItemStack swapped = new ItemStack(newMat, updatedStack.getAmount());
                    swapped.setItemMeta(meta); // meta already contains PDC + updated text
                    inv.setItem(slot, swapped);
                    continue;
                }
            }

            updatedStack.setItemMeta(meta);
            inv.setItem(slot, updatedStack);
        }
    }

    private Material pickDimensionMaterialFromPdc(ItemMeta meta, String dimensionValue) {
        try {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            String o = pdc.get(KEY_DIM_OVERWORLD, PersistentDataType.STRING);
            String n = pdc.get(KEY_DIM_NETHER, PersistentDataType.STRING);
            String e = pdc.get(KEY_DIM_END, PersistentDataType.STRING);

            String d = (dimensionValue == null) ? "" : dimensionValue.toLowerCase(Locale.ROOT);

            if (d.contains("nether")) return materialOr(Material.NETHERRACK, n);
            if (d.contains("end")) return materialOr(Material.END_STONE, e);
            return materialOr(Material.GRASS_BLOCK, o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void updateRegionSlots(Inventory inv, List<Integer> slots, String regionValue) {
        for (Integer slot : slots) {
            if (slot == null) continue;
            if (slot < 0 || slot >= inv.getSize()) continue;

            ItemStack item = inv.getItem(slot);
            if (item == null) continue;

            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            if (meta.hasDisplayName()) {
                meta.setDisplayName(meta.getDisplayName().replace("Loading...", regionValue));
            }
            List<String> lore = meta.getLore();
            if (lore != null && !lore.isEmpty()) {
                List<String> newLore = new ArrayList<>(lore.size());
                for (String line : lore) {
                    if (line == null) newLore.add(null);
                    else newLore.add(line.replace("Loading...", regionValue).replace("%region%", regionValue));
                }
                meta.setLore(newLore);
            }

            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }
    }

    // ---------------------------
    // Click + Drag handling (prevents taking items)
    // ---------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof ConfirmHolder holder)) return;

        e.setCancelled(true);

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
        if (close) p.closeInventory();

        final String sender = holder.senderName;
        final RequestType type = holder.type;

        PendingRequests.bypassForMs(p.getUniqueId(), 1200);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (doAccept) runAccept(p, sender, type);
            else runDeny(p, sender, type);

            if (sender != null && !sender.isBlank()) PendingRequests.remove(p.getUniqueId(), sender);
            else PendingRequests.clear(p.getUniqueId());
            if (close) p.closeInventory();
        }, 1L);
    }

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
                "huskhomes:tpyes"
        );

        if (!ok) p.sendMessage(config.prefix() + ChatColor.RED + "Could not accept the request.");
    }

    private void runDeny(Player p, String sender, RequestType type) {
        boolean hasSender = sender != null && !sender.isBlank();

        boolean ok = dispatchFirstSuccessful(
                p,
                hasSender ? "huskhomes:tpdecline " + sender : "huskhomes:tpdecline",
                hasSender ? "huskhomes:tpdeny " + sender : "huskhomes:tpdeny",
                "huskhomes:tpdecline",
                "huskhomes:tpdeny",
                "huskhomes:tpno"
        );

        if (!ok) p.sendMessage(config.prefix() + ChatColor.RED + "Could not decline the request.");
    }

    // ---------------------------
    // Item builders
    // ---------------------------
    private ItemStack buildPlayerHead(Map<?, ?> itemMap, Player viewer, String senderName, String dimension, String region) {
        String name = config.color(mapGetString(itemMap, "name", "&a&lPLAYER"));
        List<String> lore = mapGetStringList(itemMap, "lore");

        String headOf = mapGetString(itemMap, "head_of", "SENDER").toUpperCase(Locale.ROOT);

        String ownerName;
        switch (headOf) {
            case "VIEWER" -> ownerName = (viewer != null ? viewer.getName() : null);
            case "NAME" -> ownerName = mapGetString(itemMap, "head_name", senderName);
            case "SENDER" -> ownerName = senderName;
            default -> ownerName = senderName;
        }

        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta0 = skull.getItemMeta();
        if (meta0 instanceof SkullMeta meta) {
            if (ownerName != null && !ownerName.isBlank()) {
                Player owner = Bukkit.getPlayerExact(ownerName);
                if (owner != null) meta.setOwningPlayer(owner);
            }

            meta.setDisplayName(apply(name, senderName, dimension, region));
            meta.setLore(colorLore(applyAll(lore, senderName, dimension, region)));
            skull.setItemMeta(meta);
        }
        return skull;
    }

    private ItemStack buildDimensionItem(Map<?, ?> itemMap, String senderName, String dimension, String region) {
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
            // remote: choose based on dimension string if known; otherwise default overworld icon
            String d = (dimension == null) ? "" : dimension.toLowerCase(Locale.ROOT);
            if (d.contains("nether")) mat = materialOr(Material.NETHERRACK, netherMat);
            else if (d.contains("end")) mat = materialOr(Material.END_STONE, endMat);
            else mat = materialOr(Material.GRASS_BLOCK, overworldMat);
        }

        String name = config.color(mapGetString(itemMap, "name", "&a&lLOCATION"));
        List<String> lore = mapGetStringList(itemMap, "lore");

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            // ✅ mark as dimension item and store configured materials for later refresh swap
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_DIM_ITEM, PersistentDataType.BYTE, (byte) 1);
            pdc.set(KEY_DIM_OVERWORLD, PersistentDataType.STRING, overworldMat);
            pdc.set(KEY_DIM_NETHER, PersistentDataType.STRING, netherMat);
            pdc.set(KEY_DIM_END, PersistentDataType.STRING, endMat);

            meta.setDisplayName(apply(name, senderName, dimension, region));
            meta.setLore(colorLore(applyAll(lore, senderName, dimension, region)));
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack buildGenericItem(Map<?, ?> itemMap, String senderName, String dimension, String region) {
        Material mat = materialOr(Material.PAPER, mapGetString(itemMap, "material", "PAPER"));
        String name = config.color(mapGetString(itemMap, "name", "&a&lINFO"));
        List<String> lore = mapGetStringList(itemMap, "lore");

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(apply(name, senderName, dimension, region));
            meta.setLore(colorLore(applyAll(lore, senderName, dimension, region)));
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
    // Region + dimension resolution
    // ---------------------------
    private String resolveRegion(String senderName) {
        if (!config.proxyEnabled()) return "Local";

        String srv = (playerCache != null) ? playerCache.getServerFor(senderName) : null;
        if (srv != null && !srv.isBlank()) return srv;

        return "Local";
    }

    private String resolveDimension(Player target, String senderName) {
        Player sender = (senderName != null) ? Bukkit.getPlayerExact(senderName) : null;
        if (sender != null) {
            World.Environment env = sender.getWorld().getEnvironment();
            return switch (env) {
                case NETHER -> "Nether";
                case THE_END -> "The End";
                default -> "Overworld";
            };
        }

        if (config.proxyEnabled() && playerCache != null && target != null) {
            String dim = playerCache.getOrRequestDimension(senderName, target.getName());
            if (dim != null && !dim.isBlank()) return dim;
        }

        return "Unknown";
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
            if (key.equals(String.valueOf(e.getKey()))) return e.getValue();
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
