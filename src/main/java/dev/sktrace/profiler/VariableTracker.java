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
import java.util.concurrent.atomic.LongAdder;
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

    private final LongAdder creates = new LongAdder();
    private final LongAdder updates = new LongAdder();
    private final LongAdder deletes = new LongAdder();
    private final AtomicLong currentTickWrites = new AtomicLong();

    // name -> per-variable stats. Bounded: once maxDistinct distinct names have been seen we
    // stop adding new entries (aggregate totals stay correct) and flip `capped`. Written only on
    // Skript's single save thread (see ObservingQueue), read on the main thread at report time.
    private final Map<String, VarStat> vars = new ConcurrentHashMap<>();
    private volatile boolean capped = false;

    // Per-tick write counts, same ring/grow scheme as TriggerStats. Touched only on the main
    // thread (snapshotTick / perTickCopy), so a plain synchronized block is enough.
    private long[] perTick;
    private int perTickCount = 0;
    private int perTickPos = 0;

    private final List<QueueSwap> swaps = new ArrayList<>();

    // Lazily-resolved SerializedVariable fields, cached after first success.
    private Field svNameField;
    private Field svValueField;
    private Field valueTypeField;

    public VariableTracker(Sktrace plugin, int maxDistinct, int tickCapacity) {
        this.plugin = plugin;
        this.maxDistinct = Math.max(1, maxDistinct);
        this.tickCapacity = Math.max(0, tickCapacity);
        int initial = this.tickCapacity > 0 ? this.tickCapacity : 1024;
        this.perTick = new long[initial];
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

        VarStat st = vars.get(name);
        if (st == null) {
            if (vars.size() >= maxDistinct) {
                // At the distinct-name cap: keep aggregate totals correct but don't grow the map.
                capped = true;
                if (delete) deletes.increment(); else updates.increment();
                return;
            }
            st = new VarStat(name);
            vars.put(name, st);
            if (delete) {            // delete of a name not yet seen this window
                st.deletes++;
                deletes.increment();
            } else {                 // first sight this window => created
                st.created = true;
                st.sets++;
                st.lastType = extractType(serializedVariable);
                creates.increment();
            }
            return;
        }
        if (delete) {
            st.deletes++;
            deletes.increment();
        } else {
            st.sets++;
            st.lastType = extractType(serializedVariable);
            updates.increment();
        }
    }

    // ---------- per-tick write series (main thread) ----------

    synchronized void snapshotTick() {
        long w = currentTickWrites.getAndSet(0);
        if (tickCapacity > 0) {
            perTick[perTickPos] = w;
            perTickPos = (perTickPos + 1) % tickCapacity;
            if (perTickCount < tickCapacity) perTickCount++;
        } else {
            if (perTickCount >= perTick.length) {
                perTick = java.util.Arrays.copyOf(perTick, perTick.length * 2);
            }
            perTick[perTickCount++] = w;
            perTickPos = perTickCount;
        }
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
    public long creates() { return creates.sum(); }
    public long updates() { return updates.sum(); }
    public long deletes() { return deletes.sum(); }
    public long totalWrites() { return creates.sum() + updates.sum() + deletes.sum(); }
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
