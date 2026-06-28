// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import dev.sktrace.Sktrace;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects a frozen main thread — the one case the passive loops view can't show. A truly infinite
 * no-delay loop (e.g. {@code while true:} with nothing yielding) pins the server thread inside one
 * trigger, so the tick task stops, chat can't render, and {@code /sktrace loops} can never run.
 *
 * <p>This watchdog runs on its own daemon thread, wholly independent of the server tick loop, so it
 * keeps working while the main thread is stuck. It watches the profiler's per-tick heartbeat: if the
 * main thread hasn't ticked for {@code freezeMillis}, the server is frozen. It then probes every
 * tracked loop's iteration counter twice, a moment apart — the one whose counter is climbing while
 * everything else is stalled is the runaway — and logs it (with script:line) to the console, the
 * only surface that still works during a freeze. If no climbing loop can be pinpointed it falls back
 * to dumping the main thread's stack. Detection only: it never kills or interrupts anything.
 */
public final class HangWatchdog {

    private static final long CHECK_INTERVAL_MS = 1000L;
    private static final long PROBE_GAP_MS = 150L;

    private final Sktrace plugin;
    private final Profiler profiler;
    private final LoopWatcher loopWatcher;
    private final long freezeMillis;

    private volatile boolean active;
    private Thread thread;
    // True once the current freeze episode has been reported, so we log it once, not every second.
    private boolean reportedThisEpisode;
    // Peak staleness seen during the current episode, for the recovery message.
    private long episodePeakStaleMs;

    public HangWatchdog(Sktrace plugin, Profiler profiler, LoopWatcher loopWatcher, long freezeMillis) {
        this.plugin = plugin;
        this.profiler = profiler;
        this.loopWatcher = loopWatcher;
        this.freezeMillis = Math.max(2000L, freezeMillis);
    }

    public synchronized void start() {
        if (thread != null) return;
        active = true;
        reportedThisEpisode = false;
        episodePeakStaleMs = 0;
        thread = new Thread(this::run, "Sktrace-HangWatchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        active = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void run() {
        while (active) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                check();
            } catch (Throwable ignored) {
                // A watchdog must never throw its way out of its own loop.
            }
        }
    }

    private void check() {
        // Only meaningful while the profiler is running — that's when the heartbeat updates.
        if (!profiler.isRunning()) {
            reportedThisEpisode = false;
            episodePeakStaleMs = 0;
            return;
        }
        long hb = profiler.mainHeartbeatNanos();
        if (hb == 0) return;  // no tick has happened yet since start

        long staleMs = (System.nanoTime() - hb) / 1_000_000L;

        if (staleMs < freezeMillis) {
            // Healthy (or recovered).
            if (reportedThisEpisode) {
                plugin.getLogger().warning("[Sktrace] Main thread recovered — it was frozen for ~"
                        + (episodePeakStaleMs / 1000) + "s.");
            }
            reportedThisEpisode = false;
            episodePeakStaleMs = 0;
            return;
        }

        episodePeakStaleMs = Math.max(episodePeakStaleMs, staleMs);
        if (reportedThisEpisode) return;  // one report per freeze episode
        reportedThisEpisode = true;
        reportFreeze(staleMs);
    }

    private void reportFreeze(long staleMs) {
        long sec = staleMs / 1000;
        LoopWatcher.Probe culprit = findRunaway();
        if (culprit != null) {
            String loc = culprit.script + (culprit.line > 0 ? ":" + culprit.line : "");
            plugin.getLogger().severe("[Sktrace] MAIN THREAD FROZEN for ~" + sec + "s — runaway "
                    + (culprit.isWhile ? "while" : "loop") + " at " + loc
                    + "  [" + culprit.label + "]  is pinning the server"
                    + (culprit.maxIter > 0 ? " (iteration " + String.format("%,d", culprit.maxIter) + " and climbing)" : "")
                    + ". It has no wait/delay, so it never yields. Stop the server or fix that loop.");
        } else {
            plugin.getLogger().severe("[Sktrace] MAIN THREAD FROZEN for ~" + sec
                    + "s — couldn't pinpoint a Skript loop (it may not be loop-related). Main-thread stack:");
            dumpMainThreadStack();
        }
    }

    /**
     * Probe every tracked loop's counter twice, a short gap apart; the one that climbed the most is
     * the runaway. During a hard freeze, delayed loops sit still at their wait while the stuck loop's
     * counter races, so the climbing one stands out cleanly.
     */
    private LoopWatcher.Probe findRunaway() {
        try {
            List<LoopWatcher.Probe> before = loopWatcher.probe();
            if (before.isEmpty()) return null;
            Map<Object, Long> byLoop = new IdentityHashMap<>();
            for (LoopWatcher.Probe p : before) byLoop.put(p.loop, p.maxIter);

            Thread.sleep(PROBE_GAP_MS);

            List<LoopWatcher.Probe> after = loopWatcher.probe();
            LoopWatcher.Probe best = null;
            long bestDelta = 0;
            for (LoopWatcher.Probe p : after) {
                Long was = byLoop.get(p.loop);
                if (was == null) continue;
                long delta = p.maxIter - was;
                if (delta > bestDelta) {
                    bestDelta = delta;
                    best = p;
                }
            }
            // Require a meaningful climb so we don't fingerprint a loop that merely ticked once.
            return bestDelta > 0 ? best : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Best-effort dump of the server main thread's stack, for the no-loop fallback. */
    private void dumpMainThreadStack() {
        try {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                String name = e.getKey().getName();
                if (!name.equals("Server thread") && !name.startsWith("Server thread")) continue;
                StackTraceElement[] frames = e.getValue();
                List<String> lines = new ArrayList<>();
                int limit = Math.min(frames.length, 18);
                for (int i = 0; i < limit; i++) lines.add("    at " + frames[i]);
                plugin.getLogger().severe("[Sktrace] " + name + ":\n" + String.join("\n", lines));
                return;
            }
        } catch (Throwable ignored) { }
    }
}
