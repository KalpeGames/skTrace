# sktrace

A profiler for [Skript](https://github.com/SkriptLang/Skript) on Paper and Spigot servers. It measures where your server's Skript time actually goes, then turns it into an interactive HTML performance report you can open in a browser or share with a single link.

Share service: **https://sktrace.kal.pe**

> sktrace is an independent project. It is not affiliated with, endorsed by, or part of the SkriptLang team or the Skript project.

## How it works

1. `/sktrace start` and let real activity happen (or `/sktrace clip` to grab the last few seconds on demand).
2. `/sktrace report` builds the interactive report and saves it to `plugins/Sktrace/reports/`.
3. The report is auto-uploaded and you get a `sktrace.kal.pe/<code>` link. Shared links last 24 hours.

Inside a report: per-trigger timing, line-level hotspots, tick activity vs the 50ms budget, time-by-script, the worst tick, and a source viewer.

## Layout

- `src/main/java/dev/sktrace/` — the Bukkit/Paper plugin.
- `src/main/resources/report.{css,app.js,shell.html}` — the single source of the report renderer, loaded by the plugin as classpath resources.

The hosted share service at `sktrace.kal.pe` is a separate Cloudflare Worker kept in its own repository. It stores only each report's JSON data (never raw uploaded HTML) and re-renders every report from this same renderer.

## Building

Requires the Paper and Skript dependencies:

```
mvn -DskipTests package
```

Produces `target/Sktrace-0.1.2.jar`.

## Configuration

`plugins/Sktrace/config.yml` — `upload-endpoint` (set empty to disable uploads; reports are still written locally), and the rolling-buffer settings for `/sktrace clip`.

## License

Apache-2.0. See [LICENSE](LICENSE).
