// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace;

import ch.njol.skript.events.bukkit.PreScriptLoadEvent;
import dev.sktrace.profiler.Profiler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;

/**
 * Keeps the profiler's Skript hooks in sync across {@code /sk reload}.
 *
 * <p>skTrace's per-trigger profiling swaps Skript's real {@code Trigger} objects for
 * {@code ProfilingTrigger} wrappers inside {@code ch.njol.skript.SkriptEventHandler}'s trigger
 * multimap (and the loaded script structures). On reload Skript unloads scripts by removing each
 * trigger from that map <em>by identity</em>, and only drops the shared Bukkit listener for an
 * event/priority once no trigger remains there. Our wrappers fail that identity check, so Skript
 * can't find — and never removes — its own triggers: the stale shared listener leaks, the reload
 * registers a second one for the new generation, and every event fires twice (on join runs twice,
 * on right click runs twice, …) until a full server restart.
 *
 * <p>Fix: the instant a reload command is seen — before Skript reads the map to unload — restore
 * every original Trigger/listener ({@link Profiler#suspendForReload()}) so Skript's identity-based
 * cleanup succeeds and the shared listener is dropped cleanly. Then, once the new generation has
 * finished loading (debounced off {@link PreScriptLoadEvent}), re-hook against the fresh triggers
 * ({@link Profiler#resumeAfterReload()}). Even if the re-hook is mistimed on a huge reload,
 * doubling cannot recur: the restore-before-unload already made Skript's own cleanup correct, so
 * the worst case is briefly reduced profiling coverage, never duplicated events.
 */
public final class ReloadHookGuard implements Listener {

    // Quiet window after the last load signal before we re-hook. Each PreScriptLoadEvent (and the
    // reload command itself) pushes this back, so the re-hook fires once the reload batch settles.
    // Also the self-heal delay if a reload is cancelled after we suspended: resumeAfterReload()
    // simply re-installs the hooks we removed.
    private static final long RESYNC_DEBOUNCE_TICKS = 40L; // ~2s

    private final Sktrace plugin;
    private final Profiler profiler;

    private BukkitTask pendingResync;

    public ReloadHookGuard(Sktrace plugin, Profiler profiler) {
        this.plugin = plugin;
        this.profiler = profiler;
    }

    /**
     * Register the command listeners (annotation-based) and the load listener. The load hook is
     * registered <em>manually</em> rather than with {@code @EventHandler}: {@link PreScriptLoadEvent}
     * is {@code @Deprecated} in current Skript, and Bukkit's annotation scanner prints a "the event
     * is Deprecated / Server performance will be affected" warning for any {@code @EventHandler}
     * method bound to it. Manual registration is functionally identical but skips that scan, so the
     * warning never fires. MONITOR priority: we only observe (re-arm the debounce), never modify.
     */
    public void register() {
        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(this, plugin);
        pm.registerEvent(PreScriptLoadEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> onPreScriptLoad(), plugin);
    }

    // HIGHEST + ignoreCancelled: run just before the command is dispatched and after other plugins
    // have decided whether to cancel it, so we only suspend for a reload that will actually happen.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (isSkriptReload(e.getMessage())) onReloadCommand(); // getMessage() keeps the leading slash
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        if (isSkriptReload(e.getCommand())) onReloadCommand(); // getCommand() has no leading slash
    }

    // Registered manually in register() (not via @EventHandler) to avoid the deprecation warning.
    // Fires (possibly off the main thread) as each load batch begins. Only meaningful once a reload
    // command has suspended us — otherwise it's the initial load or an unrelated one and
    // resumeAfterReload() would no-op. Re-arm the debounce so the re-hook waits out the batch.
    private void onPreScriptLoad() {
        if (profiler.isSuspended()) armResync();
    }

    /** Restore Skript's originals now (synchronously, before unload) and arm the debounced re-hook. */
    private void onReloadCommand() {
        if (!profiler.isRunning()) return;
        profiler.suspendForReload();
        armResync();
    }

    private synchronized void armResync() {
        if (pendingResync != null) {
            try { pendingResync.cancel(); } catch (Throwable ignored) { }
        }
        // runTaskLater is thread-safe, so this is safe even when called from an async load event.
        pendingResync = plugin.getServer().getScheduler()
                .runTaskLater(plugin, this::doResync, RESYNC_DEBOUNCE_TICKS);
    }

    private synchronized void doResync() {
        pendingResync = null;
        profiler.resumeAfterReload();
    }

    /**
     * True for {@code /sk reload ...} / {@code /skript reload ...} (any reload target — all, a
     * single script, config), tolerant of a leading slash and a namespace prefix like
     * {@code skript:sk}.
     */
    static boolean isSkriptReload(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        if (s.startsWith("/")) s = s.substring(1).trim();
        String[] parts = s.split("\\s+");
        if (parts.length < 2) return false;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');          // strip a namespace such as "skript:sk"
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (!cmd.equals("skript") && !cmd.equals("sk")) return false;
        return parts[1].equalsIgnoreCase("reload");
    }
}
