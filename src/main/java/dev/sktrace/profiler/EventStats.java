// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import java.util.concurrent.atomic.LongAdder;

public final class EventStats {

    private final String eventClassName;
    private final LongAdder calls = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private volatile long maxNanos = 0;

    public EventStats(String eventClassName) {
        this.eventClassName = eventClassName;
    }

    public void record(long nanos) {
        calls.increment();
        totalNanos.add(nanos);
        if (nanos > maxNanos) maxNanos = nanos;
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
}
