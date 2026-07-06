// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.command;

import dev.sktrace.ProfilingIndicator;
import dev.sktrace.SkTrace;
import dev.sktrace.parse.ParseProfiler;
import dev.sktrace.parse.ParseStats;
import dev.sktrace.profiler.EventStats;
import dev.sktrace.profiler.LoopWatcher;
import dev.sktrace.profiler.Profiler;
import dev.sktrace.profiler.TriggerStats;
import dev.sktrace.report.ReportUploader;
import dev.sktrace.report.ReportWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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

public final class SkTraceCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<gray>[<gold>skTrace<gray>] ";

    // Semantic palette. Gold is the brand and marks the things you act on (names, numbers,
    // commands), since this is a Skript tool. The rest carry meaning: green good, yellow watch,
    // red bad, white plain text, aqua clickable. No bold, no gray body text, no glyphs.
    private static final String BRAND = "<gold>";
    private static final String SAFE = "<green>";
    private static final String WORRY = "<yellow>";
    private static final String BAD = "<red>";
    private static final String INFO = "<white>";
    private static final String ACTION = "<aqua>";

    private static final List<String> SUBCOMMANDS =
            List.of("start", "stop", "status", "loops", "parse", "confirm", "report", "clip", "rolling", "reset", "diag");
    private static final List<String> STATUS_HINTS = List.of("5", "10", "20", "50");
    private static final List<String> REPORT_HINTS = List.of("--include-files", "--no-upload", "--show-secrets", "--variable-values");
    private static final List<String> ROLLING_HINTS = List.of("on", "off");

    private final SkTrace plugin;
    private final Profiler profiler;
    private final ProfilingIndicator indicator;
    private final ParseProfiler parseProfiler;

    // A pending /sktrace parse awaiting /sktrace confirm, per sender. Cleared on confirm or timeout.
    private final java.util.Map<String, Long> pendingParse =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private static final long PARSE_CONFIRM_WINDOW_MS = 30_000L;

    public SkTraceCommand(SkTrace plugin, Profiler profiler, ProfilingIndicator indicator,
                          ParseProfiler parseProfiler) {
        this.plugin = plugin;
        this.profiler = profiler;
        this.indicator = indicator;
        this.parseProfiler = parseProfiler;
    }

    // ============================================================
    // Messaging scaffolding
    // ============================================================

    private static void msg(CommandSender s, String body) {
        s.sendMessage(MM.deserialize(PREFIX + body));
    }

    private static void cont(CommandSender s, String body) {
        s.sendMessage(MM.deserialize(body));
    }

    private static void blank(CommandSender s) {
        s.sendMessage(Component.empty());
    }

    private static void error(CommandSender s, String raw) {
        msg(s, BAD + "Something went wrong. Check console for the full error.");
        cont(s, BAD + esc(raw));
    }

    /** Escapes user or data text so it can't inject MiniMessage tags. */
    private static String esc(String v) {
        return v == null ? "null" : v.replace("\\", "\\\\").replace("<", "\\<");
    }

    private static String cmdLink(String command, String hover) {
        return "<click:run_command:'" + command + "'><hover:show_text:'" + esc(hover) + "'>" + ACTION + command + "</aqua></hover></click>";
    }

    private static String suggestLink(String command, String hover) {
        return "<click:suggest_command:'" + command + "'><hover:show_text:'" + esc(hover) + "'>" + ACTION + command + "</aqua></hover></click>";
    }

    private static String urlLink(String url) {
        return "<click:open_url:'" + url + "'><hover:show_text:'Open report'>" + ACTION + url + "</aqua></hover></click>";
    }

    // ============================================================
    // Command routing
    // ============================================================

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
            case "loops"   -> doLoops(sender);
            case "parse"   -> doParse(sender);
            case "confirm" -> doConfirm(sender);
            case "report"  -> doReport(sender, hasFlag(args, "--include-files"), !hasFlag(args, "--no-upload"), false, !hasFlag(args, "--show-secrets"), hasFlag(args, "--variable-values"));
            case "clip"    -> doClip(sender, hasFlag(args, "--include-files"), !hasFlag(args, "--no-upload"), !hasFlag(args, "--show-secrets"), hasFlag(args, "--variable-values"));
            case "rolling" -> doRolling(sender, args.length >= 2 ? args[1] : null);
            case "reset"   -> doReset(sender);
            case "diag"    -> doDiag(sender);
            default        -> {
                msg(sender, BAD + "Unknown subcommand " + INFO + esc(args[0]) + BAD + ".");
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
        if (args[0].equalsIgnoreCase("report") && args.length >= 2 && args.length <= 5) {
            return filter(REPORT_HINTS, args[args.length - 1]);
        }
        if (args[0].equalsIgnoreCase("clip") && args.length >= 2 && args.length <= 5) {
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
                msg(s, WORRY + "The rolling buffer is active. " + INFO + "Use " + cmdLink("/sktrace clip", "Snapshot the last N seconds")
                        + " " + INFO + "to capture, or turn it off with " + cmdLink("/sktrace rolling off", "Turn rolling buffer off") + " " + INFO + "for one shot mode.");
            } else {
                msg(s, WORRY + "The profiler is already running.");
            }
            return;
        }
        profiler.start();
        if (indicator != null) indicator.start();
        msg(s, SAFE + "Profiler started. " + INFO + "Now capturing tick and trigger timings.");
        if (!profiler.triggerHooksAvailable()) {
            cont(s, WORRY + "Per trigger hooks are not available on this Skript version. " + INFO + "Per event timing still works.");
            cont(s, INFO + esc(profiler.triggerHookFailureReason()));
        }
    }

    private void doStop(CommandSender s) {
        if (!profiler.isRunning()) {
            msg(s, WORRY + "The profiler is not running.");
            return;
        }
        profiler.stop();
        if (indicator != null) indicator.stop();
        long elapsed = Math.max(0, profiler.stoppedAtMillis() - profiler.startedAtMillis());
        msg(s, SAFE + "Profiler stopped. " + INFO + "It ran for " + BRAND + String.format("%.2f", elapsed / 1000.0) + "s" + INFO + ".");
        cont(s, INFO + cmdLink("/sktrace report", "Run /sktrace report") + " " + INFO + "or " + cmdLink("/sktrace status", "Show summary"));
    }

    private void doReset(CommandSender s) {
        profiler.reset();
        msg(s, SAFE + "Stats cleared.");
    }

    private void doDiag(CommandSender s) {
        msg(s, ACTION + "Running diagnostic. " + INFO + "Output is on the server console.");
        profiler.runDiagnostic();
    }

    private void doReport(CommandSender s, boolean includeSources, boolean upload, boolean clipMode,
                          boolean maskSecrets, boolean includeVariableValues) {
        final Path localPath;
        final String html;
        try {
            ReportWriter writer = new ReportWriter(plugin, profiler);
            String dataJson = writer.renderDataJson(includeSources, clipMode, maskSecrets, includeVariableValues);
            html = writer.renderShell(dataJson);
            localPath = writer.writeRendered(html, dataJson, includeSources, clipMode);
        } catch (Exception e) {
            error(s, e.getMessage());
            plugin.getLogger().warning((clipMode ? "Clip" : "Report") + " failed: " + e);
            return;
        }

        String jsonName = localPath.getFileName().toString().replaceFirst("\\.html$", ".json");

        msg(s, INFO + (clipMode ? "Clip written. " : "Report written. ") + BRAND + esc(localPath.getFileName().toString()));

        if (includeSources) {
            cont(s, WORRY + "The script source is embedded in this report. " + INFO + (upload
                    ? "Anyone with the uploaded link will be able to read it."
                    : "This is a local file only, so keep it private."));
            if (maskSecrets) {
                cont(s, INFO + "Option values and secrets are masked. Add " + BRAND + "--show-secrets " + INFO + "to include them in full.");
            } else {
                cont(s, BAD + "--show-secrets is on, so tokens and passwords in your options are embedded unmasked.");
            }
        }

        String endpoint = plugin.uploadEndpoint();
        if (!upload) {
            cont(s, INFO + "Skipped the upload because " + BRAND + "--no-upload " + INFO + "was set.");
            manualUploadHint(s, jsonName);
            return;
        }
        if (endpoint == null || endpoint.isBlank()) {
            cont(s, INFO + "Skipped the upload because no endpoint is configured.");
            manualUploadHint(s, jsonName);
            return;
        }

        cont(s, INFO + "Uploading the report...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String url;
            try {
                url = new ReportUploader(endpoint).upload(html, includeSources);
            } catch (Exception ex) {
                boolean tooLarge = ex instanceof ReportUploader.UploadException ue && ue.status() == 413;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (tooLarge) {
                        cont(s, BAD + "The report was too large to upload. " + INFO + "It went over the endpoint's size limit.");
                    } else {
                        error(s, "Upload failed. " + ex.getMessage());
                    }
                    manualUploadHint(s, jsonName);
                });
                plugin.getLogger().warning((clipMode ? "Clip" : "Report") + " upload failed: " + ex);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                cont(s, INFO + urlLink(url));
                cont(s, INFO + "This link is public and expires in " + BRAND + "24 " + INFO + "hours.");
            });
        });
    }

    /** Points at the manual upload fallback: drop the .json sidecar onto the site by hand. */
    private void manualUploadHint(CommandSender s, String jsonFileName) {
        String site = siteBaseUrl();
        cont(s, INFO + "To share it yourself, upload " + BRAND + esc(jsonFileName) + INFO
                + " from plugins/skTrace/reports/ at " + urlLink(site) + INFO + ".");
    }

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

    private void doClip(CommandSender s, boolean includeSources, boolean upload, boolean maskSecrets,
                        boolean includeVariableValues) {
        if (!profiler.isRolling()) {
            msg(s, WORRY + "The rolling buffer is off, so there is nothing to clip.");
            cont(s, INFO + "Turn it on with " + cmdLink("/sktrace rolling on", "Turn rolling buffer on")
                    + " " + INFO + "and it will stay on across restarts. It needs a few seconds of data before a clip is useful.");
            return;
        }
        long windowMs = profiler.effectiveWindowMs();
        if (windowMs < 1000) {
            msg(s, WORRY + "The rolling buffer has less than a second of data so far. Try again in a moment.");
            return;
        }
        msg(s, INFO + "Clipping the last " + BRAND + String.format("%.1f", windowMs / 1000.0) + "s " + INFO + "of activity...");
        doReport(s, includeSources, upload, true, maskSecrets, includeVariableValues);
    }

    private void doRolling(CommandSender s, String arg) {
        if (arg == null) {
            boolean active = profiler.isRolling();
            int configWindow = plugin.getConfig().getInt("rolling.windowSeconds", 60);
            boolean configEnabled = plugin.getConfig().getBoolean("rolling.enabled", false);
            msg(s, INFO + "The rolling buffer is " + (active ? SAFE + "active" : BAD + "off") + INFO + ". Its window is " + BRAND
                    + configWindow + "s" + INFO + ", and on startup it defaults to " + (configEnabled ? SAFE + "on" : BAD + "off") + INFO + ".");
            if (active) {
                long windowMs = profiler.effectiveWindowMs();
                cont(s, INFO + "Buffered " + BRAND + String.format("%.1f", windowMs / 1000.0) + "s" + INFO + ". "
                        + cmdLink("/sktrace clip", "Snapshot the rolling buffer"));
            }
            return;
        }
        String mode = arg.toLowerCase(Locale.ROOT);
        if (mode.equals("on")) {
            if (profiler.isRolling()) {
                msg(s, INFO + "The rolling buffer is already active.");
                return;
            }
            if (profiler.isRunning()) {
                msg(s, BAD + "The one shot profiler is running. Stop it first with " + cmdLink("/sktrace stop", "Stop the one shot profiler") + INFO + ".");
                return;
            }
            int windowSec = Math.max(5, plugin.getConfig().getInt("rolling.windowSeconds", 60));
            profiler.startRolling(windowSec);
            plugin.getConfig().set("rolling.enabled", true);
            plugin.saveConfig();
            msg(s, SAFE + "Rolling buffer enabled. " + INFO + "The window is " + BRAND + windowSec + "s" + INFO + ", and it will stay on across restarts.");
            return;
        }
        if (mode.equals("off")) {
            if (!profiler.isRolling()) {
                msg(s, INFO + "The rolling buffer is already off.");
                plugin.getConfig().set("rolling.enabled", false);
                plugin.saveConfig();
                return;
            }
            profiler.stop();
            plugin.getConfig().set("rolling.enabled", false);
            plugin.saveConfig();
            msg(s, SAFE + "Rolling buffer disabled. " + INFO + "It will not start again on restart.");
            return;
        }
        msg(s, BAD + "Unknown argument " + INFO + esc(arg) + BAD + ". " + INFO + "Use " + SAFE + "on" + INFO + ", " + BAD + "off" + INFO + ", or no argument for status.");
    }

    // ============================================================
    // Status
    // ============================================================

    private void doStatus(CommandSender s, int topN) {
        long elapsed = profiler.effectiveWindowMs();
        boolean running = profiler.isRunning();
        boolean rolling = profiler.isRolling();
        String stateWord = rolling ? "rolling" : running ? "running" : (elapsed > 0 ? "stopped" : "idle");

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

        long sumDur = 0;
        int counted = 0;
        for (long d : tickDur) {
            if (d <= 0) continue;
            sumDur += d;
            counted++;
        }
        double avgMsptMs = counted > 0 ? (sumDur / (double) counted) / 1_000_000.0 : 0;
        double avgTps = counted > 0 ? Math.min(20.0, 1_000_000_000.0 / (sumDur / (double) counted)) : 0;

        int totalTriggers = profiler.triggerStats().size();
        int activeTriggers = (int) profiler.triggerStats().values().stream().filter(t -> t.calls() > 0).count();
        int silent = totalTriggers - activeTriggers;

        String stateCol = (running || rolling) ? SAFE : INFO;
        blank(s);
        msg(s, stateCol + stateWord + " " + INFO + "over a " + String.format("%.2f", elapsed / 1000.0) + "s window");
        cont(s, INFO + "Triggers " + BRAND + String.format("%,d", activeTriggers) + " " + INFO + "active of " + BRAND
                + String.format("%,d", totalTriggers) + " " + INFO + "hooked" + (silent > 0 ? ", " + BRAND + String.format("%,d", silent) + " " + INFO + "silent" : ""));
        String overCol = over == 0 ? SAFE : (over < tickSkript.length / 50 ? WORRY : BAD);
        cont(s, INFO + "Skript avg " + msCol(avgSkMs) + " " + INFO + "worst " + msCol(maxSkMs)
                + "   " + overCol + over + INFO + (over == 1 ? " tick" : " ticks") + " over 50ms");
        if (counted > 0) {
            double share = avgMsptMs > 0 ? (avgSkMs / avgMsptMs) * 100.0 : 0;
            cont(s, INFO + "Server MSPT " + msptCol(avgMsptMs) + "   " + INFO + "TPS " + tpsCol(avgTps)
                    + "   " + INFO + "Skript share " + pctCol(share));
        }

        blank(s);
        cont(s, INFO + "Top " + BRAND + topN + " " + INFO + "triggers by total time");
        List<TriggerStats> hot = profiler.triggerStats().values().stream()
                .filter(t -> t.calls() > 0)
                .sorted(Comparator.comparingLong(TriggerStats::totalNanos).reversed())
                .limit(topN)
                .toList();
        if (hot.isEmpty()) {
            cont(s, INFO + "  no trigger data yet");
        } else {
            renderTriggerTable(s, hot);
        }

        blank(s);
        cont(s, INFO + "Top " + BRAND + topN + " " + INFO + "events by total time");
        List<EventStats> events = profiler.eventStats().values().stream()
                .sorted(Comparator.comparingLong(EventStats::totalNanos).reversed())
                .limit(topN)
                .toList();
        if (events.isEmpty()) {
            cont(s, INFO + "  no event data yet");
        } else {
            renderEventTable(s, events);
        }

        blank(s);
        cont(s, INFO + "For the full picture, " + cmdLink("/sktrace report", "Run /sktrace report") + " " + INFO + "writes a complete report.");
    }

    // ============================================================
    // Loops
    // ============================================================

    private void doLoops(CommandSender s) {
        if (!profiler.loopWatchingEnabled()) {
            msg(s, WORRY + "Loop watching is turned off. " + INFO + "Set " + BRAND + "loop-watching: true " + INFO + "in config.yml to use it.");
            return;
        }
        if (!profiler.loopWatchingAvailable()) {
            msg(s, WORRY + "Loop watching is not supported on this Skript build. " + INFO + "The loop iteration counter could not be read.");
            return;
        }
        if (profiler.trackedLoopCount() == 0) {
            if (!profiler.isRunning()) {
                msg(s, INFO + "No loop data yet. Loops are collected when profiling starts, run " + cmdLink("/sktrace start", "Start profiling") + INFO + ".");
            } else {
                msg(s, INFO + "No loops found in any loaded script.");
            }
            return;
        }

        List<LoopWatcher.Reading> loops = profiler.loopSnapshot();
        blank(s);
        msg(s, INFO + "Running loops, " + BRAND + profiler.trackedLoopCount() + " " + INFO + "watched");
        if (loops.isEmpty()) {
            cont(s, SAFE + "No loops are running right now.");
            cont(s, INFO + "Only loops that span ticks (contain a wait) can be seen here.");
            return;
        }
        renderLoopTable(s, loops);
        blank(s);
        cont(s, INFO + "A count that keeps climbing and never clears is a runaway loop.");
        if (profiler.hangDetectionEnabled()) {
            cont(s, INFO + "A frozen no delay loop is watched for separately and would be logged to the console.");
        }
    }

    private void renderLoopTable(CommandSender s, List<LoopWatcher.Reading> loops) {
        int n = loops.size();
        String[] nameC = new String[n], locShort = new String[n];
        String[] iterC = new String[n], rateC = new String[n], ageC = new String[n];
        String[] iterColor = new String[n], rowHover = new String[n];
        for (int i = 0; i < n; i++) {
            LoopWatcher.Reading r = loops.get(i);
            nameC[i] = truncate(r.label, 24);
            String path = r.script == null ? "?" : r.script;
            String lineSuffix = r.line > 0 ? ":" + r.line : "";
            String locFull = path + lineSuffix;
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            locShort[i] = (slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path) + lineSuffix;
            boolean climbing = r.itersPerSec > 0;
            iterC[i] = abbrevCount(r.currentIter);
            iterColor[i] = (climbing && (r.currentIter >= 100_000 || r.ageMillis >= 60_000)) ? BAD
                    : climbing ? WORRY : INFO;
            rateC[i] = climbing ? abbrevCount(Math.round(r.itersPerSec)) + "/s" : "-";
            ageC[i] = fmtAge(r.ageMillis);
            rowHover[i] = BRAND + esc(r.label == null ? "?" : r.label) + "\n" + INFO + esc(locFull)
                    + "\n\n" + INFO + "iterations " + BRAND + String.format("%,d", r.currentIter)
                    + "\n" + INFO + "rate " + BRAND + (climbing ? abbrevCount(Math.round(r.itersPerSec)) + "/s" : "not climbing")
                    + "\n" + INFO + "running for " + BRAND + fmtAge(r.ageMillis);
        }
        renderTable(s, new String[]{"loop", "file", "iters", "rate", "age"},
                new String[][]{nameC, locShort, iterC, rateC, ageC},
                new boolean[]{true, true, false, false, false},
                new String[][]{null, null, iterColor, null, null},
                rowHover, 0);
    }

    private static String fmtAge(long ms) {
        if (ms <= 0) return "-";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        sec %= 60;
        if (min < 60) return min + "m " + sec + "s";
        long hr = min / 60;
        min %= 60;
        return hr + "h " + min + "m";
    }

    // ============================================================
    // Parse audit
    // ============================================================

    private void doParse(CommandSender s) {
        ParseStats latest = parseProfiler.latest();

        blank(s);
        msg(s, INFO + "Parse audit. This measures which lines are slow to load, not to run.");

        if (latest != null && latest.isFinished()) {
            List<ParseStats.Line> flagged = latest.flaggedLines(8);
            if (flagged.isEmpty()) {
                cont(s, SAFE + "The last audit found no lines slow to parse.");
            } else {
                cont(s, INFO + "Lines Skript flagged as slow to parse, over " + BRAND + latest.thresholdMs() + "ms" + INFO + ":");
                renderParseTable(s, flagged);
            }
        } else {
            cont(s, INFO + "No audit has run yet this session. Confirm below to run the first one.");
        }

        pendingParse.put(s.getName(), System.currentTimeMillis());
        blank(s);
        cont(s, WORRY + "A fresh audit reloads every script. " + INFO + "It uses Skript's normal reload, so no one is kicked, but running triggers are briefly re registered.");
        cont(s, INFO + "Run " + cmdLink("/sktrace confirm", "Run the audit now") + " " + INFO + "within " + BRAND + "30 " + INFO + "seconds to go ahead.");
    }

    private void doConfirm(CommandSender s) {
        Long armedAt = pendingParse.remove(s.getName());
        if (armedAt == null) {
            msg(s, INFO + "There is nothing to confirm. Run " + cmdLink("/sktrace parse", "Preview and arm an audit") + " " + INFO + "first to line one up.");
            return;
        }
        if (System.currentTimeMillis() - armedAt > PARSE_CONFIRM_WINDOW_MS) {
            msg(s, WORRY + "You did not confirm in time, so nothing ran. " + INFO + "Run " + cmdLink("/sktrace parse", "Preview and arm an audit") + " " + INFO + "to line it up again.");
            return;
        }

        msg(s, INFO + "Reloading scripts to measure parse time. " + INFO + "The results will follow and the server keeps running.");

        boolean started = parseProfiler.startAudit(result -> renderAuditResult(s, result));
        if (!started) {
            msg(s, BAD + "The audit could not start. " + INFO + "One may already be running, or the loader is unavailable. Check console for details.");
        }
    }

    private void renderAuditResult(CommandSender s, ParseStats result) {
        if (result == null || !result.isFinished()) {
            error(s, "The audit finished without producing a result.");
            return;
        }
        List<ParseStats.Line> flagged = result.flaggedLines(10);
        blank(s);
        msg(s, INFO + "Parse audit done. " + BRAND + result.flaggedCount() + " " + INFO
                + (result.flaggedCount() == 1 ? "line" : "lines") + " over " + BRAND + result.thresholdMs() + "ms" + INFO + ".");
        if (flagged.isEmpty()) {
            cont(s, SAFE + "No line was slow to parse.");
        } else {
            renderParseTable(s, flagged);
            if (result.flaggedCount() > flagged.size()) {
                cont(s, INFO + "and " + BRAND + (result.flaggedCount() - flagged.size()) + " " + INFO
                        + "more, see the full list in " + BRAND + "plugins/skTrace/audits/" + INFO + ".");
            }
        }
        cont(s, INFO + "Written to " + BRAND + "plugins/skTrace/audits/" + INFO + ".");
    }

    private void renderParseTable(CommandSender s, List<ParseStats.Line> flagged) {
        int n = flagged.size();
        String[] locShort = new String[n], locFull = new String[n], srcC = new String[n];
        for (int i = 0; i < n; i++) {
            ParseStats.Line l = flagged.get(i);
            String path = l.script() == null ? "?" : l.script();
            String lineSuffix = ":" + l.lineNumber();
            locFull[i] = path + lineSuffix;
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            locShort[i] = (slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path) + lineSuffix;
            srcC[i] = truncate(l.source() == null ? "" : l.source(), 32);
        }
        String[] rowHover = new String[n];
        for (int i = 0; i < n; i++) {
            ParseStats.Line l = flagged.get(i);
            rowHover[i] = BRAND + esc(locFull[i]) + "\n" + INFO + esc(l.source() == null ? "" : l.source())
                    + "\n\n" + INFO + "Skript measured this line taking longer than the threshold to parse.";
        }
        renderTable(s, new String[]{"file", "line"},
                new String[][]{locShort, srcC},
                new boolean[]{true, true},
                new String[][]{null, null},
                rowHover, 1);
    }

    // ============================================================
    // Help
    // ============================================================

    private void usage(CommandSender s) {
        blank(s);
        msg(s, INFO + "Skript aware profiler");
        helpRow(s, "start", "Begin profiling");
        helpRow(s, "stop", "Stop profiling");
        helpRow(s, "status [N]", "Show summary and the top N hot triggers and events");
        helpRow(s, "loops", "Live view of running loops");
        helpRow(s, "parse", "Show which lines are slow to load, then confirm to run a fresh audit");
        helpRow(s, "confirm", "Confirm the parse audit armed by /sktrace parse");
        helpRow(s, "report", "Write and upload a report. --include-files embeds source, --no-upload keeps it local");
        helpRow(s, "clip", "Snapshot the last N seconds (rolling buffer must be on)");
        helpRow(s, "rolling on|off", "Toggle the always on rolling buffer (persists in config)");
        helpRow(s, "reset", "Clear collected stats");
        helpRow(s, "diag", "Dump Skript internals to console");
        cont(s, INFO + "You can also use " + ACTION + "/skt" + INFO + ", and the permission is " + BRAND + "sktrace.use" + INFO + ".");
    }

    private void helpRow(CommandSender s, String form, String desc) {
        String runForm = "/sktrace " + form.split(" ")[0];
        int pad = Math.max(1, 22 - ("/sktrace " + form).length());
        cont(s, suggestLink("/sktrace " + form, "Click to insert " + runForm) + INFO + " ".repeat(pad) + INFO + esc(desc));
    }

    // ============================================================
    // Pixel-aligned tables (chat is not monospace, pad by pixel width)
    // ============================================================

    private void renderTriggerTable(CommandSender s, List<TriggerStats> hot) {
        int n = hot.size();
        String[] nameC = new String[n], locShort = new String[n];
        String[] callsC = new String[n], totalC = new String[n], maxC = new String[n];
        String[] totalColor = new String[n], maxColor = new String[n], rowHover = new String[n];
        for (int i = 0; i < n; i++) {
            TriggerStats t = hot.get(i);
            nameC[i] = shortenName(t.triggerName());
            String path = t.scriptName() == null ? "?" : t.scriptName();
            String lineSuffix = t.line() >= 0 ? ":" + t.line() : "";
            String locFull = path + lineSuffix;
            int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            locShort[i] = (slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path) + lineSuffix;
            callsC[i] = abbrevCount(t.calls()) + "x";
            totalC[i] = abbrevDuration(t.totalNanos());
            totalColor[i] = msColorTag(t.totalNanos() / 1.0e6);
            maxC[i] = abbrevDuration(t.maxNanos());
            maxColor[i] = msColorTag(t.maxNanos() / 1.0e6);
            rowHover[i] = BRAND + esc(t.triggerName() == null ? "?" : t.triggerName()) + "\n" + INFO + esc(locFull)
                    + "\n\n" + INFO + "total " + BRAND + abbrevDuration(t.totalNanos())
                    + "\n" + INFO + "calls " + BRAND + String.format("%,d", t.calls())
                    + "\n" + INFO + "avg " + BRAND + formatTimeNs(t.avgNanos())
                    + "\n" + INFO + "worst " + BRAND + abbrevDuration(t.maxNanos());
        }
        renderTable(s, new String[]{"trigger", "file", "calls", "total", "max"},
                new String[][]{nameC, locShort, callsC, totalC, maxC},
                new boolean[]{true, true, false, false, false},
                new String[][]{null, null, null, totalColor, maxColor},
                rowHover, 1);
    }

    private void renderEventTable(CommandSender s, List<EventStats> events) {
        int n = events.size();
        String[] nameC = new String[n], callsC = new String[n], totalC = new String[n], avgC = new String[n];
        String[] totalColor = new String[n], rowHover = new String[n];
        for (int i = 0; i < n; i++) {
            EventStats e = events.get(i);
            nameC[i] = e.shortName();
            callsC[i] = abbrevCount(e.calls()) + "x";
            totalC[i] = abbrevDuration(e.totalNanos());
            totalColor[i] = msColorTag(e.totalNanos() / 1.0e6);
            avgC[i] = formatTimeNs(e.avgNanos());
            rowHover[i] = BRAND + esc(e.shortName())
                    + "\n\n" + INFO + "total " + BRAND + abbrevDuration(e.totalNanos())
                    + "\n" + INFO + "calls " + BRAND + String.format("%,d", e.calls())
                    + "\n" + INFO + "avg " + BRAND + formatTimeNs(e.avgNanos());
        }
        renderTable(s, new String[]{"event", "calls", "total", "avg"},
                new String[][]{nameC, callsC, totalC, avgC},
                new boolean[]{true, false, false, false},
                new String[][]{null, null, totalColor, null},
                rowHover, 0);
    }

    /**
     * Pixel-aligned table. {@code rowHover[r]} (already MiniMessage-formatted) is shown on hover
     * over column {@code hoverCol} of that row, restoring the full-detail tooltip. Pass a null
     * rowHover to disable hovers.
     */
    private void renderTable(CommandSender s, String[] headers, String[][] cols, boolean[] leftAlign,
                             String[][] colors, String[] rowHover, int hoverCol) {
        int nCols = headers.length;
        int nRows = cols[0].length;
        int gap = 8; // pixels between columns
        int[] w = new int[nCols];
        for (int c = 0; c < nCols; c++) {
            w[c] = Math.max(FontWidth.maxWidth(cols[c]), FontWidth.width(headers[c]));
        }

        // Header. Laid out with the EXACT same per-cell logic as the data rows below, so every
        // value sits directly under its column title.
        StringBuilder hb = new StringBuilder();
        for (int c = 0; c < nCols; c++) {
            hb.append(INFO).append(esc(layoutCell(headers[c], w[c], leftAlign[c], c == nCols - 1, gap)));
        }
        cont(s, hb.toString());

        for (int r = 0; r < nRows; r++) {
            StringBuilder rb = new StringBuilder();
            for (int c = 0; c < nCols; c++) {
                boolean last = c == nCols - 1;
                String raw = cols[c][r];
                // The laid-out cell: value + inter-column padding. Split into the visible value and
                // the padding, so a hover can wrap ONLY the value while the padding still holds the
                // column width. Padding is spaces, which esc() leaves untouched.
                String laid = layoutCell(raw, w[c], leftAlign[c], last, gap);
                int valStart = laid.indexOf(raw);
                String lead = laid.substring(0, valStart);          // left padding (right-aligned cols)
                String trail = laid.substring(valStart + raw.length()); // right padding + gap
                String color = (colors[c] != null && colors[c][r] != null) ? colors[c][r] : INFO;
                if (c == hoverCol && rowHover != null && rowHover[r] != null) {
                    rb.append(color).append(esc(lead))
                            .append("<hover:show_text:'").append(rowHover[r]).append("'>")
                            .append(ACTION).append(esc(raw)).append("</hover>")
                            .append(esc(trail));
                } else {
                    rb.append(color).append(esc(lead)).append(esc(raw)).append(esc(trail));
                }
            }
            cont(s, rb.toString());
        }
    }

    /**
     * Lay out one table cell: align the value within its column width {@code w}, then append the
     * inter-column gap (unless it's the last column). Left- and right-aligned cells both end up
     * occupying {@code w + gap} pixels, so the same call produces identical geometry for the header
     * and every row — which is what keeps values under their titles.
     */
    private static String layoutCell(String value, int w, boolean leftAlign, boolean last, int gap) {
        String aligned = leftAlign ? FontWidth.padRightPx(value, w) : FontWidth.padLeftPx(value, w);
        if (last) return aligned;
        return leftAlign ? FontWidth.padRightPx(aligned, w + gap) : aligned + FontWidth.padRightPx("", gap);
    }

    // ============================================================
    // Value + color helpers (color carries the meaning)
    // ============================================================

    private static String msCol(double ms) {
        return msColorTag(ms) + String.format("%.2f", ms) + "ms";
    }

    private static String msColorTag(double ms) {
        return ms >= 50.0 ? BAD : (ms >= 25.0 ? WORRY : SAFE);
    }

    private static String tpsCol(double tps) {
        return (tps >= 19.5 ? SAFE : tps >= 17.0 ? WORRY : BAD) + String.format("%.2f", tps);
    }

    private static String msptCol(double mspt) {
        return (mspt <= 50.0 ? SAFE : mspt <= 60.0 ? WORRY : BAD) + String.format("%.2f", mspt) + "ms";
    }

    private static String pctCol(double pct) {
        return (pct >= 40.0 ? BAD : pct >= 15.0 ? WORRY : SAFE) + String.format("%.1f", pct) + "%";
    }

    private static String formatTimeNs(long ns) {
        if (ns < 1000) return ns + "ns";
        if (ns < 1_000_000) return (ns / 1000) + "us";
        return String.format("%.2fms", ns / 1_000_000.0);
    }

    private static String abbrevDuration(long nanos) {
        double ms = nanos / 1.0e6;
        if (ms < 1.0) return String.format("%.0fus", nanos / 1.0e3);
        if (ms < 1000.0) return trimDecimal(ms) + "ms";
        double sec = ms / 1000.0;
        if (sec < 60.0) return trimDecimal(sec) + "s";
        double min = sec / 60.0;
        if (min < 60.0) return trimDecimal(min) + "m";
        return trimDecimal(min / 60.0) + "h";
    }

    private static String trimDecimal(double v) {
        String s = String.format("%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String abbrevCount(long n) {
        if (n < 1000) return Long.toString(n);
        String[] suffix = {"K", "M", "B", "T"};
        double v = n;
        int tier = -1;
        while (v >= 1000.0 && tier < suffix.length - 1) {
            v /= 1000.0;
            tier++;
        }
        String num = String.format("%.1f", v);
        if (num.endsWith(".0")) num = num.substring(0, num.length() - 2);
        return num + suffix[tier];
    }

    private static String shortenName(String raw) {
        if (raw == null || raw.isEmpty()) return "?";
        String s = raw.trim();
        int cut = s.length();
        int forIdx = s.indexOf(" for ");
        if (forIdx >= 0) cut = Math.min(cut, forIdx);
        int brIdx = s.indexOf('[');
        if (brIdx >= 0) cut = Math.min(cut, brIdx);
        int parIdx = s.indexOf('(');
        if (parIdx >= 0) cut = Math.min(cut, parIdx);
        s = s.substring(0, cut).trim();
        return truncate(s.isEmpty() ? "?" : s, 24);
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
