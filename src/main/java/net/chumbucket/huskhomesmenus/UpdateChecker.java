package net.chumbucket.huskhomesmenus;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class UpdateChecker {

    public enum Status { OUTDATED, UP_TO_DATE, UNKNOWN }

    public record Result(Status status, String currentVersion, String latestVersion) { }

    private final JavaPlugin plugin;
    private final int resourceId;

    // Cache so we can notify on join without hammering Spiget
    private volatile String cachedLatest = null;
    private volatile long lastCheckMs = 0L;

    // 30 minutes cache (tweak if you want)
    private static final long CACHE_MS = 30L * 60L * 1000L;

    public UpdateChecker(JavaPlugin plugin, int resourceId) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resourceId = resourceId;
    }

    public CompletableFuture<Result> checkNowAsync() {
        return CompletableFuture.supplyAsync(() -> {
            final String current = safe(getCurrentVersionBestEffort());

            try {
                String latest = fetchLatestVersionFromSpigot();
                cachedLatest = latest;
                lastCheckMs = System.currentTimeMillis();

                if (latest == null || latest.isBlank() || current.isBlank()) {
                    return new Result(Status.UNKNOWN, current, latest);
                }

                // Simple compare: if not equal -> consider outdated.
                if (!current.equalsIgnoreCase(latest.trim())) {
                    return new Result(Status.OUTDATED, current, latest.trim());
                }
                return new Result(Status.UP_TO_DATE, current, latest.trim());

            } catch (Throwable t) {
                return new Result(Status.UNKNOWN, current, cachedLatest);
            }
        });
    }

    public CompletableFuture<Result> checkIfNeededAsync() {
        long now = System.currentTimeMillis();
        if (cachedLatest != null && (now - lastCheckMs) < CACHE_MS) {
            String current = safe(getCurrentVersionBestEffort());
            Status st = current.equalsIgnoreCase(safe(cachedLatest)) ? Status.UP_TO_DATE : Status.OUTDATED;
            return CompletableFuture.completedFuture(new Result(st, current, cachedLatest));
        }
        return checkNowAsync();
    }

    public void notifyPlayerIfOutdated(Player p, String permission) {
        if (p == null) return;
        if (permission != null && !permission.isBlank() && !p.hasPermission(permission)) return;

        String current = safe(getCurrentVersionBestEffort());
        String latest = safe(cachedLatest);

        if (latest.isBlank() || current.isBlank()) return;
        if (current.equalsIgnoreCase(latest)) return;

        p.sendMessage("§6[HuskHomesMenus] §eUpdate available: §f" + current + " §e→ §a" + latest);
        p.sendMessage("§7Spigot: §fhttps://www.spigotmc.org/resources/" + resourceId + "/");
    }

    private String fetchLatestVersionFromSpigot() throws Exception {
        // Spiget API (used for Spigot resources):
        // https://api.spiget.org/v2/resources/<id>/versions/latest
        // returns JSON that includes "name": "<version>"
        URI uri = URI.create("https://api.spiget.org/v2/resources/" + resourceId + "/versions/latest");
        URL url = uri.toURL();

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(4000);
        con.setReadTimeout(4000);
        con.setRequestProperty("User-Agent", "HuskHomesMenus Update Checker");

        int code = con.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            // Very small JSON parse (no libs):
            // example: {"id":123,"name":"1.1.3", ...}
            String json = sb.toString();
            String key = "\"name\":\"";
            int i = json.indexOf(key);
            if (i == -1) return null;
            int start = i + key.length();
            int end = json.indexOf("\"", start);
            if (end == -1) return null;
            return json.substring(start, end);
        }
    }

    private String getCurrentVersionBestEffort() {
        // Prefer Paper's PluginMeta (getPluginMeta().getVersion()) when present.
        try {
            Object meta = plugin.getClass().getMethod("getPluginMeta").invoke(plugin);
            if (meta != null) {
                Object v = meta.getClass().getMethod("getVersion").invoke(meta);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) { }

        // Fallback: call getDescription() via reflection to avoid deprecation warning
        try {
            Object desc = plugin.getClass().getMethod("getDescription").invoke(plugin);
            if (desc != null) {
                Object v = desc.getClass().getMethod("getVersion").invoke(desc);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) { }

        return "";
    }

    // ✅ Added (fixes: "safe(String) is undefined for type UpdateChecker")
    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
