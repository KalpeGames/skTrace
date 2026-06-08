// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notifies operators (and the console) when a newer skTrace release is published on Modrinth.
 *
 * <p>Purely a notification — skTrace never downloads or installs anything. On enable it fetches the
 * project's published versions from the Modrinth API on an async thread, picks the newest
 * {@code release}, and if it's newer than the running version remembers it. Operators (and anyone
 * with {@code sktrace.use}) then get a short clickable message on join, the way SkBee does. The
 * check repeats every few hours so a long-running server still notices a release. Off via
 * {@code update-checker: false} in config.yml.
 */
public final class UpdateChecker implements Listener {

    private static final String VERSIONS_API = "https://api.modrinth.com/v2/project/sktrace/version";
    // Mirrors the link SkBee shows, e.g. https://modrinth.com/plugin/sktrace/version/0.1.4
    private static final String DOWNLOAD_BASE = "https://modrinth.com/plugin/sktrace/version/";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final long FIRST_CHECK_TICKS = 100L;                 // ~5s after enable
    private static final long RECHECK_TICKS = 20L * 60 * 60 * 3;        // every 3 hours
    private static final long JOIN_NOTICE_DELAY_TICKS = 40L;            // ~2s, so it lands after join spam

    // Modrinth asks API consumers to send a descriptive, contactable User-Agent.
    private static final Pattern VERSION_NUMBER = Pattern.compile("\"version_number\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VERSION_TYPE = Pattern.compile("\"version_type\"\\s*:\\s*\"([^\"]*)\"");

    private static final TextColor ACCENT = TextColor.color(0xff9b3d);  // skTrace orange
    private static final TextColor TEXT = TextColor.color(0xe8eaed);
    private static final TextColor DIM = TextColor.color(0x6b7180);
    private static final TextColor OK = TextColor.color(0x7ee787);
    private static final TextColor LINK = TextColor.color(0x6cb6ff);

    private final Sktrace plugin;
    private final String currentVersion;
    private final String userAgent;
    private final HttpClient client;

    // Set on the async check thread, read on the main thread (join). Non-null only when a newer
    // release than currentVersion is available.
    private volatile String latestVersion;
    // Last version we logged to console, so a repeating check doesn't re-log the same notice.
    private volatile String loggedVersion;

    public UpdateChecker(Sktrace plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
        this.userAgent = "KalpeGames/skTrace/" + currentVersion + " (github.com/KalpeGames/skTrace)";
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Schedule the checks and register the join listener, unless disabled in config. */
    public void start() {
        if (!plugin.getConfig().getBoolean("update-checker", true)) {
            plugin.getLogger().fine("[Sktrace] Update checker disabled via config.");
            return;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::check, FIRST_CHECK_TICKS, RECHECK_TICKS);
    }

    // Runs on an async scheduler thread — never touches the main thread or Bukkit state.
    private void check() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(VERSIONS_API))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                plugin.getLogger().fine("[Sktrace] Update check: HTTP " + resp.statusCode());
                return;
            }
            String latest = latestRelease(resp.body());
            if (latest != null && isNewer(latest, currentVersion)) {
                latestVersion = latest;
                if (!latest.equals(loggedVersion)) {
                    loggedVersion = latest;
                    plugin.getLogger().info("Update available: skTrace " + latest + " (you're on "
                            + currentVersion + "). Download: " + DOWNLOAD_BASE + latest);
                }
            } else {
                latestVersion = null;   // up to date, or running a dev build ahead of the release
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Network hiccups, DNS, offline servers — never let an update check be noisy or fatal.
            plugin.getLogger().fine("[Sktrace] Update check failed: " + e.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        String latest = latestVersion;
        if (latest == null) return;
        Player p = e.getPlayer();
        if (!p.isOp() && !p.hasPermission("sktrace.use")) return;
        // Delay so the notice appears after the join/MOTD spam, and re-confirm the player is still
        // online and the version is still current when it actually fires.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && latest.equals(latestVersion)) sendNotice(p, latest);
        }, JOIN_NOTICE_DELAY_TICKS);
    }

    private void sendNotice(Player p, String latest) {
        String url = DOWNLOAD_BASE + latest;
        p.sendMessage(prefix()
                .append(Component.text("Update available: ", TEXT))
                .append(Component.text(latest, OK).decorate(TextDecoration.BOLD))
                .append(Component.text(" (you have " + currentVersion + ")", DIM)));
        p.sendMessage(prefix()
                .append(Component.text("Download: ", TEXT))
                .append(Component.text(url, LINK).decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Open on Modrinth", DIM)))));
    }

    private static Component prefix() {
        return Component.text("[", DIM)
                .append(Component.text("skTrace", ACCENT).decorate(TextDecoration.BOLD))
                .append(Component.text("] ", DIM));
    }

    /**
     * Newest {@code release}-type {@code version_number} in a Modrinth versions response, or null.
     * version_number and version_type occur exactly once per version object, so pairing them by
     * document order is reliable; we then keep only releases and take the highest by semver.
     */
    static String latestRelease(String json) {
        List<String> numbers = allMatches(VERSION_NUMBER, json);
        List<String> types = allMatches(VERSION_TYPE, json);
        String best = null;
        for (int i = 0; i < numbers.size(); i++) {
            String type = i < types.size() ? types.get(i) : "release";
            if (!"release".equalsIgnoreCase(type)) continue;
            String num = numbers.get(i);
            if (best == null || isNewer(num, best)) best = num;
        }
        return best;
    }

    private static List<String> allMatches(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** Semver-ish comparison: true if {@code candidate} is a newer version than {@code current}. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parseVersion(candidate);
        int[] b = parseVersion(current);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;   // equal numeric core — treat a pre-release of the same core as not newer
    }

    /** Numeric components of a version, ignoring a leading 'v' and any -pre/+build suffix. */
    private static int[] parseVersion(String v) {
        if (v == null) return new int[0];
        String s = v.trim();
        if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) s = s.substring(1);
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '+' || c == '_' || c == ' ') { cut = i; break; }
        }
        String[] parts = s.substring(0, cut).split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException nfe) {
                out[i] = 0;
            }
        }
        return out;
    }
}
