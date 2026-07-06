// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import dev.sktrace.SkTrace;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Passively detects Skript's periodic <b>full rewrite</b> of {@code variables.csv} during a
 * profiling window — the thing the in-memory write counter ({@link VariableTracker}) can't see.
 *
 * <p>Skript's {@code FlatFileStorage} appends each global-variable change to the CSV incrementally,
 * but a {@code saveTask} fires every five minutes and, once enough changes have piled up
 * ({@code REQUIRED_CHANGES_FOR_RESAVE = 1000}), calls {@code saveVariables(false)}: it writes the
 * whole variable set to {@code variables.csv.temp} and atomically moves it over {@code variables.csv}.
 * That rewrite holds Skript's variable read-lock the whole time, so any script doing
 * {@code set {var} to …} stalls until it finishes — which is exactly why a big save can show up as a
 * tick-time spike. Surfacing <em>when</em> it happened (and roughly how long / how big) lets the
 * report line a save up against the lag.
 *
 * <p><b>How it observes, passively:</b> rather than instrument Skript's save method (which would mean
 * rewriting its code — the thing this plugin deliberately avoids), we register a {@link WatchService}
 * on the directory holding {@code variables.csv} and watch the {@code .temp} file's lifecycle:
 * its creation marks the start of a rewrite, its disappearance (the atomic move) marks the end.
 * Incremental appends never create that temp file, so a temp event uniquely identifies a full save.
 * Nothing here touches script execution or Skript's data.
 *
 * <p><b>Honest limits</b> (surfaced in the report): the duration is measured from filesystem events,
 * so it's approximate (the <em>timing</em> — which tick — is what's reliable, and that's what the lag
 * correlation needs). We never read or parse the file's contents; "size" is just {@code file.length()}.
 * Only flat-file (CSV) storage produces this signal — on a database backend the feature reports itself
 * as not applicable.
 */
public final class VariableFlushTracker {

    private final SkTrace plugin;
    // Source of the per-save variable list: rotated at each flush so a save can be paired with the
    // variables it compacted. May be null (variable tracking disabled) — then flushes carry no vars.
    private final VariableTracker variableTracker;

    private volatile boolean ok = false;
    private volatile String reason = "not installed";

    // Watched paths, resolved at install.
    private File csvFile;
    private Path watchDir;
    private String fileName;
    private String tempName;
    // Skript's per-storage change counter (reset to 0 after each full save). Read at the START of a
    // rewrite to report how many queued changes that save compacted. Best-effort; null if unreadable.
    private AtomicInteger changesCounter;

    private WatchService watchService;
    private Thread watchThread;
    private volatile boolean closed = false;

    // Total sampleTick() calls so far — the absolute tick ordinal used to place a flush on the chart.
    private final AtomicLong ticks = new AtomicLong();

    // In-progress rewrite (written/read only on the watch thread, except ticks which is atomic).
    private boolean inFlush = false;
    private long flushStartNanos = 0;
    private long flushStartTick = 0;
    private int flushStartChanges = -1;
    // Variables compacted into the in-progress save, captured (rotated) at its start.
    private List<VariableTracker.EpochVar> flushStartVars = Collections.emptyList();

    // Defensive cap: at ~one save per five minutes this is never hit in practice.
    private static final int MAX_FLUSHES = 256;
    private final List<Flush> flushes = Collections.synchronizedList(new ArrayList<>());

    public VariableFlushTracker(SkTrace plugin, VariableTracker variableTracker) {
        this.plugin = plugin;
        this.variableTracker = variableTracker;
    }

    /** One observed full rewrite of variables.csv. Tick fields are absolute ordinals (see {@link #ticks}). */
    public static final class Flush {
        public final long startTick;
        public final long endTick;
        public final long durationNanos;   // -1 if the start was missed (instant/coalesced event)
        public final long bytes;           // file size after the rewrite, -1 if unknown
        public final int changes;          // queued changes compacted, -1 if unknown
        public final List<VariableTracker.EpochVar> vars;  // distinct variables compacted into this save
        Flush(long startTick, long endTick, long durationNanos, long bytes, int changes,
              List<VariableTracker.EpochVar> vars) {
            this.startTick = startTick;
            this.endTick = endTick;
            this.durationNanos = durationNanos;
            this.bytes = bytes;
            this.changes = changes;
            this.vars = vars;
        }
    }

    // ---------- lifecycle ----------

