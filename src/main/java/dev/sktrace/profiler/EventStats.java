// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class EventStats {

    private final String eventClassName;
    private final LongAdder calls = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private volatile long maxNanos = 0;

    // Per-tick ring buffers, same scheme as TriggerStats, so a report can show this event's calls and
    // time for just the rolling window rather than the whole (never-ending) session. capacity == 0 is
    // one-shot (grow); capacity > 0 is rolling (fixed ring).
    private final AtomicLong currentTickAcc = new AtomicLong();
    private final AtomicLong currentTickCalls = new AtomicLong();
    private final int tickCapacity;
    private long[] tickNanos;
    private long[] tickCalls;
    private int tickNanosCount = 0;
    private int tickWritePos = 0;

    public EventStats(String eventClassName) {
        this(eventClassName, 0);
    }

    public EventStats(String eventClassName, int tickCapacity) {
        this.eventClassName = eventClassName;
        this.tickCapacity = Math.max(0, tickCapacity);
        int initial = this.tickCapacity > 0 ? this.tickCapacity : 1024;
        this.tickNanos = new long[initial];
        this.tickCalls = new long[initial];
    }

    public void record(long nanos) {
        calls.increment();
        totalNanos.add(nanos);
        if (nanos > maxNanos) maxNanos = nanos;
        currentTickAcc.addAndGet(nanos);
        currentTickCalls.incrementAndGet();
    }

    public synchronized void snapshotTick() {
        long ns = currentTickAcc.getAndSet(0);
        long c = currentTickCalls.getAndSet(0);
        if (tickCapacity > 0) {
            tickNanos[tickWritePos] = ns;
            tickCalls[tickWritePos] = c;
            tickWritePos = (tickWritePos + 1) % tickCapacity;
            if (tickNanosCount < tickCapacity) tickNanosCount++;
        } else {
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

    public String eventClassName() { return eventClassName; }
    public String shortName() {
        int dot = eventClassName.lastIndexOf('.');
        return dot < 0 ? eventClassName : eventClassName.substring(dot + 1);
    }
    public long calls() { return calls.sum(); }
    public long totalNanos() { return totalNanos.sum(); }
    public long maxNanos() { return maxNanos; }

    public long avgNanos() {
        long c = calls();
        return c == 0 ? 0 : totalNanos() / c;
    }

    // --- Windowed views: see TriggerStats. Reports use these so a rolling clip's event counts/time
    // reflect the buffer window; in one-shot mode they equal calls()/totalNanos().

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
