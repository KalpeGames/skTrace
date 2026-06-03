// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.profiler;

import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.Trigger;
import org.bukkit.event.Event;
import org.skriptlang.skript.lang.script.Script;

import java.util.Collections;

/**
 * Thin delegating wrapper around a Skript Trigger.
 *
 * Constructed with an empty body via super(), so we don't have to reconstruct
 * the original Trigger's internal linked-list of TriggerItems. When execute()
 * is called, we time the call and forward it to the original Trigger, which
 * still owns the real body. Metadata-fetching getters delegate to the original
 * so anything in Skript that asks "what event/script/name is this?" stays correct.
 */
public final class ProfilingTrigger extends Trigger {

    private final Trigger original;
    private final Profiler profiler;
    private final String id;
    // When true this wraps a Skript function body rather than an event trigger,
    // so timings are recorded into the profiler's separate function layer. Function
    // time is nested inside its caller's trigger time, so it must NOT feed the
    // per-tick Skript total (the trigger already counts it) — recordFunction handles that.
    private final boolean isFunction;

    public ProfilingTrigger(Script script, String name, SkriptEvent event,
                            Trigger original, Profiler profiler, String id) {
        this(script, name, event, original, profiler, id, false);
    }

    public ProfilingTrigger(Script script, String name, SkriptEvent event,
                            Trigger original, Profiler profiler, String id, boolean isFunction) {
        super(script, name, event, Collections.emptyList());
        this.original = original;
        this.profiler = profiler;
        this.id = id;
        this.isFunction = isFunction;
    }

    @Override
    public boolean execute(Event e) {
        long start = System.nanoTime();
        try {
            return original.execute(e);
        } finally {
            long elapsed = System.nanoTime() - start;
            if (isFunction) profiler.recordFunction(id, elapsed);
            else profiler.recordTrigger(id, elapsed);
        }
    }

    public Trigger original() { return original; }
    public String id() { return id; }
}
