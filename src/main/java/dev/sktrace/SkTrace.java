// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace;

import dev.sktrace.command.SkTraceCommand;
import dev.sktrace.profiler.Profiler;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkTrace extends JavaPlugin {

    // bStats plugin id for SkTrace (https://bstats.org/plugin/bukkit/SkTrace/31715).
    // Set to 0 or negative to keep metrics off without touching the rest of the code.
    private static final int BSTATS_PLUGIN_ID = 31715;

    private Profiler profiler;
    private ProfilingIndicator indicator;
    private ReloadHookGuard reloadGuard;
    private dev.sktrace.parse.ParseProfiler parseProfiler;
    private dev.sktrace.parse.ParseNotifier parseNotifier;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Bukkit only writes config.yml when it's missing, so an existing install never gets keys
        // added in a later version. Merge any new options in (keeping the user's current settings)
        // so updates don't leave a stale config behind.
        int addedKeys = ConfigUpdater.merge(this);
        if (addedKeys > 0) {
            getLogger().info("config.yml updated: added " + addedKeys
                    + " new option(s) from this version. Your existing settings were kept.");
        }
        warnIfRisky();
        profiler = new Profiler(this);
        indicator = new ProfilingIndicator(this, profiler);
        getServer().getPluginManager().registerEvents(indicator, this);
        // Resyncs the profiler's Skript hooks across script reloads so events don't start firing
        // twice afterwards. See ReloadHookGuard for the full mechanism.
        reloadGuard = new ReloadHookGuard(this, profiler);
        reloadGuard.register();

        // Parse-time auditing: which lines are slow to LOAD (invisible to the runtime profiler,
        // which only sees a script once it's already parsed). Passive mode instruments organic
        // reloads; the notifier surfaces findings to admins on join. Independent of the runtime
        // profiler above — it never has to be "started".
        parseProfiler = new dev.sktrace.parse.ParseProfiler(this);
        parseProfiler.armPassive();
        parseNotifier = new dev.sktrace.parse.ParseNotifier(this, parseProfiler);
        getServer().getPluginManager().registerEvents(parseNotifier, this);

        var cmd = getCommand("sktrace");
        if (cmd != null) {
            var handler = new SkTraceCommand(this, profiler, indicator, parseProfiler);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        setupMetrics();

        // Tells operators (and the console) when a newer release is on Modrinth. Notification only;
        // it never downloads anything. Disable with update-checker: false in config.yml.
        new UpdateChecker(this).start();

        if (getConfig().getBoolean("rolling.enabled", false)) {
            // Defer one tick so we start AFTER Skript finishes its own enable (script
            // load is async on some servers); otherwise we miss triggers that haven't
            // been registered yet when our enable runs.
            int windowSec = Math.max(5, getConfig().getInt("rolling.windowSeconds", 60));
            getServer().getScheduler().runTaskLater(this, () -> {
                profiler.startRolling(windowSec);
                getLogger().info("skTrace rolling buffer active (" + windowSec + "s window). Use /sktrace clip.");
            }, 20L);
            getLogger().info("skTrace loaded — rolling buffer will activate shortly.");
        } else {
            getLogger().info("skTrace loaded. Run /sktrace start to begin profiling.");
        }
    }

    // Surfaces a config setting that can disturb running scripts, so a stale config carried over
    // from an old install (or one that opted in long ago) doesn't silently keep biting. We warn
    // rather than auto-disable: it's the user's setting to make.
    private void warnIfRisky() {
        if (getConfig().getBoolean("line-level-profiling", false)) {
            getLogger().warning("line-level-profiling is ENABLED. It is experimental and rewrites "
                    + "Skript's internal trigger graph, which can disturb how some scripts run while "
                    + "profiling (notably addon sections such as SkBee's spawn section). If scripts "
                    + "misbehave ONLY while profiling, set line-level-profiling: false in config.yml "
                    + "(it is off by default).");
        }
    }

    // Starts bStats metrics unless the user opted out (locally via config, or
    // globally via plugins/bStats/config.yml — the Metrics constructor honours
    // the global toggle itself). bStats handles its own ~30-minute submit timer
    // and shuts down with the plugin, so there's nothing to tear down on disable.
    private void setupMetrics() {
        if (!getConfig().getBoolean("metrics", true)) {
            return; // opted out for skTrace specifically
        }
        if (BSTATS_PLUGIN_ID <= 0) {
            getLogger().info("bStats metrics disabled: no plugin id set yet. Register at "
                    + "https://bstats.org/add-plugin/bukkit and set BSTATS_PLUGIN_ID in SkTrace.java.");
            return;
        }

        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        // skTrace-specific charts on top of bStats' built-in server/player counts.
        metrics.addCustomChart(new SimplePie("rolling_buffer",
                () -> getConfig().getBoolean("rolling.enabled", false) ? "enabled" : "disabled"));
        metrics.addCustomChart(new SimplePie("line_level_profiling",
                () -> getConfig().getBoolean("line-level-profiling", false) ? "enabled" : "disabled"));
        metrics.addCustomChart(new SimplePie("variable_tracking",
                () -> getConfig().getBoolean("variable-tracking", true) ? "enabled" : "disabled"));
        metrics.addCustomChart(new SimplePie("skript_version", () -> {
            var skript = getServer().getPluginManager().getPlugin("Skript");
            return skript != null ? skript.getDescription().getVersion() : "unknown";
        }));
    }

    public String uploadEndpoint() {
        return getConfig().getString("upload-endpoint", "https://sktrace.kal.pe/upload");
    }

    @Override
    public void onDisable() {
        if (indicator != null) indicator.stop();
        // Drop the loader-event proxies first so Skript's registry doesn't keep a reference to
        // this (possibly about-to-be-discarded) classloader across a plugin-manager reload.
        if (reloadGuard != null) reloadGuard.unregister();
        // Same rationale for the parse-audit listeners registered on ScriptLoader.eventRegistry().
        if (parseProfiler != null) parseProfiler.disarmPassive();
        if (profiler != null && profiler.isRunning()) profiler.stop();
    }

    public Profiler profiler() {
        return profiler;
    }

    public ProfilingIndicator indicator() {
        return indicator;
    }
}
