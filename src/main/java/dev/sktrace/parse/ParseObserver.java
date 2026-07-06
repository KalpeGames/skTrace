// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.parse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catches Skript's own "this line took a long time to parse" signal.
 *
 * <h2>Why this is the right mechanism (after three wrong ones)</h2>
 * Skript's {@code ScriptLoader.loadItems} already parses every line of a script <b>in order and in
 * context</b> — it passes the list of already-parsed preceding lines into {@code Statement.parse},
 * which is exactly why context-dependent lines ({@code else}, {@code loop-value}, {@code return},
 * {@code if {_role}}) resolve correctly. Trying to re-parse a line in isolation (an earlier attempt)
 * makes all of those fail, because the surrounding loop / if-chain / function / variable-type hints
 * are gone.
 *
 * <p>And {@code loadItems} <b>already times each line</b> and, when a line exceeds
 * {@code long parse time warning threshold}, emits a node-tagged warning through the log system
 * (this is the built-in facility for reporting slow lines). So we don't measure anything ourselves:
 * we lower that threshold, let Skript's normal reload run (its regular async path — no forced
 * synchronous loading, so no player is disconnected), and simply record which lines Skript itself
 * flags as slow, reading the file + line straight off each warning's {@code Node}.
 *
 * <p>This is a {@link ch.njol.skript.log.LogHandler} placed on the parser's log stack for the
 * duration of a parse. It records the flagged lines and <b>swallows</b> the warnings so they don't
 * flood the console. Only lines over the threshold ever reach it, so there is no per-line spam and
 * no measuring of trivial lines — the threshold is, in effect, the "is this line worth a builder's
 * attention?" bar.
 */
public final class ParseObserver extends ch.njol.skript.log.LogHandler {

    /** The marker text Skript uses for the slow-line warning; matched as a substring so wording
     *  tweaks across versions still match. */
    private static final String SLOW_LINE_MARKER = "took a long time to parse";

    /** file:line -> flagged line (deduped: a line re-flagged across reloads updates in place). */
    private final Map<String, Flagged> flagged = new LinkedHashMap<>();

    public record Flagged(String script, int line, String source) {}

    @Override
    public LogResult log(ch.njol.skript.log.LogEntry entry) {
        try {
            String msg = entry.getMessage();
            if (msg != null && msg.contains(SLOW_LINE_MARKER)) {
                record(entry);
                // Swallow it — we surface these through the audit, not as raw console spam.
                return LogResult.DO_NOT_LOG;
            }
        } catch (Throwable ignored) {
            // Never let observation break a parse.
        }
        // Everything else (real errors, other warnings, info) passes through untouched.
        return LogResult.LOG;
    }

    private void record(ch.njol.skript.log.LogEntry entry) {
        Object node = entry.node;
        if (node == null) return;
        int line = safeLine(node);
        String script = safeScript(node);
        if (line <= 0 || script == null) return;
        String source = safeSource(node);
        flagged.put(script + ":" + line, new Flagged(script, line, source));
    }

    /** Snapshot of the lines flagged so far, in first-seen order. */
    public java.util.List<Flagged> snapshot() {
        return new java.util.ArrayList<>(flagged.values());
    }

    public void clear() { flagged.clear(); }
    public int count() { return flagged.size(); }

    // ---- node introspection via the public config API ----

    private static int safeLine(Object node) {
        try { return ((ch.njol.skript.config.Node) node).getLine(); }
        catch (Throwable t) { return -1; }
    }

    private static String safeScript(Object node) {
        try {
            ch.njol.skript.config.Config config = ((ch.njol.skript.config.Node) node).getConfig();
            String name = config.getFileName();
            return name != null && !name.isEmpty() ? name : null;
        } catch (Throwable t) { return null; }
    }

    private static String safeSource(Object node) {
        try {
            String key = ((ch.njol.skript.config.Node) node).getKey();
            return key == null ? "" : key.trim();
        } catch (Throwable t) { return ""; }
    }

    @Override
    public ParseObserver start() {
        return (ParseObserver) super.start();
    }
}
