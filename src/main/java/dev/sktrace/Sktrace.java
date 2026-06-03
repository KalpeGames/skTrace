// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace;

import dev.sktrace.command.SktraceCommand;
import dev.sktrace.profiler.Profiler;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

public final class Sktrace extends JavaPlugin {

    // bStats plugin id for Sktrace (https://bstats.org/plugin/bukkit/Sktrace/31715).
    // Set to 0 or negative to keep metrics off without touching the rest of the code.
    private static final int BSTATS_PLUGIN_ID = 31715;

    private Profiler profiler;
    private ProfilingIndicator indicator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        profiler = new Profiler(this);
        indicator = new ProfilingIndicator(this, profiler);
        getServer().getPluginManager().registerEvents(indicator, this);

        var cmd = getCommand("sktrace");
        if (cmd != null) {
            var handler = new SktraceCommand(this, profiler, indicator);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        setupMetrics();

        if (getConfig().getBoolean("rolling.enabled", false)) {
            // Defer one tick so we start AFTER Skript finishes its own enable (script
            // load is async on some servers); otherwise we miss triggers that haven't
            // been registered yet when our enable runs.
            int windowSec = Math.max(5, getConfig().getInt("rolling.windowSeconds", 60));
            getServer().getScheduler().runTaskLater(this, () -> {
                profiler.startRolling(windowSec);
                getLogger().info("Sktrace rolling buffer active (" + windowSec + "s window). Use /sktrace clip.");
            }, 20L);
            getLogger().info("Sktrace loaded — rolling buffer will activate shortly.");
        } else {
            getLogger().info("Sktrace loaded. Run /sktrace start to begin profiling.");
        }
    }

    // Starts bStats metrics unless the user opted out (locally via config, or
    // globally via plugins/bStats/config.yml — the Metrics constructor honours
    // the global toggle itself). bStats handles its own ~30-minute submit timer
    // and shuts down with the plugin, so there's nothing to tear down on disable.
    private void setupMetrics() {
        if (!getConfig().getBoolean("metrics", true)) {
            return; // opted out for Sktrace specifically
        }
        if (BSTATS_PLUGIN_ID <= 0) {
            getLogger().info("bStats metrics disabled: no plugin id set yet. Register at "
                    + "https://bstats.org/add-plugin/bukkit and set BSTATS_PLUGIN_ID in Sktrace.java.");
            return;
        }

        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);

        // Sktrace-specific charts on top of bStats' built-in server/player counts.
        metrics.addCustomChart(new SimplePie("rolling_buffer",
                () -> getConfig().getBoolean("rolling.enabled", false) ? "enabled" : "disabled"));
        metrics.addCustomChart(new SimplePie("line_level_profiling",
                () -> getConfig().getBoolean("line-level-profiling", false) ? "enabled" : "disabled"));
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
        if (profiler != null && profiler.isRunning()) profiler.stop();
    }

    public Profiler profiler() {
        return profiler;
    }

    public ProfilingIndicator indicator() {
        return indicator;
    }
}
