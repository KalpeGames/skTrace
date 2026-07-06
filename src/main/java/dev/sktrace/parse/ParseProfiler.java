// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.parse;

import ch.njol.util.OpenCloseable;
import dev.sktrace.SkTrace;
import org.bukkit.Bukkit;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * The parse-audit engine. Answers "which lines are expensive to PARSE (load)?" — invisible to the
 * runtime profiler, which only sees a script after it's parsed.
 *
 * <h2>How it works</h2>
 * It doesn't measure anything itself. Skript's own script loader already parses every line in order
 * and in context, times each, and emits a node-tagged warning for any line that exceeds
 * {@code long parse time warning threshold}. So we:
 * <ol>
 *   <li>lower that threshold to a small "worth a builder's attention" value;</li>
 *   <li>re-run Skript's normal {@code loadScripts} over the scripts folder — the SAME call
 *       {@code /sk reload} makes, on the SAME async path, so no player is disconnected — but with
 *       our {@link ParseObserver} spliced in as the load's log handler;</li>
 *   <li>collect the lines Skript itself flagged as slow (file + line read straight off each
 *       warning's node), and swallow the warnings so the console isn't flooded.</li>
 * </ol>
 * Only slow lines ever reach the observer, so the result is exactly the lines worth looking at —
 * no per-line spam, no measuring of the thousands of trivial lines.
 */
public final class ParseProfiler {

    private final SkTrace plugin;
    private final ParseReport report;

    private volatile ParseStats latest;
    private final AtomicLong generationSeq = new AtomicLong();
    private volatile boolean running = false;

    public ParseProfiler(SkTrace plugin) {
        this.plugin = plugin;
        this.report = new ParseReport(plugin);
    }

    public ParseStats latest() { return latest; }
    public boolean isRunning() { return running; }

    // Passive-mode hooks are gone; kept as no-ops so the plugin lifecycle is unchanged.
    public boolean armPassive() { return false; }
    public void disarmPassive() { }

    /**
     * Start a parse audit and return immediately — the main thread is NEVER blocked waiting on the
     * reload. We kick off Skript's own loader (with the slow-line threshold lowered and our observer
     * attached) and attach a non-blocking completion callback to the returned future. When loading
     * finishes (on whatever thread Skript completes it), we publish the result and hop back to the
     * main thread only for the short {@code onDone} callback.
     *
     * <p>Blocking here is what froze the server for 30s and got players kicked by the anticheat for
     * missing confirmation packets. So this method does no waiting: {@code onDone} is invoked later,
     * on the main thread, with the finished stats (or null on failure).
     *
     * @param onDone main-thread callback invoked with the result when the audit completes, or null.
     * @return true if the audit was started; false if it couldn't be (already running, off main
     *         thread, or the loader API is unavailable) — in which case {@code onDone} is not called.
     */
    public synchronized boolean startAudit(java.util.function.Consumer<ParseStats> onDone) {
        if (running) return false;
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("[skTrace] Parse audit must be started on the main thread.");
            return false;
        }

        long thresholdMs = Math.max(1, plugin.getConfig().getLong("parse-audit.slow-line-threshold-ms", 2));
        Object savedThreshold = lowerSlowLineThreshold(thresholdMs);
        ParseObserver observer = new ParseObserver();
        long gen = generationSeq.incrementAndGet();

        // CRITICAL: Skript only parses OFF the main thread when async loading is enabled — otherwise
        // loadScripts runs the parse inline and freezes the server (which got players kicked by the
        // anticheat for missing confirmation packets). So ensure async loading is on for the audit;
        // the parse then runs on a loader thread and Skript posts its main-thread registration work
        // back via the scheduler in pieces, keeping the server ticking. Restored on completion.
        final int savedAsyncSize = ensureAsyncLoading();

        // Completion runs off-thread when the (async) load finishes. It restores the threshold,
        // builds the stats, then bounces the user-facing part to the main thread. Guarded so it
        // runs exactly once.
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable complete = () -> {
            if (!done.compareAndSet(false, true)) return;
            restoreAsyncLoading(savedAsyncSize);
            restoreSlowLineThreshold(savedThreshold);
            ParseStats stats = new ParseStats(gen, System.currentTimeMillis());
            for (ParseObserver.Flagged f : observer.snapshot()) {
                stats.add(f.script(), f.line(), f.source());
            }
            stats.finish(System.currentTimeMillis(), thresholdMs);
            latest = stats;
            plugin.getLogger().info(String.format(
                    "[skTrace] Parse audit: %d line(s) over %dms flagged as slow to parse.",
                    stats.flaggedCount(), thresholdMs));
            try {
                Path written = report.write(stats, stats.finishedAtMillis());
                plugin.getLogger().info("[skTrace] Parse audit written to " + written);
            } catch (Throwable t) {
                plugin.getLogger().fine("[skTrace] Could not write parse audit file: " + t.getMessage());
            }
            running = false;
            // Short, main-thread handoff for the user-facing callback only.
            if (onDone != null) {
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> onDone.accept(stats));
                } catch (Throwable ignored) { /* scheduler refused during shutdown */ }
            }
        };

        // Set running BEFORE kicking off: if the load completes synchronously, `complete` runs
        // inline and needs to see running==true so it can clear it. Cleared again below on failure.
        running = true;
        boolean started = reloadAllWithObserver(observer, complete);
        if (!started) {
            restoreAsyncLoading(savedAsyncSize);
            restoreSlowLineThreshold(savedThreshold);
            running = false;
            plugin.getLogger().warning("[skTrace] Parse audit: couldn't run Skript's loader "
                    + "(API differs on this build). No audit produced.");
            return false;
        }
        return true;
    }

    /**
     * Kick off {@code ScriptLoader.loadScripts(scriptsFolder, observer)} — the same call
     * {@code /sk reload} makes, so the observer sits on the parser's log stack during the real,
     * in-context parse — and attach {@code complete} to the returned future WITHOUT blocking. Skript
     * runs the heavy parse off-thread and posts its main-thread work (registration) back via the
     * scheduler in pieces, so the server keeps ticking. Unloads first so everything is re-parsed.
     */
    private boolean reloadAllWithObserver(ParseObserver observer, Runnable complete) {
        try {
            Class<?> loader = Class.forName("ch.njol.skript.ScriptLoader");
            Class<?> skript = Class.forName("ch.njol.skript.Skript");

            Object instance = skript.getMethod("getInstance").invoke(null);
            File scriptsFolder = (File) instance.getClass().getMethod("getScriptsFolder").invoke(instance);

            // Unload current scripts so the reload re-parses them (and our observer sees every line).
            Object loaded = loader.getMethod("getLoadedScripts").invoke(null);
            loader.getMethod("unloadScripts", java.util.Set.class).invoke(null, loaded);

            Object future = loader.getMethod("loadScripts", File.class, OpenCloseable.class)
                    .invoke(null, scriptsFolder, observer);
            if (future instanceof java.util.concurrent.CompletableFuture<?> cf) {
                // Non-blocking: fire `complete` when loading finishes, on whatever thread completes it.
                cf.whenComplete((res, err) -> complete.run());
            } else {
                // Loader returned something we can't attach to — run completion now (load was sync).
                complete.run();
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().fine("[skTrace] reloadAllWithObserver failed: " + t.getMessage());
            return false;
        }
    }

    // ---- async loading (ensure the parse runs OFF the main thread so the server doesn't freeze) ----

    /**
     * Ensure Skript async loading is enabled so {@code loadScripts} parses off the main thread.
     * Returns the prior size to restore, or -1 if it was already async / couldn't be read (in which
     * case nothing is changed and restore is a no-op). If it was 0 (sync), bump it to 1.
     */
    private int ensureAsyncLoading() {
        try {
            Class<?> loader = Class.forName("ch.njol.skript.ScriptLoader");
            java.lang.reflect.Field f = loader.getDeclaredField("asyncLoaderSize");
            f.setAccessible(true);
            int current = f.getInt(null);
            if (current > 0) return -1; // already async — leave it, nothing to restore
            loader.getMethod("setAsyncLoaderSize", int.class).invoke(null, 1);
            return current; // 0 — remember so we can turn it back off
        } catch (Throwable t) {
            plugin.getLogger().fine("[skTrace] Could not enable async loading (audit may run sync): "
                    + t.getMessage());
            return -1;
        }
    }

    private void restoreAsyncLoading(int savedSize) {
        if (savedSize < 0) return; // we didn't change it
        try {
            Class<?> loader = Class.forName("ch.njol.skript.ScriptLoader");
            loader.getMethod("setAsyncLoaderSize", int.class).invoke(null, savedSize);
        } catch (Throwable t) {
            plugin.getLogger().fine("[skTrace] Could not restore async loader size: " + t.getMessage());
        }
    }

    // ---- slow-line threshold (Option<Timespan>, no setter — set parsedValue reflectively) ----

    /** Set Skript's slow-line warning threshold to {@code ms}; return the prior Timespan to restore. */
    private Object lowerSlowLineThreshold(long ms) {
        try {
            Class<?> config = Class.forName("ch.njol.skript.SkriptConfig");
            Object option = config.getField("longParseTimeWarningThreshold").get(null);
            Class<?> timespan = Class.forName("ch.njol.skript.util.Timespan");

            java.lang.reflect.Field parsed = option.getClass().getDeclaredField("parsedValue");
            parsed.setAccessible(true);
            Object current = parsed.get(option);

            Object low = timespan.getConstructor(long.class).newInstance(ms);
            parsed.set(option, low);
            return current;
        } catch (Throwable t) {
            plugin.getLogger().fine("[skTrace] Could not lower slow-line threshold: " + t.getMessage());
            return null;
        }
    }

    private void restoreSlowLineThreshold(Object saved) {
        if (saved == null) return;
        try {
            Class<?> config = Class.forName("ch.njol.skript.SkriptConfig");
            Object option = config.getField("longParseTimeWarningThreshold").get(null);
            java.lang.reflect.Field parsed = option.getClass().getDeclaredField("parsedValue");
            parsed.setAccessible(true);
            parsed.set(option, saved);
        } catch (Throwable t) {
            plugin.getLogger().fine("[skTrace] Could not restore slow-line threshold: " + t.getMessage());
        }
    }
}
