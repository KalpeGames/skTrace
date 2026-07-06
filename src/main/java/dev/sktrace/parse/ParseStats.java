// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of one parse audit: the lines Skript itself flagged as slow to parse.
 *
 * <p>A line is here only because Skript's own loader — parsing it in order and in full context —
 * measured it taking longer than the configured threshold. So this is deliberately a short list of
 * lines worth a builder's attention, not a ranking of every line. The thousands of fast lines never
 * appear, because they never crossed the bar. That's the point: it answers "which lines are bad",
 * not "how does every line compare".
 */
public final class ParseStats {

    /** One flagged line: where it is and what it says. */
    public static final class Line {
        public final String script;
        public final int lineNumber;
        public final String source;

        Line(String script, int lineNumber, String source) {
            this.script = script;
            this.lineNumber = lineNumber;
            this.source = source;
        }

        public String script() { return script; }
        public int lineNumber() { return lineNumber; }
        public String source() { return source; }
    }

    private final List<Line> lines = new ArrayList<>();
    private final long generation;
    private final long startedAtMillis;
    private long finishedAtMillis;
    private long thresholdMs;
    private boolean finished = false;

    public ParseStats(long generation, long startedAtMillis) {
        this.generation = generation;
        this.startedAtMillis = startedAtMillis;
    }

    public void add(String script, int lineNumber, String source) {
        lines.add(new Line(script, lineNumber, source));
    }

    public void finish(long finishedAtMillis, long thresholdMs) {
        this.finishedAtMillis = finishedAtMillis;
        this.thresholdMs = thresholdMs;
        this.finished = true;
    }

    /** The flagged lines, capped, in the order Skript reported them. */
    public List<Line> flaggedLines(int limit) {
        return lines.size() > limit ? new ArrayList<>(lines.subList(0, limit)) : new ArrayList<>(lines);
    }

    public List<Line> flaggedLines() { return new ArrayList<>(lines); }

    public boolean isFinished() { return finished; }
    public long generation() { return generation; }
    public int flaggedCount() { return lines.size(); }
    public long thresholdMs() { return thresholdMs; }
    public long startedAtMillis() { return startedAtMillis; }
    public long finishedAtMillis() { return finishedAtMillis; }
}
