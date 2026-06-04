# Changelog

## [0.1.2] - Unreleased

- **Added** function-level profiling: per-function timing in reports, with per-line detail inside functions available as an opt-in.
- **Report UI:** mobile rendering fixes (legible tick chart, touch range-select, horizontally scrollable tables, ellipsized long names with tooltips), clearer labels, and worst-tick severity colors (green / amber / red).
- **In-game UI:** refreshed all chat messages and the boss bar — a branded header rule on `/sktrace` help and `status`, tightened copy so nothing wraps, report flags moved into a hover, and an on-brand boss bar.

## [0.1.1] - 2026-06-01

- **Fixed** a crash (`ClassCastException` + console spam) when profiling triggers with `if all:` / `if any:` / `then:`, and a related bug where `else` could run even after the `if` matched.
- **Fixed** the shared report source viewer: long lines no longer break the highlights, timing column, or top bar when scrolling sideways.
- **Changed:** line-level profiling is now opt-in (off by default) so a profile never disturbs a live server. Enable it with `line-level-profiling: true` in `config.yml`. Per-event and per-trigger timing are unaffected.
- **Faster:** about half the per-statement overhead when line-level profiling is on.
- **Added** a [bStats](https://bstats.org/plugin/bukkit/skTrace/31715) chart tracking how many servers enable line-level profiling.
