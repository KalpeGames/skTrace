// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import java.util.concurrent.atomic.LongAdder;

/**
 * Per-statement (top-level body item) aggregate stats for one trigger.
 * Counterpart to {@link TriggerStats}, one rung lower.
 */
public final class ItemStats {

    private final int index;
    private final String label;
    private final int line;
    private final LongAdder calls = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private volatile long maxNanos = 0;

    public ItemStats(int index, String label) {
        this(index, label, -1);
    }

    public ItemStats(int index, String label, int line) {
        this.index = index;
        this.label = label;
        this.line = line;
    }

    public void record(long nanos) {
        calls.increment();
        totalNanos.add(nanos);
        if (nanos > maxNanos) maxNanos = nanos;
    }

    public int index() { return index; }
    public String label() { return label; }
    public int line() { return line; }
    public long calls() { return calls.sum(); }
    public long totalNanos() { return totalNanos.sum(); }
    public long maxNanos() { return maxNanos; }

    public long avgNanos() {
        long c = calls();
        return c == 0 ? 0 : totalNanos() / c;
    }
}
