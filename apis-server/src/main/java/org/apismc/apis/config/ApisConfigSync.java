// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.config;

import io.papermc.paper.configuration.GlobalConfiguration;
import io.papermc.paper.configuration.WorldConfiguration;
import java.util.List;
import java.util.Properties;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.apismc.apis.hooks.ApisHooks;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import org.spigotmc.SpigotConfig;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Enforces apis.yml as the Single Source of Truth by overriding upstream config fields
 * at runtime and stripping direct binary duplicates before disk serialization.
 */
public final class ApisConfigSync {

    private static final List<String> LEGACY_PROPERTIES_DUPLICATES = List.of(
        "allow-nether",
        "spawn-monsters",
        "spawn-animals",
        "spawn-npcs"
    );

    private ApisConfigSync() {
    }

    /**
     * Forces mirror fields inside {@link GlobalConfiguration} or {@link WorldConfiguration}
     * to match {@link ApisConfig} strictly.
     *
     * @param configObject loaded or reloaded Paper configuration object
     */
    public static void syncPaperConfigObject(final @Nullable Object configObject) {
        if (configObject instanceof GlobalConfiguration global) {
            syncGlobal(global);
        } else if (configObject instanceof WorldConfiguration world) {
            syncWorld(world);
        }
    }

    /**
     * Synchronizes {@link GlobalConfiguration} mirror flags from Apis.
     *
     * @param global Paper global configuration instance
     */
    public static void syncGlobal(final GlobalConfiguration global) {
        global.misc.enableNether = !ApisHooks.isDimensionDisabled(Level.NETHER);
        global.collisions.enablePlayerCollisions = !ApisHooks.isPlayerCollisionDisabled();
        global.spark.enabled = !ApisHooks.isSparkDisabled();
        if (ApisHooks.isSparkDisabled()) {
            global.spark.enableImmediately = false;
        }
        global.updateChecker.enabled = ApisHooks.isPaperVersionCheckEnabled();
    }

    /**
     * Synchronizes {@link WorldConfiguration} mirror flags from Apis.
     *
     * @param world Paper world configuration instance
     */
    public static void syncWorld(final WorldConfiguration world) {
        final boolean weatherDisabled = ApisHooks.isWeatherDisabled();
        world.environment.disableThunder = weatherDisabled;
        world.environment.disableIceAndSnow = weatherDisabled;
        world.unsupportedSettings.ticking.chunks = !ApisHooks.isChunkTickingDisabled();
    }

    /**
     * Forces Spigot static flags to match Apis instead of spigot.yml values.
     */
    public static void syncSpigot() {
        final boolean advDisabled = ApisHooks.isAdvancementsDisabled();
        SpigotConfig.disableAdvancementSaving = advDisabled;
        SpigotConfig.disabledAdvancements = advDisabled ? List.of("*") : List.of();
        SpigotConfig.disablePlayerDataSaving = ApisHooks.isPlayerDataSavingDisabled();
    }

    /**
     * Refreshes all upstream configuration mirrors after a runtime /apis reload.
     *
     * @param server active MinecraftServer instance
     */
    public static void syncAll(final MinecraftServer server) {
        syncSpigot();
        if (GlobalConfiguration.get() != null) {
            syncGlobal(GlobalConfiguration.get());
        }
        for (final ServerLevel level : server.getAllLevels()) {
            syncWorld(level.paperConfig());
        }
    }

    /**
     * Removes legacy binary duplicate keys from server.properties before saving.
     *
     * @param properties server.properties map
     */
    public static void stripLegacyProperties(final Properties properties) {
        for (final String key : LEGACY_PROPERTIES_DUPLICATES) {
            properties.remove(key);
        }
    }

    /**
     * Removes direct binary duplicates from bukkit.yml before disk serialization.
     *
     * @param config loaded Bukkit YAML configuration
     */
    public static void stripLegacyBukkitSettings(final YamlConfiguration config) {
        config.set("settings.allow-end", null);
        final @Nullable ConfigurationSection defaults = config.getDefaults();
        if (defaults != null) {
            defaults.set("settings.allow-end", null);
        }
    }

    /**
     * Removes direct binary duplicates from spigot.yml before disk serialization.
     *
     * @param config loaded Spigot YAML configuration
     */
    public static void stripLegacySpigotSettings(final YamlConfiguration config) {
        clearPathAndDefault(config, "advancements.disable-saving");
        clearPathAndDefault(config, "advancements.disabled");
        pruneEmptySection(config, "advancements");
        clearPathAndDefault(config, "players.disable-saving");
        pruneEmptySection(config, "players");
    }

    /**
     * Removes direct binary duplicates from Paper's Configurate AST before saving to disk.
     *
     * @param node     Configurate node to sanitize
     * @param filename target filename for context (global vs world config)
     */
    public static void stripLegacyPaperNode(final ConfigurationNode node, final String filename) {
        if (filename.endsWith("paper-global.yml")) {
            node.node("misc", "enable-nether").raw(null);
            node.node("collisions", "enable-player-collisions").raw(null);
        } else {
            node.node("environment", "disable-thunder").raw(null);
            node.node("environment", "disable-ice-and-snow").raw(null);
            node.node("unsupported-settings", "ticking", "chunks").raw(null);
        }
    }

    private static void clearPathAndDefault(final YamlConfiguration config, final String path) {
        config.set(path, null);
        final @Nullable ConfigurationSection defaults = config.getDefaults();
        if (defaults != null) {
            defaults.set(path, null);
        }
    }

    private static void pruneEmptySection(final YamlConfiguration config, final String sectionPath) {
        final @Nullable ConfigurationSection section = config.getConfigurationSection(sectionPath);
        if (section != null && section.getKeys(false).isEmpty()) {
            config.set(sectionPath, null);
        }
        final @Nullable ConfigurationSection defaults = config.getDefaults();
        if (defaults != null) {
            final @Nullable ConfigurationSection defaultSection = defaults.getConfigurationSection(sectionPath);
            if (defaultSection != null && defaultSection.getKeys(false).isEmpty()) {
                defaults.set(sectionPath, null);
            }
        }
    }
}
