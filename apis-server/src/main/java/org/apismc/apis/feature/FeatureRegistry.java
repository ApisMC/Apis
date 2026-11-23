// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.feature;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.apismc.apis.config.ApisConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Lock-free O(1) bitmask registry for Apis kill-switches and fast reference sets for per-ID filters.
 * A bit is set when its capability is configured as {@code false} (kill-switch active).
 */
public final class FeatureRegistry {

    private static final String WILDCARD = "*";
    private static final Set<String> DEFAULT_ENABLED_DIMENSION_IDS = Set.of(
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    );
    private static final Set<String> DEFAULT_ALWAYS_TICKING_DIMENSION_IDS = Set.of("minecraft:overworld");

    private static volatile long disabledMask0;
    private static volatile long disabledMask1;

    private static volatile Set<String> enabledDimensionIds = DEFAULT_ENABLED_DIMENSION_IDS;
    private static volatile boolean enabledDimensionsWildcard;
    private static volatile boolean overworldOmittedInConfig;
    private static volatile Set<String> alwaysTickingDimensionIds = DEFAULT_ALWAYS_TICKING_DIMENSION_IDS;
    private static volatile boolean alwaysTickingWildcard;
    private static volatile Set<String> neverTickingDimensionIds = Set.of();
    private static volatile boolean neverTickingWildcard;
    private static volatile Set<String> aiTypeFilterIds = Set.of();

    private static volatile Set<ResourceKey<Level>> enabledDimensions = Set.of();
    private static volatile Set<ResourceKey<Level>> alwaysTickingDimensions = Set.of();
    private static volatile Set<ResourceKey<Level>> neverTickingDimensions = Set.of();
    private static volatile Set<EntityType<?>> aiTypeFilterTypes = Set.of();
    private static volatile boolean registriesResolved;

    private static volatile int projectileTrackingRange = 32;
    private static volatile double particleBroadcastRangeSq = 256.0d;
    private static volatile int armorEquipmentRange = 32;
    private static volatile boolean standaloneMode = true;

    private FeatureRegistry() {
    }

    /**
     * Checks in O(1) whether a capability is enabled ({@code true} in apis.yml).
     *
     * @param feature capability flag to test
     * @return {@code true} if active (not killed)
     */
    public static boolean isEnabled(final Feature feature) {
        return !isDisabled(feature);
    }

    /**
     * Checks in O(1) whether a capability is killed ({@code false} in apis.yml).
     *
     * @param feature capability flag to test
     * @return {@code true} if the kill-switch is active
     */
    public static boolean isDisabled(final Feature feature) {
        return feature.word == 0
            ? (disabledMask0 & feature.bit) != 0L
            : (disabledMask1 & feature.bit) != 0L;
    }

    /**
     * Checks whether loading a dimension is disallowed by the {@code dimensions.enabled} positive whitelist.
     * Primary dimension {@code minecraft:overworld} is always protected and never disabled.
     *
     * @param dimensionKey dimension resource key
     * @return {@code true} if excluded from {@code dimensions.enabled}
     */
    public static boolean isDimensionDisabled(final ResourceKey<Level> dimensionKey) {
        if (enabledDimensionsWildcard || "minecraft:overworld".equals(dimensionKey.identifier().toString())) {
            return false;
        }
        if (registriesResolved) {
            return !enabledDimensions.contains(dimensionKey);
        }
        return !enabledDimensionIds.contains(dimensionKey.identifier().toString());
    }

    /**
     * Checks whether ticking a dimension should be skipped for the current tick.
     * Priority: {@code dimensions.never-ticking} &gt; {@code dimensions.always-ticking} &gt; dynamic player presence.
     *
     * @param dimensionKey dimension resource key
     * @param hasPlayers   whether the dimension currently has at least one player inside
     * @return {@code true} if {@code level.tick()} should be skipped
     */
    public static boolean isWorldTickDisabled(final ResourceKey<Level> dimensionKey, final boolean hasPlayers) {
        if (matchesDimensionFilter(dimensionKey, neverTickingWildcard, neverTickingDimensions, neverTickingDimensionIds)) {
            return true;
        }
        if (matchesDimensionFilter(dimensionKey, alwaysTickingWildcard, alwaysTickingDimensions, alwaysTickingDimensionIds)) {
            return false;
        }
        return !hasPlayers;
    }

