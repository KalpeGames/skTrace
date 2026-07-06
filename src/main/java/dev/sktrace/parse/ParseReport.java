// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.parse;

import dev.sktrace.SkTrace;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a parse audit to {@code plugins/skTrace/audits/} — just the lines Skript flagged as slow
 * to parse, with their source text so a builder can see what to change. No listing of the fine
 * lines: those aren't a problem and padding the report with them is what made an earlier version
 * read as "everything is bad".
 */
public final class ParseReport {

    private final SkTrace plugin;

    public ParseReport(SkTrace plugin) {
        this.plugin = plugin;
    }

    public Path write(ParseStats stats, long stampMillis) throws IOException {
        Path dir = plugin.getDataFolder().toPath().resolve("audits");
        Files.createDirectories(dir);
        Path file = dir.resolve("parse-audit-" + stampMillis + ".txt");

        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("skTrace parse audit\n");
            w.write("===================\n\n");
            w.write("These are the lines Skript itself measured taking longer than "
                    + stats.thresholdMs() + "ms to PARSE (load), while parsing each script in order\n");
            w.write("and in full context. Parse cost is separate from runtime cost — a line can be\n");
            w.write("slow to load without being slow to run — and it's normally invisible (it scrolls\n");
            w.write("past in console on boot/reload). Fast lines aren't listed; they're not a problem.\n\n");

            List<ParseStats.Line> flagged = stats.flaggedLines();
            w.write(String.format("LINES SLOW TO PARSE  (%d)%n", flagged.size()));
            w.write("------------------------------------------------------------\n");
            if (flagged.isEmpty()) {
                w.write("  (none — no line took longer than the threshold to parse. Nothing to do.)\n");
            } else {
                for (ParseStats.Line l : flagged) {
                    String src = l.source() == null ? "" : l.source();
                    if (src.length() > 90) src = src.substring(0, 89) + "…";
                    w.write(String.format("  %s:%d%n      %s%n", l.script(), l.lineNumber(), src));
                }
            }
        }

        pruneOld(dir);
        return file;
    }

    private void pruneOld(Path dir) {
        int keep = Math.max(1, plugin.getConfig().getInt("parse-audit.keep-files", 10));
        try (var files = Files.list(dir)) {
            var sorted = files
                    .filter(p -> p.getFileName().toString().startsWith("parse-audit-"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .toList();
            for (int i = keep; i < sorted.size(); i++) {
                try { Files.deleteIfExists(sorted.get(i)); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