    public void install() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Skript") == null) {
                reason = "Skript not found.";
                return;
            }
            resolveCsvFile();
            if (csvFile == null) {
                reason = "No flat-file storage — variables go to a database (or none), so there is no "
                        + "variables.csv rewrite to observe.";
                return;
            }
            File parent = csvFile.getParentFile();
            if (parent == null) {
                reason = "Could not resolve the variables.csv directory.";
                return;
            }
            watchDir = parent.toPath();
            fileName = csvFile.getName();
            tempName = fileName + ".temp";

            watchService = watchDir.getFileSystem().newWatchService();
            watchDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

            watchThread = new Thread(this::watchLoop, "SkTrace-csv-watch");
            watchThread.setDaemon(true);
            watchThread.start();

            ok = true;
            reason = null;
            plugin.getLogger().info("[skTrace] Watching " + csvFile.getName() + " for full saves.");
        } catch (Throwable t) {
            ok = false;
            reason = t.getClass().getSimpleName() + ": " + t.getMessage();
            plugin.getLogger().log(Level.FINE, "[skTrace] CSV save tracking unavailable: " + t.getMessage());
            closeQuietly();
        }
    }

    public void uninstall() {
        closed = true;
        closeQuietly();
        if (watchThread != null) {
            watchThread.interrupt();
            try { watchThread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            watchThread = null;
        }
    }

    private void closeQuietly() {
        if (watchService != null) {
            try { watchService.close(); } catch (Throwable ignored) { }
        }
    }

    /** Called once per server tick from the profiler's tick aggregator. */
    public void tick() {
        ticks.incrementAndGet();
    }

    // ---------- watch thread ----------

    private void watchLoop() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                break;
            }
            try {
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.kind() == StandardWatchEventKinds.OVERFLOW) continue;
                    Object ctx = ev.context();
                    if (!(ctx instanceof Path p)) continue;
                    handleEvent(ev.kind(), p.getFileName().toString());
                }
            } catch (Throwable t) {
                // A rewrite's bookkeeping must never kill the watcher or the server.
                plugin.getLogger().log(Level.FINE, "[skTrace] CSV watch event error: " + t.getMessage());
            }
            if (!key.reset()) break;
        }
    }

    private void handleEvent(WatchEvent.Kind<?> kind, String name) {
        if (!name.equals(tempName)) return;   // only the .temp file marks a full rewrite
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            if (!inFlush) beginFlush();
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            if (inFlush) finalizeFlush();
            else recordInstantFlush();   // create+delete coalesced into one wakeup
        }
        // ENTRY_MODIFY on the temp file (the bulk write) needs no action — start/end bracket it.
    }

    private void beginFlush() {
        inFlush = true;
        flushStartNanos = System.nanoTime();
        flushStartTick = ticks.get();
        // A full save only fires once >= 1000 changes have piled up, so a non-positive read means we
        // caught the counter after Skript reset it (event latency) — report it as unknown instead.
        int c = readChanges();
        flushStartChanges = c > 0 ? c : -1;
        // The variables written since the previous save are exactly what this save compacts.
        flushStartVars = variableTracker != null ? variableTracker.rotateEpoch() : Collections.emptyList();
    }

    private void finalizeFlush() {
        long dur = flushStartNanos > 0 ? System.nanoTime() - flushStartNanos : -1;
        addFlush(new Flush(flushStartTick, ticks.get(), dur, csvFile.length(), flushStartChanges, flushStartVars));
        inFlush = false;
        flushStartNanos = 0;
        flushStartChanges = -1;
        flushStartVars = Collections.emptyList();
    }

    /** We saw the temp vanish without seeing it appear (very fast save, events coalesced). */
    private void recordInstantFlush() {
        long t = ticks.get();
        // Still rotate so these writes aren't mis-attributed to the next save's list.
        List<VariableTracker.EpochVar> vars = variableTracker != null
                ? variableTracker.rotateEpoch() : Collections.emptyList();
        addFlush(new Flush(t, t, -1, csvFile.length(), -1, vars));
    }

    private void addFlush(Flush f) {
        if (flushes.size() < MAX_FLUSHES) flushes.add(f);
    }

    private int readChanges() {
        AtomicInteger c = changesCounter;
        if (c == null) return -1;
        try { return c.get(); } catch (Throwable t) { return -1; }
    }

    // ---------- accessors (read on the main thread at report time) ----------

    public boolean ok() { return ok; }
    public String reason() { return reason; }
    public long totalTicks() { return ticks.get(); }

    public List<Flush> flushes() {
        synchronized (flushes) { return new ArrayList<>(flushes); }
    }

    // ---------- reflection: locate variables.csv + the change counter ----------

    private void resolveCsvFile() {
        try {
            Class<?> variablesClass = Class.forName("ch.njol.skript.variables.Variables");
            List<?> storages = findStorages(variablesClass);
            if (storages != null) {
                for (Object storage : new ArrayList<>(storages)) {
                    if (storage == null) continue;
                    File f = readFileField(storage);
                    if (f == null) continue;
                    // Prefer a flat-file (.csv) storage; remember it and grab its change counter.
                    if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
                        csvFile = f;
                        changesCounter = readChangesField(storage);
                        return;
                    }
                    if (csvFile == null) csvFile = f;   // non-csv flat file as a weaker fallback
                }
            }
            if (csvFile != null) return;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "[skTrace] Could not reflect variables.csv path: " + t.getMessage());
        }
        // Fallback to Skript's default location if reflection found nothing.
        Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
        if (skript != null) {
            File def = new File(skript.getDataFolder(), "variables.csv");
            if (def.isFile()) csvFile = def;
        }
    }

    private static List<?> findStorages(Class<?> variablesClass) {
        try {
            Field f = variablesClass.getDeclaredField("STORAGES");
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof List<?> l) return l;
        } catch (Throwable ignored) { }
        for (Field f : variablesClass.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!List.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof List<?> l && !l.isEmpty()) return l;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Read the protected {@code File file} field declared on VariablesStorage (or a subclass). */
    private static File readFileField(Object storage) {
        for (Class<?> c = storage.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (File.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(storage);
                        if (v instanceof File file) return file;
                    } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }

    /** Read FlatFileStorage's {@code AtomicInteger changes} (the resave counter), if present. */
    private static AtomicInteger readChangesField(Object storage) {
        for (Class<?> c = storage.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (AtomicInteger.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(storage);
                        if (v instanceof AtomicInteger ai) return ai;
                    } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }
}