    private static boolean matchesDimensionFilter(
        final ResourceKey<Level> dimensionKey,
        final boolean wildcard,
        final Set<ResourceKey<Level>> resolvedSet,
        final Set<String> rawIdSet
    ) {
        if (wildcard) {
            return true;
        }
        if (registriesResolved) {
            return resolvedSet.contains(dimensionKey);
        }
        return rawIdSet.contains(dimensionKey.identifier().toString());
    }

    /**
     * Checks whether AI is disabled globally via master switch or matched by {@code entities.ai.type-filter}.
     *
     * @param entityType entity type to query
     * @return {@code true} if AI is disabled
     */
    public static boolean isEntityAiDisabled(final EntityType<?> entityType) {
        if (isDisabled(Feature.ENTITIES_AI_ENABLED)) {
            return true;
        }
        if (registriesResolved) {
            return aiTypeFilterTypes.contains(entityType);
        }
        if (aiTypeFilterIds.isEmpty()) {
            return false;
        }
        final Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        return aiTypeFilterIds.contains(key.toString());
    }

    public static int projectileTrackingRange() {
        return projectileTrackingRange;
    }

    public static double particleBroadcastRangeSq() {
        return particleBroadcastRangeSq;
    }

    public static int armorEquipmentRange() {
        return armorEquipmentRange;
    }

