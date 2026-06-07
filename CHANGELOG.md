# Changelog

## [0.1.3] - Unreleased

- **Added** a variable viewer in reports: how many global variables were created, updated, and deleted during the window, the most-written ones, and a writes-per-tick sparkline. It passively observes Skript's variable save path — the same path that writes `variables.csv` (or your database) — without touching how scripts run, and it never reads or scans your existing variables, so there is no cost from a large `variables.csv`. Global variables only (local `{_temp}` variables are never persisted, so cannot be tracked). On by default; opt out with `variable-tracking: false`.
- **Added** the ability to open a report on the website by uploading its file. The `.json` report file is now a complete, self-contained report, so when an auto-upload is skipped or fails (for example HTTP 413, "report too large"), `/sktrace report` points you to the homepage where you can drop the `.json` in and get a shareable link. On busy servers the variable list is collapsed behind a "Show variables" dropdown so it never overwhelms the page.
- **Added** a running count of reports shared, displayed on the website.
- **Added** a [bStats](https://bstats.org/plugin/bukkit/skTrace/31715) chart tracking how many servers enable variable tracking.
- **Added** automatic config migration: updating skTrace now merges any new `config.yml` options into your existing file (keeping every setting you've changed), instead of leaving a stale config behind on update. skTrace also warns at startup if the experimental `line-level-profiling` is enabled, since it can disturb how some scripts run while profiling.
- **Added** disk-save tracking: skTrace now detects Skript's periodic full rewrite of `variables.csv` (the save that runs about every 5 minutes) when it happens during a capture, and draws it as a band on the tick chart alongside roughly how long it took and the file size. While that rewrite runs it holds Skript's variable lock, so scripts setting variables stall until it finishes — this lets you see at a glance whether a save lines up with a lag spike. It watches the file on disk (never reads its contents) and only applies to flat-file storage; part of `variable-tracking`.
- **Report UI:** the Triggers, Functions, Events, and trigger-breakdown (selected-range) tables now show the top 5 rows with a "Show N more" dropdown for the rest (the kept rows follow whatever column you sort by), matching the Variables list. The breakdown's old fixed 40-row cutoff is replaced by this dropdown, so every trigger in a range is reachable.

## [0.1.2] - 2026-06-03

- **Added** function-level profiling: per-function timing in reports, with per-line detail inside functions available as an opt-in.
- **Report UI:** mobile rendering fixes (legible tick chart, touch range-select, horizontally scrollable tables, ellipsized long names with tooltips), clearer labels, and worst-tick severity colors (green / amber / red).
- **In-game UI:** refreshed all chat messages and the boss bar — a branded header rule on `/sktrace` help and `status`, tightened copy so nothing wraps, report flags moved into a hover, and an on-brand boss bar.

## [0.1.1] - 2026-06-01

- **Fixed** a crash (`ClassCastException` + console spam) when profiling triggers with `if all:` / `if any:` / `then:`, and a related bug where `else` could run even after the `if` matched.
- **Fixed** the shared report source viewer: long lines no longer break the highlights, timing column, or top bar when scrolling sideways.
- **Changed:** line-level profiling is now opt-in (off by default) so a profile never disturbs a live server. Enable it with `line-level-profiling: true` in `config.yml`. Per-event and per-trigger timing are unaffected.
- **Faster:** about half the per-statement overhead when line-level profiling is on.
- **Added** a [bStats](https://bstats.org/plugin/bukkit/skTrace/31715) chart tracking how many servers enable line-level profiling.
