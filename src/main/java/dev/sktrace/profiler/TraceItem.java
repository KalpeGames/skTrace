// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import ch.njol.skript.lang.TriggerItem;
import org.bukkit.event.Event;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A non-executing TriggerItem inserted around a user statement to record its wall
 * time. Tracers come in pairs: a START before the user item and an END after it,
 * sharing a unique {@code key}. The START stamps {@code System.nanoTime()} into a
 * per-thread map under that key; the END pops it and adds the delta straight into
 * the statement's {@link ItemStats} slot. Pair-keyed timing survives reentrancy
 * (event A's tracer firing triggers another script chain on the same thread)
 * because each pair has its own slot.
 *
 * The END marker holds its {@link ItemStats} directly, so the hot path is a single
 * field dereference plus a {@link ItemStats#record} — no per-execution lookups by
 * triggerId/itemIndex, and no overhead-accounting nanoTime calls.
 *
 * Known limitation: if the wrapped item calls {@code wait Xs}, Skript's runtime
 * returns from walk() and reschedules later. The END tracer then runs much later
 * and the recorded duration includes the delay. We don't filter that here — the
 * report can choose to flag implausibly large per-item times.
 */
public final class TraceItem extends TriggerItem {

    private static final ThreadLocal<HashMap<Long, Long>> STARTS =
            ThreadLocal.withInitial(HashMap::new);
    private static final AtomicLong NEXT_KEY = new AtomicLong();

    public static long newKey() { return NEXT_KEY.incrementAndGet(); }

    private final boolean isStart;
    private final long key;
    private final int itemIndex;
    // Set on END markers only: the slot to record this statement's duration into.
    private ItemStats stats;
    private TriggerItem realNext;

    TraceItem(boolean isStart, long key, int itemIndex) {
        this.isStart = isStart;
        this.key = key;
        this.itemIndex = itemIndex;
    }

    void setStats(ItemStats stats) { this.stats = stats; }

    void setRealNext(TriggerItem realNext) { this.realNext = realNext; }

    TriggerItem realNext() { return realNext; }

    @Override
    protected boolean run(Event e) { return true; }

    @Override
    protected TriggerItem walk(Event e) {
        long now = System.nanoTime();
        HashMap<Long, Long> starts = STARTS.get();
        if (isStart) {
            starts.put(key, now);
        } else {
            Long start = starts.remove(key);
            if (start != null && stats != null) {
                stats.record(now - start);
            }
        }
        return realNext;
    }

    @Override
    public String toString(Event e, boolean debug) {
        return "[sktrace " + (isStart ? "start" : "end") + " #" + itemIndex + "]";
    }
}