    /**
     * Phase 1: Applies positive capability flags to primitive kill-switch bitmasks before registries boot.
     *
     * @param config loaded Apis configuration
     */
    public static synchronized void applyEarly(final ApisConfig config) {
        final long[] masks = new long[2];

        // 1. Startup
        markIfDisabled(masks, Feature.STARTUP_DATAFIXERS, config.startup.datafixers);
        markIfDisabled(masks, Feature.STARTUP_SEARCH_SPAWN_POINT, config.startup.searchSpawnPoint);
        markIfDisabled(masks, Feature.STARTUP_INITIAL_CHUNK_LOAD, config.startup.initialChunkLoad);
        markIfDisabled(masks, Feature.STARTUP_MOJANG_SERVICE_DISCOVERY, config.startup.mojangServiceDiscovery);
        markIfDisabled(masks, Feature.STARTUP_PAPER_VERSION_CHECK, config.startup.paperVersionCheck);
        markIfDisabled(masks, Feature.STARTUP_VALIDATE_CREATIVE_TABS, config.startup.validateCreativeTabs);
        markIfDisabled(masks, Feature.STARTUP_VALIDATE_LOOT, config.startup.validateLoot);
        markIfDisabled(masks, Feature.STARTUP_TEST_REGISTRIES, config.startup.testRegistries);

        // 2. Caches
        markIfDisabled(masks, Feature.CACHES_EAGER_BLOCKSTATE, config.caches.eagerBlockstateCache);
        markIfDisabled(masks, Feature.CACHES_EAGER_CRAFTBLOCKDATA, config.caches.eagerCraftblockdataCache);
        markIfDisabled(masks, Feature.CACHES_EAGER_MAP_COLOR, config.caches.eagerMapColorCache);

        // 3. Dimensions
        markIfDisabled(masks, Feature.DIMENSIONS_CUSTOM_WORLDS, config.dimensions.customWorlds);
        markIfDisabled(masks, Feature.DIMENSIONS_PORTALS, config.dimensions.portals);

        // 4. Chunks
        markIfDisabled(masks, Feature.CHUNKS_STRUCTURES, config.chunks.structures);
        markIfDisabled(masks, Feature.CHUNKS_BIOME_DECORATION, config.chunks.biomeDecoration);
        markIfDisabled(masks, Feature.CHUNKS_TICKING, config.chunks.ticking.enabled);
        markIfDisabled(masks, Feature.CHUNKS_RANDOM_TICKS, config.chunks.ticking.subFeatures.randomTicks);
        markIfDisabled(masks, Feature.CHUNKS_BLOCK_TICKS, config.chunks.ticking.subFeatures.blockTicks);
        markIfDisabled(masks, Feature.CHUNKS_FLUID_TICKS, config.chunks.ticking.subFeatures.fluidTicks);
        markIfDisabled(masks, Feature.CHUNKS_POI_TICKING, config.chunks.ticking.subFeatures.poiTicking);

        // 5. Block Entities & Blocks/Physics
        markIfDisabled(masks, Feature.BLOCK_ENTITIES_TICKING, config.blockEntities.ticking.enabled);
        markIfDisabled(masks, Feature.BLOCK_ENTITIES_HOPPERS, config.blockEntities.ticking.subFeatures.hoppers);
        markIfDisabled(masks, Feature.PHYSICS_REDSTONE, config.blocksAndPhysics.redstone);
        markIfDisabled(masks, Feature.PHYSICS_BLOCK_GRAVITY, config.blocksAndPhysics.blockGravity);
        markIfDisabled(masks, Feature.PHYSICS_LEAF_DECAY, config.blocksAndPhysics.leafDecay);
        markIfDisabled(masks, Feature.PHYSICS_FIRE_SPREAD, config.blocksAndPhysics.fireSpread);
        markIfDisabled(masks, Feature.PHYSICS_ITEM_PHYSICS, config.blocksAndPhysics.itemPhysics);
        markIfDisabled(masks, Feature.PHYSICS_BLOCK_EVENTS, config.blocksAndPhysics.blockEvents);

        // 6. World Environment
        markIfDisabled(masks, Feature.ENV_DAYLIGHT_CYCLE, config.worldEnvironment.daylightCycle);
        markIfDisabled(masks, Feature.ENV_WEATHER, config.worldEnvironment.weather);
        markIfDisabled(masks, Feature.ENV_RAIDS, config.worldEnvironment.raids);
        markIfDisabled(masks, Feature.ENV_SLEEP_CYCLE, config.worldEnvironment.sleepCycle);
        markIfDisabled(masks, Feature.ENV_END_DRAGON_FIGHT, config.worldEnvironment.endDragonFight);
        markIfDisabled(masks, Feature.ENV_WORLD_BORDER_TICK, config.worldEnvironment.worldBorderTick);
        markIfDisabled(masks, Feature.ENV_ENVIRONMENT_ATTRIBUTES, config.worldEnvironment.environmentAttributes);
        markIfDisabled(masks, Feature.ENV_GAME_EVENTS, config.worldEnvironment.gameEvents);

        // 7. Spawning
        markIfDisabled(masks, Feature.SPAWNING_NATURAL, config.spawning.natural.enabled);
        markIfDisabled(masks, Feature.SPAWNING_MONSTERS, config.spawning.natural.subFeatures.monsters);
        markIfDisabled(masks, Feature.SPAWNING_ANIMALS, config.spawning.natural.subFeatures.animals);
        markIfDisabled(masks, Feature.SPAWNING_CUSTOM_SPAWNERS, config.spawning.natural.subFeatures.customSpawners);

        // 8. Entities
        markIfDisabled(masks, Feature.ENTITIES_AI_ENABLED, config.entities.ai.enabled);
        markIfDisabled(masks, Feature.ENTITIES_AI_GOAL_SELECTOR, config.entities.ai.subFeatures.goalSelector);
        markIfDisabled(masks, Feature.ENTITIES_AI_BRAIN, config.entities.ai.subFeatures.brain);
        markIfDisabled(masks, Feature.ENTITIES_AI_PATHFINDING, config.entities.ai.subFeatures.pathfinding);
        markIfDisabled(masks, Feature.ENTITIES_AI_SENSORS, config.entities.ai.subFeatures.sensors);
        markIfDisabled(masks, Feature.ENTITIES_COLLISIONS, config.entities.collisions.entities);
        markIfDisabled(masks, Feature.ENTITIES_PLAYER_COLLISIONS, config.entities.collisions.players);
        markIfDisabled(masks, Feature.ENTITIES_DESPAWN_CHECKS, config.entities.despawnChecks);
        markIfDisabled(masks, Feature.ENTITIES_INSIDE_BLOCK_CHECKS, config.entities.insideBlockChecks);
        markIfDisabled(masks, Feature.ENTITIES_FLUID_INTERACTION, config.entities.fluidInteraction);
        markIfDisabled(masks, Feature.ENTITIES_SPRINT_PARTICLES, config.entities.sprintParticles);

        // 9. Gameplay
        markIfDisabled(masks, Feature.GAMEPLAY_ADVANCEMENTS, config.gameplay.advancements);
        markIfDisabled(masks, Feature.GAMEPLAY_RECIPES, config.gameplay.recipes);
        markIfDisabled(masks, Feature.GAMEPLAY_LOOT_TABLES, config.gameplay.lootTables);
        markIfDisabled(masks, Feature.GAMEPLAY_COMMAND_FUNCTIONS, config.gameplay.commandFunctions);

        // 10. Players
        markIfDisabled(masks, Feature.PLAYERS_DATA_SAVING, config.players.dataSaving);
        markIfDisabled(masks, Feature.PLAYERS_HUNGER, config.players.hunger);
        markIfDisabled(masks, Feature.PLAYERS_STATISTICS, config.players.statistics);
        markIfDisabled(masks, Feature.PLAYERS_WARDEN_TRACKER, config.players.wardenTracker);
        markIfDisabled(masks, Feature.PLAYERS_LOCATOR_BAR, config.players.locatorBar);
        markIfDisabled(
            masks,
            Feature.PLAYERS_INVENTORY_SPECIAL_UPDATES,
            config.players.inventorySpecialItemUpdates
        );
        markIfDisabled(
            masks,
            Feature.PLAYERS_VANILLA_SCOREBOARD_CRITERIA,
            config.players.vanillaScoreboardCriteria
        );
        markIfDisabled(masks, Feature.PLAYERS_FLOATING_KICK, config.players.floatingKick);

        // 11. Network
        markIfDisabled(masks, Feature.NETWORK_GENERATE_KEYPAIR, config.network.generateKeypair);
        markIfDisabled(masks, Feature.ANALYTICS_BSTATS, config.network.analytics.bstats);
        markIfDisabled(masks, Feature.ANALYTICS_SPARK, config.network.analytics.spark);
        markIfDisabled(masks, Feature.ANALYTICS_TICK_MONITOR, config.network.analytics.tickMonitor);
        markIfDisabled(masks, Feature.ANALYTICS_DEBUG_SYNCHRONIZERS, config.network.analytics.debugSynchronizers);
        markIfDisabled(masks, Feature.NETWORK_SERVER_PING_SAMPLE, config.network.playerList.serverPingSample);
        markIfDisabled(masks, Feature.NETWORK_TAB_LATENCY_UPDATES, config.network.playerList.tabLatencyUpdates);
        markIfDisabled(
            masks,
            Feature.NETWORK_FULL_PACKET_BROADCAST,
            config.network.packetRangeClamping.fullPacketBroadcast
        );

        // 12. Storage & System
        markIfDisabled(masks, Feature.STORAGE_WORLD_SAVING, config.storage.worldSaving);
        markIfDisabled(masks, Feature.STORAGE_PERIODIC_AUTOSAVE, config.storage.periodicAutosave);
        markIfDisabled(masks, Feature.SYSTEM_GRACEFUL_SHUTDOWN, config.system.lifecycle.gracefulShutdown);

        // 13. Compatibility
        markIfDisabled(
            masks,
            Feature.LEGACY_PLUGIN_BYTECODE_REROUTING,
            config.compatibility.legacy.pluginBytecodeRerouting
        );
        markIfDisabled(masks, Feature.LEGACY_BOOTSTRAP_DATA_TABLES, config.compatibility.legacy.bootstrapDataTables);
        markIfDisabled(
            masks,
            Feature.LEGACY_VALIDATE_ENTITY_ATTRIBUTES,
            config.compatibility.legacy.validateEntityAttributes
        );

        disabledMask0 = masks[0];
        disabledMask1 = masks[1];

        final Set<String> normalizedEnabledDims = normalizeDimensionIds(config.dimensions.enabled);
        enabledDimensionsWildcard = normalizedEnabledDims.contains(WILDCARD);
        if (!enabledDimensionsWildcard) {
            overworldOmittedInConfig = !normalizedEnabledDims.contains("minecraft:overworld");
            final Set<String> withOverworld = new HashSet<>(normalizedEnabledDims);
            withOverworld.add("minecraft:overworld");
            enabledDimensionIds = Set.copyOf(withOverworld);
        } else {
            overworldOmittedInConfig = false;
            enabledDimensionIds = normalizedEnabledDims;
        }

        final Set<String> normalizedAlwaysTickDims = normalizeDimensionIds(config.dimensions.alwaysTicking);
        alwaysTickingWildcard = normalizedAlwaysTickDims.contains(WILDCARD);
        alwaysTickingDimensionIds = normalizedAlwaysTickDims;

        final Set<String> normalizedNeverTickDims = normalizeDimensionIds(config.dimensions.neverTicking);
        neverTickingWildcard = normalizedNeverTickDims.contains(WILDCARD);
        neverTickingDimensionIds = normalizedNeverTickDims;

        aiTypeFilterIds = normalizeIds(config.entities.ai.typeFilter);
        standaloneMode = ApisConfig.MODE_STANDALONE.equals(config.dimensions.mode);

        projectileTrackingRange = config.network.packetRangeClamping.projectileTrackingRange;
        final double radius = config.network.packetRangeClamping.particleBroadcastRange;
        particleBroadcastRangeSq = radius * radius;
        armorEquipmentRange = config.network.packetRangeClamping.armorEquipmentRange;
        registriesResolved = false;
    }

