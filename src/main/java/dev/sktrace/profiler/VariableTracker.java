// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import dev.sktrace.Sktrace;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Passively observes Skript <b>global</b> variable writes during a profiling window.
 *
 * <p>Skript fires no variable-change event, so we observe the persistence path instead.
 * Every global set/delete flows {@code Variables.setVariable} → {@code saveQueue} → the
 * single save thread → {@code VariablesStorage.save(SerializedVariable)} → that storage's
 * async {@code changesQueue} → its write thread → {@code variables.csv}/DB. We replace each
 * storage's {@code changesQueue} field with {@link ObservingQueue}, a {@link LinkedBlockingQueue}
 * subclass whose own storage is unused and whose every operation delegates to the original
 * queue instance — intercepting only the enqueue calls ({@code offer}/{@code put}) to count.
 *
 * <p><b>Why not snapshot-diff the variable map:</b> diffing a server's whole variable
 * {@code TreeMap} on a timer (the same data that makes {@code variables.csv} huge) is O(n) per
 * snapshot and only yields net change. Observing the save queue gives exact per-write counts at
 * O(writes) cost, independent of how many variables exist.
 *
 * <p><b>Safety:</b> this never touches Skript's trigger/execution graph (unlike the opt-in
 * line-level tracer). Because {@link ObservingQueue} forwards every operation to the very same
 * original queue, both Skript's producer (save thread) and consumer (write thread) keep operating
 * on one underlying queue — so persistence is byte-identical, no in-flight writes are lost, and a
 * thread parked in {@code take()} at swap time still wakes correctly. If any reflective step
 * fails, {@link #install()} is a no-op and the report simply marks variable data unavailable.
 *
 * <p><b>Scope &amp; limits</b> (surfaced honestly in the report):
 * <ul>
 *   <li>Global variables only — exactly the ones persisted to {@code variables.csv} / a DB.
 *       Local/temporary {@code {_x}} variables never touch a storage and are invisible here.</li>
 *   <li>"created" vs "updated" is relative to the window: the first time a name is seen in the
 *       window counts as a create, later writes as updates.</li>
 *   <li>A {@code null} serialized value is a delete.</li>
 * </ul>
 */
public final class VariableTracker {

    private final Sktrace plugin;
    private final int maxDistinct;
    private final int tickCapacity;

    private volatile boolean ok = false;
    private volatile String reason = "not installed";

    private final AtomicLong currentTickWrites = new AtomicLong();
    // Per-tick deltas for the create/update/delete split, folded into the ring buffers below each
    // tick (parallel to perTick). The report reads these as windowed sums so its headline counts
    // reflect the RETAINED WINDOW, not the whole session — critical in rolling mode, where a
    // long-lived buffer would otherwise accumulate the count into the millions.
    private final AtomicLong currentTickCreates = new AtomicLong();
    private final AtomicLong currentTickUpdates = new AtomicLong();
    private final AtomicLong currentTickDeletes = new AtomicLong();

    // name -> per-variable stats. Bounded: once maxDistinct distinct names have been seen we
    // stop adding new entries (aggregate totals stay correct) and flip `capped`. Written only on
    // Skript's single save thread (see ObservingQueue), read on the main thread at report time.
    private final Map<String, VarStat> vars = new ConcurrentHashMap<>();
    private volatile boolean capped = false;

    // Per-flush "epoch": distinct global variables WRITTEN since the last flush boundary, each with
    // its latest serialized value. VariableFlushTracker rotates this when a variables.csv save runs,
    // so a report can answer "which variables (and values) made up that save's compacted changes".
    // Captured cheaply on every write (just the latest value reference per name, memory-bounded);
    // whether it reaches the report is a per-report choice (the --variable-values flag). volatile so
    // rotate() can swap the whole map atomically against the save thread's writes.
    private volatile ConcurrentHashMap<String, EpochVar> epoch = new ConcurrentHashMap<>();
    private volatile long epochValueBytes = 0;                  // value bytes retained this epoch (soft cap)
    private static final int MAX_VALUE_BYTES = 2048;            // don't retain a single value larger than this
    private static final long MAX_EPOCH_VALUE_BYTES = 2_000_000;// nor keep more than this much value data total

    // Per-tick write counts, same ring/grow scheme as TriggerStats. Touched only on the main
    // thread (snapshotTick / perTickCopy), so a plain synchronized block is enough.
    private long[] perTick;
    private long[] createsPerTick;
    private long[] updatesPerTick;
    private long[] deletesPerTick;
    private int perTickCount = 0;
    private int perTickPos = 0;

    private final List<QueueSwap> swaps = new ArrayList<>();

    // Lazily-resolved SerializedVariable fields, cached after first success.
    private Field svNameField;
    private Field svValueField;
    private Field valueTypeField;
    private Field svDataField;

    public VariableTracker(Sktrace plugin, int maxDistinct, int tickCapacity) {
        this.plugin = plugin;
        this.maxDistinct = Math.max(1, maxDistinct);
        this.tickCapacity = Math.max(0, tickCapacity);
        int initial = this.tickCapacity > 0 ? this.tickCapacity : 1024;
        this.perTick = new long[initial];
        this.createsPerTick = new long[initial];
        this.updatesPerTick = new long[initial];
        this.deletesPerTick = new long[initial];
    }

    // ---------- lifecycle ----------

    public void install() {
        try {
            Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
            if (skript == null) { reason = "Skript not found."; return; }

            Class<?> variablesClass = Class.forName("ch.njol.skript.variables.Variables");
            List<?> storages = findStorages(variablesClass);
            if (storages == null) {
                reason = "Could not locate Skript's variable storages on this version.";
                return;
            }
            // Snapshot to avoid concurrent-modification if Skript mutates the list.
            List<Object> snapshot = new ArrayList<>(storages);
            int wrapped = 0;
            for (Object storage : snapshot) {
                if (storage == null) continue;
                try {
                    Field qf = findChangesQueueField(storage);
                    if (qf == null) continue;
                    Object q = qf.get(storage);
                    if (!(q instanceof LinkedBlockingQueue) || q instanceof ObservingQueue) continue;
                    @SuppressWarnings("unchecked")
                    LinkedBlockingQueue<Object> real = (LinkedBlockingQueue<Object>) q;
                    ObservingQueue obs = new ObservingQueue(real);
                    qf.set(storage, obs);
                    swaps.add(new QueueSwap(qf, storage, real));
                    wrapped++;
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.FINE,
                            "[Sktrace] variable tracker: could not wrap a storage queue: " + t.getMessage());
                }
            }
            if (wrapped == 0) {
                reason = storages.isEmpty()
                        ? "No variable storage configured (no global variable persistence)."
                        : "Could not attach to any variable storage queue on this Skript version.";
                uninstall();
                return;
            }
            ok = true;
            reason = null;
            plugin.getLogger().info("[Sktrace] Variable tracking active on " + wrapped + " storage queue(s).");
        } catch (Throwable t) {
            ok = false;
            reason = t.getClass().getSimpleName() + ": " + t.getMessage();
            plugin.getLogger().log(Level.FINE, "[Sktrace] Variable tracking unavailable: " + t.getMessage());
        }
    }

    public void uninstall() {
        for (int i = swaps.size() - 1; i >= 0; i--) {
            QueueSwap s = swaps.get(i);
            try {
                s.field.set(s.holder, s.original);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "[Sktrace] Failed to restore a variable storage queue: " + t.getMessage(), t);
            }
        }
        swaps.clear();
    }

    // ---------- recording (called on Skript's single save thread) ----------

    private void record(Object serializedVariable) {
        if (serializedVariable == null) return;
        String name = extractName(serializedVariable);
        if (name == null) return;
        boolean delete = extractValue(serializedVariable) == null;

        currentTickWrites.incrementAndGet();
        recordEpoch(name, serializedVariable, delete);

        VarStat st = vars.get(name);
        if (st == null) {
            if (vars.size() >= maxDistinct) {
                // At the distinct-name cap: keep aggregate totals correct but don't grow the map.
                capped = true;
                if (delete) currentTickDeletes.incrementAndGet(); else currentTickUpdates.incrementAndGet();
                return;
            }
            st = new VarStat(name);
            vars.put(name, st);
            if (delete) {            // delete of a name not yet seen this window
                st.deletes++;
                currentTickDeletes.incrementAndGet();
            } else {                 // first sight this window => created
                st.created = true;
                st.sets++;
                st.lastType = extractType(serializedVariable);
                currentTickCreates.incrementAndGet();
            }
            return;
        }
        if (delete) {
            st.deletes++;
            currentTickDeletes.incrementAndGet();
        } else {
            st.sets++;
            st.lastType = extractType(serializedVariable);
            currentTickUpdates.incrementAndGet();
        }
    }

    // ---------- per-tick write series (main thread) ----------

    synchronized void snapshotTick() {
        long w = currentTickWrites.getAndSet(0);
        long c = currentTickCreates.getAndSet(0);
        long u = currentTickUpdates.getAndSet(0);
        long d = currentTickDeletes.getAndSet(0);
        if (tickCapacity > 0) {
            perTick[perTickPos] = w;
            createsPerTick[perTickPos] = c;
            updatesPerTick[perTickPos] = u;
            deletesPerTick[perTickPos] = d;
            perTickPos = (perTickPos + 1) % tickCapacity;
            if (perTickCount < tickCapacity) perTickCount++;
        } else {
            if (perTickCount >= perTick.length) {
                int grown = perTick.length * 2;
                perTick = java.util.Arrays.copyOf(perTick, grown);
                createsPerTick = java.util.Arrays.copyOf(createsPerTick, grown);
                updatesPerTick = java.util.Arrays.copyOf(updatesPerTick, grown);
                deletesPerTick = java.util.Arrays.copyOf(deletesPerTick, grown);
            }
            perTick[perTickCount] = w;
            createsPerTick[perTickCount] = c;
            updatesPerTick[perTickCount] = u;
            deletesPerTick[perTickCount] = d;
            perTickCount++;
            perTickPos = perTickCount;
        }
    }

    /** Sum of a per-tick ring over just the retained window (windowed in rolling mode, the whole
     *  run otherwise). Order doesn't matter for a sum, so the ring is summed in place. Caller holds
     *  the monitor (all callers are synchronized methods). */
    private long sumValid(long[] arr) {
        int n = (tickCapacity > 0) ? Math.min(perTickCount, tickCapacity) : perTickCount;
        long s = 0;
        for (int i = 0; i < n; i++) s += arr[i];
        return s;
    }

    public synchronized long[] perTickCopy() {
        if (tickCapacity == 0 || perTickCount < tickCapacity) {
            return java.util.Arrays.copyOf(perTick, perTickCount);
        }
        long[] out = new long[perTickCount];
        int oldest = perTickPos;
        int tail = tickCapacity - oldest;
        System.arraycopy(perTick, oldest, out, 0, tail);
        if (oldest > 0) System.arraycopy(perTick, 0, out, tail, oldest);
        return out;
    }

    // ---------- accessors (for ReportWriter) ----------

    public boolean ok() { return ok; }
    public String reason() { return reason; }
    // Windowed: summed over the retained per-tick ring, so rolling-mode reports show counts for the
    // buffer window rather than the whole (never-ending) session. In one-shot mode the ring spans the
    // entire run, so these equal the session totals.
    public synchronized long creates() { return sumValid(createsPerTick); }
    public synchronized long updates() { return sumValid(updatesPerTick); }
    public synchronized long deletes() { return sumValid(deletesPerTick); }
    public synchronized long totalWrites() { return sumValid(perTick); }
    public int distinct() { return vars.size(); }
    public boolean capped() { return capped; }

    /** Most-written variables (by sets + deletes), descending, at most {@code n}. */
    public List<VarStat> topVars(int n) {
        List<VarStat> all = new ArrayList<>(vars.values());
        all.sort(Comparator.comparingLong((VarStat v) -> v.sets + v.deletes).reversed()
                .thenComparing(v -> v.name));
        return all.size() > n ? all.subList(0, n) : all;
    }

    /** One tracked variable. Long fields are written only on the save thread; volatile so the
     *  main thread sees fresh values when the report snapshots a live (rolling) buffer. */
    public static final class VarStat {
        public final String name;
        public volatile long sets = 0;
        public volatile long deletes = 0;
        public volatile boolean created = false;
        public volatile String lastType = null;
        VarStat(String name) { this.name = name; }
    }

    // ---------- per-flush epoch (writes since the last variables.csv save) ----------

    /** A variable written during the current epoch. The value is kept as the raw serialized
     *  (type, bytes) Skript produced and is only turned into a display string at report time
     *  (and only if the report asks for values via --variable-values). */
    public static final class EpochVar {
        public final String name;
        public long writes = 0;
        public boolean deleted = false;
        public String type = null;
        public byte[] data = null;   // latest non-delete serialized value; null if delete or not retained
        EpochVar(String name) { this.name = name; }
    }

    /** Record this write into the current epoch (runs on the save thread, same as {@link #record}). */
    private void recordEpoch(String name, Object sv, boolean delete) {
        ConcurrentHashMap<String, EpochVar> ep = epoch;
        EpochVar ev = ep.get(name);
        if (ev == null) {
            if (ep.size() >= maxDistinct) return;   // same distinct-name bound as the cumulative map
            EpochVar created = new EpochVar(name);
            ev = ep.putIfAbsent(name, created);
            if (ev == null) ev = created;
        }
        ev.writes++;
        if (delete) {
            ev.deleted = true;
            ev.data = null;
        } else {
            ev.deleted = false;
            ev.type = extractType(sv);
            byte[] d = extractData(sv);
            if (d != null && d.length <= MAX_VALUE_BYTES && epochValueBytes + d.length <= MAX_EPOCH_VALUE_BYTES) {
                ev.data = d;
                epochValueBytes += d.length;   // soft cap: over-counts across overwrites, never under
            } else {
                ev.data = null;                // too large / over budget: keep the name + count, drop the value
            }
        }
    }

    /**
     * Take and reset the current epoch — the distinct variables written since the previous call.
     * Called by {@link VariableFlushTracker} when a variables.csv save runs (on the watch thread),
     * so each save can be paired with the variables it compacted. Sorted by write count, then name.
     */
    public List<EpochVar> rotateEpoch() {
        ConcurrentHashMap<String, EpochVar> old = epoch;
        epoch = new ConcurrentHashMap<>();
        epochValueBytes = 0;
        List<EpochVar> list = new ArrayList<>(old.values());
        list.sort(Comparator.comparingLong((EpochVar v) -> v.writes).reversed().thenComparing(v -> v.name));
        return list;
    }

    // ---------- reflection helpers ----------

    private List<?> findStorages(Class<?> variablesClass) {
        // Prefer the documented field name.
        try {
            Field f = variablesClass.getDeclaredField("STORAGES");
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof List<?> l) return l;
        } catch (Throwable ignored) { }
        // Fallback: any static List field whose elements are VariablesStorage instances.
        for (Field f : variablesClass.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!List.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(null);
                if (v instanceof List<?> l && !l.isEmpty() && isVariablesStorage(l.get(0))) return l;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static boolean isVariablesStorage(Object o) {
        if (o == null) return false;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().equals("ch.njol.skript.variables.VariablesStorage")) return true;
        }
        return false;
    }

    private static Field findChangesQueueField(Object storage) {
        // Prefer the documented field name (declared on the VariablesStorage superclass).
        for (Class<?> c = storage.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("changesQueue");
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        // Fallback: first LinkedBlockingQueue-typed field.
        for (Class<?> c = storage.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (LinkedBlockingQueue.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    private String extractName(Object sv) {
        try {
            if (svNameField == null) svNameField = field(sv.getClass(), "name");
            if (svNameField == null) return null;
            Object n = svNameField.get(sv);
            return n instanceof String s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object extractValue(Object sv) {
        try {
            if (svValueField == null) svValueField = field(sv.getClass(), "value");
            if (svValueField == null) return null;
            return svValueField.get(sv);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The latest serialized value bytes of a SerializedVariable (the {@code byte[]} on its Value). */
    private byte[] extractData(Object sv) {
        try {
            Object value = extractValue(sv);
            if (value == null) return null;
            if (svDataField == null || !svDataField.getDeclaringClass().isInstance(value)) {
                svDataField = byteArrayField(value.getClass());
            }
            if (svDataField == null) return null;
            Object d = svDataField.get(value);
            return d instanceof byte[] b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** First {@code byte[]}-typed field walking up the hierarchy, made accessible; null if none. */
    private static Field byteArrayField(Class<?> c) {
        for (; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == byte[].class) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    private String extractType(Object sv) {
        try {
            Object value = extractValue(sv);
            if (value == null) return null;
            if (valueTypeField == null || !valueTypeField.getDeclaringClass().isInstance(value)) {
                valueTypeField = field(value.getClass(), "type");
            }
            if (valueTypeField == null) return null;
            Object t = valueTypeField.get(value);
            return t instanceof String s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** First field with {@code name} walking up the hierarchy, made accessible; null if none. */
    private static Field field(Class<?> c, String name) {
        for (; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private record QueueSwap(Field field, Object holder, Object original) {}

    /**
     * A {@link LinkedBlockingQueue} whose own storage is never used: every operation delegates to
     * the {@code original} queue, so both Skript's producer and consumer keep operating on one
     * underlying queue. We intercept only the enqueue methods ({@code offer}/{@code put} — the
     * ones {@code VariablesStorage.save} actually calls) to count writes. Type-compatible with the
     * concrete {@code LinkedBlockingQueue changesQueue} field so it can be swapped in by reflection.
     */
    private final class ObservingQueue extends LinkedBlockingQueue<Object> {
        private final LinkedBlockingQueue<Object> original;

        ObservingQueue(LinkedBlockingQueue<Object> original) {
            super(1);            // placeholder capacity; this instance's own storage is never used
            this.original = original;
        }

        private void observe(Object e) {
            try { record(e); } catch (Throwable ignored) { }   // never let bookkeeping break a save
        }

        // --- enqueue (observed) ---
        @Override public boolean offer(Object e) { observe(e); return original.offer(e); }
        @Override public boolean offer(Object e, long timeout, TimeUnit unit) throws InterruptedException {
            observe(e); return original.offer(e, timeout, unit);
        }
        @Override public void put(Object e) throws InterruptedException { observe(e); original.put(e); }

        // --- everything else: pure delegation to the original queue ---
        @Override public Object take() throws InterruptedException { return original.take(); }
        @Override public Object poll() { return original.poll(); }
        @Override public Object poll(long timeout, TimeUnit unit) throws InterruptedException {
            return original.poll(timeout, unit);
        }
        @Override public Object peek() { return original.peek(); }
        @Override public int size() { return original.size(); }
        @Override public int remainingCapacity() { return original.remainingCapacity(); }
        @Override public boolean isEmpty() { return original.isEmpty(); }
        @Override public boolean remove(Object o) { return original.remove(o); }
        @Override public boolean contains(Object o) { return original.contains(o); }
        @Override public void clear() { original.clear(); }
        @Override public Object[] toArray() { return original.toArray(); }
        @Override public <T> T[] toArray(T[] a) { return original.toArray(a); }
        @Override public String toString() { return original.toString(); }
        @Override public int drainTo(Collection<? super Object> c) { return original.drainTo(c); }
        @Override public int drainTo(Collection<? super Object> c, int maxElements) {
            return original.drainTo(c, maxElements);
        }
        @Override public Iterator<Object> iterator() { return original.iterator(); }
        @Override public Spliterator<Object> spliterator() { return original.spliterator(); }
    }
}
