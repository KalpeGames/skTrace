// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.command;

import dev.sktrace.ProfilingIndicator;
import dev.sktrace.Sktrace;
import dev.sktrace.profiler.EventStats;
import dev.sktrace.profiler.Profiler;
import dev.sktrace.profiler.TriggerStats;
import dev.sktrace.report.ReportUploader;
import dev.sktrace.report.ReportWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SktraceCommand implements CommandExecutor, TabCompleter {

    // Palette matching the HTML report dashboard.
    private static final TextColor ACCENT = TextColor.color(0xff9b3d);  // sktrace orange
    private static final TextColor ACCENT_DIM = TextColor.color(0xd6740f);
    private static final TextColor TEXT = TextColor.color(0xe8eaed);
    private static final TextColor MUTED = TextColor.color(0xa4a9b3);
    private static final TextColor DIM = TextColor.color(0x6b7180);
    private static final TextColor CRIT = TextColor.color(0xff5252);
    private static final TextColor WARN = TextColor.color(0xffc66b);
    private static final TextColor OK = TextColor.color(0x7ee787);

    private static final List<String> SUBCOMMANDS =
            List.of("start", "stop", "status", "report", "clip", "rolling", "reset", "diag");
    private static final List<String> STATUS_HINTS = List.of("5", "10", "20", "50");
    private static final List<String> REPORT_HINTS = List.of("--include-files", "--no-upload", "--show-secrets");
    private static final List<String> ROLLING_HINTS = List.of("on", "off");

    private final Sktrace plugin;
    private final Profiler profiler;
    private final ProfilingIndicator indicator;

    public SktraceCommand(Sktrace plugin, Profiler profiler, ProfilingIndicator indicator) {
        this.plugin = plugin;
        this.profiler = profiler;
        this.indicator = indicator;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start"   -> doStart(sender);
            case "stop"    -> doStop(sender);
            case "status"  -> doStatus(sender, parseTopN(args, 5));
            case "report"  -> doReport(sender, hasFlag(args, "--include-files"), !hasFlag(args, "--no-upload"), false, !hasFlag(args, "--show-secrets"));
            case "clip"    -> doClip(sender, hasFlag(args, "--include-files"), !hasFlag(args, "--no-upload"), !hasFlag(args, "--show-secrets"));
            case "rolling" -> doRolling(sender, args.length >= 2 ? args[1] : null);
            case "reset"   -> doReset(sender);
            case "diag"    -> doDiag(sender);
            default        -> {
                sender.sendMessage(line(Component.text("Unknown subcommand: ", MUTED)
                        .append(Component.text(args[0], CRIT))));
                usage(sender);
            }
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("status")) {
            return filter(STATUS_HINTS, args[1]);
        }
        if (args[0].equalsIgnoreCase("report") && args.length >= 2 && args.length <= 3) {
            return filter(REPORT_HINTS, args[args.length - 1]);
        }
        if (args[0].equalsIgnoreCase("clip") && args.length >= 2 && args.length <= 3) {
            return filter(REPORT_HINTS, args[args.length - 1]);
        }
        if (args[0].equalsIgnoreCase("rolling") && args.length == 2) {
            return filter(ROLLING_HINTS, args[1]);
        }
        return List.of();
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase(flag)) return true;
        }
        return false;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(options.size());
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    // ============================================================
    // Subcommand handlers
    // ============================================================

    private void doStart(CommandSender s) {
        if (profiler.isRunning()) {
            if (profiler.isRolling()) {
                s.sendMessage(glyphLine("•", WARN,
                        Component.text("Rolling buffer is active.", MUTED)
                                .append(Component.text("  Use ", DIM))
                                .append(clickable("/sktrace clip", "Snapshot the last N seconds"))
                                .append(Component.text(" to capture, or ", DIM))
                                .append(clickable("/sktrace rolling off", "Turn rolling buffer off"))
                                .append(Component.text(" first for one-shot mode.", DIM))));
            } else {
                s.sendMessage(glyphLine("•", WARN, Component.text("Profiler is already running.", MUTED)));
            }
            return;
        }
        profiler.start();
        if (indicator != null) indicator.start();
        s.sendMessage(glyphLine("▶", OK,
                Component.text("Profiler started.", TEXT)
                        .append(Component.text("  Capturing tick & trigger timings.", DIM))));
        if (!profiler.triggerHooksAvailable()) {
            s.sendMessage(line(Component.text("  ⚠ ", WARN)
                    .append(Component.text("Per-trigger hooks unavailable on this Skript version.", WARN))
                    .append(Component.text("  Per-event timing still active.", DIM))));
            s.sendMessage(line(Component.text("    " + profiler.triggerHookFailureReason(), DIM)));
        }
    }

    private void doStop(CommandSender s) {
        if (!profiler.isRunning()) {
            s.sendMessage(glyphLine("•", WARN, Component.text("Profiler is not running.", MUTED)));
            return;
        }
        profiler.stop();
        if (indicator != null) indicator.stop();
        long elapsed = Math.max(0, profiler.stoppedAtMillis() - profiler.startedAtMillis());

        Component reportLink = Component.text("[click to write report]", ACCENT, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand("/sktrace report"))
                .hoverEvent(HoverEvent.showText(Component.text("Run /sktrace report")));

        s.sendMessage(glyphLine("■", OK,
                Component.text("Profiler stopped.", TEXT)
                        .append(Component.text("  Window: ", DIM))
                        .append(Component.text(String.format("%.2fs", elapsed / 1000.0), MUTED))));
        s.sendMessage(line(Component.text("  ", TEXT)
                .append(reportLink)
                .append(Component.text("   or   ", DIM))
                .append(clickable("/sktrace status", "Show summary"))));
    }

    private void doReset(CommandSender s) {
        profiler.reset();
        s.sendMessage(glyphLine("✓", OK, Component.text("Stats cleared.", TEXT)));
    }

    private void doDiag(CommandSender s) {
        s.sendMessage(glyphLine("⚙", ACCENT,
                Component.text("Running diagnostic.", TEXT)
                        .append(Component.text("  Output is on the server console.", DIM))));
        profiler.runDiagnostic();
    }

    private void doReport(CommandSender s, boolean includeSources, boolean upload, boolean clipMode, boolean maskSecrets) {
        // Always write the local file first — instant feedback, useful offline. The .json
        // sidecar holds the same data blob as the HTML, so it can be uploaded by hand to
        // reproduce the report when auto-upload is off or fails.
        final Path localPath;
        final String html;
        try {
            ReportWriter writer = new ReportWriter(plugin, profiler);
            String dataJson = writer.renderDataJson(includeSources, clipMode, maskSecrets);
            html = writer.renderShell(dataJson);
            localPath = writer.writeRendered(html, dataJson, includeSources, clipMode);
        } catch (Exception e) {
            s.sendMessage(glyphLine("✗", CRIT,
                    Component.text(clipMode ? "Clip failed: " : "Report failed: ", TEXT)
                            .append(Component.text(String.valueOf(e.getMessage()), CRIT))));
            plugin.getLogger().warning((clipMode ? "Clip" : "Report") + " failed: " + e);
            return;
        }

        // File name of the JSON sidecar (same stem as the .html) — what users drag onto the site.
        final String jsonName = localPath.getFileName().toString().replaceFirst("\\.html$", ".json");

        s.sendMessage(glyphLine("✓", OK,
                Component.text(clipMode ? "Clip written.  " : "Report written.  ", TEXT)
                        .append(Component.text(localPath.getFileName().toString(), ACCENT_DIM))));

        if (includeSources) {
            s.sendMessage(line(Component.text("  ⚠ ", WARN)
                    .append(Component.text("Script source is embedded.", WARN))
                    .append(Component.text(upload ? "  The uploaded link will expose it to anyone who has it." : "  Local file only. Keep it private.", DIM))));
            if (maskSecrets) {
                s.sendMessage(line(Component.text("    ", TEXT)
                        .append(Component.text("Option values & secrets are masked.", DIM))
                        .append(Component.text("  Use ", DIM))
                        .append(Component.text("--show-secrets", MUTED))
                        .append(Component.text(" to embed them in full.", DIM))));
            } else {
                s.sendMessage(line(Component.text("    ⚠ ", CRIT)
                        .append(Component.text("--show-secrets is set: tokens & passwords in options are embedded unmasked.", CRIT))));
            }
        }

        // Upload path. Honors --no-upload, and treats an empty config endpoint as opt-out.
        String endpoint = plugin.uploadEndpoint();
        if (!upload) {
            s.sendMessage(line(Component.text("  Upload skipped (--no-upload).", DIM)));
            manualUploadHint(s, jsonName);
            return;
        }
        if (endpoint == null || endpoint.isBlank()) {
            s.sendMessage(line(Component.text("  Upload skipped (no endpoint configured).", DIM)));
            manualUploadHint(s, jsonName);
            return;
        }

        s.sendMessage(line(Component.text("  ⇪ Uploading…", DIM)));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String url;
            try {
                url = new ReportUploader(endpoint).upload(html, includeSources);
            } catch (Exception ex) {
                // 413 = the report was too big for the endpoint. The JSON sidecar is much smaller
                // than the HTML (no embedded shell/CSS/JS), so a manual upload often gets through.
                boolean tooLarge = ex instanceof ReportUploader.UploadException ue && ue.status() == 413;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (tooLarge) {
                        s.sendMessage(line(Component.text("  ✗ Too large to auto-upload.", CRIT)
                                .append(Component.text("  The report exceeded the server's size limit.", DIM))));
                    } else {
                        s.sendMessage(line(Component.text("  ✗ Upload failed: ", CRIT)
                                .append(Component.text(String.valueOf(ex.getMessage()), CRIT))));
                    }
                    manualUploadHint(s, jsonName);
                });
                plugin.getLogger().warning((clipMode ? "Clip" : "Report") + " upload failed: " + ex);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Component link = Component.text(url, ACCENT, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
                        .hoverEvent(HoverEvent.showText(Component.text("Open report")));
                s.sendMessage(line(Component.text("  ", TEXT).append(link)));
                s.sendMessage(line(Component.text("  Public link; expires in 24h.", DIM)));
            });
        });
    }

    /**
     * Points the user at the manual-upload fallback: the {@code .json} sidecar in
     * plugins/Sktrace/reports/ can be dropped onto the site to get a shareable report. Shown
     * whenever an auto-uploaded link wasn't produced (--no-upload, no endpoint, or a failure
     * such as HTTP 413).
     */
    private void manualUploadHint(CommandSender s, String jsonFileName) {
        String site = siteBaseUrl();
        s.sendMessage(line(Component.text("  ↪ ", ACCENT)
                .append(Component.text("Share it yourself: upload ", DIM))
                .append(Component.text(jsonFileName, MUTED))
                .append(Component.text(" (in plugins/Sktrace/reports/) at ", DIM))
                .append(Component.text(site, ACCENT, TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(site))
                        .hoverEvent(HoverEvent.showText(Component.text("Open " + site + " to upload your report", MUTED))))));
    }

    /** Site origin derived from the configured upload endpoint (scheme://host), for the manual
     *  upload hint. Falls back to the public host. */
    private String siteBaseUrl() {
        try {
            String ep = plugin.uploadEndpoint();
            if (ep != null && !ep.isBlank()) {
                java.net.URI u = java.net.URI.create(ep);
                if (u.getScheme() != null && u.getAuthority() != null) {
                    return u.getScheme() + "://" + u.getAuthority();
                }
            }
        } catch (Exception ignored) { }
        return "https://sktrace.kal.pe";
    }

    private void doClip(CommandSender s, boolean includeSources, boolean upload, boolean maskSecrets) {
        if (!profiler.isRolling()) {
            s.sendMessage(glyphLine("•", WARN, Component.text("Rolling buffer is off. Nothing to clip.", MUTED)));
            s.sendMessage(line(Component.text("  Enable with ", DIM)
                    .append(clickable("/sktrace rolling on", "Turn rolling buffer on"))
                    .append(Component.text(" (persists in config). The buffer needs a few seconds of data before clips are meaningful.", DIM))));
            return;
        }
        long windowMs = profiler.effectiveWindowMs();
        if (windowMs < 1000) {
            s.sendMessage(glyphLine("•", WARN, Component.text("Rolling buffer has < 1s of data so far. Try again in a moment.", MUTED)));
            return;
        }
        s.sendMessage(glyphLine("✂", ACCENT,
                Component.text("Clipping last ", TEXT)
                        .append(Component.text(String.format("%.1fs", windowMs / 1000.0), MUTED))
                        .append(Component.text(" of activity…", DIM))));
        doReport(s, includeSources, upload, true, maskSecrets);
    }

    private void doRolling(CommandSender s, String arg) {
        if (arg == null) {
            // Status mode
            boolean active = profiler.isRolling();
            int configWindow = plugin.getConfig().getInt("rolling.windowSeconds", 60);
            boolean configEnabled = plugin.getConfig().getBoolean("rolling.enabled", false);
            s.sendMessage(glyphLine("●", active ? OK : DIM,
                    Component.text("Rolling buffer: ", TEXT)
                            .append(Component.text(active ? "active" : "off", active ? OK : MUTED))
                            .append(Component.text("  ·  window ", DIM))
                            .append(Component.text(configWindow + "s", MUTED))
                            .append(Component.text("  ·  default on startup: ", DIM))
                            .append(Component.text(configEnabled ? "yes" : "no", MUTED))));
            if (active) {
                long windowMs = profiler.effectiveWindowMs();
                s.sendMessage(line(Component.text("  Buffered: ", DIM)
                        .append(Component.text(String.format("%.1fs", windowMs / 1000.0), MUTED))
                        .append(Component.text("  ·  ", DIM))
                        .append(clickable("/sktrace clip", "Snapshot the rolling buffer"))));
            }
            return;
        }
        String mode = arg.toLowerCase(Locale.ROOT);
        if (mode.equals("on")) {
            if (profiler.isRolling()) {
                s.sendMessage(glyphLine("•", DIM, Component.text("Rolling buffer is already active.", MUTED)));
                return;
            }
            if (profiler.isRunning()) {
                s.sendMessage(glyphLine("✗", CRIT,
                        Component.text("One-shot profiler is running. Stop it first with ", TEXT)
                                .append(clickable("/sktrace stop", "Stop the one-shot profiler"))));
                return;
            }
            int windowSec = Math.max(5, plugin.getConfig().getInt("rolling.windowSeconds", 60));
            profiler.startRolling(windowSec);
            plugin.getConfig().set("rolling.enabled", true);
            plugin.saveConfig();
            s.sendMessage(glyphLine("▶", OK,
                    Component.text("Rolling buffer enabled.", TEXT)
                            .append(Component.text("  Window ", DIM))
                            .append(Component.text(windowSec + "s", MUTED))
                            .append(Component.text(".  Survives restarts.", DIM))));
            return;
        }
        if (mode.equals("off")) {
            if (!profiler.isRolling()) {
                s.sendMessage(glyphLine("•", DIM, Component.text("Rolling buffer is already off.", MUTED)));
                // Still flip the persistent flag in case it was set to start-on-boot.
                plugin.getConfig().set("rolling.enabled", false);
                plugin.saveConfig();
                return;
            }
            profiler.stop();
            plugin.getConfig().set("rolling.enabled", false);
            plugin.saveConfig();
            s.sendMessage(glyphLine("■", OK,
                    Component.text("Rolling buffer disabled.", TEXT)
                            .append(Component.text("  Won't auto-start on restart.", DIM))));
            return;
        }
        s.sendMessage(line(Component.text("Unknown argument: ", MUTED)
                .append(Component.text(arg, CRIT))
                .append(Component.text(".  Use ", DIM))
                .append(Component.text("on", ACCENT))
                .append(Component.text(", ", DIM))
                .append(Component.text("off", ACCENT))
                .append(Component.text(", or no argument for status.", DIM))));
    }

    // ============================================================
    // Status — the meaty one
    // ============================================================

    private void doStatus(CommandSender s, int topN) {
        long elapsed = profiler.effectiveWindowMs();

        boolean running = profiler.isRunning();
        boolean rolling = profiler.isRolling();
        String stateWord = rolling ? "rolling" : running ? "running" : (elapsed > 0 ? "stopped" : "idle");
        TextColor stateColor = (running || rolling) ? OK : (elapsed > 0 ? MUTED : DIM);

        // Header bar
        s.sendMessage(Component.empty());
        s.sendMessage(barTitle("skTrace", stateWord, stateColor,
                String.format("%.2fs window", elapsed / 1000.0)));

        // ---- Tick aggregates ----
        long[] tickSkript = profiler.tickSamplesCopy();
        long[] tickDur = profiler.tickDurationsCopy();
        long sumSk = 0, maxSk = 0;
        int over = 0;
        for (long v : tickSkript) {
            sumSk += v;
            if (v > maxSk) maxSk = v;
            if (v > 50_000_000L) over++;
        }
        double avgSkMs = tickSkript.length > 0 ? (sumSk / (double) tickSkript.length) / 1_000_000.0 : 0;
        double maxSkMs = maxSk / 1_000_000.0;

        long sumDur = 0, maxDur = 0;
        int counted = 0;
        for (long d : tickDur) {
            if (d <= 0) continue;
            sumDur += d;
            if (d > maxDur) maxDur = d;
            counted++;
        }
        double avgMsptMs = counted > 0 ? (sumDur / (double) counted) / 1_000_000.0 : 0;
        double avgTps = counted > 0 ? Math.min(20.0, 1_000_000_000.0 / (sumDur / (double) counted)) : 0;

        int totalTriggers = profiler.triggerStats().size();
        int activeTriggers = (int) profiler.triggerStats().values().stream().filter(t -> t.calls() > 0).count();
        int silent = totalTriggers - activeTriggers;

        s.sendMessage(stat("Triggers",
                num(activeTriggers).append(Component.text(" active", DIM))
                        .append(Component.text("  /  ", DIM))
                        .append(num(totalTriggers))
                        .append(Component.text(" hooked", DIM))
                        .append(silent > 0
                                ? Component.text("   ·  ", DIM).append(num(silent)).append(Component.text(" silent", DIM))
                                : Component.empty())));

        TextColor overColor = over == 0 ? OK : (over < tickSkript.length / 50 ? WARN : CRIT);
        s.sendMessage(stat("Skript",
                Component.text("avg ", DIM).append(ms(avgSkMs))
                        .append(Component.text("  ·  ", DIM))
                        .append(Component.text("worst ", DIM)).append(ms(maxSkMs))
                        .append(Component.text("  ·  ", DIM))
                        .append(Component.text(over + (over == 1 ? " tick" : " ticks") + " over 50ms",
                                over == 0 ? OK : overColor))));

        if (counted > 0) {
            TextColor tpsColor = avgTps >= 19.5 ? OK : avgTps >= 17.0 ? WARN : CRIT;
            TextColor msptColor = avgMsptMs <= 50 ? OK : avgMsptMs <= 60 ? WARN : CRIT;
            double share = avgMsptMs > 0 ? (avgSkMs / avgMsptMs) * 100.0 : 0;
            s.sendMessage(stat("Server",
                    Component.text("MSPT ", DIM).append(Component.text(String.format("%.2f", avgMsptMs), msptColor))
                            .append(Component.text("ms", DIM))
                            .append(Component.text("  ·  ", DIM))
                            .append(Component.text("TPS ", DIM)).append(Component.text(String.format("%.2f", avgTps), tpsColor))
                            .append(Component.text("  ·  ", DIM))
                            .append(Component.text("Skript share ", DIM)).append(Component.text(String.format("%.1f%%", share), MUTED))));
        }

        // ---- Hot triggers ----
        s.sendMessage(Component.empty());
        s.sendMessage(sectionHead("Top " + topN + " triggers", "by total time"));
        List<TriggerStats> hot = profiler.triggerStats().values().stream()
                .filter(t -> t.calls() > 0)
                .sorted(Comparator.comparingLong(TriggerStats::totalNanos).reversed())
                .limit(topN)
                .toList();
        if (hot.isEmpty()) {
            s.sendMessage(line(Component.text("  (no trigger data yet)", DIM, TextDecoration.ITALIC)));
        } else {
            int rank = 1;
            for (TriggerStats t : hot) {
                s.sendMessage(triggerRow(rank++, t));
            }
        }

        // ---- Hot events ----
        s.sendMessage(Component.empty());
        s.sendMessage(sectionHead("Top " + topN + " events", "by total time"));
        List<EventStats> events = profiler.eventStats().values().stream()
                .sorted(Comparator.comparingLong(EventStats::totalNanos).reversed())
                .limit(topN)
                .toList();
        if (events.isEmpty()) {
            s.sendMessage(line(Component.text("  (no event data yet)", DIM, TextDecoration.ITALIC)));
        } else {
            int rank = 1;
            for (EventStats e : events) {
                s.sendMessage(eventRow(rank++, e));
            }
        }

        // ---- Footer tip ----
        s.sendMessage(Component.empty());
        s.sendMessage(line(Component.text("  Tip ", DIM)
                .append(clickable("/sktrace report", "Run /sktrace report"))
                .append(Component.text(" writes a full HTML dashboard.", DIM))));
    }

    // ============================================================
    // Help
    // ============================================================

    private void usage(CommandSender s) {
        s.sendMessage(Component.empty());
        s.sendMessage(brandRule());
        s.sendMessage(line(Component.text("  Skript-aware profiler", MUTED)));
        s.sendMessage(Component.empty());
        helpRow(s, "start",          "Begin profiling");
        helpRow(s, "stop",           "Stop profiling");
        helpRow(s, "status [N]",     "Summary + hot triggers");
        helpRow(s, "report",         "Write & upload a report",
                "Writes an HTML dashboard, then uploads it.\n"
                + "--include-files  embed script source\n"
                + "--no-upload  keep the file local\n"
                + "--show-secrets  unmask option values");
        helpRow(s, "clip",           "Snapshot the last N seconds");
        helpRow(s, "rolling on|off", "Toggle the rolling buffer");
        helpRow(s, "reset",          "Clear collected stats");
        helpRow(s, "diag",           "Dump internals to console");
        s.sendMessage(Component.empty());
        s.sendMessage(line(Component.text("  Aliases ", DIM)
                .append(Component.text("/skt", ACCENT))
                .append(Component.text("   ·   Permission ", DIM))
                .append(Component.text("sktrace.use", MUTED))));
    }

    private void helpRow(CommandSender s, String form, String desc) {
        helpRow(s, form, desc, null);
    }

    private void helpRow(CommandSender s, String form, String desc, String hover) {
        String runForm = "/sktrace " + form.split(" ")[0];
        Component hoverText = Component.text(
                hover != null ? hover : "Click to insert " + runForm, MUTED);
        Component cmd = Component.text("/sktrace " + form, ACCENT)
                .clickEvent(ClickEvent.suggestCommand(runForm))
                .hoverEvent(HoverEvent.showText(hoverText));
        // Pad the command column to ~22 chars so descriptions line up.
        int pad = Math.max(1, 22 - ("/sktrace " + form).length());
        s.sendMessage(line(Component.text("  ", TEXT)
                .append(cmd)
                .append(Component.text(" ".repeat(pad), DIM))
                .append(Component.text(desc, MUTED))));
    }

    // ============================================================
    // Component helpers
    // ============================================================

    private static Component line(Component body) {
        // .decoration(...) ensures Adventure doesn't apply italic by default for some servers.
        return body.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * A glyph-led status line. The glyph is bold, but the body is appended as a
     * SIBLING under a neutral root so bold doesn't inherit into it — Adventure
     * propagates an unset decoration from parent to child, so rooting a line at a
     * bold component (the old pattern) silently bolded the whole message.
     */
    private static Component glyphLine(String glyph, TextColor glyphColor, Component body) {
        return line(Component.empty()
                .append(Component.text(glyph + " ", glyphColor, TextDecoration.BOLD))
                .append(body));
    }

    private static Component barTitle(String name, String state, TextColor stateColor, String tail) {
        return line(Component.text("▌ ", ACCENT)
                .append(Component.text(name, TEXT, TextDecoration.BOLD))
                .append(Component.text("   ", DIM))
                .append(Component.text(state, stateColor))
                .append(Component.text("  ·  ", DIM))
                .append(Component.text(tail, MUTED)));
    }

    /** Centered brand header rule:  ──────  ▌ skTrace ▐  ────── */
    private static Component brandRule() {
        Component dashes = Component.text("──────", DIM);
        return line(dashes
                .append(Component.text("  ▌ ", ACCENT))
                .append(Component.text("skTrace", TEXT, TextDecoration.BOLD))
                .append(Component.text(" ▐  ", ACCENT))
                .append(dashes));
    }

    private static Component sectionHead(String title, String sub) {
        return line(Component.text("▌ ", ACCENT)
                .append(Component.text(title, TEXT, TextDecoration.BOLD))
                .append(Component.text("   " + sub, DIM)));
    }

    private static Component stat(String label, Component value) {
        // Pad label to 9 chars for alignment.
        String paddedLabel = (label + "         ").substring(0, 9);
        return line(Component.text("  ", TEXT)
                .append(Component.text(paddedLabel, DIM))
                .append(Component.text("  ", TEXT))
                .append(value));
    }

    private static Component num(long n) {
        return Component.text(String.format("%,d", n), MUTED);
    }

    private static Component ms(double v) {
        TextColor c = v >= 50 ? CRIT : v >= 25 ? WARN : MUTED;
        return Component.text(String.format("%.2f", v), c).append(Component.text("ms", DIM));
    }

    // Compact rows: one short line each (name · total · calls · avg) so they never
    // wrap in chat. Full detail — location, worst-case time — lives in the hover.
    private static Component triggerRow(int rank, TriggerStats t) {
        TextColor totalColor = rank <= 3 ? ACCENT : MUTED;
        double totalMs = t.totalNanos() / 1_000_000.0;
        double maxMs = t.maxNanos() / 1_000_000.0;
        TextColor maxColor = maxMs >= 5 ? CRIT : maxMs >= 1 ? WARN : MUTED;
        String loc = t.scriptName() + (t.line() >= 0 ? ":" + t.line() : "");
        Component hover = line(Component.empty()
                .append(Component.text(t.triggerName(), TEXT, TextDecoration.BOLD))
                .append(Component.text("\n" + loc, ACCENT_DIM))
                .append(Component.text("\n\ntotal   ", DIM)).append(Component.text(fmtMs(totalMs), TEXT))
                .append(Component.text("\ncalls   ", DIM)).append(Component.text(String.format("%,d", t.calls()), TEXT))
                .append(Component.text("\navg     ", DIM)).append(Component.text(formatTimeNs(t.avgNanos()), TEXT))
                .append(Component.text("\nworst   ", DIM)).append(Component.text(fmtMs(maxMs), maxColor)));
        return line(Component.text(String.format(" %2d ", rank), DIM)
                .append(Component.text(truncate(t.triggerName(), 18), TEXT))
                .append(sep())
                .append(Component.text(fmtMs(totalMs), totalColor))
                .append(sep())
                .append(Component.text(abbrevCount(t.calls()) + "×", DIM)))
                .hoverEvent(HoverEvent.showText(hover));
    }

    private static Component eventRow(int rank, EventStats e) {
        TextColor totalColor = rank <= 3 ? ACCENT : MUTED;
        double totalMs = e.totalNanos() / 1_000_000.0;
        Component hover = line(Component.empty()
                .append(Component.text(e.shortName(), TEXT, TextDecoration.BOLD))
                .append(Component.text("\n\ntotal   ", DIM)).append(Component.text(fmtMs(totalMs), TEXT))
                .append(Component.text("\ncalls   ", DIM)).append(Component.text(String.format("%,d", e.calls()), TEXT))
                .append(Component.text("\navg     ", DIM)).append(Component.text(formatTimeNs(e.avgNanos()), TEXT)));
        return line(Component.text(String.format(" %2d ", rank), DIM)
                .append(Component.text(truncate(e.shortName(), 20), TEXT))
                .append(sep())
                .append(Component.text(fmtMs(totalMs), totalColor))
                .append(sep())
                .append(Component.text(abbrevCount(e.calls()) + "×", DIM)))
                .hoverEvent(HoverEvent.showText(hover));
    }

    private static Component sep() {
        return Component.text("  ·  ", DIM);
    }

    private static Component clickable(String cmd, String hover) {
        return Component.text(cmd, ACCENT, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text(hover, MUTED)));
    }

    private static String formatTimeNs(long ns) {
        if (ns < 1000) return ns + "ns";
        if (ns < 1_000_000) return (ns / 1000) + "μs";
        return String.format("%.2fms", ns / 1_000_000.0);
    }

    /** Compact, width-bounded duration: 1.23ms / 45.6ms / 124ms / 1.24s. */
    private static String fmtMs(double ms) {
        if (ms >= 1000.0) return String.format("%.2fs", ms / 1000.0);
        if (ms >= 100.0)  return String.format("%.0fms", ms);
        if (ms >= 10.0)   return String.format("%.1fms", ms);
        return String.format("%.2fms", ms);
    }

    /** Compact call counts so a row stays narrow: 942 / 1.2k / 3.4M. */
    private static String abbrevCount(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%.1fk", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(1, max - 1)) + "…";
    }

    private int parseTopN(String[] args, int fallback) {
        if (args.length < 2) return fallback;
        try { return Math.max(1, Math.min(50, Integer.parseInt(args[1]))); }
        catch (NumberFormatException nfe) { return fallback; }
    }
}
