## Find out where your server's Skript time actually goes

skTrace is a profiler built for Skript. It measures your triggers, functions, events, loops and
variable writes while the server runs, then turns that into an interactive HTML report you can open
in a browser or share with one link.

`/sktrace start`, let the lag happen, `/sktrace report`. You get a link.

![Description](https://raw.githubusercontent.com/KalpeGames/skTrace/main/docs/banners/description.png)

Server timings tell you Skript is expensive. They do not tell you *which script*, *which trigger*,
or *which line*. skTrace does, because it hooks Skript's own trigger graph rather than sampling the
JVM from outside.

**Inside a report:**

* **Per-trigger timing.** Calls, total, average and worst single call for every trigger, sorted so
  the expensive ones are at the top.
* **Per-function timing.** Functions get their own table, so cost hidden inside whatever called
  them stops being invisible.
* **Line-level hotspots.** Optional, off by default. Turn it on and the report highlights the
  exact statements eating the time, in a source viewer with a timing column.
* **A tick chart against the 50ms budget.** Drag to select any range and the tables rebuild for
  just that window. The worst tick gets its own breakdown.
* **Time by script.** Which file is responsible, at a glance.
* **Variable churn.** How many global variables were created, updated and deleted, which ones were
  written most, and a writes per tick sparkline. It also detects Skript's periodic full rewrite of
  `variables.csv` and draws it on the tick chart, so you can see when a save lines up with a spike.
* **A live loop view.** `/sktrace loops` shows which loops are running right now, their iteration
  count and how fast it is climbing. A background watchdog catches a truly frozen no-delay loop and
  logs the exact `script:line` to console, which is the only place still working during a freeze.

### Missed the spike?

Turn on the rolling buffer and skTrace profiles continuously in the background. When something
spikes, `/sktrace clip` snapshots the last 60 seconds (configurable) into a report, with no
`/sktrace start` beforehand. Overhead is the same as a normal capture, and memory is roughly 20KB
per trigger.

### Built to be safe on a live server

Per-trigger, per-function and per-event timing are passive wrappers with negligible overhead, fully
removed when profiling stops. Variable tracking observes Skript's existing save path and never
reads or scans your `variables.csv`, so a huge file costs nothing. Loop watching only reads the
iteration counter Skript already keeps.

The one feature that rewrites anything, `line-level-profiling`, is off by default and clearly
labelled experimental. Everything else in the report works without it.

### Commands

| | |
| --- | --- |
| `/sktrace start` / `stop` | Begin and end a capture |
| `/sktrace status` | Live summary in chat, hottest triggers first |
| `/sktrace report` | Write the report and get a shareable link |
| `/sktrace clip` | Snapshot the last N seconds from the rolling buffer |
| `/sktrace loops` | Live view of running loops |
| `/sktrace rolling on\|off` | Toggle the always-on buffer |
| `/sktrace reset` | Clear collected stats |

Aliased to `/skt`, permission `sktrace.use` (op by default).

Flags on `report` and `clip`: `--no-upload` keeps it local, `--include-files` embeds script source,
`--variable-values` lists what each save compacted, `--show-secrets` unmasks option values (option
secrets are masked by default).

### Reports and privacy

Every report is written to `plugins/Sktrace/reports/` first. It is then uploaded to
[sktrace.kal.pe](https://sktrace.kal.pe) for a short share link, and shared links expire after 24
hours. The service stores only the report's JSON data, never raw uploaded HTML, and re-renders each
report from the same renderer shipped in the plugin.

Do not want that? Set `upload-endpoint` to an empty string, or pass `--no-upload`. Reports are still
written locally, and the site can open a `.json` file you drop into it. You can also self-host the
worker.

Option values are masked in reports unless you explicitly ask for them, so a token in an option does
not end up in a link you paste in Discord.

### Requirements

Paper or Spigot 1.20+, Skript 2.9 or newer, Java 21. Works alongside
[spark](https://modrinth.com/plugin/spark), which profiles the JVM. skTrace profiles Skript.

> skTrace is an independent project. It is not affiliated with, endorsed by, or part of the
> SkriptLang team or the Skript project.

![Development](https://raw.githubusercontent.com/KalpeGames/skTrace/main/docs/banners/development.png)

Open source under Apache-2.0 at [github.com/KalpeGames/skTrace](https://github.com/KalpeGames/skTrace).
Bug reports and pull requests welcome, and the changelog for every release lives in the repo.

The report renderer (`report.css`, `report.app.js`, `report.shell.html`) ships inside the plugin as
the single source of truth, so a locally written report and a shared one look identical. The share
service is a small Cloudflare Worker kept in its own repository, and you are free to deploy your own
and point `upload-endpoint` at it.

Anonymous usage metrics go through [bStats](https://bstats.org/plugin/bukkit/Sktrace/31715): server
count, server software and Java version, plus whether the rolling buffer, line-level profiling and
variable tracking are enabled. No personal or server-identifying data. Set `metrics: false` to opt
out, or opt every bStats plugin out at once in `plugins/bStats/config.yml`.

An update notifier checks Modrinth on startup and every few hours after. When a newer release is
out, operators get a clickable notice on join and it is logged once to console. It never downloads
or installs anything, and `update-checker: false` turns it off.

![Licence](https://raw.githubusercontent.com/KalpeGames/skTrace/main/docs/banners/licence.png)

Apache License 2.0. Use it, modify it, ship it, including commercially. Keep the notice and state
your changes.

Copyright © 2026 KALPE.
[Full licence](https://github.com/KalpeGames/skTrace/blob/main/LICENSE)
