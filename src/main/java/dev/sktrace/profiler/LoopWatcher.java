// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import ch.njol.skript.lang.LoopSection;
import org.bukkit.event.Event;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Passive observer of currently-running Skript loops.
 *
 * <p>Both {@code loop} and {@code while} are {@link LoopSection} subclasses (SecLoop / SecWhile),
 * and LoopSection keeps a per-event iteration counter — {@code currentLoopCounter: Map<Event,Long>}
 * — that Skript increments on each pass and clears when the loop exits. We only ever <em>read</em>
 * that map (never its contents, never the trigger graph), so this layer cannot perturb a running
 * script, unlike per-line tracing. That's why it can stay on by default.
 *
 * <p>A loop is only visible here while its counter is non-empty <em>between</em> ticks — i.e. it
 * spans ticks because something inside it yields (a {@code wait}/{@code delay}). That is exactly the
 * "this loop just keeps running" case operators want to catch. An instantaneous no-delay loop
 * finishes and clears its counter within a single tick, so it never shows; a truly infinite no-delay
 * loop pins the main thread, so nothing — including this sampler — runs while it's stuck. Catching
 * that one needs a separate async watchdog and is out of scope here.
 *
 * <p>All access is on the server main thread (graph walk at hook install, periodic {@link #sample}
 * from the tick task, {@link #snapshot} from the command), but the registry is guarded anyway so a
 * stray async caller can't corrupt it.
 */
public final class LoopWatcher {

    /** {@code LoopSection.currentLoopCounter}, resolved once. Null when the field layout differs. */
    private static final Field COUNTER_FIELD = resolveCounterField();

    private static Field resolveCounterField() {
        try {
            Field f = LoopSection.class.getDeclaredField("currentLoopCounter");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** One tracked loop. Registry is identity-keyed so a shared LoopSection is tracked once. */
    private static final class Tracked {
        final LoopSection loop;
        final String script;
        final int line;
        final String label;
        final boolean isWhile;

        // Sampler state — written only by sample(), read by snapshot():
        long firstActiveNanos;   // when the counter was first seen non-empty; 0 = idle
        long lastSampleNanos;    // timestamp of the last sample while active; 0 = no baseline
        long lastMaxIter;        // max counter value at lastSampleNanos
        double itersPerSec;      // last computed iteration rate

        // Window-scoped stats, for the report (a historical capture, unlike the live command):
        boolean everActive;      // was this loop ever seen running during the window?
        long peakIter;           // highest iteration observed across the window
        int peakConcurrent;      // most simultaneous executions observed across the window

        Tracked(LoopSection loop, String script, int line, String label, boolean isWhile) {
            this.loop = loop;
            this.script = script;
            this.line = line;
            this.label = label;
            this.isWhile = isWhile;
        }
    }

    private final Map<LoopSection, Tracked> registry = new IdentityHashMap<>();

    /** True when loop counters are readable on this Skript build. */
    public boolean available() {
        return COUNTER_FIELD != null;
    }

    /** Register a loop discovered during a trigger-graph walk. Idempotent per LoopSection. */
    public void track(LoopSection loop, String script, int line, String label, boolean isWhile) {
        if (loop == null || COUNTER_FIELD == null) return;
        synchronized (registry) {
            registry.putIfAbsent(loop, new Tracked(loop, script, line, label, isWhile));
        }
    }

    public void clear() {
        synchronized (registry) {
            registry.clear();
        }
    }

    public int trackedCount() {
        synchronized (registry) {
            return registry.size();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Event, Long> counterOf(LoopSection loop) {
        if (COUNTER_FIELD == null) return null;
        try {
            return (Map<Event, Long>) COUNTER_FIELD.get(loop);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Live read of a loop's counter: {@code [maxIteration, concurrentExecutions]}. Returns
     * {@code [0, 0]} when idle or unreadable. The values are copied out before scanning so a
     * concurrent GC expunge on the WeakHashMap backing the counter can't trip iteration.
     */
    private static long[] read(LoopSection loop) {
        Map<Event, Long> counter = counterOf(loop);
        if (counter == null) return new long[]{0, 0};
        long max = 0;
        long n = 0;
        try {
            for (Long v : new ArrayList<>(counter.values())) {
                if (v == null) continue;
                n++;
                if (v > max) max = v;
            }
        } catch (Throwable t) {
            return new long[]{0, 0};
        }
        return new long[]{max, n};
    }

    /**
     * Periodic sample, called from the profiler's tick task (throttled to ~1Hz). Updates each
     * loop's iteration rate and active-age baseline. Cheap: a few map reads per tracked loop.
     */
    public void sample(long nowNanos) {
        if (COUNTER_FIELD == null) return;
        List<Tracked> all;
        synchronized (registry) {
            all = new ArrayList<>(registry.values());
        }
        for (Tracked t : all) {
            long[] r = read(t.loop);
            long max = r[0];
            long n = r[1];
            if (n == 0) {
                // Loop finished (or never started this window): reset so age/rate restart cleanly.
                t.firstActiveNanos = 0;
                t.lastSampleNanos = 0;
                t.lastMaxIter = 0;
                t.itersPerSec = 0;
                continue;
            }
            if (t.firstActiveNanos == 0) t.firstActiveNanos = nowNanos;
            if (t.lastSampleNanos != 0) {
                double dt = (nowNanos - t.lastSampleNanos) / 1_000_000_000.0;
                if (dt > 0) {
                    double rate = (max - t.lastMaxIter) / dt;
                    // Clamp negatives: the max can dip when the leading execution finishes and a
                    // younger one remains. A coarse, non-negative rate is what we want.
                    t.itersPerSec = rate < 0 ? 0 : rate;
                }
            }
            t.lastSampleNanos = nowNanos;
            t.lastMaxIter = max;
            t.everActive = true;
            if (max > t.peakIter) t.peakIter = max;
            if (n > t.peakConcurrent) t.peakConcurrent = (int) n;
        }
    }

    /** Immutable view of one loop, for the command and the report. */
    public static final class Reading {
        public final String script;
        public final int line;
        public final String label;
        public final boolean isWhile;
        public final long currentIter;   // highest iteration among live executions (0 if idle)
        public final int concurrent;     // how many executions are looping right now
        public final double itersPerSec; // last sampled rate (0 until the first interval elapses)
        public final long ageMillis;     // how long continuously active (0 = unknown / idle)
        public final boolean running;    // is it executing at this instant?
        public final long peakIter;      // highest iteration seen across the window
        public final int peakConcurrent; // most simultaneous executions seen across the window

        Reading(String script, int line, String label, boolean isWhile,
                long currentIter, int concurrent, double itersPerSec, long ageMillis,
                boolean running, long peakIter, int peakConcurrent) {
            this.script = script;
            this.line = line;
            this.label = label;
            this.isWhile = isWhile;
            this.currentIter = currentIter;
            this.concurrent = concurrent;
            this.itersPerSec = itersPerSec;
            this.ageMillis = ageMillis;
            this.running = running;
            this.peakIter = peakIter;
            this.peakConcurrent = peakConcurrent;
        }
    }

    /**
     * Fresh snapshot of every loop running right now. Iteration count and concurrency are read
     * live (so the result reflects the instant the command runs); rate and age come from the
     * periodic sampler's accumulated state. Sorted by current iteration, descending.
     */
    public List<Reading> snapshot(long nowNanos) {
        List<Tracked> all;
        synchronized (registry) {
            all = new ArrayList<>(registry.values());
        }
        List<Reading> out = new ArrayList<>();
        for (Tracked t : all) {
            long[] r = read(t.loop);
            long max = r[0];
            int n = (int) r[1];
            if (n == 0) continue;  // not currently running
            long age = t.firstActiveNanos == 0 ? 0 : (nowNanos - t.firstActiveNanos) / 1_000_000L;
            out.add(new Reading(t.script, t.line, t.label, t.isWhile, max, n, t.itersPerSec, age,
                    true, t.peakIter, t.peakConcurrent));
        }
        out.sort((a, b) -> Long.compare(b.currentIter, a.currentIter));
        return out;
    }

    /**
     * Report-oriented view: every loop that ran at least once during the window (not just the
     * ones running this instant), with its peak iteration/concurrency plus a live read of whether
     * it's still going. Sorted by peak iteration, descending.
     */
    public List<Reading> reportReadings(long nowNanos) {
        List<Tracked> all;
        synchronized (registry) {
            all = new ArrayList<>(registry.values());
        }
        List<Reading> out = new ArrayList<>();
        for (Tracked t : all) {
            if (!t.everActive) continue;
            long[] r = read(t.loop);
            long max = r[0];
            int n = (int) r[1];
            boolean running = n > 0;
            long age = (running && t.firstActiveNanos != 0) ? (nowNanos - t.firstActiveNanos) / 1_000_000L : 0;
            out.add(new Reading(t.script, t.line, t.label, t.isWhile, max, n,
                    running ? t.itersPerSec : 0, age, running, t.peakIter, t.peakConcurrent));
        }
        out.sort((a, b) -> Long.compare(b.peakIter, a.peakIter));
        return out;
    }

    /** A single live counter reading paired with the loop's identity, for the hang watchdog. */
    public static final class Probe {
        final LoopSection loop;       // identity key for matching across two probes
        public final String script;
        public final int line;
        public final String label;
        public final boolean isWhile;
        public final long maxIter;

        Probe(LoopSection loop, String script, int line, String label, boolean isWhile, long maxIter) {
            this.loop = loop;
            this.script = script;
            this.line = line;
            this.label = label;
            this.isWhile = isWhile;
            this.maxIter = maxIter;
        }
    }

    /**
     * Live read of every tracked loop's counter, for the hang watchdog to call twice and diff: a
     * loop whose counter climbed between two probes during a main-thread freeze is the runaway.
     * Safe to call off-thread — a running loop's counter map holds a single, non-resizing entry.
     */
    public List<Probe> probe() {
        List<Tracked> all;
        synchronized (registry) {
            all = new ArrayList<>(registry.values());
        }
        List<Probe> out = new ArrayList<>(all.size());
        for (Tracked t : all) {
            long max = read(t.loop)[0];
            out.add(new Probe(t.loop, t.script, t.line, t.label, t.isWhile, max));
        }
        return out;
    }
}
