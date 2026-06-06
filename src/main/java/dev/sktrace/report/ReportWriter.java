// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.report;

import dev.sktrace.Sktrace;
import dev.sktrace.profiler.EventStats;
import dev.sktrace.profiler.ItemStats;
import dev.sktrace.profiler.Profiler;
import dev.sktrace.profiler.TriggerStats;
import dev.sktrace.profiler.VariableTracker;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class ReportWriter {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Sktrace plugin;
    private final Profiler profiler;

    public ReportWriter(Sktrace plugin, Profiler profiler) {
        this.plugin = plugin;
        this.profiler = profiler;
    }

    public Path write() throws IOException {
        return write(false);
    }

    public Path write(boolean includeSources) throws IOException {
        String dataJson = renderDataJson(includeSources, false, true);
        return writeRendered(renderShell(dataJson), dataJson, includeSources, false);
    }

    /**
     * Persist a rendered HTML body plus the JSON sidecar. The sidecar holds the SAME data blob
     * that's embedded in the HTML ({@code window.SKTRACE_DATA}), so it can be uploaded to the
     * share worker to reproduce the identical report — handy when auto-upload fails (e.g. HTTP
     * 413 on a large capture). Clip mode just changes the filename prefix so users can
     * distinguish on-demand clips from full /sktrace start/stop reports in their reports/ folder.
     */
    public Path writeRendered(String html, String dataJson, boolean includeSources, boolean clipMode)
            throws IOException {
        Path dir = plugin.getDataFolder().toPath().resolve("reports");
        Files.createDirectories(dir);

        String stem = (clipMode ? "sktrace-clip-" : "sktrace-") + LocalDateTime.now().format(FILE_TS);
        Path htmlPath = dir.resolve(stem + ".html");
        Path jsonPath = dir.resolve(stem + ".json");

        Files.writeString(htmlPath, html, StandardCharsets.UTF_8);
        Files.writeString(jsonPath, dataJson, StandardCharsets.UTF_8);

        return htmlPath;
    }

    public String renderHtml(boolean includeSources) {
        return renderHtml(includeSources, false, true);
    }

    public String renderHtml(boolean includeSources, boolean clipMode) {
        return renderHtml(includeSources, clipMode, true);
    }

    public String renderHtml(boolean includeSources, boolean clipMode, boolean maskSecrets) {
        return renderShell(renderDataJson(includeSources, clipMode, maskSecrets));
    }

    /**
     * Wrap the trusted static shell around an already-built data blob. Public so callers that
     * computed the blob to also write it as the {@code .json} sidecar don't have to build it
     * twice (and so the embedded HTML and the sidecar are guaranteed identical).
     *
     * The whole page = trusted static shell + the data blob. Every visible element (header,
     * glance, tables, charts) is rendered client-side from the data by report.app.js. The share
     * worker reuses the same shell + JS, stores only this JSON, and never serves uploaded HTML —
     * see ../sktrace-web/src/index.ts.
     */
    public String renderShell(String dataJson) {
        return SHELL
                .replace("{{FONT_URL}}", assetUrl("fonts.css"))
                .replace("{{LOGO_LIGHT}}", assetUrl("logo-light.png"))
                .replace("{{LOGO_DARK}}", assetUrl("logo-dark.png"))
                .replace("{{CSS}}", CSS)
                .replace("{{APP_JS}}", APP_JS)
                .replace("{{DATA_JSON}}", dataJson);
    }

    /**
     * Build just the profiler data blob (the {@code window.SKTRACE_DATA} object). This is exactly
     * what gets embedded in the HTML <em>and</em> written as the {@code .json} sidecar, so
     * uploading the sidecar to the share worker reproduces the identical report.
     */
    public String renderDataJson(boolean includeSources, boolean clipMode, boolean maskSecrets) {
        List<TriggerStats> allTriggers = profiler.triggerStats().values().stream()
                .sorted(Comparator.comparingLong(TriggerStats::totalNanos).reversed())
                .toList();
        List<TriggerStats> activeTriggers = allTriggers.stream()
                .filter(t -> t.calls() > 0)
                .toList();
        int silentCount = allTriggers.size() - activeTriggers.size();
        // Functions are a separate layer: ranked, but kept out of the trigger
        // aggregations (donut/worst-tick) since their time nests inside callers'.
        List<TriggerStats> activeFunctions = profiler.functionStats().values().stream()
                .filter(t -> t.calls() > 0)
                .sorted(Comparator.comparingLong(TriggerStats::totalNanos).reversed())
                .toList();
        long durationMs = profiler.effectiveWindowMs();

        // Pre-load script sources for the modal viewer (best-effort, capped per-file size).
        // Skipped when includeSources=false so the report can be shared without leaking code.
        Map<String, String> sources;
        if (includeSources) {
            Set<String> wantedScripts = new HashSet<>();
            for (TriggerStats t : activeTriggers) wantedScripts.add(t.scriptName());
            for (TriggerStats f : activeFunctions) wantedScripts.add(f.scriptName());
            sources = loadScriptSources(wantedScripts);
            // Mask secrets before the source is ever embedded, so tokens never leave the
            // server even when uploaded. Opt out with --show-secrets (maskSecrets=false).
            if (maskSecrets && !sources.isEmpty()) {
                sources.replaceAll((name, code) -> maskSensitiveSource(code));
            }
        } else {
            sources = java.util.Collections.emptyMap();
        }

        String generatedAt = LocalDateTime.now().format(HUMAN_TS);
        return renderTickJsonData(activeTriggers, activeFunctions, sources, durationMs,
                includeSources, clipMode, allTriggers.size(), silentCount, generatedAt, maskSecrets);
    }

    private String renderTickJsonData(List<TriggerStats> activeTriggers,
                                      List<TriggerStats> activeFunctions,
                                      Map<String, String> sources, long windowMs,
                                      boolean sourcesIncluded, boolean clipMode,
                                      int totalTriggers, int silentCount, String generatedAt,
                                      boolean maskSecrets) {
        long[] totals = profiler.tickSamplesCopy();
        long[] durations = profiler.tickDurationsCopy();
        StringBuilder sb = new StringBuilder(activeTriggers.size() * 256 + totals.length * 16);
        sb.append("{\"overheadNs\":").append(profiler.selfOverheadNanos());
        sb.append(",\"windowMs\":").append(windowMs);
        sb.append(",\"sourcesIncluded\":").append(sourcesIncluded);
        sb.append(",\"clipMode\":").append(clipMode);
        // Self-contained chrome metadata: the page (whether written by the plugin or
        // re-rendered by the share worker) is built entirely from this JSON plus a
        // static, trusted template — no server-rendered, user-influenced HTML.
        sb.append(",\"generatedAt\":\"").append(jsonEscape(generatedAt)).append("\"");
        sb.append(",\"hooksOk\":").append(profiler.triggerHooksAvailable());
        sb.append(",\"hookReason\":\"").append(jsonEscape(profiler.triggerHookFailureReason())).append("\"");
        sb.append(",\"totalTriggers\":").append(totalTriggers);
        sb.append(",\"silentCount\":").append(silentCount);
        sb.append(",\"tickTotal\":[");
        for (int i = 0; i < totals.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(totals[i]);
        }
        sb.append("],\"tickDuration\":[");
        for (int i = 0; i < durations.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(durations[i]);
        }
        sb.append("],\"triggers\":[");
        boolean first = true;
        for (TriggerStats t : activeTriggers) {
            if (!first) sb.append(',');
            first = false;
            appendUnitJson(sb, t);
        }
        sb.append("],\"functions\":[");
        boolean firstFn = true;
        for (TriggerStats f : activeFunctions) {
            if (!firstFn) sb.append(',');
            firstFn = false;
            appendUnitJson(sb, f);
        }
        sb.append("],\"events\":[");
        boolean firstEv = true;
        for (EventStats e : profiler.eventStats().values().stream()
                .sorted(Comparator.comparingLong(EventStats::totalNanos).reversed())
                .toList()) {
            if (!firstEv) sb.append(',');
            firstEv = false;
            sb.append("{\"name\":\"").append(jsonEscape(e.shortName())).append("\"")
                    .append(",\"calls\":").append(e.calls())
                    .append(",\"totalNs\":").append(e.totalNanos())
                    .append(",\"avgNs\":").append(e.avgNanos())
                    .append(",\"maxNs\":").append(e.maxNanos()).append('}');
        }
        sb.append("]");
        appendVariablesJson(sb, maskSecrets);
        // Script sources are emitted ONCE here as a shared map (script name -> text),
        // never inline per unit. Many functions (and triggers) share one script file,
        // so inlining duplicated the same source dozens of times and could push a
        // report past the upload limit. The client looks up data.sources[unit.script].
        sb.append(",\"sources\":{");
        boolean firstSrc = true;
        for (Map.Entry<String, String> e : sources.entrySet()) {
            if (e.getValue() == null) continue;
            if (!firstSrc) sb.append(',');
            firstSrc = false;
            sb.append('"').append(jsonEscape(e.getKey())).append("\":\"")
                    .append(jsonEscape(e.getValue())).append('"');
        }
        sb.append("}}");
        return sb.toString();
    }

    /**
     * Appends one trigger/function as a JSON object (no leading/trailing comma). Triggers
     * and functions share this shape so the report's source viewer — per-line gutter, hot
     * lines, heat map — works identically for both. Per-line "lines" come from the
     * profiler's itemStats keyed by the unit's id (only present when line-level tracing
     * was on). The script text is NOT inlined here — units only carry "script", and the
     * client resolves it against the shared top-level "sources" map.
     */
    private void appendUnitJson(StringBuilder sb, TriggerStats t) {
        long[] ticks = t.tickNanosCopy();
        long[] tcalls = t.tickCallsCopy();
        sb.append("{\"script\":\"").append(jsonEscape(t.scriptName())).append("\"")
                .append(",\"line\":").append(t.line())
                .append(",\"name\":\"").append(jsonEscape(t.triggerName())).append("\"")
                .append(",\"calls\":").append(t.calls())
                .append(",\"totalNs\":").append(t.totalNanos())
                .append(",\"avgNs\":").append(t.avgNanos())
                .append(",\"maxNs\":").append(t.maxNanos())
                .append(",\"nanos\":[");
        for (int i = 0; i < ticks.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(ticks[i]);
        }
        sb.append("],\"tickCalls\":[");
        for (int i = 0; i < tcalls.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(tcalls[i]);
        }
        sb.append("]");
        Map<Integer, ItemStats> lines = profiler.itemStats(t.id());
        if (!lines.isEmpty()) {
            sb.append(",\"lines\":[");
            boolean firstLine = true;
            // Map keys are Integer (insertion-ordered via LinkedHashMap on the
            // profiler side), but ConcurrentHashMap.computeIfAbsent doesn't
            // preserve order, so sort by index here to keep source order.
            java.util.List<Map.Entry<Integer, ItemStats>> entries =
                    new java.util.ArrayList<>(lines.entrySet());
            entries.sort(Map.Entry.comparingByKey());
            for (Map.Entry<Integer, ItemStats> e : entries) {
                ItemStats it = e.getValue();
                if (it.calls() == 0) continue;
                if (!firstLine) sb.append(',');
                firstLine = false;
                sb.append("{\"i\":").append(it.index())
                        .append(",\"label\":\"").append(jsonEscape(it.label())).append("\"")
                        .append(",\"line\":").append(it.line())
                        .append(",\"calls\":").append(it.calls())
                        .append(",\"totalNs\":").append(it.totalNanos())
                        .append(",\"avgNs\":").append(it.avgNanos())
                        .append(",\"maxNs\":").append(it.maxNanos())
                        .append('}');
            }
            sb.append("]");
        }
        sb.append("}");
    }

    /**
     * Append {@code ,"variables":{...}} describing GLOBAL variable writes observed during the
     * window. When tracking is off/unavailable we still emit the object with {@code ok:false} plus
     * a reason, so the report can explain why the section is empty. Only variable NAMES (and a
     * Skript serialized-type tag) are emitted — never values; under {@code maskSecrets}, names that
     * look sensitive are redacted the same way option values are.
     */
    private void appendVariablesJson(StringBuilder sb, boolean maskSecrets) {
        VariableTracker vt = profiler.variableTracker();
        sb.append(",\"variables\":{");
        if (vt == null || !vt.ok()) {
            sb.append("\"ok\":false,\"reason\":\"")
                    .append(jsonEscape(vt == null
                            ? "Variable tracking is off. Enable variable-tracking in config.yml."
                            : String.valueOf(vt.reason())))
                    .append("\"}");
            return;
        }
        sb.append("\"ok\":true")
                .append(",\"creates\":").append(vt.creates())
                .append(",\"updates\":").append(vt.updates())
                .append(",\"deletes\":").append(vt.deletes())
                .append(",\"distinct\":").append(vt.distinct())
                .append(",\"capped\":").append(vt.capped());
        long[] perTick = vt.perTickCopy();
        sb.append(",\"perTick\":[");
        for (int i = 0; i < perTick.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(perTick[i]);
        }
        sb.append("],\"top\":[");
        boolean first = true;
        for (VariableTracker.VarStat v : vt.topVars(50)) {
            if (!first) sb.append(',');
            first = false;
            String name = (maskSecrets && looksSensitive(v.name.toLowerCase(Locale.ROOT)))
                    ? maskValue(v.name) : v.name;
            sb.append("{\"name\":\"").append(jsonEscape(name)).append("\"")
                    .append(",\"sets\":").append(v.sets)
                    .append(",\"deletes\":").append(v.deletes)
                    .append(",\"created\":").append(v.created);
            if (v.lastType != null) {
                sb.append(",\"type\":\"").append(jsonEscape(v.lastType)).append("\"");
            }
            sb.append('}');
        }
        sb.append("]}");
    }

    /**
     * Best-effort load: walks Skript's plugins/Skript/scripts/ directory recursively
     * and pulls any .sk file matching a script we recorded a trigger from. Files larger
     * than 200KB are skipped to keep the report a reasonable size.
     *
     * Triggers can report their script as either a bare filename ("foo.sk") or a path
     * relative to scripts/ ("subdir/foo.sk"), depending on Skript version. We index every
     * .sk on disk by both forms, then for each wanted key try: exact match, basename
     * match, and finally a basename match against the wanted key's own basename. Last
     * resort handles the inverse case where the trigger reports a path but the file
     * happens to be at the root.
     */
    private Map<String, String> loadScriptSources(Set<String> wanted) {
        Map<String, String> out = new HashMap<>();
        if (wanted.isEmpty()) return out;
        try {
            Plugin skript = Bukkit.getPluginManager().getPlugin("Skript");
            if (skript == null) return out;
            Path scriptsDir = skript.getDataFolder().toPath().resolve("scripts");
            if (!Files.isDirectory(scriptsDir)) return out;

            Map<String, Path> byRelPath = new HashMap<>();
            Map<String, Path> byBasename = new HashMap<>();
            try (Stream<Path> stream = Files.walk(scriptsDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sk"))
                        .forEach(p -> {
                            String rel = scriptsDir.relativize(p).toString().replace('\\', '/');
                            byRelPath.put(rel, p);
                            // putIfAbsent so name collisions across folders deterministically resolve
                            // to the first one we found (better than overwriting with whoever is last).
                            byBasename.putIfAbsent(p.getFileName().toString(), p);
                        });
            }

            for (String key : wanted) {
                Path path = byRelPath.get(key);
                if (path == null) path = byBasename.get(key);
                if (path == null) {
                    String basename = stripDirs(key);
                    if (!basename.equals(key)) path = byBasename.get(basename);
                }
                if (path == null) continue;
                try {
                    if (Files.size(path) > 200_000L) continue;
                    out.put(key, Files.readString(path, StandardCharsets.UTF_8));
                } catch (IOException ignored) {}
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("[Sktrace] Failed to load script sources: " + t.getMessage());
        }
        return out;
    }

    private static String stripDirs(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // Lower-cased substrings that mark a line as likely holding a secret, used for
    // the keyword pass that masks quoted values outside of options: blocks
    // (e.g. `set {-token} to "..."`).
    private static final String[] SECRET_HINTS = {
            "token", "secret", "password", "passwd", "apikey", "api-key", "api_key",
            "api key", "webhook", "authorization", "auth-key", "credential", "private key"
    };

    /**
     * Mask values that look sensitive before the source is embedded, so secrets
     * (Discord tokens, API keys, passwords) never leave the server — even when the
     * report is uploaded with --include-files. Two heuristics:
     *   1. Every value inside a Skript {@code options:} block is masked. This is
     *      where tokens are overwhelmingly stashed.
     *   2. On any other line, a quoted string is masked when the line also mentions
     *      a sensitive keyword (token / secret / password / key / webhook / auth).
     * Only the first few characters of each value survive, so the line stays
     * recognizable without leaking the secret. Disabled with --show-secrets.
     */
    static String maskSensitiveSource(String src) {
        if (src == null || src.isEmpty()) return src;
        String[] lines = src.split("\n", -1);
        int optIndent = -1; // -1 = not in an options: block; else indent of its header
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String trimmed = line.strip();
            int indent = leadingWhitespace(line);

            if (optIndent >= 0) {
                if (trimmed.isEmpty()) continue;          // blanks stay inside the block
                if (indent <= optIndent) {
                    optIndent = -1;                        // dedent → block ended; fall through
                } else {
                    lines[li] = maskOptionLine(line);      // option entry → mask its value
                    continue;
                }
            }

            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("options:")) {            // start of an options: block
                optIndent = indent;
                continue;
            }
            if (looksSensitive(lower) && line.indexOf('"') >= 0) {
                lines[li] = maskQuoted(line);
            }
        }
        return String.join("\n", lines);
    }

    private static boolean looksSensitive(String lowerLine) {
        for (String hint : SECRET_HINTS) if (lowerLine.contains(hint)) return true;
        return false;
    }

    private static int leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    // `    discord_token: SECRET` → `    discord_token: SE…`. Keeps the option name,
    // collapses spacing after the colon, masks the value.
    private static String maskOptionLine(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) return line;
        String value = line.substring(colon + 1).strip();
        if (value.isEmpty()) return line;
        return line.substring(0, colon + 1) + " " + maskValue(value);
    }

    // Mask the contents of every "..." on the line, keeping the quotes.
    private static String maskQuoted(String line) {
        StringBuilder out = new StringBuilder(line.length() + 4);
        boolean in = false;
        int start = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (!in) { in = true; start = i + 1; out.append('"'); }
                else { out.append(maskValue(line.substring(start, i))).append('"'); in = false; }
            } else if (!in) {
                out.append(c);
            }
        }
        if (in) out.append(maskValue(line.substring(start))); // unterminated quote
        return out.toString();
    }

    // Keep the first few characters then an ellipsis. Never reveals the whole value:
    // at most length-1 chars survive, capped at 3.
    private static String maskValue(String v) {
        int show = Math.min(3, Math.max(0, v.length() - 1));
        return v.substring(0, show) + "…";
    }

    private static final String DEFAULT_ASSET_BASE = "https://sktrace.kal.pe/assets";

    /**
     * Static assets (fonts, brand logo) are served once by the share worker under
     * /assets and referenced by every report instead of being inlined. Derive the
     * host from the configured upload endpoint so a self-hosted worker serves its
     * own assets; fall back to the public host. Opened offline, fonts fall back to
     * the system stack and the logo simply does not load.
     */
    private String assetUrl(String file) {
        try {
            java.net.URI u = java.net.URI.create(plugin.uploadEndpoint());
            if (u.getScheme() != null && u.getAuthority() != null) {
                return u.getScheme() + "://" + u.getAuthority() + "/assets/" + file;
            }
        } catch (Exception ignored) {}
        return DEFAULT_ASSET_BASE + "/" + file;
    }

    /** Reads a bundled UTF-8 resource (CSS / app JS / HTML shell); "" if missing. */
    private static String loadResource(String path) {
        try (var in = ReportWriter.class.getResourceAsStream(path)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static final String CSS = loadResource("/report.css");

    private static final String APP_JS = loadResource("/report.app.js");

    private static final String SHELL = loadResource("/report.shell.html");

    /**
     * Escapes a string for embedding as a JSON string literal inside an inline
     * {@code <script>} block. Beyond the usual JSON escapes we also escape
     * {@code <}, {@code >} and {@code &}: without this, a script name, trigger
     * name, item label or script source containing {@code </script>} would close
     * the surrounding tag and let attacker-controlled markup execute when the
     * report is opened — a stored XSS, since reports are uploaded and shared and
     * the share worker's CSP allows inline scripts. U+2028/U+2029 are valid in
     * JSON but are line terminators in JS, so they're escaped too. Everything is
     * emitted as {@code \\uXXXX}, which parses back to the identical string.
     */
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<'  -> out.append("\\u003c");
                case '>'  -> out.append("\\u003e");
                case '&'  -> out.append("\\u0026");
                default   -> {
                    // Control chars and the JS line terminators U+2028/U+2029,
                    // which are legal in JSON but break out of a JS string.
                    if (c < 0x20 || c == 0x2028 || c == 0x2029)
                        out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
