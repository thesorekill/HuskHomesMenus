/*
 * Copyright © 2025 Sorekill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

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

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfirmRequestMenu implements Listener {

    public enum RequestType { TPA, TPAHERE }

    private final HuskHomesMenus plugin;
    private final HHMConfig config;
    private final ProxyPlayerCache playerCache;

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    private final NamespacedKey KEY_DIM_ITEM;
    private final NamespacedKey KEY_DIM_OVERWORLD;
    private final NamespacedKey KEY_DIM_NETHER;
    private final NamespacedKey KEY_DIM_END;

    // ------------------------------------------------------------------
    // session tracking so "close menu" can auto-deny
    // ------------------------------------------------------------------
    private static final class Session {
        final String senderName;
        volatile boolean acted;

        Session(String senderName) {
            this.senderName = senderName;
            this.acted = false;
        }
    }

    // viewerUuid -> session
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    // ------------------------------------------------------------------

    public ConfirmRequestMenu(HuskHomesMenus plugin, HHMConfig config, ProxyPlayerCache playerCache) {
        this.plugin = plugin;
        this.config = config;
        this.playerCache = playerCache;

        this.KEY_DIM_ITEM = new NamespacedKey(plugin, "hhm_dim_item");
        this.KEY_DIM_OVERWORLD = new NamespacedKey(plugin, "hhm_dim_overworld_mat");
        this.KEY_DIM_NETHER = new NamespacedKey(plugin, "hhm_dim_nether_mat");
        this.KEY_DIM_END = new NamespacedKey(plugin, "hhm_dim_end_mat");
    }

    /**
     * Converts legacy text (with & color codes) into a Component, and disables italics unless the
     * config explicitly requests italics via &o / §o.
     */
    private Component legacyToComponentNoItalic(String legacy) {
        String colored = config.color(legacy == null ? "" : legacy);

        // If they explicitly asked for italics in config, don't override it
        boolean wantsItalic = colored.contains("&o") || colored.contains("§o");

        Component c = AMP.deserialize(colored);
        return wantsItalic ? c : c.decoration(TextDecoration.ITALIC, false);
    }

    public void open(Player target, String senderName, RequestType type) {
        if (!config.isEnabled("menus.confirm_request.enabled", true)) return;

        ConfigurationSection menu = config.section("menus.confirm_request");
        if (menu == null) return;

        int rows = Math.max(1, Math.min(6, menu.getInt("rows", 3)));
        String titleKey = (type == RequestType.TPA) ? "title_tpa" : "title_tpahere";
        String title = config.color(menu.getString(titleKey, menu.getString("title", "&7CONFIRM REQUEST")));

        ConfirmHolder holder = new ConfirmHolder(senderName, type);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, AMP.deserialize(title));

        boolean senderLocal = (senderName != null && Bukkit.getPlayerExact(senderName) != null);

        String region = resolveRegion(senderName, senderLocal);
        String dimension = resolveDimension(target, senderName, senderLocal);

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
        List<Integer> headSlotsToRefresh = new ArrayList<>();

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
                case "DIMENSION_ITEM" -> buildDimensionItem(itemMap, senderName, dimension, region);
                default -> buildGenericItem(itemMap, senderName, dimension, region);
            };

            inv.setItem(slot, built);

            // If this is a sender head and sender is remote, refresh later if skin isn't ready yet
            if ("PLAYER_HEAD".equalsIgnoreCase(itemType) && config.proxyEnabled() && !senderLocal) {
                String headOf = mapGetString(itemMap, "head_of", "SENDER").toUpperCase(Locale.ROOT);
                if ("SENDER".equals(headOf) || "NAME".equals(headOf)) {
                    String ownerName = switch (headOf) {
                        case "NAME" -> mapGetString(itemMap, "head_name", senderName);
                        default -> senderName;
                    };

                    PendingRequests.Skin skin = (ownerName != null && target != null)
                            ? PendingRequests.getSkin(target.getUniqueId(), ownerName)
                            : null;

                    if (skin == null || skin.value() == null || skin.value().isBlank()) {
                        headSlotsToRefresh.add(slot);
                    }
                }
            }
        }

        // deny
        ConfigurationSection deny = menu.getConfigurationSection("deny");
        if (deny != null) {
            int slot = deny.getInt("slot", -1);
            ItemStack item = buildSimpleItem(deny.getConfigurationSection("item"),
                    Material.RED_STAINED_GLASS_PANE, "&c&lCANCEL",
                    List.of("&7Click to cancel the teleport"), false, 0,
                    Map.of("%sender%", safe(senderName), "%dimension_name%", safe(dimension), "%region%", safe(region)));
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, item);
        }

        // accept
        ConfigurationSection accept = menu.getConfigurationSection("accept");
        if (accept != null) {
            int slot = accept.getInt("slot", -1);
            ItemStack item = buildSimpleItem(accept.getConfigurationSection("item"),
                    Material.LIME_STAINED_GLASS_PANE, "&a&lCONFIRM",
                    List.of("&7Click to accept %sender%'s request"), false, 0,
                    Map.of("%sender%", safe(senderName), "%dimension_name%", safe(dimension), "%region%", safe(region)));
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, item);
        }

        // store session before opening
        sessions.put(target.getUniqueId(), new Session(senderName));

        target.openInventory(inv);

        // Region refresh
        if (config.proxyEnabled() && playerCache != null && !senderLocal && !regionSlots.isEmpty()) {
            if ("Loading...".equalsIgnoreCase(region) || region == null || region.isBlank() || "Unknown".equalsIgnoreCase(region)) {
                scheduleRegionRefresh(target, inv, senderName, regionSlots);
            }
        }

        // Dimension refresh
        if (config.proxyEnabled() && playerCache != null && !senderLocal && !dimensionSlots.isEmpty()) {
            if ("Loading...".equalsIgnoreCase(dimension) || dimension == null || dimension.isBlank() || "Unknown".equalsIgnoreCase(dimension)) {
                scheduleDimensionRefresh(target, inv, senderName, dimensionSlots);
            }
        }

        // Head refresh
        if (config.proxyEnabled() && !senderLocal && !headSlotsToRefresh.isEmpty()) {
            scheduleHeadRefresh(target, inv, senderName, headSlotsToRefresh);
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

    // =========================================================
    // ✅ Folia-safe refresh loops (2 ticks, up to 20 tries)
    // =========================================================

    private boolean stillViewing(Player viewer, Inventory inv) {
        if (viewer == null || !viewer.isOnline()) return false;
        try {
            return viewer.getOpenInventory() != null && viewer.getOpenInventory().getTopInventory() == inv;
        } catch (Throwable t) {
            return false;
        }
    }

    private void scheduleRegionRefresh(Player viewer, Inventory inv, String senderName, List<Integer> regionSlots) {
        scheduleRegionRefresh0(viewer, inv, senderName, regionSlots, 0);
    }

    private void scheduleRegionRefresh0(Player viewer, Inventory inv, String senderName, List<Integer> regionSlots, int tries) {
        Sched.later(viewer, 2L, () -> {
            int nextTries = tries + 1;

            if (!stillViewing(viewer, inv)) return;

            String srv = playerCache.getServerForFresh(senderName);
            if (srv != null && !srv.isBlank()) {
                updateRegionSlots(inv, regionSlots, srv.trim());
                try { viewer.updateInventory(); } catch (Throwable ignored) {}
                return;
            }

            if (nextTries >= 20) {
                updateRegionSlots(inv, regionSlots, "Offline");
                try { viewer.updateInventory(); } catch (Throwable ignored) {}
                return;
            }

            scheduleRegionRefresh0(viewer, inv, senderName, regionSlots, nextTries);
        });
    }

    private void scheduleDimensionRefresh(Player viewer, Inventory inv, String senderName, List<Integer> dimensionSlots) {
        scheduleDimensionRefresh0(viewer, inv, senderName, dimensionSlots, 0);
    }

    private void scheduleDimensionRefresh0(Player viewer, Inventory inv, String senderName, List<Integer> dimensionSlots, int tries) {
        Sched.later(viewer, 2L, () -> {
            int nextTries = tries + 1;

            if (!stillViewing(viewer, inv)) return;

            String dim = playerCache.getOrRequestDimension(senderName, viewer.getName());
            if (dim != null && !dim.isBlank() && !"Loading...".equalsIgnoreCase(dim) && !"Unknown".equalsIgnoreCase(dim)) {
                updateDimensionSlots(inv, dimensionSlots, dim);
                try { viewer.updateInventory(); } catch (Throwable ignored) {}
                return;
            }

            if (nextTries >= 20) {
                updateDimensionSlots(inv, dimensionSlots, "Unknown");
                try { viewer.updateInventory(); } catch (Throwable ignored) {}
                return;
            }

            scheduleDimensionRefresh0(viewer, inv, senderName, dimensionSlots, nextTries);
        });
    }

    private void scheduleHeadRefresh(Player viewer, Inventory inv, String senderName, List<Integer> headSlots) {
        scheduleHeadRefresh0(viewer, inv, senderName, headSlots, 0);
    }

    private void scheduleHeadRefresh0(Player viewer, Inventory inv, String senderName, List<Integer> headSlots, int tries) {
        Sched.later(viewer, 2L, () -> {
            int nextTries = tries + 1;

            if (!stillViewing(viewer, inv)) return;

            PendingRequests.Skin skin = PendingRequests.getSkin(viewer.getUniqueId(), senderName);
            if (skin != null && skin.value() != null && !skin.value().isBlank()) {
                for (Integer slot : headSlots) {
                    if (slot == null) continue;
                    if (slot < 0 || slot >= inv.getSize()) continue;

                    ItemStack it = inv.getItem(slot);
                    if (it == null || it.getType() != Material.PLAYER_HEAD) continue;

                    ItemMeta im = it.getItemMeta();
                    if (!(im instanceof SkullMeta)) continue;

                    ItemStack clone = it.clone();
                    SkullMeta meta = (SkullMeta) clone.getItemMeta();
                    if (meta == null) continue;

                    boolean ok = applyTexturesToSkull(meta, senderName, skin.value(), skin.signature());
                    if (config.debug()) {
                        plugin.getLogger().info("applyTexturesToSkull(slot=" + slot + ", owner=" + senderName + ") ok=" + ok
                                + " valueLen=" + skin.value().length());
                    }

                    if (ok) {
                        clone.setItemMeta(meta);
                        inv.setItem(slot, clone);
                    }
                }

                try { viewer.updateInventory(); } catch (Throwable ignored) {}
                return;
            }

            if (nextTries >= 20) return;

            scheduleHeadRefresh0(viewer, inv, senderName, headSlots, nextTries);
        });
    }

    private void updateDimensionSlots(Inventory inv, List<Integer> slots, String dimensionValue) {
        for (Integer slot : slots) {
            if (slot == null) continue;
            if (slot < 0 || slot >= inv.getSize()) continue;

            ItemStack current = inv.getItem(slot);
            if (current == null) continue;

            ItemMeta curMeta = current.getItemMeta();
            if (curMeta == null) continue;

            ItemStack updatedStack = current.clone();
            ItemMeta meta = updatedStack.getItemMeta();
            if (meta == null) continue;

            String safeDim = safe(dimensionValue);

            Component dn = meta.displayName();
            if (dn != null) {
                String text = AMP.serialize(dn);

                text = text.replace("%dimension_name%", safeDim)
                        .replace("Loading...", safeDim);

                meta.displayName(legacyToComponentNoItalic(text));
            }

            List<Component> lore = meta.lore();
            if (lore != null) {
                List<Component> newLore = new ArrayList<>(lore.size());

                for (Component lineComp : lore) {
                    if (lineComp == null) {
                        newLore.add(null);
                        continue;
                    }

                    String line = AMP.serialize(lineComp);

                    line = line.replace("%dimension_name%", safeDim)
                            .replace("Loading...", safeDim);

                    newLore.add(legacyToComponentNoItalic(line));
                }

                meta.lore(newLore);
            }

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            boolean isDimItem = pdc.has(KEY_DIM_ITEM, PersistentDataType.BYTE);

            if (isDimItem) {
                Material newMat = pickDimensionMaterialFromPdc(meta, safeDim);
                if (newMat != null && newMat != updatedStack.getType()) {
                    ItemStack swapped = new ItemStack(newMat, updatedStack.getAmount());
                    swapped.setItemMeta(meta);
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
        String safeRegion = safe(regionValue);

        for (Integer slot : slots) {
            if (slot == null) continue;
            if (slot < 0 || slot >= inv.getSize()) continue;

            ItemStack item = inv.getItem(slot);
            if (item == null) continue;

            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;

            Component dn = meta.displayName();
            if (dn != null) {
                String text = AMP.serialize(dn)
                        .replace("Loading...", safeRegion)
                        .replace("%region%", safeRegion);

                meta.displayName(legacyToComponentNoItalic(text));
            }

            List<Component> lore = meta.lore();
            if (lore != null && !lore.isEmpty()) {
                List<Component> newLore = new ArrayList<>(lore.size());

                for (Component lineComp : lore) {
                    if (lineComp == null) {
                        newLore.add(null);
                        continue;
                    }

                    String line = AMP.serialize(lineComp)
                            .replace("Loading...", safeRegion)
                            .replace("%region%", safeRegion);

                    newLore.add(legacyToComponentNoItalic(line));
                }

                meta.lore(newLore);
            }

            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }
    }

    // ---------------------------
    // Auto-deny on close
    // ---------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        // Only for our confirm menu
        if (!(e.getInventory().getHolder() instanceof ConfirmHolder)) return;

        // If auto-deny disabled, just clear session
        if (!config.isEnabled("menus.confirm_request.auto_deny_on_close", true)) {
            sessions.remove(p.getUniqueId());
            return;
        }

        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;

        // If they already accepted/denied, do nothing
        if (s.acted) {
            sessions.remove(p.getUniqueId());
            return;
        }

        // mark acted + remove session immediately to prevent double-firing
        s.acted = true;
        sessions.remove(p.getUniqueId());

        String resolvedSender = s.senderName;
        if (resolvedSender == null || resolvedSender.isBlank()) {
            PendingRequests.Pending last = PendingRequests.get(p.getUniqueId());
            if (last != null) resolvedSender = last.senderName();
        }
        if (resolvedSender == null || resolvedSender.isBlank()) return;

        final String senderFinal = resolvedSender;

        // prevent the deny command we run here from being "caught" by any PendingRequests hooks
        PendingRequests.bypassForMs(p.getUniqueId(), 1200);

        // IMPORTANT: don't remove the pending request until AFTER the command runs,
        // otherwise /tpdeny <name> can fail to match on some setups.
        Sched.run(p, () -> {
            try {
                Bukkit.dispatchCommand(p, "huskhomes:tpdeny " + senderFinal);
            } finally {
                PendingRequests.remove(p.getUniqueId(), senderFinal);
            }
        });
    }

    // ---------------------------
    // Click + Drag handling
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

    // ---------------------------
    // ✅ Click actions (player/console)
    // ---------------------------

    private void runClickCommands(Player p, ConfigurationSection buttonSection, ConfirmHolder holder) {
        if (p == null || buttonSection == null) return;

        ConfigurationSection click = buttonSection.getConfigurationSection("click");
        if (click == null) return;

        List<String> playerCmds = click.getStringList("player_commands");
        List<String> consoleCmds = click.getStringList("console_commands");

        if ((playerCmds == null || playerCmds.isEmpty()) && (consoleCmds == null || consoleCmds.isEmpty())) return;

        String sender = (holder != null) ? holder.senderName : null;
        String senderSafe = (sender == null) ? "" : sender;

        boolean senderLocal = (sender != null && Bukkit.getPlayerExact(sender) != null);
        String region = resolveRegion(sender, senderLocal);
        String dimension = resolveDimension(p, sender, senderLocal);

        Map<String, String> ph = new HashMap<>();
        ph.put("%player%", p.getName());
        ph.put("%sender%", senderSafe);
        ph.put("%dimension_name%", safe(dimension));
        ph.put("%region%", safe(region));

        // player commands
        if (playerCmds != null) {
            for (String raw : playerCmds) {
                dispatchWithPlaceholdersAsPlayer(p, raw, ph);
            }
        }

        // console commands
        if (consoleCmds != null) {
            for (String raw : consoleCmds) {
                dispatchWithPlaceholdersAsConsole(raw, ph);
            }
        }
    }

    private void dispatchWithPlaceholdersAsPlayer(Player p, String raw, Map<String, String> ph) {
        if (p == null || raw == null) return;

        String cmd = applyPlaceholders(raw, ph).trim();
        if (cmd.isEmpty()) return;

        if (cmd.startsWith("/")) cmd = cmd.substring(1);

        try { Bukkit.dispatchCommand(p, cmd); } catch (Throwable ignored) {}
    }

    private void dispatchWithPlaceholdersAsConsole(String raw, Map<String, String> ph) {
        if (raw == null) return;

        String cmd = applyPlaceholders(raw, ph).trim();
        if (cmd.isEmpty()) return;

        if (cmd.startsWith("/")) cmd = cmd.substring(1);

        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd); } catch (Throwable ignored) {}
    }

    private String applyPlaceholders(String s, Map<String, String> ph) {
        if (s == null) return "";
        String out = s;

        if (ph != null && !ph.isEmpty()) {
            for (Map.Entry<String, String> ent : ph.entrySet()) {
                if (ent.getKey() == null) continue;
                out = out.replace(ent.getKey(), ent.getValue() == null ? "" : ent.getValue());
            }
        }
        return out;
    }

    private void handleButton(Player p, ConfigurationSection section, ConfirmHolder holder, boolean doAccept) {
        if (section == null) return;

        // mark acted so close event doesn't auto-deny
        Session s = sessions.get(p.getUniqueId());
        if (s != null) s.acted = true;

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

        // ✅ Folia-safe: 1 tick later on player's thread
        Sched.later(p, 1L, () -> {
            if (doAccept) runAccept(p, sender, type);
            else runDeny(p, sender, type);

            // ✅ run configured click commands (player + console)
            runClickCommands(p, section, holder);

            if (sender != null && !sender.isBlank()) PendingRequests.remove(p.getUniqueId(), sender);
            else PendingRequests.clear(p.getUniqueId());

            sessions.remove(p.getUniqueId());

            if (close) p.closeInventory();
        });
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

        if (!ok) {
            p.sendMessage(
                    AMP.deserialize(config.prefix())
                            .append(AMP.deserialize("&cCould not accept the request."))
            );
        }
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

        if (!ok) {
            p.sendMessage(
                    AMP.deserialize(config.prefix())
                            .append(AMP.deserialize("&cCould not decline the request."))
            );
        }
    }

    // ---------------------------
    // Item builders
    // ---------------------------
    private ItemStack buildPlayerHead(Map<?, ?> itemMap, Player viewer, String senderName, String dimension, String region) {
        String name = mapGetString(itemMap, "name", "&a&lPLAYER");
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
                if (owner != null) {
                    meta.setOwningPlayer(owner);
                } else if (config.proxyEnabled() && viewer != null) {
                    PendingRequests.Skin skin = PendingRequests.getSkin(viewer.getUniqueId(), ownerName);
                    if (skin != null && skin.value() != null && !skin.value().isBlank()) {
                        boolean ok = applyTexturesToSkull(meta, ownerName, skin.value(), skin.signature());
                        if (config.debug()) {
                            plugin.getLogger().info("buildPlayerHead applyTexturesToSkull(owner=" + ownerName + ") ok=" + ok
                                    + " valueLen=" + skin.value().length());
                        }
                    }
                }
            }

            meta.displayName(legacyToComponentNoItalic(apply(name, senderName, dimension, region)));

            List<String> loreLines = applyAll(lore, senderName, dimension, region);
            List<Component> loreComps = new ArrayList<>(loreLines.size());
            for (String line : loreLines) loreComps.add(legacyToComponentNoItalic(line));
            meta.lore(loreComps.isEmpty() ? null : loreComps);

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
            String d = (dimension == null) ? "" : dimension.toLowerCase(Locale.ROOT);
            if (d.contains("nether")) mat = materialOr(Material.NETHERRACK, netherMat);
            else if (d.contains("end")) mat = materialOr(Material.END_STONE, endMat);
            else mat = materialOr(Material.GRASS_BLOCK, overworldMat);
        }

        String name = mapGetString(itemMap, "name", "&a&lLOCATION");
        List<String> lore = mapGetStringList(itemMap, "lore");

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_DIM_ITEM, PersistentDataType.BYTE, (byte) 1);
            pdc.set(KEY_DIM_OVERWORLD, PersistentDataType.STRING, overworldMat);
            pdc.set(KEY_DIM_NETHER, PersistentDataType.STRING, netherMat);
            pdc.set(KEY_DIM_END, PersistentDataType.STRING, endMat);

            meta.displayName(legacyToComponentNoItalic(apply(name, senderName, dimension, region)));

            List<String> loreLines = applyAll(lore, senderName, dimension, region);
            List<Component> loreComps = new ArrayList<>(loreLines.size());
            for (String line : loreLines) loreComps.add(legacyToComponentNoItalic(line));
            meta.lore(loreComps.isEmpty() ? null : loreComps);

            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack buildGenericItem(Map<?, ?> itemMap, String senderName, String dimension, String region) {
        Material mat = materialOr(Material.PAPER, mapGetString(itemMap, "material", "PAPER"));
        String name = mapGetString(itemMap, "name", "&a&lINFO");
        List<String> lore = mapGetStringList(itemMap, "lore");

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(legacyToComponentNoItalic(apply(name, senderName, dimension, region)));

            List<String> loreLines = applyAll(lore, senderName, dimension, region);
            List<Component> loreComps = new ArrayList<>(loreLines.size());
            for (String line : loreLines) loreComps.add(legacyToComponentNoItalic(line));
            meta.lore(loreComps.isEmpty() ? null : loreComps);

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
            meta.displayName(legacyToComponentNoItalic(replaceAll(name, repl)));

            List<Component> outLore = new ArrayList<>();
            for (String l : lore) {
                outLore.add(legacyToComponentNoItalic(replaceAll(l, repl)));
            }
            if (!outLore.isEmpty()) meta.lore(outLore);

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
    private String resolveRegion(String senderName, boolean senderLocal) {
        if (!config.proxyEnabled()) return config.backendName();
        if (senderLocal) return config.backendName();

        if (playerCache == null) return "Loading...";

        String srv = playerCache.getServerForFresh(senderName);
        if (srv != null && !srv.isBlank()) return srv.trim();

        return "Loading...";
    }

    private String resolveDimension(Player target, String senderName, boolean senderLocal) {
        if (senderLocal) {
            Player sender = (senderName != null) ? Bukkit.getPlayerExact(senderName) : null;
            if (sender != null) {
                World.Environment env = sender.getWorld().getEnvironment();
                return switch (env) {
                    case NETHER -> "Nether";
                    case THE_END -> "The End";
                    default -> "Overworld";
                };
            }
            return "Overworld";
        }

        if (config.proxyEnabled() && playerCache != null && target != null) {
            return playerCache.getOrRequestDimension(senderName, target.getName());
        }

        return "Unknown";
    }

    // ---------------------------
    // Skull texture injection (remote skin)
    // ---------------------------
    private boolean applyTexturesToSkull(SkullMeta meta, String ownerName, String texturesValue, String texturesSignature) {
        if (meta == null) return false;
        if (texturesValue == null || texturesValue.isBlank()) return false;

        final boolean dbg = (config != null && config.debug());
        final String safeName = (ownerName == null || ownerName.isBlank()) ? "HHM" : ownerName;
        final String sig = (texturesSignature == null || texturesSignature.isBlank()) ? null : texturesSignature;

        try {
            Class<?> gameProfileClz = Class.forName("com.mojang.authlib.GameProfile");
            Object gameProfile = gameProfileClz
                    .getConstructor(UUID.class, String.class)
                    .newInstance(UUID.randomUUID(), safeName);

            Class<?> propClz = Class.forName("com.mojang.authlib.properties.Property");
            Object texturesProp;
            try {
                texturesProp = propClz.getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", texturesValue, sig);
            } catch (NoSuchMethodException ignored) {
                texturesProp = propClz.getConstructor(String.class, String.class)
                        .newInstance("textures", texturesValue);
            }

            Object propertyMap = gameProfileClz.getMethod("getProperties").invoke(gameProfile);
            boolean putOk = false;
            try {
                propertyMap.getClass().getMethod("put", String.class, propClz)
                        .invoke(propertyMap, "textures", texturesProp);
                putOk = true;
            } catch (Throwable ignored) {}
            if (!putOk) {
                propertyMap.getClass().getMethod("put", Object.class, Object.class)
                        .invoke(propertyMap, "textures", texturesProp);
            }

            Field profileField = findSkullProfileField(meta.getClass());
            if (profileField == null) {
                if (dbg) plugin.getLogger().info("applyTexturesToSkull: could not find skull profile field on " + meta.getClass().getName());
                return false;
            }

            profileField.setAccessible(true);
            Class<?> fieldType = profileField.getType();
            String fieldTypeName = fieldType.getName();

            if (fieldType.isAssignableFrom(gameProfileClz) || fieldTypeName.equals(gameProfileClz.getName())) {
                profileField.set(meta, gameProfile);
                if (dbg) plugin.getLogger().info("applyTexturesToSkull: injected GameProfile into " + profileField.getName());
                return true;
            }

            String ftLower = fieldTypeName.toLowerCase(Locale.ROOT);
            if (ftLower.contains("resolvableprofile")) {
                Object resolvable = null;

                try {
                    resolvable = fieldType.getConstructor(gameProfileClz).newInstance(gameProfile);
                } catch (Throwable ignored) {}

                if (resolvable == null) {
                    for (String mname : List.of("of", "fromGameProfile", "a")) {
                        try {
                            Method m = fieldType.getMethod(mname, gameProfileClz);
                            resolvable = m.invoke(null, gameProfile);
                            break;
                        } catch (Throwable ignored) {}
                    }
                }

                if (resolvable == null) {
                    if (dbg) plugin.getLogger().info("applyTexturesToSkull: couldn't build ResolvableProfile for type=" + fieldTypeName);
                    return false;
                }

                profileField.set(meta, resolvable);
                if (dbg) plugin.getLogger().info("applyTexturesToSkull: injected ResolvableProfile into " + profileField.getName());
                return true;
            }

            if (dbg) plugin.getLogger().info("applyTexturesToSkull: found field '" + profileField.getName()
                    + "' but unsupported type: " + fieldTypeName);
            return false;

        } catch (Throwable t) {
            if (dbg) {
                Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null)
                        ? ite.getCause()
                        : t;
                plugin.getLogger().info("applyTexturesToSkull: FAILED: "
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
            return false;
        }
    }

    private Field findSkullProfileField(Class<?> metaClass) {
        Class<?> c = metaClass;
        Field best = null;

        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                c = c.getSuperclass();
                continue;
            }

            for (Field f : fields) {
                String fname = f.getName();
                String ftype = f.getType().getName();
                String ftypeLower = ftype.toLowerCase(Locale.ROOT);

                boolean looksLikeProfileType =
                        ftype.endsWith("GameProfile")
                                || ftypeLower.contains("gameprofile")
                                || ftypeLower.contains("resolvableprofile");

                if (!looksLikeProfileType) continue;

                if ("profile".equalsIgnoreCase(fname)) return f;
                if (best == null) best = f;
            }

            c = c.getSuperclass();
        }

        return best;
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
