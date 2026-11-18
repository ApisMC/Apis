package org.apismc.apis.hooks;

import io.papermc.paper.util.StackWalkerUtil;
import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Single-line WARN diagnostics for plugin calls targeting kill-switched subsystems,
 * deduplicated once per (feature, target, caller) per server run and formatted without plugin tag prefixes.
 */
public final class ApisDiagnostics {

    private static final Logger ROOT_STYLE_LOGGER = LogManager.getLogger("Minecraft");
    private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();

    private static final List<String> RELOCATED_CONFIG_FILES = List.of(
        "server.properties",
        "bukkit.yml",
        "spigot.yml",
        "commands.yml",
        "apis.yml",
        "paper.yml"
    );

    private static final List<String> RELOCATED_USER_FILES = List.of(
        "ops.json",
        "whitelist.json",
        "banned-players.json",
        "banned-ips.json",
        "usercache.json"
    );

    private ApisDiagnostics() {
    }

    /**
     * Emits a single-line WARN once per unique (featureKey, target, callerPlugin) combination.
     *
     * @param featureKey configuration key in apis.yml responsible for disabling the subsystem
     * @param context    method or subsystem invoked by caller
     * @param target     target world, dimension, or resource identifier
     */
    public static void warnBlocked(final String featureKey, final String context, final String target) {
        final @Nullable JavaPlugin plugin = StackWalkerUtil.getFirstPluginCaller();
        final String callerName = plugin != null ? plugin.getName() : "server";
        final String dedupeKey = featureKey + '|' + context + '|' + target + '|' + callerName;
        if (!WARNED_KEYS.add(dedupeKey)) {
            return;
        }
        ROOT_STYLE_LOGGER.warn(
            "Blocked {} call {}({}): disabled by {} in apis.yml",
            plugin != null ? "plugin '" + callerName + "'" : "server",
            context,
            target,
            featureKey
        );
    }

    /**
     * Checks whether a plugin startup failure was caused by missing relocated root files
     * and logs a concise two-line ERROR without a stacktrace or plugin tag prefix.
     *
     * @param pluginName plugin name
     * @param error      thrown exception
     * @return {@code true} if the failure matched a relocated root file and was reported cleanly
     */
    public static boolean handleLegacyPluginFailure(final String pluginName, final Throwable error) {
        final @Nullable String missingFile = detectLegacyRootFile(error);
        if (missingFile == null) {
            return false;
        }
        final boolean userFile = RELOCATED_USER_FILES.contains(missingFile);
        final String targetDir = userFile ? "users/" : "configs/";
        final String kind = userFile ? "file" : "configuration file";
        final String category = userFile ? "player data files" : "configs";
        ROOT_STYLE_LOGGER.error(
            "Plugin '{}' failed to load: requires legacy root {} '{}'.",
            pluginName,
            kind,
            missingFile
        );
        ROOT_STYLE_LOGGER.error(
            "Apis stores all {} in '{}'. To support this plugin, create a symlink.",
            category,
            targetDir
        );
        return true;
    }

    static @Nullable String detectLegacyRootFile(final Throwable error) {
        final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        final Deque<Throwable> queue = new ArrayDeque<>();
        queue.add(error);

        boolean hasTargetExceptionType = false;
        @Nullable String matchedFile = null;

        while (!queue.isEmpty()) {
            final Throwable current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof FileNotFoundException
                || current instanceof NoSuchFileException
                || current instanceof NullPointerException) {
                hasTargetExceptionType = true;
            }
            final @Nullable String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                final @Nullable String candidate = matchRelocatedFilename(message);
                if (candidate != null) {
                    matchedFile = candidate;
                }
            }
            if (current.getCause() != null) {
                queue.addLast(current.getCause());
            }
            Collections.addAll(queue, current.getSuppressed());
        }
        return hasTargetExceptionType ? matchedFile : null;
    }

    private static @Nullable String matchRelocatedFilename(final String message) {
        final String lower = message.toLowerCase(Locale.ROOT);
        for (final String name : RELOCATED_CONFIG_FILES) {
            if (containsFilenameToken(lower, name)) {
                return name;
            }
        }
        for (final String name : RELOCATED_USER_FILES) {
            if (containsFilenameToken(lower, name)) {
                return name;
            }
        }
        return null;
    }

    private static boolean containsFilenameToken(final String text, final String filename) {
        int fromIndex = 0;
        while ((fromIndex = text.indexOf(filename, fromIndex)) >= 0) {
            final boolean validBefore = fromIndex == 0 || isPathOrWordSeparator(text.charAt(fromIndex - 1));
            final int endIdx = fromIndex + filename.length();
            final boolean validAfter = endIdx == text.length() || isTrailingSeparator(text.charAt(endIdx));
            if (validBefore && validAfter) {
                return true;
            }
            fromIndex = endIdx;
        }
        return false;
    }

    private static boolean isPathOrWordSeparator(final char ch) {
        return ch == '/' || ch == '\\' || Character.isWhitespace(ch) || ch == '\'' || ch == '"' || ch == ':';
    }

    private static boolean isTrailingSeparator(final char ch) {
        return Character.isWhitespace(ch) || ch == '\'' || ch == '"' || ch == ')' || ch == ':' || ch == ',' || ch == ';';
    }

    /**
     * Clears deduplication state between unit tests.
     */
    public static void resetForTesting() {
        WARNED_KEYS.clear();
    }
}
