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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

/**
 * Keeps the profiler's Skript hooks in sync across script reloads.
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
 * <p>Fix: restore every original Trigger/listener ({@link Profiler#suspendForReload()}) BEFORE
 * Skript reads the map to unload, so its identity-based cleanup succeeds and the shared listener
 * is dropped cleanly. Then, once the new generation has finished loading (debounced), re-hook
 * against the fresh triggers ({@link Profiler#resumeAfterReload()}).
 *
 * <p>How we learn a reload is happening, in order of preference:
 * <ul>
 *   <li><b>Modern Skript (~2.10+):</b> {@code ScriptLoader.eventRegistry()} exposes
 *       {@code ScriptUnloadEvent}/{@code ScriptLoadEvent}. The unload event fires synchronously
 *       <em>before</em> {@code Structure.unload()} unregisters anything (verified against Skript
 *       2.15's {@code ScriptLoader.unloadScripts}), so suspending there always beats the
 *       identity-based removal — no matter how the reload was started: {@code /sk reload}, a
 *       script running {@code execute console command "sk reload ..."}, an addon calling
 *       {@code ScriptLoader} directly, a panel plugin dispatching the command programmatically.
 *       Registered reflectively (we compile against Skript 2.9, which lacks the API) via a
 *       {@link Proxy}, and unregistered on disable so a plugin-manager unload of skTrace doesn't
 *       leak our classloader into Skript's registry.</li>
 *   <li><b>Legacy fallback:</b> sniff {@code /sk reload ...} from the command events and debounce
 *       the re-hook off {@link PreScriptLoadEvent}. This cannot see programmatic reloads —
 *       {@code Bukkit.dispatchCommand} fires neither {@link PlayerCommandPreprocessEvent} nor
 *       {@link ServerCommandEvent} — so on old Skript a programmatic reload still double-fires
 *       until the next profiler stop/start. The identity-guarded restores in {@link Profiler}
 *       keep even that case from becoming permanent corruption.</li>
 * </ul>
 */
public final class ReloadHookGuard implements Listener {

    // Quiet window after the last load signal before we re-hook. Each per-script load event (and,
    // in legacy mode, the reload command itself) pushes this back, so the re-hook fires once the
    // reload batch settles. Also the self-heal delay if a reload is cancelled after we suspended:
    // resumeAfterReload() simply re-installs the hooks we removed.
    private static final long RESYNC_DEBOUNCE_TICKS = 40L; // ~2s

    private final SkTrace plugin;
    private final Profiler profiler;

    private BukkitTask pendingResync;

    // Modern path: proxies registered on ScriptLoader.eventRegistry(), kept for unregister().
    private Object eventRegistry;
    private Class<?> registryEventIface;   // org.skriptlang.skript.util.event.Event
    private Object unloadProxy;
    private Object loadProxy;

    public ReloadHookGuard(SkTrace plugin, Profiler profiler) {
        this.plugin = plugin;
        this.profiler = profiler;
    }

    /**
     * Prefer Skript's own script load/unload events; fall back to command sniffing when the
     * registry API doesn't exist. The legacy {@link PreScriptLoadEvent} hook is registered
     * <em>manually</em> rather than with {@code @EventHandler}: the event is {@code @Deprecated}
     * in current Skript, and Bukkit's annotation scanner prints a "the event is Deprecated"
     * warning for any {@code @EventHandler} method bound to it. MONITOR priority: we only
     * observe (re-arm the debounce), never modify.
     */
    public void register() {
        if (tryRegisterLoaderEvents()) {
            plugin.getLogger().info("[skTrace] Reload guard: hooked Skript's script load/unload events.");
            return;
        }
        PluginManager pm = plugin.getServer().getPluginManager();
        pm.registerEvents(this, plugin);
        pm.registerEvent(PreScriptLoadEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> onScriptLoadSignal(), plugin);
        plugin.getLogger().info("[skTrace] Reload guard: this Skript has no script load/unload "
                + "events; falling back to reload-command detection. Reloads started "
                + "programmatically (not typed by a player or the console) cannot be detected "
                + "on this Skript version.");
    }

    /** Drop the loader-event proxies and any pending resync. Safe to call when never registered. */
    public void unregister() {
        synchronized (this) {
            if (pendingResync != null) {
                try { pendingResync.cancel(); } catch (Throwable ignored) { }
                pendingResync = null;
            }
        }
        if (eventRegistry == null) return;
        try {
            Method unreg = eventRegistry.getClass().getMethod("unregister", registryEventIface);
            unreg.setAccessible(true);
            if (unloadProxy != null) unreg.invoke(eventRegistry, unloadProxy);
            if (loadProxy != null) unreg.invoke(eventRegistry, loadProxy);
        } catch (Throwable t) {
            plugin.getLogger().fine("[skTrace] Could not unregister loader events: " + t.getMessage());
        }
        eventRegistry = null;
        unloadProxy = null;
        loadProxy = null;
    }

