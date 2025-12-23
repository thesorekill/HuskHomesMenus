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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class UpdateChecker {

    public enum Status { OUTDATED, UP_TO_DATE, UNKNOWN }

    public record Result(Status status, String currentVersion, String latestVersion) { }

    private final JavaPlugin plugin;
    private final HHMConfig config; // ✅ NEW
    private final int resourceId;

    private volatile String cachedLatest = null;
    private volatile long lastCheckMs = 0L;

    private static final long CACHE_MS = 30L * 60L * 1000L;

    private static final Pattern NAME_FIELD =
            Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();

    // ✅ Updated constructor
    public UpdateChecker(JavaPlugin plugin, HHMConfig config, int resourceId) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.resourceId = resourceId;
    }

    public CompletableFuture<Result> checkNowAsync() {
        return CompletableFuture.supplyAsync(() -> {
            final String current = safe(getCurrentVersionBestEffort());
            try {
                final String latest = safe(fetchLatestVersionFromSpigot());

                if (!latest.isBlank()) {
                    cachedLatest = latest;
                    lastCheckMs = System.currentTimeMillis();
                }

                if (latest.isBlank() || current.isBlank()) {
                    return new Result(Status.UNKNOWN, current, latest);
                }

                // (Optional but recommended) Only OUTDATED if current < latest
                int cmp = compareVersions(current, latest);
                if (cmp < 0) return new Result(Status.OUTDATED, current, latest);
                return new Result(Status.UP_TO_DATE, current, latest);

            } catch (Throwable t) {
                plugin.getLogger().warning("[UpdateChecker] Failed to check updates: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                return new Result(Status.UNKNOWN, current, safe(cachedLatest));
            }
        });
    }

    public CompletableFuture<Result> checkIfNeededAsync() {
        long now = System.currentTimeMillis();

        if (cachedLatest != null && (now - lastCheckMs) < CACHE_MS) {
            String current = safe(getCurrentVersionBestEffort());
            String latest = safe(cachedLatest);

            if (current.isBlank() || latest.isBlank()) {
                return CompletableFuture.completedFuture(new Result(Status.UNKNOWN, current, latest));
            }

            Status st = (compareVersions(current, latest) < 0) ? Status.OUTDATED : Status.UP_TO_DATE;
            return CompletableFuture.completedFuture(new Result(st, current, latest));
        }

        return checkNowAsync();
    }

    // ✅ Uses config.prefix()
    public void notifyPlayerIfOutdated(Player p, String permission) {
        if (p == null) return;
        if (permission != null && !permission.isBlank() && !p.hasPermission(permission)) return;

        String current = safe(getCurrentVersionBestEffort());
        String latest = safe(cachedLatest);

        if (latest.isBlank() || current.isBlank()) return;

        // Only notify if current < latest
        if (compareVersions(current, latest) >= 0) return;

        p.sendMessage(AMP.deserialize(config.prefix() + "&eUpdate available: &f" + current + " &e→ &a" + latest));
        p.sendMessage(AMP.deserialize(config.prefix() + "&7Spigot: &fhttps://www.spigotmc.org/resources/" + resourceId + "/"));
    }

    private String fetchLatestVersionFromSpigot() throws Exception {
        URI uri = URI.create("https://api.spiget.org/v2/resources/" + resourceId + "/versions/latest");
        URL url = uri.toURL();

        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(6000);
        con.setReadTimeout(6000);
        con.setRequestProperty("User-Agent", "HuskHomesMenus Update Checker");

        int code = con.getResponseCode();
        if (code != 200) throw new IllegalStateException("HTTP " + code);

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);

            String json = sb.toString();

            Matcher m = NAME_FIELD.matcher(json);
            if (!m.find()) {
                plugin.getLogger().warning("[UpdateChecker] Could not parse latest version from Spiget response: "
                        + (json.length() > 250 ? json.substring(0, 250) + "..." : json));
                return "";
            }
            return safe(m.group(1));
        }
    }

    private String getCurrentVersionBestEffort() {
        try {
            Object meta = plugin.getClass().getMethod("getPluginMeta").invoke(plugin);
            if (meta != null) {
                Object v = meta.getClass().getMethod("getVersion").invoke(meta);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) { }

        try {
            Object desc = plugin.getClass().getMethod("getDescription").invoke(plugin);
            if (desc != null) {
                Object v = desc.getClass().getMethod("getVersion").invoke(desc);
                if (v instanceof String s && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) { }

        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    // ✅ Simple numeric compare for versions like 1.2.3
    private static int compareVersions(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";

        String[] pa = a.trim().split("[^0-9]+");
        String[] pb = b.trim().split("[^0-9]+");

        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int ai = (i < pa.length && !pa[i].isEmpty()) ? Integer.parseInt(pa[i]) : 0;
            int bi = (i < pb.length && !pb[i].isEmpty()) ? Integer.parseInt(pb[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }
}