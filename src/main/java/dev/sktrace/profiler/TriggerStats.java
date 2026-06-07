// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class TriggerStats {

    private final String id;
    private final String scriptName;
    private final String triggerName;
    private final int line;
    private final LongAdder calls = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private volatile long maxNanos = 0;

    // Per-tick: accumulator filled during a tick, snapshotted by the aggregator at tick end.
    // tickNanos and tickCalls grow in lockstep; tickNanosCount is the shared length.
    //
    // Two storage modes:
    //   - capacity == 0 (one-shot): arrays grow unboundedly via copyOf.
    //   - capacity  > 0 (rolling):  arrays are fixed-size ring buffers — once full,
    //     writes wrap to position 0, oldest entries are overwritten. Used so a
    //     rolling profile that's been on for hours stays bounded in memory.
    private final AtomicLong currentTickAcc = new AtomicLong();
    private final AtomicLong currentTickCalls = new AtomicLong();
    private final int tickCapacity;
    private long[] tickNanos;
    private long[] tickCalls;
    private int tickNanosCount = 0;  // # valid entries (clamped to tickCapacity in rolling mode)
    private int tickWritePos = 0;    // next write index in the buffer (mod tickCapacity in rolling)

    public TriggerStats(String id, String scriptName, String triggerName, int line) {
        this(id, scriptName, triggerName, line, 0);
    }

    public TriggerStats(String id, String scriptName, String triggerName, int line, int tickCapacity) {
        this.id = id;
        this.scriptName = scriptName;
        this.triggerName = triggerName;
        this.line = line;
        this.tickCapacity = Math.max(0, tickCapacity);
        int initial = tickCapacity > 0 ? tickCapacity : 1024;
        this.tickNanos = new long[initial];
        this.tickCalls = new long[initial];
    }

    public void record(long nanos) {
        calls.increment();
        totalNanos.add(nanos);
        // not atomic but acceptable for max-tracking under low contention
        if (nanos > maxNanos) maxNanos = nanos;
        currentTickAcc.addAndGet(nanos);
        currentTickCalls.incrementAndGet();
    }

    public synchronized void snapshotTick() {
        long ns = currentTickAcc.getAndSet(0);
        long c = currentTickCalls.getAndSet(0);
        if (tickCapacity > 0) {
            // Ring buffer: overwrite oldest once full.
            tickNanos[tickWritePos] = ns;
            tickCalls[tickWritePos] = c;
            tickWritePos = (tickWritePos + 1) % tickCapacity;
            if (tickNanosCount < tickCapacity) tickNanosCount++;
        } else {
            // Growing array (one-shot mode).
            if (tickNanosCount >= tickNanos.length) {
                int newLen = tickNanos.length * 2;
                tickNanos = java.util.Arrays.copyOf(tickNanos, newLen);
                tickCalls = java.util.Arrays.copyOf(tickCalls, newLen);
            }
            tickNanos[tickNanosCount] = ns;
            tickCalls[tickNanosCount] = c;
            tickNanosCount++;
            tickWritePos = tickNanosCount;
        }
    }

    public synchronized long[] tickNanosCopy() {
        return chronologicalCopy(tickNanos);
    }

    public synchronized long[] tickCallsCopy() {
        return chronologicalCopy(tickCalls);
    }

    private long[] chronologicalCopy(long[] buf) {
        if (tickCapacity == 0 || tickNanosCount < tickCapacity) {
            // Linear: data is in [0, tickNanosCount), already chronological.
            return java.util.Arrays.copyOf(buf, tickNanosCount);
        }
        // Ring is full: oldest sits at tickWritePos. Re-stitch.
        long[] out = new long[tickNanosCount];
        int oldest = tickWritePos;
        int tailLen = tickCapacity - oldest;
        System.arraycopy(buf, oldest, out, 0, tailLen);
        if (oldest > 0) System.arraycopy(buf, 0, out, tailLen, oldest);
        return out;
    }

    public synchronized void resetTickData() {
        tickNanosCount = 0;
        tickWritePos = 0;
        currentTickAcc.set(0);
        currentTickCalls.set(0);
    }

    public String id() { return id; }
    public String scriptName() { return scriptName; }
    public String triggerName() { return triggerName; }
    public int line() { return line; }
    public long calls() { return calls.sum(); }
    public long totalNanos() { return totalNanos.sum(); }
    public long maxNanos() { return maxNanos; }

    public long avgNanos() {
        long c = calls();
        return c == 0 ? 0 : totalNanos() / c;
    }

    // --- Windowed views: summed over the retained per-tick ring plus the in-progress tick. In
    // rolling mode these reflect just the buffer window (instead of the ever-climbing session totals
    // above); in one-shot mode the ring spans the whole run, so they equal calls()/totalNanos().
    // Reports use these so a rolling clip's Calls/Total columns match its window.

    private long sumValid(long[] arr) {
        int n = (tickCapacity > 0) ? Math.min(tickNanosCount, tickCapacity) : tickNanosCount;
        long s = 0;
        for (int i = 0; i < n; i++) s += arr[i];
        return s;
    }

    public synchronized long windowedCalls() { return sumValid(tickCalls) + currentTickCalls.get(); }
    public synchronized long windowedTotalNanos() { return sumValid(tickNanos) + currentTickAcc.get(); }
    public synchronized long windowedAvgNanos() {
        long c = sumValid(tickCalls) + currentTickCalls.get();
        return c == 0 ? 0 : (sumValid(tickNanos) + currentTickAcc.get()) / c;
    }
}
