// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace.parse;

import dev.sktrace.SkTrace;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Collections;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Surfaces the parse audit to admins on join, once per audit.
 *
 * <p>Gated on the {@code sktrace.audit.notify} permission, and fires at most once per admin per
 * audit generation, so a fresh reload re-notifies but re-joining after seeing the current audit
 * doesn't nag. The "already told this admin" set is keyed by (uuid, generation), and a new
 * generation naturally supersedes the old membership so the set doesn't grow without bound.
 */
public final class ParseNotifier implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<gray>[<gold>skTrace<gray>] ";

    public static final String NOTIFY_PERMISSION = "sktrace.audit.notify";

    private final SkTrace plugin;
    private final ParseProfiler profiler;

    // (player uuid) -> generation we last notified them about. WeakHashMap so players who leave and
    // are GC'd don't leak; per-player single value, so it stays tiny.
    private final java.util.Map<UUID, Long> notified =
            Collections.synchronizedMap(new WeakHashMap<>());

    public ParseNotifier(SkTrace plugin, ParseProfiler profiler) {
        this.plugin = plugin;
        this.profiler = profiler;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(NOTIFY_PERMISSION)) return;

        ParseStats audit = profiler.latest();
        if (audit == null || !audit.isFinished()) return;

        int limit = Math.max(1, plugin.getConfig().getInt("parse-audit.notify-max-lines", 5));
        int notableCount = audit.flaggedCount();
        if (notableCount == 0) return; // nothing worth interrupting them for

        Long seen = notified.get(player.getUniqueId());
        if (seen != null && seen == audit.generation()) return; // already told them about this one
        notified.put(player.getUniqueId(), audit.generation());

        // Defer a couple ticks so our line lands after the vanilla join spam, not buried in it.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendNotice(player, notableCount, Math.min(notableCount, limit));
        }, 40L);
    }

    private void sendNotice(Player player, int notableCount, int previewCount) {
        String count = "<gold>" + notableCount + "<white> " + (notableCount == 1 ? "line" : "lines");
        player.sendMessage(MM.deserialize(PREFIX + "<white>The last parse audit flagged " + count + " slow to load."));
        player.sendMessage(MM.deserialize(
                "<white>Run <click:run_command:'/sktrace parse'>"
                + "<hover:show_text:'Show the parse audit findings'><aqua>/sktrace parse</aqua></hover></click>"
                + " <white>to see them, or check <gold>plugins/skTrace/audits/<white>."));
    }

    /** Forget who we've notified — used if the config/permission model changes at runtime. */
    public void reset() {
        notified.clear();
    }
}
