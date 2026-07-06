// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import ch.njol.skript.lang.LoopSection;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import com.google.common.collect.Multimap;
import dev.sktrace.SkTrace;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Central profiler. Tracks two layers:
 *   1. Per-event-class total time, by wrapping Skript's Bukkit RegisteredListeners.
 *      Robust across Skript versions.
 *   2. Per-trigger time, by replacing Trigger instances inside SkriptEventHandler's
 *      internal trigger map with ProfilingTrigger subclass instances.
 *      Best-effort; depends on Skript's internal layout.
 */
public final class Profiler {

    private final SkTrace plugin;

    private final Map<String, TriggerStats> triggerStats = new ConcurrentHashMap<>();
    private final Map<String, EventStats> eventStats = new ConcurrentHashMap<>();
    // Per-function time, a third layer parallel to triggers/events. Functions are
    // wrapped by replacing the body Trigger inside each Skript ScriptFunction. Kept
    // in a separate map (not merged into triggerStats) because a function's time is
    // nested inside its caller's trigger time — mixing them would double-count in the
    // time-by-script donut and worst-tick. Same separation rationale as eventStats.
    private final Map<String, TriggerStats> functionStats = new ConcurrentHashMap<>();
    // Per-line aggregates: triggerId -> (itemIndex -> stats). LinkedHashMap so
    // the report iterates items in source order (the order they were installed).
    private final Map<String, Map<Integer, ItemStats>> itemStats = new ConcurrentHashMap<>();
    // Triggers whose bodies we've already injected tracers into. Avoids double-wrapping
    // when the same Trigger is reached via multiple Skript registries.
    private final Set<Trigger> bodyTracedTriggers = Collections.newSetFromMap(new IdentityHashMap<>());
    // One wrapper per original Trigger, no matter how many containers reference it (the event
    // multimap and the script-structure scan both reach the same Trigger objects). Reusing the
    // wrapper keeps identities consistent across containers and avoids duplicate stats churn.
    private final Map<Trigger, ProfilingTrigger> wrapperCache = new IdentityHashMap<>();
    private final List<BodyRewire> bodyRewires = new ArrayList<>();
    // Per-line body tracing rewrites Skript's internal trigger graph and is the
    // only layer that can perturb a running script, so it's opt-in. Read from
    // config at each start; when false, only the non-invasive event- and
    // trigger-level timing runs. See config.yml `line-level-profiling`.
    private boolean lineLevelTracing;
    // Per-function call timing is passive (a delegating wrapper around each function's
    // body, like the trigger-level wrapping) so it's on by default, but exposed as a
    // config switch so a server hitting a pathological hot function can turn it off.
    // Per-line timing INSIDE functions still rides on lineLevelTracing above.
    private boolean functionProfiling;
    // Per-window global-variable write tracking. Passive: observes Skript's async variable
    // save path (the variables.csv writers) without touching script execution. On by default;
    // see config.yml `variable-tracking`. The tracker is created per start() and kept after
    // stop() so the report can read the collected stats; null when disabled/unavailable.
    private boolean variableTracking;
    private VariableTracker variableTracker;
    // Live observer of running loops (loop/while). Passive: it only reads each LoopSection's
    // iteration counter, never the trigger graph, so it's on by default (config loop-watching).
    // Populated during hook install by walking each trigger body; sampled by the tick task.
    private boolean loopWatching;
    private final LoopWatcher loopWatcher = new LoopWatcher();
    // Throttle for loop sampling: the tick task runs every tick, but loop counters only need a
    // coarse rate, so we sample them once every LOOP_SAMPLE_TICKS ticks (~1s) to keep cost trivial.
    private int loopSampleCounter;
    private static final int LOOP_SAMPLE_TICKS = 20;
    // Per-tick heartbeat (System.nanoTime of the last sampleTick), read off-thread by the hang
    // watchdog to tell whether the main thread is still ticking. Volatile: written on the main
    // thread, read on the watchdog thread. 0 until the first tick after start.
    private volatile long mainHeartbeatNanos;
    // Async detector for a frozen main thread (an infinite no-delay loop). Daemon-threaded so it
    // keeps running while the server is hung; created per start when enabled, null otherwise.
    private boolean hangDetection;
    private HangWatchdog hangWatchdog;
    // Companion to variableTracker: watches variables.csv for Skript's periodic full rewrite
    // (the 5-minute saveTask). Passive (a filesystem WatchService, never touches Skript), gated by
    // the same variable-tracking config, and only active for flat-file storage. Kept after stop()
    // so the report can read the flushes it observed; null when disabled/unavailable.
    private VariableFlushTracker variableFlushTracker;
    // Per-script source content, loaded lazily during install so we can map each
    // tracked item to its source line. Cleared on stop() so a re-install picks up
    // the current state of files on disk.
    private final Map<String, List<String>> scriptSourceCache = new HashMap<>();

    private volatile boolean running = false;
    private volatile boolean rolling = false;
    // True while running but the per-event/trigger/function hooks are temporarily removed
    // for a Skript reload (see suspendForReload/resumeAfterReload). Tick/MSPT sampling and
    // variable tracking keep going through the gap; only the swap-based hooks pause.
    private volatile boolean suspended = false;
    private long startedAtMillis = 0;
    private long stoppedAtMillis = 0;
    // 0 = unbounded (one-shot). >0 = ring buffer size for rolling mode.
    private int tickCapacity = 0;

    // For uninstall: remember which listeners we swapped, so we can put the originals back.
    private final List<ListenerSwap> listenerSwaps = new ArrayList<>();
    // For uninstall: remember which Trigger we replaced, and where, so we can restore.
    private final List<TriggerSwap> triggerSwaps = new ArrayList<>();
    // For uninstall: scheduler-task trigger field swaps (periodic events, etc.)
    private final List<SchedulerSwap> schedulerSwaps = new ArrayList<>();
    // For uninstall: ScriptFunction.trigger field swaps (per-function profiling).
    private final List<SchedulerSwap> functionSwaps = new ArrayList<>();

    private boolean triggerHooksAvailable = true;
    private String triggerHookFailureReason = null;

    // Per-tick aggregation: accumulator reset each server tick by the tick aggregator task.
    // Same dual-mode storage as TriggerStats: unbounded growth in one-shot mode, ring
    // buffer in rolling mode. tickWritePos is the next slot to write; tickSampleCount
    // is # valid entries (capped at tickCapacity when rolling).
    private final AtomicLong currentTickNanos = new AtomicLong();
    private long[] tickSamples = new long[1024];
    // Real wall-clock duration between consecutive sampleTick calls — i.e., true server MSPT.
    // Parallel to tickSamples; first entry is 0 (no baseline yet) and should be ignored by consumers.
    private long[] tickDurationNanos = new long[1024];
    private long lastSampleNanoTime = 0;
    private int tickSampleCount = 0;
    private int tickWritePos = 0;
    private BukkitTask tickAggregator;

    // Self-overhead: total nanoseconds skTrace's own bookkeeping consumes during the window.
    private final AtomicLong selfOverheadNanos = new AtomicLong();

    public Profiler(SkTrace plugin) {
        this.plugin = plugin;
    }

    public synchronized void start() {
        startInternal(0);
    }

    /**
     * Rolling profiler: same hooks as start(), but tick arrays become bounded ring
     * buffers (~windowSeconds × 20 ticks) so the buffer stays trimmed to roughly
     * the last windowSeconds of activity. Used to support /sktrace clip — snapshot
     * the last minute on demand without leaving stats running forever.
     */
    public synchronized void startRolling(int windowSeconds) {
        int cap = Math.max(20, windowSeconds * 20);
        startInternal(cap);
        rolling = true;
    }