    /**
     * Register {@code ScriptUnloadEvent}/{@code ScriptLoadEvent} proxies on
     * {@code ScriptLoader.eventRegistry()}. Returns false (leaving no half-registered state
     * behind) on any Skript without the API.
     */
    private boolean tryRegisterLoaderEvents() {
        try {
            Class<?> loader = Class.forName("ch.njol.skript.ScriptLoader");
            Object registry = loader.getMethod("eventRegistry").invoke(null);
            if (registry == null) return false;
            Class<?> eventIface = Class.forName("org.skriptlang.skript.util.event.Event");
            Class<?> unloadIface = Class.forName("ch.njol.skript.ScriptLoader$ScriptUnloadEvent");
            Class<?> loadIface = Class.forName("ch.njol.skript.ScriptLoader$ScriptLoadEvent");
            Method register = registry.getClass().getMethod("register", Class.class, eventIface);
            register.setAccessible(true);

            Object onUnload = proxyFor(unloadIface, "onUnload", this::onScriptUnloadSignal);
            Object onLoad = proxyFor(loadIface, "onLoad", this::onScriptLoadSignal);
            register.invoke(registry, unloadIface, onUnload);
            try {
                register.invoke(registry, loadIface, onLoad);
            } catch (Throwable t) {
                // Couldn't add the load half — back out the unload half rather than run lopsided.
                Method unreg = registry.getClass().getMethod("unregister", eventIface);
                unreg.setAccessible(true);
                unreg.invoke(registry, onUnload);
                throw t;
            }

            this.eventRegistry = registry;
            this.registryEventIface = eventIface;
            this.unloadProxy = onUnload;
            this.loadProxy = onLoad;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A proxy implementing {@code iface} whose {@code callbackName} method runs {@code callback}.
     * Object methods are answered locally — the registry keeps proxies in a Set, so equals and
     * hashCode must not recurse into the handler.
     */
    private Object proxyFor(Class<?> iface, String callbackName, Runnable callback) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if (name.equals(callbackName)) {
                try {
                    callback.run();
                } catch (Throwable t) {
                    plugin.getLogger().fine("[skTrace] Reload guard callback failed: " + t.getMessage());
                }
                return null;
            }
            return switch (name) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "SkTrace." + callbackName;
                default -> null;
            };
        };
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, handler);
    }

    // ---------- Legacy fallback: command sniffing ----------

    // HIGHEST + ignoreCancelled: run just before the command is dispatched and after other plugins
    // have decided whether to cancel it, so we only suspend for a reload that will actually happen.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (isSkriptReload(e.getMessage())) onScriptUnloadSignal(); // getMessage() keeps the leading slash
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        if (isSkriptReload(e.getCommand())) onScriptUnloadSignal(); // getCommand() has no leading slash
    }

    // ---------- Shared signal handling ----------

    /**
     * A script is about to be unloaded (modern: per script, synchronously before its triggers are
     * unregistered; legacy: a reload command was seen). Restore Skript's originals now and arm the
     * debounced re-hook. Idempotent — a batch reload fires this once per script.
     */
    private void onScriptUnloadSignal() {
        if (!profiler.isRunning()) return;
        profiler.suspendForReload();
        armResync();
    }

    /**
     * A script (re)load signal — modern: per script as it finishes loading (possibly off the main
     * thread); legacy: {@link PreScriptLoadEvent} as each load batch begins. Only meaningful once
     * an unload suspended us — otherwise it's the initial load or an unrelated one and
     * resumeAfterReload() would no-op. Re-arm the debounce so the re-hook waits out the batch.
     */
    private void onScriptLoadSignal() {
        if (profiler.isSuspended()) armResync();
    }

    private synchronized void armResync() {
        if (pendingResync != null) {
            try { pendingResync.cancel(); } catch (Throwable ignored) { }
        }
        try {
            // runTaskLater is thread-safe, so this is safe even from an async load thread. It can
            // refuse during shutdown — fine, onDisable's stop() restores the hooks anyway.
            pendingResync = plugin.getServer().getScheduler()
                    .runTaskLater(plugin, this::doResync, RESYNC_DEBOUNCE_TICKS);
        } catch (Throwable ignored) { }
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