    /**
     * Phase 2: Resolves string IDs into O(1) identity sets once Minecraft registries exist.
     *
     * @param registryAccess server registry access (nullable in early tests)
     * @param logger         diagnostic logger
     */
    public static synchronized void resolveRegistries(final @Nullable RegistryAccess registryAccess, final Logger logger) {
        final ReferenceOpenHashSet<ResourceKey<Level>> enabledDimSet = new ReferenceOpenHashSet<>();
        enabledDimSet.add(Level.OVERWORLD);
        if (overworldOmittedInConfig) {
            logger.warn("Primary dimension minecraft:overworld is protected in dimensions.enabled; keeping it enabled.");
        }
        if (!enabledDimensionsWildcard) {
            for (final String raw : enabledDimensionIds) {
                if (WILDCARD.equals(raw)) {
                    continue;
                }
                final ResourceKey<Level> key = parseDimensionKey(raw, "dimensions.enabled", logger);
                if (key != null) {
                    enabledDimSet.add(key);
                }
            }
        }
        enabledDimensions = ReferenceSets.unmodifiable(enabledDimSet);

        alwaysTickingDimensions = resolveDimensionFilterSet(
            alwaysTickingWildcard,
            alwaysTickingDimensionIds,
            "dimensions.always-ticking",
            logger
        );
        neverTickingDimensions = resolveDimensionFilterSet(
            neverTickingWildcard,
            neverTickingDimensionIds,
            "dimensions.never-ticking",
            logger
        );

        final Registry<EntityType<?>> entityRegistry = registryAccess != null
            ? registryAccess.lookupOrThrow(Registries.ENTITY_TYPE)
            : BuiltInRegistries.ENTITY_TYPE;

        final ReferenceOpenHashSet<EntityType<?>> aiSet = new ReferenceOpenHashSet<>();
        for (final String raw : aiTypeFilterIds) {
            final @Nullable Identifier id = Identifier.tryParse(normalizeNamespace(raw));
            if (id == null) {
                logger.warn("Invalid entity type ID in entities.ai.type-filter: '{}'", raw);
                continue;
            }
            final Optional<EntityType<?>> type = entityRegistry.getOptional(id);
            if (type.isEmpty()) {
                logger.warn("Unknown entity type in entities.ai.type-filter: '{}'", raw);
                continue;
            }
            aiSet.add(type.get());
        }
        aiTypeFilterTypes = ReferenceSets.unmodifiable(aiSet);
        registriesResolved = true;
    }

