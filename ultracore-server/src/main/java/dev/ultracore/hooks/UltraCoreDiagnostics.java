package dev.ultracore.hooks;

import io.papermc.paper.util.StackWalkerUtil;
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
public final class UltraCoreDiagnostics {

    private static final Logger ROOT_STYLE_LOGGER = LogManager.getLogger("Minecraft");
    private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();

    private UltraCoreDiagnostics() {
    }

    /**
     * Emits a single-line WARN once per unique (featureKey, target, callerPlugin) combination.
     *
     * @param featureKey configuration key in ultracore.yml responsible for disabling the subsystem
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
            "Blocked {} call {}({}): disabled by {} in ultracore.yml",
            plugin != null ? "plugin '" + callerName + "'" : "server",
            context,
            target,
            featureKey
        );
    }

    /**
     * Clears deduplication state between unit tests.
     */
    public static void resetForTesting() {
        WARNED_KEYS.clear();
    }
}