    private void startInternal(int tickCapacity) {
        if (running) return;
        this.tickCapacity = tickCapacity;
        triggerStats.clear();
        eventStats.clear();
        functionStats.clear();
        itemStats.clear();
        bodyTracedTriggers.clear();
        wrapperCache.clear();
        bodyRewires.clear();
        listenerSwaps.clear();
        triggerSwaps.clear();
        schedulerSwaps.clear();
        functionSwaps.clear();
        scriptSourceCache.clear();
        variableTracker = null;
        variableFlushTracker = null;
        int initial = tickCapacity > 0 ? tickCapacity : 1024;
        tickSamples = new long[initial];
        tickDurationNanos = new long[initial];
        tickSampleCount = 0;
        tickWritePos = 0;
        lastSampleNanoTime = 0;
        currentTickNanos.set(0);
        selfOverheadNanos.set(0);
        rolling = false;  // startRolling() flips this back on after we return
        suspended = false;
        lineLevelTracing = plugin.getConfig().getBoolean("line-level-profiling", false);
        functionProfiling = plugin.getConfig().getBoolean("function-profiling", true);
        variableTracking = plugin.getConfig().getBoolean("variable-tracking", true);
        loopWatching = plugin.getConfig().getBoolean("loop-watching", true);
        hangDetection = loopWatching && plugin.getConfig().getBoolean("loop-hang-detection", true);
        loopWatcher.clear();
        loopSampleCounter = 0;
        mainHeartbeatNanos = 0;
        installAllHooks(true);
        // Variable tracking is independent of the trigger/function hooks above: it observes the
        // variable persistence path, not the execution graph. Created here so it's ready before
        // the tick aggregator starts sampling its per-tick write series.
        if (variableTracking) {
            variableTracker = new VariableTracker(plugin,
                    Math.max(1, plugin.getConfig().getInt("variable-tracking-max-distinct", 5000)),
                    tickCapacity);
            variableTracker.install();
            // Watch the CSV for Skript's periodic full rewrite. Independent of the queue observer
            // above: that counts in-memory writes; this catches the rewrite to disk and how long it
            // took, so a save can be lined up against a tick-time spike.
            variableFlushTracker = new VariableFlushTracker(plugin, variableTracker);
            variableFlushTracker.install();
        }
        plugin.getLogger().info("Line-level profiling: " + (lineLevelTracing
                ? "ENABLED (experimental — instruments individual lines by rewriting Skript's trigger graph)."
                : "disabled (passive event/trigger timing only). Set line-level-profiling: true in config.yml to enable."));
        // Source cache was only needed for fuzzy line-matching during install.
        // Drop it now so the rolling buffer doesn't hold onto every script's text
        // for the lifetime of the session.
        scriptSourceCache.clear();
        tickAggregator = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleTick, 1L, 1L);
        running = true;
        startedAtMillis = System.currentTimeMillis();
        stoppedAtMillis = 0;
        if (hangDetection && loopWatcher.available()) {
            long freezeMs = Math.max(2, plugin.getConfig().getInt("loop-hang-seconds", 10)) * 1000L;
            hangWatchdog = new HangWatchdog(plugin, this, loopWatcher, freezeMs);
            hangWatchdog.start();
        }
    }

    public synchronized void stop() {
        if (!running) return;
        if (hangWatchdog != null) {
            try { hangWatchdog.stop(); } catch (Throwable ignored) {}
            hangWatchdog = null;
        }
        if (tickAggregator != null) {
            try { tickAggregator.cancel(); } catch (Throwable ignored) {}
            tickAggregator = null;
        }
        uninstallAllHooks();
        // Stop observing variable writes, but keep the tracker (and its collected stats) so a
        // report written after stop can still read them. It's cleared on the next start().
        if (variableTracker != null) variableTracker.uninstall();
        if (variableFlushTracker != null) variableFlushTracker.uninstall();
        running = false;
        rolling = false;
        suspended = false;
        stoppedAtMillis = System.currentTimeMillis();
    }

    /** True while running but the swap-based hooks are paused for a Skript reload. */
    public boolean isSuspended() { return suspended; }

    /**
     * Install/uninstall the swap-based hooks as a unit. These are the layers that replace
     * Skript's Trigger objects (and wrap its Bukkit listeners), so they must be fully
     * removed before Skript unloads scripts on reload and reinstalled against the new
     * generation afterwards. Variable tracking is deliberately excluded — it observes the
     * variable-persistence path, which a reload does not rebuild, so it stays put.
     */
    private void installAllHooks(boolean verbose) {
        installEventHooks();
        installTriggerHooks(verbose);
        // After trigger hooks (functions aren't in the event registries) and before the
        // scheduler/structure scan: once we've replaced a ScriptFunction.trigger with a
        // ProfilingTrigger, the structure scan's "!(v instanceof ProfilingTrigger)" guard
        // naturally skips it, so functions don't get double-wrapped into triggerStats.
        if (functionProfiling) installFunctionHooks();
        installSchedulerHooks();
    }

    private void uninstallAllHooks() {
        uninstallSchedulerHooks();
        uninstallFunctionHooks();
        uninstallBodyTracers();
        uninstallTriggerHooks();
        uninstallEventHooks();
    }

    /**
     * Remove every swap-based hook — restoring Skript's real Triggers and listeners — without
     * stopping the profiler. Called the instant a {@code /sk reload} is seen, BEFORE Skript
     * unloads the old scripts. Skript unregisters each trigger from its internal multimap by
     * identity and only drops the shared Bukkit listener once none remain at that priority; our
     * wrappers fail that identity check, so without restoring first Skript can't find its own
     * triggers, the shared listener leaks, the reload registers a second one, and every event
     * fires twice. Restoring first makes Skript's identity-based cleanup succeed. Idempotent;
     * no-op when not running or already suspended.
     */
    public synchronized void suspendForReload() {
        if (!running || suspended) return;
        uninstallAllHooks();
        suspended = true;
    }

    /**
     * Reinstall the swap-based hooks against the freshly loaded generation. The old generation's
     * per-trigger/function/line stats reference Triggers that no longer exist, so they're cleared
     * before re-hooking (event-class stats and the tick/MSPT series are generation-independent and
     * kept). The swap bookkeeping was already emptied by {@link #suspendForReload()}, so this never
     * replays stale list/set positions against the new map. Idempotent; no-op unless suspended.
     */
    public synchronized void resumeAfterReload() {
        if (!running || !suspended) return;
        triggerStats.clear();
        functionStats.clear();
        itemStats.clear();
        bodyTracedTriggers.clear();
        wrapperCache.clear();
        bodyRewires.clear();
        loopWatcher.clear();
        loopSampleCounter = 0;
        installAllHooks(false);
        // Source text was only needed for line matching during install; drop it so the rolling
        // buffer doesn't retain every script's text (mirrors startInternal).
        scriptSourceCache.clear();
        suspended = false;
        plugin.getLogger().info("[skTrace] Re-synced profiler hooks after Skript reload ("
                + triggerSwaps.size() + " triggers, " + functionSwaps.size() + " functions).");
    }

    private void sampleTick() {
        long t0 = System.nanoTime();
        mainHeartbeatNanos = t0;  // liveness beacon for the hang watchdog (off-thread reader)
        long ns = currentTickNanos.getAndSet(0);
        long durationNs = lastSampleNanoTime == 0 ? 0 : t0 - lastSampleNanoTime;
        lastSampleNanoTime = t0;
        if (tickCapacity > 0) {
            tickSamples[tickWritePos] = ns;
            tickDurationNanos[tickWritePos] = durationNs;
            tickWritePos = (tickWritePos + 1) % tickCapacity;
            if (tickSampleCount < tickCapacity) tickSampleCount++;
        } else {
            if (tickSampleCount >= tickSamples.length) {
                tickSamples = java.util.Arrays.copyOf(tickSamples, tickSamples.length * 2);
                tickDurationNanos = java.util.Arrays.copyOf(tickDurationNanos, tickDurationNanos.length * 2);
            }
            tickSamples[tickSampleCount] = ns;
            tickDurationNanos[tickSampleCount] = durationNs;
            tickSampleCount++;
            tickWritePos = tickSampleCount;
        }
        // Snapshot each trigger's per-tick contribution so the report can break a selected
        // time range down to "which triggers were running during these ticks?"
        for (TriggerStats s : triggerStats.values()) {
            s.snapshotTick();
        }
        for (TriggerStats s : functionStats.values()) {
            s.snapshotTick();
        }
        for (EventStats e : eventStats.values()) {
            e.snapshotTick();
        }
        if (variableTracker != null) variableTracker.snapshotTick();
        if (variableFlushTracker != null) variableFlushTracker.tick();
        // Coarsely sample running-loop counters (~1Hz) so the loops view has rate & age.
        if (loopWatching && ++loopSampleCounter >= LOOP_SAMPLE_TICKS) {
            loopSampleCounter = 0;
            loopWatcher.sample(t0);
        }
        selfOverheadNanos.addAndGet(System.nanoTime() - t0);
    }

    public synchronized long[] tickSamplesCopy() {
        return chronologicalCopy(tickSamples);
    }

    public synchronized long[] tickDurationsCopy() {
        return chronologicalCopy(tickDurationNanos);
    }

    private long[] chronologicalCopy(long[] buf) {
        if (tickCapacity == 0 || tickSampleCount < tickCapacity) {
            return java.util.Arrays.copyOf(buf, tickSampleCount);
        }
        long[] out = new long[tickSampleCount];
        int oldest = tickWritePos;
        int tailLen = tickCapacity - oldest;
        System.arraycopy(buf, oldest, out, 0, tailLen);
        if (oldest > 0) System.arraycopy(buf, 0, out, tailLen, oldest);
        return out;
    }

    /**
     * Best-estimate elapsed window:
     *   - rolling: sum of recorded tick durations (≈ actual wall time of the buffer)
     *   - one-shot running: time since start
     *   - one-shot stopped: time between start and stop
     */
    public long effectiveWindowMs() {
        if (running && rolling) {
            long sumNs = 0;
            long[] durs = tickDurationsCopy();
            for (long d : durs) if (d > 0) sumNs += d;
            if (sumNs > 0) return sumNs / 1_000_000L;
            // Fallback before any duration samples: estimate at 20tps.
            return tickSampleCount * 50L;
        }
        if (running) return System.currentTimeMillis() - startedAtMillis;
        return Math.max(0, stoppedAtMillis - startedAtMillis);
    }

    public boolean isRolling() { return rolling; }

    public synchronized void reset() {
        triggerStats.clear();
        eventStats.clear();
    }

    public boolean isRunning() { return running; }
    public long startedAtMillis() { return startedAtMillis; }
    public long stoppedAtMillis() { return stoppedAtMillis; }
    public boolean triggerHooksAvailable() { return triggerHooksAvailable; }
    public String triggerHookFailureReason() { return triggerHookFailureReason; }

    public Map<String, TriggerStats> triggerStats() { return triggerStats; }
    public Map<String, EventStats> eventStats() { return eventStats; }
    public Map<String, TriggerStats> functionStats() { return functionStats; }
    /** True when loop watching is enabled in config (read live, so it's correct before the
     *  first start() — the runtime {@code loopWatching} flag is only set when profiling begins). */
    public boolean loopWatchingEnabled() { return plugin.getConfig().getBoolean("loop-watching", true); }
    /** True when this Skript build exposes the loop iteration counter we read. */
    public boolean loopWatchingAvailable() { return loopWatcher.available(); }
    /** How many loop/while sections we collected from the current generation's trigger bodies. */
    public int trackedLoopCount() { return loopWatcher.trackedCount(); }
    /** Snapshot of every loop running right now (iteration, rate, age), sorted by iteration desc. */
    public List<LoopWatcher.Reading> loopSnapshot() { return loopWatcher.snapshot(System.nanoTime()); }
    /** Every loop that ran during the window (peak iteration + live running state), for the report. */
    public List<LoopWatcher.Reading> loopReportReadings() { return loopWatcher.reportReadings(System.nanoTime()); }
    /** nanoTime of the last tick sample; read off-thread by the hang watchdog. 0 until first tick. */
    public long mainHeartbeatNanos() { return mainHeartbeatNanos; }
    /** True when the frozen-loop watchdog is enabled (config, read live). */
    public boolean hangDetectionEnabled() {
        return loopWatchingEnabled() && plugin.getConfig().getBoolean("loop-hang-detection", true);
    }

    /** The variable-write tracker for the current/last window, or null when disabled/unavailable. */
    public VariableTracker variableTracker() { return variableTracker; }
    /** The variables.csv full-rewrite watcher for the current/last window, or null when disabled/unavailable. */
    public VariableFlushTracker variableFlushTracker() { return variableFlushTracker; }

    /**
     * Names (as {@link #scriptDisplayName} produces them, matching {@code TriggerStats.scriptName()})
     * of the scripts Skript currently has loaded. Lets a report drop triggers/functions whose script
     * was deleted/disabled/unloaded since they were hooked — otherwise their stats linger (especially
     * in the long-lived rolling buffer, which never clears between clips). Returns {@code null} if
     * Skript's loaded-script set can't be read, so callers fail open and prune nothing.
     */
    public java.util.Set<String> currentlyLoadedScriptNames() {
        try {
            Class<?> loaderClass = Class.forName("ch.njol.skript.ScriptLoader");
            Object res = loaderClass.getMethod("getLoadedScripts").invoke(null);
            if (!(res instanceof Collection<?> col)) return null;
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Object script : col) {
                if (script == null) continue;
                String n = scriptDisplayName(script);
                if (n != null && !n.equals("unknown")) names.add(n);
            }
            return names;
        } catch (Throwable t) {
            return null;
        }
    }
    public Map<Integer, ItemStats> itemStats(String triggerId) {
        Map<Integer, ItemStats> m = itemStats.get(triggerId);
        return m == null ? Collections.emptyMap() : m;
    }

    void recordTrigger(String id, long nanos) {
        long t0 = System.nanoTime();
        TriggerStats s = triggerStats.get(id);
        if (s != null) s.record(nanos);
        currentTickNanos.addAndGet(nanos);
        selfOverheadNanos.addAndGet(System.nanoTime() - t0);
    }


    void recordFunction(String id, long nanos) {
        long t0 = System.nanoTime();
        TriggerStats s = functionStats.get(id);
        if (s != null) s.record(nanos);
        // Deliberately NOT added to currentTickNanos: the trigger that called this
        // function already timed the whole call, so the per-tick Skript total and MSPT
        // must not count it twice. Functions are a nested drill-down, not an extra layer
        // of tick cost.
        selfOverheadNanos.addAndGet(System.nanoTime() - t0);
    }


    void recordEvent(Class<? extends Event> cls, long nanos) {
        long t0 = System.nanoTime();
        eventStats.computeIfAbsent(cls.getName(), k -> new EventStats(k, tickCapacity)).record(nanos);
        selfOverheadNanos.addAndGet(System.nanoTime() - t0);
    }

    public long selfOverheadNanos() { return selfOverheadNanos.get(); }

    // ---------- Event-level hooks (Bukkit RegisteredListener wrapping) ----------

    private void installEventHooks() {
        Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
        if (skript == null) {
            plugin.getLogger().warning("Skript plugin not found; nothing to profile.");
            return;
        }

        for (HandlerList hl : HandlerList.getHandlerLists()) {
            for (RegisteredListener rl : hl.getRegisteredListeners()) {
                if (rl.getPlugin() != skript) continue;
                try {
                    RegisteredListener wrapped = wrapListener(rl);
                    hl.unregister(rl);
                    hl.register(wrapped);
                    listenerSwaps.add(new ListenerSwap(hl, rl, wrapped));
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to wrap a Skript listener: " + t.getMessage(), t);
                }
            }
        }

        plugin.getLogger().info("Installed event-level hooks on " + listenerSwaps.size() + " Skript listeners.");
    }

    private RegisteredListener wrapListener(RegisteredListener rl) throws ReflectiveOperationException {
        Field executorField = RegisteredListener.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        EventExecutor original = (EventExecutor) executorField.get(rl);

        boolean ignoreCancelled;
        try {
            Field ic = RegisteredListener.class.getDeclaredField("ignoreCancelled");
            ic.setAccessible(true);
            ignoreCancelled = ic.getBoolean(rl);
        } catch (NoSuchFieldException nsfe) {
            ignoreCancelled = false;
        }

        EventPriority priority = rl.getPriority();

        EventExecutor profilingExec = (listener, event) -> {
            long start = System.nanoTime();
            try {
                original.execute(listener, event);
            } finally {
                recordEvent(event.getClass(), System.nanoTime() - start);
            }
        };

        return new RegisteredListener(rl.getListener(), profilingExec, priority, rl.getPlugin(), ignoreCancelled);
    }

    private void uninstallEventHooks() {
        for (ListenerSwap swap : listenerSwaps) {
            try {
                // Only swap back if our wrapped listener is still registered. If Skript already
                // unregistered it (its own unregister matches on the Listener object, which the
                // wrapper shares), re-registering the original would add a duplicate handler.
                boolean present = false;
                for (RegisteredListener rl : swap.handlerList.getRegisteredListeners()) {
                    if (rl == swap.wrapped) { present = true; break; }
                }
                if (!present) continue;
                swap.handlerList.unregister(swap.wrapped);
                swap.handlerList.register(swap.original);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to restore listener: " + t.getMessage(), t);
            }
        }
        listenerSwaps.clear();
    }

    // ---------- Trigger-level hooks (Skript reflection) ----------

    @SuppressWarnings("unchecked")
    private void installTriggerHooks(boolean verbose) {
        try {
            Class<?> handlerClass = Class.forName("ch.njol.skript.SkriptEventHandler");
            if (verbose) plugin.getLogger().info("[skTrace] Scanning ch.njol.skript.SkriptEventHandler for trigger registries...");

            int hookedBefore = triggerSwaps.size();
            for (Field f : handlerClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(null);
                } catch (Throwable t) {
                    if (verbose) plugin.getLogger().info("[skTrace]   " + f.getName() + ": (inaccessible)");
                    continue;
                }
                if (verbose) {
                    String summary = describeValue(value);
                    plugin.getLogger().info("[skTrace]   " + f.getName() + " : "
                            + f.getType().getSimpleName() + " = " + summary);
                }
                if (value == null) continue;
                int n = tryHookContainer(value, "SkriptEventHandler." + f.getName());
                if (n > 0 && verbose) plugin.getLogger().info("[skTrace]     -> hooked " + n + " triggers here");
            }

            int hooked = triggerSwaps.size() - hookedBefore;
            if (verbose) plugin.getLogger().info("[skTrace] Total triggers hooked: " + hooked);

            if (hooked == 0) {
                triggerHooksAvailable = false;
                triggerHookFailureReason = "No Trigger objects found inside SkriptEventHandler. "
                        + "Run /sktrace diag and share the console output.";
                plugin.getLogger().warning("[skTrace] " + triggerHookFailureReason);
            }
        } catch (Throwable t) {
            triggerHooksAvailable = false;
            triggerHookFailureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            plugin.getLogger().log(Level.WARNING,
                    "Per-trigger profiling disabled (Skript internals differ from expected layout). "
                            + "Per-event profiling still works.", t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int tryHookContainer(Object value, String path) {
        if (value == null) return 0;
        if (value instanceof Multimap<?, ?>) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Multimap<Object, Object> mm = (Multimap) value;
            int total = 0;
            // Snapshot keys to avoid concurrent-modification during iteration
            for (Object key : new ArrayList<>(mm.keySet())) {
                Collection<?> values = mm.get(key);
                int before = triggerSwaps.size();
                if (values instanceof List<?> list) {
                    hookTriggerList((List<Object>) list, path + "[" + safeKey(key) + "]");
                } else if (values instanceof Set<?> set) {
                    hookTriggerSet((Set<Object>) set, path + "[" + safeKey(key) + "]");
                }
                total += triggerSwaps.size() - before;
            }
            return total;
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return 0;
            int before = triggerSwaps.size();
            hookTriggerList((List<Object>) list, path);
            return triggerSwaps.size() - before;
        }
        if (value instanceof Map<?, ?> map) {
            int total = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                total += tryHookContainer(e.getValue(), path + "[" + safeKey(e.getKey()) + "]");
            }
            return total;
        }
        if (value instanceof Set<?> set) {
            return hookTriggerSet((Set<Object>) set, path);
        }
        if (value instanceof Collection<?> col) {
            int found = 0;
            for (Object o : col) if (unwrapTrigger(o) != null) found++;
            if (found > 0) {
                plugin.getLogger().info("[skTrace]     (found " + found
                        + " trigger-like entries in " + path + " but cannot replace in this collection type)");
            }
            return 0;
        }
        return 0;
    }

    private int hookTriggerSet(Set<Object> set, String path) {
        // Snapshot, find wrappable triggers, remove originals and add wrappers.
        List<Object> snapshot = new ArrayList<>(set);
        int hooked = 0;
        for (Object entry : snapshot) {
            Trigger original = unwrapTrigger(entry);
            if (original == null || original instanceof ProfilingTrigger) continue;
            try {
                ProfilingTrigger replacement = wrapTrigger(original);
                if (replacement == null) continue;
                triggerStats.putIfAbsent(replacement.id(), buildStats(replacement.id(), original));
                if (entry == original) {
                    set.remove(entry);
                    set.add(replacement);
                    triggerSwaps.add(new TriggerSwap(null, set, null, null, entry, replacement));
                } else {
                    // Container entry holds the Trigger in a field — swap the field in place.
                    Field f = findTriggerField(entry);
                    if (f == null) continue;
                    f.set(entry, replacement);
                    triggerSwaps.add(new TriggerSwap(null, null, f, entry, original, replacement));
                }
                hooked++;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[skTrace] Set wrap failed: " + t.getMessage());
            }
        }
        return hooked;
    }

    private String describeValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Multimap<?, ?> mm) {
            String sample = "";
            if (!mm.isEmpty()) {
                var e = mm.entries().iterator().next();
                sample = ", sample=" + e.getKey().getClass().getSimpleName()
                        + "->" + (e.getValue() == null ? "null" : e.getValue().getClass().getSimpleName());
            }
            return v.getClass().getSimpleName() + "(size=" + mm.size() + sample + ")";
        }
        if (v instanceof Collection<?> c) {
            String sample = "";
            if (!c.isEmpty()) {
                Object first = c.iterator().next();
                sample = ", sample=" + (first == null ? "null" : first.getClass().getSimpleName());
            }
            return v.getClass().getSimpleName() + "(size=" + c.size() + sample + ")";
        }
        if (v instanceof Map<?, ?> m) {
            String sampleK = "", sampleV = "";
            if (!m.isEmpty()) {
                var e = m.entrySet().iterator().next();
                sampleK = e.getKey() == null ? "null" : e.getKey().getClass().getSimpleName();
                sampleV = e.getValue() == null ? "null" : e.getValue().getClass().getSimpleName();
            }
            return v.getClass().getSimpleName() + "(size=" + m.size() + ", sample=" + sampleK + "->" + sampleV + ")";
        }
        return v.getClass().getSimpleName();
    }

    private String safeKey(Object k) {
        if (k == null) return "null";
        String s = k.toString();
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }

    /**
     * Public diagnostic — dumps SkriptEventHandler structure to console for inspection.
     * Does not modify state.
     */
    public void runDiagnostic() {
        try {
            Class<?> handlerClass = Class.forName("ch.njol.skript.SkriptEventHandler");
            plugin.getLogger().info("[skTrace diag] === SkriptEventHandler static fields ===");
            for (Field f : handlerClass.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    plugin.getLogger().info("[skTrace diag] " + f.getName() + " : "
                            + f.getType().getSimpleName() + " = " + describeValue(v));
                    if (v instanceof Multimap<?, ?> mm && !mm.isEmpty()) {
                        var e = mm.entries().iterator().next();
                        plugin.getLogger().info("  first entry: " + safeKey(e.getKey()) + " -> " + describeValue(e.getValue()));
                        dumpEntryFields(e.getValue(), "    ");
                    } else if (v instanceof Collection<?> col && !col.isEmpty()) {
                        Object first = col.iterator().next();
                        dumpEntryFields(first, "  ");
                    } else if (v instanceof Map<?, ?> m && !m.isEmpty()) {
                        var e = m.entrySet().iterator().next();
                        plugin.getLogger().info("  first entry: " + safeKey(e.getKey()) + " -> " + describeValue(e.getValue()));
                        if (e.getValue() instanceof Collection<?> innerCol && !innerCol.isEmpty()) {
                            dumpEntryFields(innerCol.iterator().next(), "    ");
                        }
                    }
                } catch (Throwable t) {
                    plugin.getLogger().info("[skTrace diag] " + f.getName() + ": (failed: " + t.getMessage() + ")");
                }
            }
            plugin.getLogger().info("[skTrace diag] === end ===");
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[skTrace diag] failed: " + t.getMessage(), t);
        }
    }

    private void dumpEntryFields(Object entry, String indent) {
        if (entry == null) return;
        plugin.getLogger().info(indent + "entry type: " + entry.getClass().getName());
        Class<?> c = entry.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                plugin.getLogger().info(indent + "  " + c.getSimpleName() + "." + f.getName()
                        + " : " + f.getType().getSimpleName());
            }
            c = c.getSuperclass();
        }
    }

    private void hookTriggerList(List<Object> list, Object containerKey) {
        for (int i = 0; i < list.size(); i++) {
            Object entry = list.get(i);
            Trigger original = unwrapTrigger(entry);
            if (original == null || original instanceof ProfilingTrigger) continue;
            try {
                ProfilingTrigger replacement = wrapTrigger(original);
                if (replacement == null) continue;
                triggerStats.putIfAbsent(replacement.id(), buildStats(replacement.id(), original));
                if (entry == original) {
                    list.set(i, replacement);
                    triggerSwaps.add(new TriggerSwap(list, null, null, null, entry, replacement));
                } else {
                    // Container entry holds the Trigger in a field — swap the field in place.
                    Field f = findTriggerField(entry);
                    if (f == null) continue;
                    f.set(entry, replacement);
                    triggerSwaps.add(new TriggerSwap(null, null, f, entry, original, replacement));
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE,
                        "Could not wrap trigger #" + i + " (" + describe(original) + "): " + t.getMessage());
            }
        }
    }

    /**
     * The list may contain bare Triggers, or TriggerContainer-style wrappers that hold a Trigger inside.
     * Find a Trigger field; if the entry IS a Trigger, return it.
     */
    private Trigger unwrapTrigger(Object entry) {
        if (entry instanceof Trigger t) return t;
        // Walk declared fields looking for a Trigger-typed field
        Class<?> c = entry.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Trigger.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return (Trigger) f.get(entry);
                    } catch (ReflectiveOperationException ignored) { }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** The first Trigger-typed field on a container entry (walking up the hierarchy), or null. */
    private static Field findTriggerField(Object entry) {
        Class<?> c = entry.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Trigger.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private ProfilingTrigger wrapTrigger(Trigger t) throws ReflectiveOperationException {
        ProfilingTrigger cached = wrapperCache.get(t);
        if (cached != null) return cached;
        // Modern Skript stores the trigger body as a linked list (first/last + next),
        // not a List<TriggerItem>. We don't reconstruct it - ProfilingTrigger is a thin
        // wrapper that delegates execute() back to the original.
        Object script = readFieldByType(t, "org.skriptlang.skript.lang.script.Script");
        String name = (String) readFieldByType(t, String.class);
        SkriptEvent event = (SkriptEvent) readFieldByType(t, SkriptEvent.class);

        if (event == null) return null;

        String id = describe(t);
        ProfilingTrigger wrapper = new ProfilingTrigger(
                (org.skriptlang.skript.lang.script.Script) script,
                name == null ? "trigger" : name,
                event,
                t,
                this,
                id
        );
        // Per-line tracing is the only instrumentation that rewrites Skript's
        // execution graph, so it's gated behind an explicit opt-in. Off by default
        // the profiler stays purely passive (event/trigger timing only) and cannot
        // disturb a running script.
        if (lineLevelTracing) {
            installBodyTracers(t, id);
        }
        // Read-only graph walk to find loop/while sections (independent of lineLevelTracing).
        collectLoops(t);
        wrapperCache.put(t, wrapper);
        return wrapper;
    }

    private TriggerStats buildStats(String id, Trigger original) {
        String script = "unknown";
        String name = bestLabel(original);
        int line = -1;
        try {
            Object scriptObj = readFieldByType(original, "org.skriptlang.skript.lang.script.Script");
            if (scriptObj != null) script = scriptDisplayName(scriptObj);
            line = triggerLine(original);
        } catch (Throwable ignored) { }
        return new TriggerStats(id, script, name, line, tickCapacity);
    }

    private String describe(Trigger t) {
        try {
            Object script = readFieldByType(t, "org.skriptlang.skript.lang.script.Script");
            return scriptDisplayName(script) + "#" + bestLabel(t);
        } catch (Throwable e) {
            return "trigger@" + System.identityHashCode(t);
        }
    }

    /**
     * Best human label for a trigger. Normally this is {@link #triggerDisplayName} (the event's
     * own toString plus any parsed identifiers). But Skript's {@code SimpleEvent} — used by
     * placeholder events (skript-placeholders), SkBee custom events, and many addons — has a
     * hardcoded {@code toString()} of "simple event" with no identifier, so every such trigger
     * would show (and collide) as "simple event". For those, fall back to the actual line the user
     * wrote: the script's source line at the trigger's header, then Skript's own debug label.
     */
    private String bestLabel(Trigger t) {
        String base = triggerDisplayName(t);
        if (!isGenericLabel(base)) return base;
        String src = sourceLineLabel(t);   // the literal line the user wrote — cleanest and unique
        if (src != null) return src;
        try {
            String dbg = t.getDebugLabel();
            if (!isGenericLabel(dbg)) return cleanEventLine(dbg);
        } catch (Throwable ignored) { }
        return base;
    }

    /** Uninformative trigger labels we should try to replace with the real source line. */
    private static boolean isGenericLabel(String s) {
        if (s == null) return true;
        String x = s.trim();
        return x.isEmpty() || x.equalsIgnoreCase("simple event") || x.equalsIgnoreCase("trigger");
    }

    /** The script's source line at the trigger's header, cleaned for display, or null if unavailable. */
    private String sourceLineLabel(Trigger t) {
        try {
            int line = triggerLine(t);
            if (line <= 0) return null;
            Object scriptObj = readFieldByType(t, "org.skriptlang.skript.lang.script.Script");
            List<String> lines = loadScriptLines(scriptDisplayName(scriptObj));
            if (line - 1 < 0 || line - 1 >= lines.size()) return null;
            String label = cleanEventLine(lines.get(line - 1));
            if (label == null) return null;
            return label.length() > 80 ? label.substring(0, 80) + "..." : label;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Trim a raw script line into a readable event label: drop an inline comment and trailing colon. */
    private static String cleanEventLine(String raw) {
        if (raw == null) return null;
        boolean inStr = false;
        int hash = -1;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') inStr = !inStr;
            else if (c == '#' && !inStr) { hash = i; break; }
        }
        String s = (hash >= 0 ? raw.substring(0, hash) : raw).trim();
        while (s.endsWith(":")) s = s.substring(0, s.length() - 1).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Best display name for a Trigger.
     *
     * Many addon SkriptEvent implementations (SkBee's "simple event", custom
     * placeholder events, etc.) return a hardcoded generic string from
     * toString() — e.g. every PAPI placeholder shows as "simple event"
     * regardless of which identifier was parsed. The parsed identifier is
     * stored in the SkriptEvent subclass's own fields. We do three things:
     *   1. Take Trigger.name or event.toString() as a base.
     *   2. Walk the event subclass's instance fields and pull out any String /
     *      Literal&lt;String|Number&gt; values — those are typically the parsed
     *      identifiers.
     *   3. Append them to the base, unless they're already in it.
     *
     * This also distinguishes triggers that would otherwise collide in
     * describe()'s ID (two placeholders named differently but both saying
     * "simple event"), preventing stats from being merged across them.
     */
    private static String triggerDisplayName(Trigger t) {
        SkriptEvent event = null;
        try {
            event = (SkriptEvent) readFieldByType(t, SkriptEvent.class);
        } catch (Throwable ignored) { }

        String base = null;
        if (event != null) {
            try {
                String s = event.toString(null, false);
                if (s != null && !s.isEmpty()) base = s;
            } catch (Throwable ignored) { }
        }
        if (base == null) {
            try {
                Object name = readFieldByType(t, String.class);
                if (name instanceof String str && !str.isEmpty()) base = str;
            } catch (Throwable ignored) { }
        }
        if (base == null) base = "trigger";

        if (event != null) {
            String extra = extractEventArgs(event);
            if (extra != null && !extra.isEmpty() && !base.contains(extra)) {
                base = base + " " + extra;
            }
        }
        return base;
    }

    /**
     * Walk the SkriptEvent subclass's own instance fields (not the
     * ch.njol.skript.lang.SkriptEvent base — its fields are framework state,
     * not user identifiers) and stringify anything that looks like a parsed
     * argument. Capped at 80 chars to keep labels readable.
     */
    private static String extractEventArgs(SkriptEvent event) {
        Class<?> stopAt = null;
        try { stopAt = Class.forName("ch.njol.skript.lang.SkriptEvent"); } catch (Throwable ignored) { }
        StringBuilder out = new StringBuilder();
        Class<?> c = event.getClass();
        while (c != null && c != Object.class && c != stopAt) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(event);
                    if (v == null) continue;
                    String s = stringifyForLabel(v);
                    if (s == null || s.isEmpty()) continue;
                    if (out.length() > 0) out.append(' ');
                    out.append(s);
                    if (out.length() > 80) return out.toString();
                } catch (Throwable ignored) { }
            }
            c = c.getSuperclass();
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String stringifyForLabel(Object v) {
        if (v instanceof String s) {
            if (s.isEmpty() || s.length() > 60) return null;
            return "\"" + s + "\"";
        }
        if (v instanceof Number) return v.toString();
        String cn = v.getClass().getName();
        // Skript Literal types expose getSingle() with no args. Expression types
        // need an Event to resolve — we don't have one at install time, so skip.
        if (cn.contains("Literal") || cn.contains("VariableString")) {
            try {
                java.lang.reflect.Method m = v.getClass().getMethod("getSingle");
                Object res = m.invoke(v);
                if (res instanceof String s && !s.isEmpty() && s.length() <= 60) return "\"" + s + "\"";
                if (res instanceof Number n) return n.toString();
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private String scriptDisplayName(Object script) {
        if (script == null) return "unknown";
        // Modern Skript: script.getConfig().getFileName()
        Object config = invokeNoArg(script, "getConfig");
        if (config != null) {
            Object fileName = invokeNoArg(config, "getFileName");
            if (fileName instanceof String s && !s.isEmpty()) return s;
            Object file = invokeNoArg(config, "getFile");
            if (file != null) {
                Object name = invokeNoArg(file, "getName");
                if (name instanceof String s && !s.isEmpty()) return s;
            }
        }
        // Older Skript: script.getFile().getName()
        Object file = invokeNoArg(script, "getFile");
        if (file != null) {
            Object name = invokeNoArg(file, "getName");
            if (name instanceof String s && !s.isEmpty()) return s;
        }
        Object name = invokeNoArg(script, "getName");
        if (name instanceof String s && !s.isEmpty()) return s;
        return script.toString();
    }

    private static Object invokeNoArg(Object o, String method) {
        if (o == null) return null;
        try {
            return o.getClass().getMethod(method).invoke(o);
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------- Per-line body tracers (insert TraceItems around top-level body items) ----------

    /**
     * Inject pre/post TraceItems around every top-level body item of {@code trigger}.
     * Idempotent per trigger; safe to call twice (second call is a no-op).
     *
     * Top-level only for v1: nested items inside sections (loop bodies, if-bodies)
     * are not individually timed. The section itself is timed as a single line, so
     * its time includes everything inside it. This is the natural granularity for
     * "which top-level line of my trigger is slow?" without the complexity (and
     * overhead) of recursing into deep structures.
     *
     * For top-level items that ARE sections, we also redirect any internal
     * "exit pointer" inside their body — Skript wires each section's
     * body's last child's {@code next} to {@code section.next}, so when the body
     * finishes control jumps directly out. Without re-aiming those at our post
     * tracer we'd never close the timing for the section.
     */
    // Per-trigger ceilings so a pathological trigger body (cycles, shared subgraphs
    // from addons like SkBee's "simple event", millions of generated statements)
    // can't take down the server. Per-line tracing is best-effort — silently bail
    // for triggers that exceed these bounds and the trigger-level timing still works.
    private static final int MAX_TRACED_UNITS = 20_000;   // total tracer pairs per trigger
    private static final int MAX_TRACE_DEPTH = 12;         // loop-nesting depth we descend into
    private static final int MAX_REACHABLE_ITEMS = 50_000;

    private void installBodyTracers(Trigger trigger, String triggerId) {
        if (trigger == null) return;
        if (!bodyTracedTriggers.add(trigger)) return;
        BodyRewire rewire = new BodyRewire();
        try {
            Field firstField = TriggerSection.class.getDeclaredField("first");
            firstField.setAccessible(true);
            Field nextField = TriggerItem.class.getDeclaredField("next");
            nextField.setAccessible(true);

            TriggerItem firstItem = (TriggerItem) firstField.get(trigger);
            if (firstItem == null) return;

            int headerLine = -1;
            String scriptName = "unknown";
            try {
                headerLine = triggerLine(trigger);
                Object scriptObj = readFieldByType(trigger, "org.skriptlang.skript.lang.script.Script");
                if (scriptObj != null) scriptName = scriptDisplayName(scriptObj);
            } catch (Throwable ignored) { }

            // Reverse index of ORIGINAL next-pointers (captured before any rewiring) so a
            // plain section's body-exit can be redirected to its post tracer at any nesting
            // level. Bail if the reachable set explodes — best-effort guard.
            Set<TriggerItem> allItems = Collections.newSetFromMap(new IdentityHashMap<>());
            if (!collectReachable(trigger, firstField, nextField, allItems, MAX_REACHABLE_ITEMS)) {
                plugin.getLogger().fine("[skTrace] Skipping per-line tracing for "
                        + triggerId + ": > " + MAX_REACHABLE_ITEMS + " reachable items");
                return;
            }
            IdentityHashMap<TriggerItem, List<TriggerItem>> backRefs = new IdentityHashMap<>();
            for (TriggerItem item : allItems) {
                TriggerItem n = (TriggerItem) nextField.get(item);
                if (n == null) continue;
                backRefs.computeIfAbsent(n, k -> new ArrayList<>()).add(item);
            }

            TraceCtx ctx = new TraceCtx(firstField, nextField, loadScriptLines(scriptName),
                    rewire, backRefs, headerLine > 0 ? headerLine : 0);

            // Recursively splice tracers through the body, descending into loop bodies so
            // per-iteration line cost is captured — not just the loop as a single unit.
            traceContainer(ctx, trigger, firstItem, 0);

            if (ctx.bailed) {
                restoreRewire(rewire);
                plugin.getLogger().fine("[skTrace] Per-line tracing bailed for " + triggerId
                        + " (cycle/cap/anomaly); per-trigger timing still active.");
                return;
            }
            if (ctx.stats.isEmpty()) { restoreRewire(rewire); return; }
            itemStats.put(triggerId, ctx.stats);
            bodyRewires.add(rewire);
            plugin.getLogger().log(Level.FINE,
                    "[skTrace] Installed per-line tracers on " + triggerId
                            + " (" + ctx.stats.size() + " units, recursed into loops)");
        } catch (Throwable t) {
            // Undo any partial rewiring so the trigger runs exactly as before.
            try { rewire.restoreAll(); } catch (Throwable ignored) { }
            plugin.getLogger().log(Level.WARNING,
                    "[skTrace] Per-line tracer install failed for " + triggerId
                            + ": " + t.getMessage(), t);
        }
    }

    /** Mutable state threaded through the recursive {@link #traceContainer} descent. */
    private static final class TraceCtx {
        final Field firstField, nextField;
        final List<String> srcLines;
        final BodyRewire rewire;
        final IdentityHashMap<TriggerItem, List<TriggerItem>> backRefs;
        final Map<Integer, ItemStats> stats = new LinkedHashMap<>();
        final Set<TriggerItem> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        // Items whose next we've already repointed, so a plain-section body-exit rewrite
        // never double-redirects one.
        final Set<TriggerItem> touchedNext = Collections.newSetFromMap(new IdentityHashMap<>());
        int nextIdx = 0;     // unique stats-slot id (display orders by source line, not this)
        int cursor;          // forward position into srcLines for in-order line matching
        boolean bailed = false;
        TraceCtx(Field firstField, Field nextField, List<String> srcLines, BodyRewire rewire,
                 IdentityHashMap<TriggerItem, List<TriggerItem>> backRefs, int cursor) {
            this.firstField = firstField;
            this.nextField = nextField;
            this.srcLines = srcLines;
            this.rewire = rewire;
            this.backRefs = backRefs;
            this.cursor = cursor;
        }
    }

    /**
     * Splice START/END tracers around the direct children of {@code container} (a trigger
     * or a section) and recurse into nested LOOP sections so per-iteration line cost is
     * captured. Conditionals and plain (non-loop) sections are timed as whole units and
     * are NOT descended into — that keeps us clear of the conditional-clause wiring
     * hazards. Sets {@code container.first} to the first START tracer. On any cycle, cap,
     * or anomaly it sets {@code ctx.bailed}; the caller then restores every change so the
     * trigger runs exactly as before (only per-trigger timing remains).
     */
    private void traceContainer(TraceCtx ctx, TriggerItem container, TriggerItem firstChild, int depth)
            throws ReflectiveOperationException {
        if (ctx.bailed || firstChild == null || depth > MAX_TRACE_DEPTH) return;

        // 1. Walk children at this level. A loop's body ends when the chain points back at
        //    the container (its self-edge) — that's the natural end, not a cycle. Loops are
        //    advanced via actualNext (nextAtTopLevel) so we step over, not into, them here.
        List<TriggerItem> children = new ArrayList<>();
        TriggerItem cur = firstChild;
        while (cur != null && cur != container) {
            if (!ctx.seen.add(cur)) { ctx.bailed = true; return; }   // genuine cycle / shared node
            if (children.size() >= MAX_TRACED_UNITS || ctx.nextIdx >= MAX_TRACED_UNITS) { ctx.bailed = true; return; }
            children.add(cur);
            cur = nextAtTopLevel(cur, ctx.nextField);
        }
        if (children.isEmpty()) return;

        // 2. Group consecutive conditional clauses into single units (never split if/else).
        List<List<TriggerItem>> units = new ArrayList<>();
        for (int i = 0; i < children.size(); ) {
            int j = i + 1;
            if (isConditional(children.get(i))) {
                while (j < children.size()
                        && isConditional(children.get(j))
                        && !isConditionalIf(children.get(j))) {
                    j++;
                }
            }
            units.add(new ArrayList<>(children.subList(i, j)));
            i = j;
        }

        int n = units.size();
        TraceItem[] pres = new TraceItem[n];
        TraceItem[] posts = new TraceItem[n];
        int[] idxs = new int[n];
        // Pass 1: build tracer pairs (source-line matching happens in pass 2, in pre-order).
        for (int u = 0; u < n; u++) {
            int idx = ctx.nextIdx++;
            idxs[u] = idx;
            long key = TraceItem.newKey();
            pres[u] = new TraceItem(true, key, idx);
            posts[u] = new TraceItem(false, key, idx);
            pres[u].setRealNext(units.get(u).get(0));
        }

        // Install this container's new first (recorded for restore / rollback).
        ctx.rewire.setFirst(ctx.firstField, container, pres[0]);

        // Pass 2: match lines (pre-order: header, then its body, then next sibling), wire
        // each unit's exit through its post, and descend into loop bodies.
        for (int u = 0; u < n; u++) {
            if (ctx.bailed) return;
            List<TriggerItem> unit = units.get(u);
            TriggerItem head = unit.get(0);
            TriggerItem tail = unit.get(unit.size() - 1);
            TraceItem post = posts[u];

            String label = labelFor(head);
            ItemStats slot = new ItemStats(idxs[u], label, matchLineForward(label, ctx));
            ctx.stats.put(idxs[u], slot);
            post.setStats(slot);

            TriggerItem originalExit;
            if (tail instanceof LoopSection loop) {
                // Time the loop as a unit via its actualNext exit (never the self-edge).
                originalExit = loop.getActualNext();
                ctx.rewire.setLoopExit(loop, post);
            } else {
                originalExit = (TriggerItem) ctx.nextField.get(tail);
                ctx.rewire.setNextField(ctx.nextField, tail, post);
                ctx.touchedNext.add(tail);
                // Plain (non-loop, non-conditional) section: redirect internal body-exit
                // pointers to post so the body-finished path is timed and continues. Skipped
                // for conditionals (Skript routes their body exit through their own next,
                // which we just set) and loops (handled via actualNext above).
                if (unit.size() == 1 && head instanceof TriggerSection
                        && !isConditional(head) && originalExit != null) {
                    List<TriggerItem> referrers = ctx.backRefs.get(originalExit);
                    if (referrers != null) {
                        for (TriggerItem inner : referrers) {
                            if (inner == head) continue;
                            if (!ctx.touchedNext.add(inner)) continue;
                            ctx.rewire.setNextField(ctx.nextField, inner, post);
                        }
                    }
                }
            }
            // Non-last units continue to the next sibling's START; the last unit continues
            // to the body's real exit (null for a trigger, the loop itself for a loop body).
            post.setRealNext(u + 1 < n ? pres[u + 1] : originalExit);

            // Descend ONLY into loops — that's where per-iteration cost hides. Isolated:
            // if deepening this loop fails for any reason, undo just its body tracing and
            // keep it as a single timed unit (the proven fallback). Everything else stands.
            if (unit.size() == 1 && head instanceof LoopSection loop) {
                int undoMark = ctx.rewire.mark();
                int idxMark = ctx.nextIdx;
                try {
                    traceContainer(ctx, loop, (TriggerItem) ctx.firstField.get(loop), depth + 1);
                } catch (Throwable t) {
                    ctx.bailed = true;
                }
                if (ctx.bailed) {
                    ctx.rewire.rollbackTo(undoMark);
                    for (int k = idxMark; k < ctx.nextIdx; k++) ctx.stats.remove(k);
                    ctx.nextIdx = idxMark;
                    ctx.bailed = false;
                    plugin.getLogger().fine("[skTrace] Kept loop as a single unit (couldn't deepen its body) at depth " + depth);
                }
            }
        }
    }

    /**
     * Find the next source line (from the forward cursor) whose normalized text matches
     * {@code label}; advances the cursor past it. Pre-order traversal keeps the cursor in
     * source order across nesting. Returns the 1-indexed line, or -1 if unmatched.
     */
    private int matchLineForward(String label, TraceCtx ctx) {
        List<String> srcLines = ctx.srcLines;
        if (srcLines.isEmpty()) return -1;
        String needle = normalizeForMatch(label);
        if (needle.isEmpty()) return -1;
        for (int li = ctx.cursor; li < srcLines.size(); li++) {
            String have = normalizeForMatch(srcLines.get(li));
            if (have.isEmpty()) continue;
            if (matchesLabel(have, needle)) {
                ctx.cursor = li + 1;
                return li + 1;
            }
        }
        return -1;
    }

    /** Undo every graph change recorded in a rewire (used on bail and on stop). */
    private void restoreRewire(BodyRewire r) {
        r.restoreAll();
    }

    // SecConditional.type field, resolved once. null if Skript's layout changed.
    private volatile Field secCondTypeField;
    private volatile boolean secCondTypeFieldResolved;

    /** True if {@code item} is (or extends) Skript's SecConditional. */
    private static boolean isConditional(TriggerItem item) {
        for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("ch.njol.skript.sections.SecConditional")) return true;
        }
        return false;
    }

    /**
     * True only when we can positively confirm {@code item} is a leading `if`
     * (ConditionalType.IF) — the start of a new conditional construct rather than
     * a then/else-if/else continuation. Returns false when the type can't be read,
     * so callers keep the clause grouped with the current construct: the safe,
     * never-break-a-chain default.
     */
    private boolean isConditionalIf(TriggerItem item) {
        try {
            Field f = secCondTypeField(item);
            if (f == null) return false;
            Object type = f.get(item);
            return type != null && "IF".equals(type.toString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Field secCondTypeField(TriggerItem item) {
        if (!secCondTypeFieldResolved) {
            Field found = null;
            for (Class<?> c = item.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.getName().equals("ch.njol.skript.sections.SecConditional")) {
                    try {
                        found = c.getDeclaredField("type");
                        found.setAccessible(true);
                    } catch (Throwable ignored) {
                        found = null;
                    }
                    break;
                }
            }
            secCondTypeField = found;
            secCondTypeFieldResolved = true;
        }
        return secCondTypeField;
    }

    /**
     * The next sibling at the current nesting level. For a {@link LoopSection} this is
     * its {@code actualNext} (the loop's own {@code next} field points back at itself
     * for iteration), so following it advances past the loop instead of spinning on the
     * self-edge. For everything else it's the plain {@code next} field.
     */
    private static TriggerItem nextAtTopLevel(TriggerItem item, Field nextField)
            throws ReflectiveOperationException {
        if (item instanceof LoopSection loop) return loop.getActualNext();
        return (TriggerItem) nextField.get(item);
    }

    // ---------- Loop collection (read-only graph walk for the loops view) ----------

    /**
     * Walk a trigger body and register every loop/while section with the {@link LoopWatcher}.
     * Read-only: it follows the same first/next graph the per-line tracer does but never rewrites
     * it, so it's safe to run unconditionally (not gated behind line-level-profiling). Best-effort —
     * bails silently on any reflective mismatch or an oversized body. Called once per unique trigger
     * (wrapTrigger dedups via wrapperCache) and once per function.
     */
    private void collectLoops(Trigger original) {
        if (!loopWatching || original == null || !loopWatcher.available()) return;
        try {
            Field firstField = TriggerSection.class.getDeclaredField("first");
            firstField.setAccessible(true);
            Field nextField = TriggerItem.class.getDeclaredField("next");
            nextField.setAccessible(true);

            Set<TriggerItem> all = Collections.newSetFromMap(new IdentityHashMap<>());
            if (!collectReachable(original, firstField, nextField, all, MAX_REACHABLE_ITEMS)) return;

            String script = "unknown";
            Object scriptObj = readFieldByType(original, "org.skriptlang.skript.lang.script.Script");
            if (scriptObj != null) script = scriptDisplayName(scriptObj);

            for (TriggerItem item : all) {
                if (!(item instanceof LoopSection loop)) continue;
                String label = labelFor(loop);
                boolean isWhile = loop.getClass().getSimpleName().contains("While");
                loopWatcher.track(loop, script, loopLine(loop, script, label), label, isWhile);
            }
        } catch (Throwable ignored) {
            // Per-trigger best-effort: a body we can't walk just contributes no loops.
        }
    }

    /**
     * Best-effort source line for a loop section: the reflective {@code getLineNumber()} accessor
     * if this Skript build exposes one on trigger items, else a unique fuzzy match of the loop's
     * label against the script source, else -1 (the label alone still identifies it).
     */
    private int loopLine(TriggerItem loop, String script, String label) {
        try {
            Object v = loop.getClass().getMethod("getLineNumber").invoke(loop);
            if (v instanceof Integer i && i > 0) return i;
        } catch (Throwable ignored) { }
        return fuzzyLineFor(script, label);
    }

    /**
     * Locate {@code label} in the script's source by normalized comparison, returning the 1-indexed
     * line only when exactly one line matches — ambiguity yields -1 rather than a wrong number. Loop
     * headers ("loop ...", "while ...") are distinctive enough that this is usually unique.
     */
    private int fuzzyLineFor(String script, String label) {
        try {
            String norm = normalizeForMatch(label);
            if (norm.length() < 5) return -1;
            List<String> lines = loadScriptLines(script);
            int found = -1;
            for (int i = 0; i < lines.size(); i++) {
                String sn = normalizeForMatch(lines.get(i));
                if (sn.isEmpty()) continue;
                if (sn.equals(norm) || sn.startsWith(norm)) {
                    if (found != -1) return -1;   // ambiguous — don't guess
                    found = i + 1;
                }
            }
            return found;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * BFS the reachable set from trigger.first via first/next pointers. Returns
     * true if the whole set fit within {@code cap}, false if we bailed early.
     * Bailing early is the desired behavior for malformed/huge bodies — caller
     * skips per-line tracing for the trigger. Follows actualNext past loops (via
     * {@link #nextAtTopLevel}) so items after a loop are still reached.
     */
    private static boolean collectReachable(Trigger trigger, Field firstField, Field nextField,
                                            Set<TriggerItem> out, int cap)
            throws ReflectiveOperationException {
        ArrayList<TriggerItem> stack = new ArrayList<>();
        TriggerItem firstChild = (TriggerItem) firstField.get(trigger);
        if (firstChild != null) stack.add(firstChild);
        while (!stack.isEmpty()) {
            TriggerItem cur = stack.remove(stack.size() - 1);
            if (cur == null || !out.add(cur)) continue;
            if (out.size() > cap) return false;
            TriggerItem n = nextAtTopLevel(cur, nextField);
            if (n != null) stack.add(n);
            if (cur instanceof TriggerSection) {
                TriggerItem f = (TriggerItem) firstField.get(cur);
                if (f != null) stack.add(f);
            }
        }
        return true;
    }

    private void uninstallBodyTracers() {
        for (int i = bodyRewires.size() - 1; i >= 0; i--) {
            try {
                restoreRewire(bodyRewires.get(i));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "[skTrace] Failed to restore body tracers: " + t.getMessage(), t);
            }
        }
        bodyRewires.clear();
        bodyTracedTriggers.clear();
    }

    private static String labelFor(TriggerItem item) {
        try {
            String s = item.toString(null, false);
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) { }
        return item.getClass().getSimpleName();
    }

    private static boolean matchesLabel(String srcLine, String item) {
        if (srcLine.equals(item)) return true;
        if (srcLine.startsWith(item)) return true;
        // Skript's toString sometimes reorders or normalizes — fall back to a
        // sizeable shared prefix. 8 chars is enough to avoid matching "set x".
        if (item.length() >= 8) {
            String head = item.substring(0, Math.min(item.length(), 30));
            if (srcLine.contains(head)) return true;
        }
        return false;
    }

    /** Lowercase, drop whitespace, strip inline comments and trailing colons. */
    private static String normalizeForMatch(String s) {
        if (s == null) return "";
        int hash = -1;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inStr = !inStr;
            else if (c == '#' && !inStr) { hash = i; break; }
        }
        if (hash >= 0) s = s.substring(0, hash);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) out.append(Character.toLowerCase(c));
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == ':') out.setLength(out.length() - 1);
        return out.toString();
    }

    /** Load and cache a script's source. Returns empty list if unavailable. */
    private List<String> loadScriptLines(String scriptName) {
        if (scriptName == null || scriptName.equals("unknown")) return Collections.emptyList();
        List<String> cached = scriptSourceCache.get(scriptName);
        if (cached != null) return cached;
        List<String> loaded = loadScriptLinesFresh(scriptName);
        scriptSourceCache.put(scriptName, loaded);
        return loaded;
    }

    private List<String> loadScriptLinesFresh(String scriptName) {
        try {
            Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
            if (skript == null) return Collections.emptyList();
            java.nio.file.Path scriptsDir = skript.getDataFolder().toPath().resolve("scripts");
            if (!java.nio.file.Files.isDirectory(scriptsDir)) return Collections.emptyList();

            // Find by relative path, then by basename. Walks the scripts folder
            // once per cache miss — startup-only cost.
            java.nio.file.Path[] match = {null};
            String basename = scriptName.replaceAll(".*[/\\\\]", "");
            try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(scriptsDir)) {
                stream.filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sk"))
                        .forEach(p -> {
                            if (match[0] != null) return;
                            String rel = scriptsDir.relativize(p).toString().replace('\\', '/');
                            if (rel.equals(scriptName)
                                    || p.getFileName().toString().equals(scriptName)
                                    || p.getFileName().toString().equals(basename)) {
                                match[0] = p;
                            }
                        });
            }
            if (match[0] == null) return Collections.emptyList();
            if (java.nio.file.Files.size(match[0]) > 200_000L) return Collections.emptyList();
            String content = java.nio.file.Files.readString(match[0], java.nio.charset.StandardCharsets.UTF_8);
            // -1 limit so trailing blank lines are preserved — keeps line numbers honest.
            String[] arr = content.split("\\r?\\n", -1);
            return java.util.Arrays.asList(arr);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * Ordered log of graph edits, each paired with how to undo it. Ordered (not a map)
     * so we can roll back to any earlier checkpoint — used to abandon the deepening of a
     * single loop body without disturbing the rest of the trigger, and to fully restore
     * everything on /sktrace stop.
     */
    private static final class BodyRewire {
        private final List<Runnable> undo = new ArrayList<>();

        int mark() { return undo.size(); }

        void rollbackTo(int mark) {
            for (int i = undo.size() - 1; i >= mark; i--) {
                try { undo.get(i).run(); } catch (Throwable ignored) { }
            }
            while (undo.size() > mark) undo.remove(undo.size() - 1);
        }

        void restoreAll() { rollbackTo(0); }

        void setFirst(Field firstField, TriggerItem container, TriggerItem newFirst)
                throws ReflectiveOperationException {
            final TriggerItem orig = (TriggerItem) firstField.get(container);
            firstField.set(container, newFirst);
            undo.add(() -> { try { firstField.set(container, orig); } catch (Throwable ignored) { } });
        }

        void setNextField(Field nextField, TriggerItem item, TriggerItem newNext)
                throws ReflectiveOperationException {
            final TriggerItem orig = (TriggerItem) nextField.get(item);
            nextField.set(item, newNext);
            undo.add(() -> { try { nextField.set(item, orig); } catch (Throwable ignored) { } });
        }

        void setLoopExit(LoopSection loop, TriggerItem newExit) {
            final TriggerItem orig = loop.getActualNext();
            loop.setNext(newExit);
            undo.add(() -> { try { loop.setNext(orig); } catch (Throwable ignored) { } });
        }
    }

    // ---------- Scheduler hooks (periodic triggers, scheduled tasks) ----------

    private void installSchedulerHooks() {
        Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
        if (skript == null) return;

        List<BukkitTask> tasks;
        try {
            tasks = new ArrayList<>(Bukkit.getScheduler().getPendingTasks());
        } catch (Throwable t) {
            plugin.getLogger().warning("[skTrace] Could not enumerate scheduler tasks: " + t.getMessage());
            return;
        }

        int tasksScanned = 0, hooked = 0;
        for (BukkitTask task : tasks) {
            try {
                if (task.getOwner() != skript) continue;
                tasksScanned++;
                Object runnable = readRunnableFromTask(task);
                if (runnable == null) continue;
                int before = schedulerSwaps.size();
                swapTriggerFieldsDeep(runnable, 0, new java.util.IdentityHashMap<>());
                int found = schedulerSwaps.size() - before;
                if (found == 0) {
                    plugin.getLogger().log(Level.FINE,
                            "[skTrace]   task runnable class=" + runnable.getClass().getName()
                                    + " - no Trigger found");
                }
                hooked += found;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE,
                        "[skTrace] Failed to scan scheduler task: " + t.getMessage());
            }
        }
        plugin.getLogger().info("[skTrace] Scanned " + tasksScanned
                + " Skript scheduler tasks; hooked " + hooked + " periodic-trigger references.");

        // Second pass: walk every loaded Script's structures and grab Triggers from there.
        int scriptHooked = installScriptStructureHooks();
        plugin.getLogger().info("[skTrace] Script-structure scan hooked " + scriptHooked + " additional triggers.");
    }

    private int installScriptStructureHooks() {
        int hooked = 0;
        try {
            Class<?> scriptLoaderClass = Class.forName("ch.njol.skript.ScriptLoader");
            java.lang.reflect.Method getLoaded;
            try {
                getLoaded = scriptLoaderClass.getMethod("getLoadedScripts");
            } catch (NoSuchMethodException nsme) {
                plugin.getLogger().info("[skTrace] ScriptLoader.getLoadedScripts() not found - skipping script scan");
                return 0;
            }
            Object scriptsObj = getLoaded.invoke(null);
            if (!(scriptsObj instanceof Iterable<?> scripts)) {
                plugin.getLogger().info("[skTrace] Loaded scripts not iterable: "
                        + (scriptsObj == null ? "null" : scriptsObj.getClass().getName()));
                return 0;
            }
            int scriptCount = 0;
            for (Object script : scripts) {
                scriptCount++;
                int before = schedulerSwaps.size();
                swapTriggerFieldsDeep(script, 0, new java.util.IdentityHashMap<>());
                hooked += schedulerSwaps.size() - before;
            }
            plugin.getLogger().info("[skTrace] Walked " + scriptCount + " loaded scripts.");
            return hooked;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "[skTrace] Script-structure scan failed: " + t.getMessage());
            return 0;
        }
    }

    /**
     * Recursively scan a container for Trigger-typed fields and swap them with wrappers.
     * Recurses up to 3 levels deep, and only into objects whose class is from a Skript
     * package or is a lambda/anonymous class likely to hold trigger captures.
     */
    private void swapTriggerFieldsDeep(Object container, int depth,
                                       java.util.IdentityHashMap<Object, Boolean> visited) {
        if (container == null || depth > 3) return;
        if (visited.put(container, Boolean.TRUE) != null) return;

        Class<?> c = container.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(container);
                    if (v == null) continue;

                    if (v instanceof Trigger original && !(v instanceof ProfilingTrigger)) {
                        try {
                            ProfilingTrigger wrapper = wrapTrigger(original);
                            if (wrapper != null) {
                                String id = wrapper.id();
                                triggerStats.putIfAbsent(id, buildStats(id, original));
                                f.set(container, wrapper);
                                schedulerSwaps.add(new SchedulerSwap(f, container, original, wrapper));
                            }
                        } catch (Throwable t) {
                            plugin.getLogger().log(Level.FINE,
                                    "[skTrace] swap failed at depth " + depth + ": " + t.getMessage());
                        }
                    } else if (depth < 3 && shouldRecurse(v)) {
                        swapTriggerFieldsDeep(v, depth + 1, visited);
                    } else if (depth < 3 && v instanceof Iterable<?> iter) {
                        for (Object item : iter) swapTriggerFieldsDeep(item, depth + 1, visited);
                    }
                } catch (Throwable ignored) { }
            }
            c = c.getSuperclass();
        }
    }

    private boolean shouldRecurse(Object v) {
        Class<?> c = v.getClass();
        String name = c.getName();
        if (name.startsWith("ch.njol.") || name.startsWith("org.skriptlang.")) return true;
        // Synthetic classes (lambdas) usually have $$Lambda$ or $$Lambda/ in their name
        if (name.contains("$$Lambda")) return true;
        if (v instanceof Runnable && !name.startsWith("org.bukkit.") && !name.startsWith("java.")) return true;
        return false;
    }

    private Object readRunnableFromTask(BukkitTask task) {
        // CraftTask holds the user runnable in a non-static Runnable field (commonly named "task").
        Class<?> c = task.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!Runnable.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(task);
                    // Avoid recursive self-reference (CraftTask implements Runnable too)
                    if (v != null && v != task) return v;
                } catch (Throwable ignored) { }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private void uninstallSchedulerHooks() {
        for (int i = schedulerSwaps.size() - 1; i >= 0; i--) {
            SchedulerSwap swap = schedulerSwaps.get(i);
            try {
                // Only restore if the field still holds our wrapper (see uninstallTriggerHooks).
                if (swap.field.get(swap.holder) == swap.wrapper) {
                    swap.field.set(swap.holder, swap.original);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to restore scheduler-task trigger: " + t.getMessage(), t);
            }
        }
        schedulerSwaps.clear();
    }

    // ---------- Function hooks (per-function profiling) ----------

    /**
     * Wrap every Skript script-function's body so each function call is timed into its
     * own layer. A function's body is a Trigger held inside a ScriptFunction; we replace
     * that field with a delegating {@link ProfilingTrigger}. Functions execute via
     * {@code trigger.execute(event)}, so the wrapper captures every call. `return` is
     * unaffected: EffReturn binds its handler at parse time and writes into the
     * ScriptFunction's own state, so the live trigger object can be swapped freely.
     *
     * Java/default functions have no Trigger body and are skipped automatically.
     */
    private void installFunctionHooks() {
        Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
        if (skript == null) return;

        List<Object> functions = enumerateFunctions();
        int hooked = 0;
        for (Object fn : functions) {
            try {
                if (wrapFunction(fn)) hooked++;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[skTrace] Function wrap failed: " + t.getMessage());
            }
        }
        plugin.getLogger().info("[skTrace] Enumerated " + functions.size()
                + " functions; hooked " + hooked + " script function(s)"
                + (lineLevelTracing ? " with per-line tracing." : "."));
    }

    /**
     * Collect every Skript Function instance via reflection. Primary path is the modern
     * FunctionRegistry (Skript 2.13+); we also sweep the legacy Functions namespaces so
     * older Skript versions still work. Identity-deduped, since the two registries share
     * instances. All reflective so the plugin still compiles against older Skript APIs.
     */
    private List<Object> enumerateFunctions() {
        Set<Object> out = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Class<?> frClass = Class.forName("ch.njol.skript.lang.function.FunctionRegistry");
            Object registry = frClass.getMethod("getRegistry").invoke(null);
            Object elements = frClass.getMethod("elements").invoke(registry);
            if (elements instanceof Collection<?> col) out.addAll(col);
        } catch (Throwable t) {
            plugin.getLogger().fine("[skTrace] FunctionRegistry enumeration unavailable: " + t.getMessage());
        }
        try {
            Class<?> fnsClass = Class.forName("ch.njol.skript.lang.function.Functions");
            collectNamespaceFunctions(fnsClass, "namespaces", out);
            collectNamespaceFunctions(fnsClass, "globalFunctions", out);
        } catch (Throwable t) {
            plugin.getLogger().fine("[skTrace] Legacy Functions enumeration unavailable: " + t.getMessage());
        }
        return new ArrayList<>(out);
    }

    /** Read a static Map field of namespaces and pull each namespace's functions. */
    private void collectNamespaceFunctions(Class<?> fnsClass, String fieldName, Set<Object> out) {
        try {
            Field f = fnsClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof Map<?, ?> map) {
                for (Object ns : map.values()) {
                    Object fns = invokeNoArg(ns, "getFunctions");
                    if (fns instanceof Collection<?> col) out.addAll(col);
                }
            }
        } catch (Throwable ignored) { }
    }

    /** Wrap one function's body Trigger; returns true if it was a script function we hooked. */
    private boolean wrapFunction(Object fn) throws ReflectiveOperationException {
        Field trigField = findFieldByType(fn, Trigger.class);
        if (trigField == null) return false;          // Java/default functions: no Trigger body
        Object trigVal = trigField.get(fn);
        if (!(trigVal instanceof Trigger original)) return false;
        if (original instanceof ProfilingTrigger) return false;   // already wrapped

        SkriptEvent event = (SkriptEvent) readFieldByType(original, SkriptEvent.class);
        if (event == null) return false;              // can't build a valid wrapper without one
        Object script = readFieldByType(original, "org.skriptlang.skript.lang.script.Script");

        String name = functionLabel(fn);
        String id = "fn#" + name + "#" + System.identityHashCode(fn);

        ProfilingTrigger wrapper = new ProfilingTrigger(
                (org.skriptlang.skript.lang.script.Script) script,
                name, event, original, this, id, true);
        functionStats.put(id, buildFunctionStats(id, name, original));
        // Per-line tracing inside the function body, gated by the same opt-in as triggers.
        if (lineLevelTracing) installBodyTracers(original, id);
        collectLoops(original);
        trigField.set(fn, wrapper);
        functionSwaps.add(new SchedulerSwap(trigField, fn, original, wrapper));
        return true;
    }

    /** Display label for a function, e.g. {@code "tpPlayer()"}. */
    private String functionLabel(Object fn) {
        Object n = invokeNoArg(fn, "getName");
        String name = (n instanceof String s && !s.isEmpty()) ? s : "function";
        return name + "()";
    }

    private TriggerStats buildFunctionStats(String id, String name, Trigger trigger) {
        String script = "unknown";
        try {
            Object scriptObj = readFieldByType(trigger, "org.skriptlang.skript.lang.script.Script");
            if (scriptObj != null) script = scriptDisplayName(scriptObj);
        } catch (Throwable ignored) { }
        return new TriggerStats(id, script, name, triggerLine(trigger), tickCapacity);
    }

    private void uninstallFunctionHooks() {
        for (int i = functionSwaps.size() - 1; i >= 0; i--) {
            SchedulerSwap swap = functionSwaps.get(i);
            try {
                // Only restore if the field still holds our wrapper (see uninstallTriggerHooks).
                if (swap.field.get(swap.holder) == swap.wrapper) {
                    swap.field.set(swap.holder, swap.original);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "[skTrace] Failed to restore function trigger: " + t.getMessage(), t);
            }
        }
        functionSwaps.clear();
    }

    /**
     * Identity-guarded restore: put each original back ONLY where our wrapper still sits. A
     * container that no longer holds the wrapper was rebuilt or pruned by Skript (a reload we
     * didn't intercept) — re-inserting the original there would resurrect a trigger Skript has
     * already dropped, leaving an orphan that double-fires until a full server restart.
     */
    private void uninstallTriggerHooks() {
        for (int i = triggerSwaps.size() - 1; i >= 0; i--) {
            TriggerSwap swap = triggerSwaps.get(i);
            try {
                if (swap.list != null) {
                    List<Object> list = swap.list;
                    for (int idx = list.size() - 1; idx >= 0; idx--) {
                        if (list.get(idx) == swap.wrapper) {
                            list.set(idx, swap.original);
                            break;
                        }
                    }
                } else if (swap.set != null) {
                    // Trigger doesn't override equals, so remove() is an identity test here.
                    if (swap.set.remove(swap.wrapper)) swap.set.add(swap.original);
                } else if (swap.field != null) {
                    if (swap.field.get(swap.holder) == swap.wrapper) {
                        swap.field.set(swap.holder, swap.original);
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to restore trigger: " + t.getMessage(), t);
            }
        }
        triggerSwaps.clear();
    }

    // ---------- Reflection helpers ----------

    private static Field findStaticField(Class<?> cls, Class<?> fieldType) {
        for (Field f : cls.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && fieldType.isAssignableFrom(f.getType())) {
                return f;
            }
        }
        return null;
    }

    /** First field (walking up the hierarchy) whose declared type is assignable to {@code type}. */
    private static Field findFieldByType(Object obj, Class<?> type) {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * A Trigger's 1-indexed source line (its header line). Prefers the public
     * getLineNumber() accessor (present on modern Skript), falling back to the
     * private {@code line} field. Returns -1 when neither is available.
     */
    private static int triggerLine(Trigger t) {
        try {
            Object v = t.getClass().getMethod("getLineNumber").invoke(t);
            if (v instanceof Integer i) return i;
        } catch (Throwable ignored) { }
        try {
            Field f = Trigger.class.getDeclaredField("line");
            f.setAccessible(true);
            return f.getInt(t);
        } catch (Throwable ignored) { }
        return -1;
    }

    private static Object readFieldByType(Object obj, Class<?> type) throws ReflectiveOperationException {
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(obj);
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object readFieldByType(Object obj, String typeName) throws ReflectiveOperationException {
        Class<?> type;
        try {
            type = Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            return null;
        }
        return readFieldByType(obj, type);
    }

    // ---------- Bookkeeping records ----------

    private record ListenerSwap(HandlerList handlerList, RegisteredListener original, RegisteredListener wrapped) {}

    // One reversible container edit, in exactly one of three shapes: a list slot, a set member,
    // or a Trigger-typed field inside a holder object. Restores are identity-guarded — each
    // checks that OUR wrapper is still where we put it and silently skips otherwise. If a reload
    // slipped past the ReloadHookGuard, Skript has already rebuilt or mutated these containers;
    // blindly writing originals back (the old index-based restore) resurrected triggers Skript
    // had dropped from its bookkeeping, and those orphans double-fired every event until a full
    // server restart, surviving /sktrace stop and even plugin upgrades.
    private record TriggerSwap(List<Object> list, Set<Object> set, Field field, Object holder,
                               Object original, Object wrapper) {}
    private record SchedulerSwap(Field field, Object holder, Trigger original, Trigger wrapper) {}
}