    /**
     * Returns all currently active kill-switches (every option set to {@code false} plus non-default whitelists/filters).
     *
     * @return immutable list of active kill-switch identifiers
     */
    public static List<String> activeKillSwitches() {
        final List<String> active = new ArrayList<>();
        for (final Feature feature : Feature.values()) {
            if (isDisabled(feature)) {
                active.add(feature.configKey() + "=false");
            }
        }
        if (!enabledDimensionsWildcard && !enabledDimensionIds.equals(DEFAULT_ENABLED_DIMENSION_IDS)) {
            final List<String> sorted = new ArrayList<>(enabledDimensionIds);
            Collections.sort(sorted);
            active.add("dimensions.enabled=" + sorted);
        }
        if (!alwaysTickingDimensionIds.equals(DEFAULT_ALWAYS_TICKING_DIMENSION_IDS)) {
            final List<String> sorted = new ArrayList<>(alwaysTickingDimensionIds);
            Collections.sort(sorted);
            active.add("dimensions.always-ticking=" + sorted);
        }
        if (!neverTickingDimensionIds.isEmpty()) {
            final List<String> sorted = new ArrayList<>(neverTickingDimensionIds);
            Collections.sort(sorted);
            active.add("dimensions.never-ticking=" + sorted);
        }
        if (!aiTypeFilterIds.isEmpty()) {
            final List<String> sorted = new ArrayList<>(aiTypeFilterIds);
            Collections.sort(sorted);
            active.add("entities.ai.type-filter=" + sorted);
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * Clears registry state between unit tests.
     */
    public static synchronized void resetForTesting() {
        disabledMask0 = 0L;
        disabledMask1 = 0L;
        enabledDimensionIds = DEFAULT_ENABLED_DIMENSION_IDS;
        enabledDimensionsWildcard = false;
        overworldOmittedInConfig = false;
        alwaysTickingDimensionIds = DEFAULT_ALWAYS_TICKING_DIMENSION_IDS;
        alwaysTickingWildcard = false;
        neverTickingDimensionIds = Set.of();
        neverTickingWildcard = false;
        aiTypeFilterIds = Set.of();
        enabledDimensions = Set.of();
        alwaysTickingDimensions = Set.of();
        neverTickingDimensions = Set.of();
        aiTypeFilterTypes = Set.of();
        registriesResolved = false;
        projectileTrackingRange = 32;
        particleBroadcastRangeSq = 256.0d;
        armorEquipmentRange = 32;
        standaloneMode = true;
    }

    /**
     * Checks whether the last applied config selects standalone world architecture.
     *
     * @return {@code true} if {@code dimensions.mode} is standalone
     */
    public static boolean isStandaloneMode() {
        return standaloneMode;
    }

    private static void markIfDisabled(final long[] masks, final Feature feature, final boolean enabled) {
        if (!enabled) {
            masks[feature.word] |= feature.bit;
        }
    }

    private static Set<String> normalizeDimensionIds(final @Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        final Set<String> out = new HashSet<>(raw.size());
        for (final String entry : raw) {
            if (entry != null && !entry.isBlank()) {
                final String trimmed = entry.trim().toLowerCase(Locale.ROOT);
                out.add(WILDCARD.equals(trimmed) ? WILDCARD : normalizeNamespace(trimmed));
            }
        }
        return Set.copyOf(out);
    }

    private static Set<String> normalizeIds(final @Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        final Set<String> out = new HashSet<>(raw.size());
        for (final String entry : raw) {
            if (entry != null && !entry.isBlank()) {
                out.add(normalizeNamespace(entry.trim().toLowerCase(Locale.ROOT)));
            }
        }
        return Set.copyOf(out);
    }

    private static String normalizeNamespace(final String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static Set<ResourceKey<Level>> resolveDimensionFilterSet(
        final boolean wildcard,
        final Set<String> rawIds,
        final String configPath,
        final Logger logger
    ) {
        if (wildcard || rawIds.isEmpty()) {
            return Set.of();
        }
        final ReferenceOpenHashSet<ResourceKey<Level>> resolved = new ReferenceOpenHashSet<>();
        for (final String raw : rawIds) {
            if (WILDCARD.equals(raw)) {
                continue;
            }
            final @Nullable ResourceKey<Level> key = parseDimensionKey(raw, configPath, logger);
            if (key != null) {
                resolved.add(key);
            }
        }
        return ReferenceSets.unmodifiable(resolved);
    }

    private static @Nullable ResourceKey<Level> parseDimensionKey(
        final String raw,
        final String configPath,
        final Logger logger
    ) {
        final @Nullable Identifier id = Identifier.tryParse(normalizeNamespace(raw));
        if (id == null) {
            logger.warn("Invalid dimension identifier in {}: '{}'", configPath, raw);
            return null;
        }
        return ResourceKey.create(Registries.DIMENSION, id);
    }
}
