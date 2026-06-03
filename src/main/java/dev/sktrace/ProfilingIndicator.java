// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace;

import dev.sktrace.profiler.Profiler;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Live indicator shown while the profiler is running.
 * Renders a boss bar to every online player holding {@code sktrace.use};
 * the bar updates every second with elapsed time and hides on stop.
 */
public final class ProfilingIndicator implements Listener {

    private static final String PERMISSION = "sktrace.use";

    private static final TextColor ACCENT = TextColor.color(0xff9b3d);  // sktrace orange
    private static final TextColor TEXT = TextColor.color(0xe8eaed);
    private static final TextColor DIM = TextColor.color(0x6b7180);
    private static final TextColor OK = TextColor.color(0x7ee787);

    private final Sktrace plugin;
    private final Profiler profiler;

    private BossBar bar;
    private BukkitTask updateTask;

    public ProfilingIndicator(Sktrace plugin, Profiler profiler) {
        this.plugin = plugin;
        this.profiler = profiler;
    }

    public synchronized void start() {
        if (bar != null) return;
        bar = BossBar.bossBar(renderTitle(0), 1.0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERMISSION)) p.showBossBar(bar);
        }
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public synchronized void stop() {
        if (updateTask != null) {
            try { updateTask.cancel(); } catch (Throwable ignored) {}
            updateTask = null;
        }
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
            bar = null;
        }
    }

    private synchronized void tick() {
        if (bar == null) return;
        if (!profiler.isRunning()) { stop(); return; }
        long elapsedSec = (System.currentTimeMillis() - profiler.startedAtMillis()) / 1000L;
        bar.name(renderTitle(elapsedSec));
    }

    @EventHandler
    public synchronized void onJoin(PlayerJoinEvent e) {
        if (bar != null && e.getPlayer().hasPermission(PERMISSION)) {
            e.getPlayer().showBossBar(bar);
        }
    }

    private static Component renderTitle(long sec) {
        long m = sec / 60;
        long s = sec % 60;
        String time = m > 0 ? m + "m " + s + "s" : s + "s";
        return Component.text("▶ ", OK, TextDecoration.BOLD)
                .append(Component.text("Sktrace profiling", TEXT, TextDecoration.BOLD))
                .append(Component.text("   ", TEXT))
                .append(Component.text(time, ACCENT))
                .append(Component.text(" elapsed", DIM));
    }
}
