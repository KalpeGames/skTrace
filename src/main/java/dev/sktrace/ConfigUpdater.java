// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 Billy
package dev.sktrace;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

/**
 * Brings an existing on-disk config.yml up to date with the version bundled in the jar.
 *
 * Bukkit's {@code saveDefaultConfig()} only writes config.yml when it's absent — it never merges
 * new keys into a config a user already has. So after a plugin update, options added in the new
 * version (and their documentation) never appear in the user's file, and the plugin silently falls
 * back to code defaults. This walks the bundled default and adds any keys the user is missing,
 * copying the default value and its doc comments, while leaving every existing user value and
 * comment exactly as-is. Idempotent: once a config has all current keys it makes no changes.
 *
 * It deliberately does NOT overwrite existing values (we never silently change a setting someone
 * chose) and never deletes keys. A risky setting that's already enabled is surfaced by a separate
 * startup warning rather than being flipped.
 */
final class ConfigUpdater {

    private ConfigUpdater() {}

    /** @return number of new keys added (0 if the config was already current or on any failure). */
    static int merge(Sktrace plugin) {
        InputStream in = plugin.getResource("config.yml");
        if (in == null) return 0;
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "[Sktrace] config merge: couldn't read bundled default: " + e.getMessage());
            return 0;
        }

        FileConfiguration live = plugin.getConfig();
        int added = 0;
        // getKeys(true) yields deep paths; only leaves carry values (sections are auto-created when
        // a leaf under them is set). contains() is false only when the user is genuinely missing it.
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path)) continue;
            if (live.contains(path)) continue;
            live.set(path, defaults.get(path));
            copyComments(defaults, live, path);
            added++;
        }

        if (added > 0) {
            plugin.saveConfig();
        }
        return added;
    }

    // Best-effort: the comment API exists on modern Paper/Spigot (1.18.1+). If it's somehow
    // unavailable, the key value is still merged — just without its explanatory comment.
    private static void copyComments(YamlConfiguration from, FileConfiguration to, String path) {
        try {
            List<String> block = from.getComments(path);
            if (block != null && !block.isEmpty()) to.setComments(path, block);
            List<String> inline = from.getInlineComments(path);
            if (inline != null && !inline.isEmpty()) to.setInlineComments(path, inline);
        } catch (Throwable ignored) {
            // Older configuration API without comment support — values still merged.
        }
    }
}
