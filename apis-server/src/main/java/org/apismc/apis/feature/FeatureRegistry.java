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

    private static volatile long disabledMask0;
    private static volatile long disabledMask1;

    private static volatile Set<String> disabledDimensionIds = Set.of();
    private static volatile Set<String> disabledTickDimensionIds = Set.of();
    private static volatile Set<String> disabledAiEntityIds = Set.of();

    private static volatile Set<ResourceKey<Level>> disabledDimensions = Set.of();
    private static volatile Set<ResourceKey<Level>> disabledTickDimensions = Set.of();
    private static volatile Set<EntityType<?>> disabledAiEntityTypes = Set.of();
    private static volatile boolean registriesResolved;

    private static volatile int projectileTrackingRange = 32;
    private static volatile double particleBroadcastRangeSq = 256.0d;
    private static volatile int armorEquipmentRange = 32;

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
     * Checks whether loading a dimension is blocked in {@code worlds.disabled-dimensions}.
     *
     * @param dimensionKey dimension resource key
     * @return {@code true} if disabled
     */
    public static boolean isDimensionDisabled(final ResourceKey<Level> dimensionKey) {
        if (registriesResolved) {
            return disabledDimensions.contains(dimensionKey);
        }
        return !disabledDimensionIds.isEmpty()
            && disabledDimensionIds.contains(dimensionKey.identifier().toString());
    }

    /**
     * Checks whether ticking a dimension is skipped in {@code worlds.disabled-ticking-dimensions}.
     *
     * @param dimensionKey dimension resource key
     * @return {@code true} if ticking is disabled
     */
    public static boolean isWorldTickDisabled(final ResourceKey<Level> dimensionKey) {
        if (registriesResolved) {
            return disabledTickDimensions.contains(dimensionKey);
        }
        return !disabledTickDimensionIds.isEmpty()
            && disabledTickDimensionIds.contains(dimensionKey.identifier().toString());
    }

    /**
     * Checks whether AI is disabled globally or for the given entity type.
     *
     * @param entityType entity type to query
     * @return {@code true} if AI is disabled
     */
    public static boolean isEntityAiDisabled(final EntityType<?> entityType) {
        if (isDisabled(Feature.AI_ENABLED)) {
            return true;
        }
        if (registriesResolved) {
            return disabledAiEntityTypes.contains(entityType);
        }
        if (disabledAiEntityIds.isEmpty()) {
            return false;
        }
        final Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        return disabledAiEntityIds.contains(key.toString());
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
        // 1. Startup & Bootstrap
        markIfDisabled(masks, Feature.STARTUP_DATAFIXERS, config.startup.datafixers);
        markIfDisabled(masks, Feature.STARTUP_SEARCH_SPAWN_POINT, config.startup.searchSpawnPoint);
        markIfDisabled(masks, Feature.STARTUP_INITIAL_CHUNK_LOAD, config.startup.initialChunkLoad);
        markIfDisabled(masks, Feature.STARTUP_VALIDATE_REGISTRIES, config.startup.validateRegistries);
        markIfDisabled(masks, Feature.STARTUP_MOJANG_SERVICE_DISCOVERY, config.startup.mojangServiceDiscovery);
        markIfDisabled(masks, Feature.STARTUP_PAPER_VERSION_CHECK, config.startup.paperVersionCheck);
        markIfDisabled(masks, Feature.STARTUP_EAGER_BLOCKSTATE_CACHE, config.startup.eagerBlockstateCache);
        markIfDisabled(masks, Feature.STARTUP_EAGER_CRAFTBLOCKDATA_CACHE, config.startup.eagerCraftblockdataCache);

        // 2. Worlds
        markIfDisabled(masks, Feature.WORLDS_CUSTOM_WORLDS, config.worlds.customWorlds);
        markIfDisabled(masks, Feature.WORLDS_PORTALS, config.worlds.portals);

        // 3. Entities & AI
        markIfDisabled(masks, Feature.AI_ENABLED, config.entities.ai.enabled);
        markIfDisabled(masks, Feature.AI_GOAL_SELECTOR, config.entities.ai.goalSelector);
        markIfDisabled(masks, Feature.AI_BRAIN, config.entities.ai.brain);
        markIfDisabled(masks, Feature.AI_PATHFINDING, config.entities.ai.pathfinding);
        markIfDisabled(masks, Feature.AI_SENSORS, config.entities.ai.sensors);
        markIfDisabled(masks, Feature.ENTITIES_COLLISIONS, config.entities.ai.collisions);

        // 4. Chunks & Generation
        markIfDisabled(masks, Feature.CHUNKS_STRUCTURES, config.chunks.structures);
        markIfDisabled(masks, Feature.CHUNKS_BIOME_DECORATION, config.chunks.biomeDecoration);
        markIfDisabled(masks, Feature.CHUNKS_TICKING, config.chunks.chunkTicking);
        markIfDisabled(masks, Feature.CHUNKS_RANDOM_TICKS, config.chunks.randomTicks);
        markIfDisabled(masks, Feature.CHUNKS_BLOCK_TICKS, config.chunks.blockTicks);
        markIfDisabled(masks, Feature.CHUNKS_FLUID_TICKS, config.chunks.fluidTicks);

        // 5. Gameplay & Environment
        markIfDisabled(masks, Feature.GAMEPLAY_WEATHER, config.gameplay.weather);
        markIfDisabled(masks, Feature.GAMEPLAY_RAIDS, config.gameplay.raids);
        markIfDisabled(masks, Feature.GAMEPLAY_REDSTONE, config.gameplay.redstone);
        markIfDisabled(masks, Feature.GAMEPLAY_NATURAL_SPAWNING, config.gameplay.naturalSpawning);
        markIfDisabled(masks, Feature.GAMEPLAY_CUSTOM_SPAWNERS, config.gameplay.customSpawners);
        markIfDisabled(masks, Feature.GAMEPLAY_MONSTERS, config.gameplay.monsters);
        markIfDisabled(masks, Feature.GAMEPLAY_ANIMALS, config.gameplay.animals);
        markIfDisabled(masks, Feature.GAMEPLAY_ADVANCEMENTS, config.gameplay.advancements);
        markIfDisabled(masks, Feature.GAMEPLAY_RECIPES, config.gameplay.recipes);
        markIfDisabled(masks, Feature.GAMEPLAY_LOOT_TABLES, config.gameplay.lootTables);
        markIfDisabled(masks, Feature.GAMEPLAY_WARDEN_AND_SLEEP, config.gameplay.wardenAndSleep);
        markIfDisabled(masks, Feature.GAMEPLAY_COMMAND_FUNCTIONS, config.gameplay.commandFunctions);

        // 6. Network & Analytics
        markIfDisabled(masks, Feature.NETWORK_GENERATE_KEYPAIR, config.network.generateKeypair);
        markIfDisabled(masks, Feature.ANALYTICS_METRICS_AND_PROFILER, config.network.analytics.metricsAndProfiler);
        markIfDisabled(masks, Feature.ANALYTICS_DEBUG_SYNCHRONIZERS, config.network.analytics.debugSynchronizers);
        markIfDisabled(masks, Feature.NETWORK_PLAYER_COLLISIONS, config.network.tracking.playerCollisions);
        markIfDisabled(masks, Feature.NETWORK_FULL_PACKET_BROADCAST, config.network.tracking.fullPacketBroadcast);
        markIfDisabled(masks, Feature.NETWORK_SERVER_PING_SAMPLE, config.network.tracking.serverPingSample);
        markIfDisabled(masks, Feature.NETWORK_TAB_LATENCY_UPDATES, config.network.tracking.tabLatencyUpdates);

        // 7. Ephemeral Container Lifecycle & Storage
        markIfDisabled(masks, Feature.STORAGE_WORLD_SAVING, config.storage.ephemeral.worldSaving);
        markIfDisabled(masks, Feature.STORAGE_GRACEFUL_SHUTDOWN, config.storage.ephemeral.gracefulShutdown);

        // 8. Players Lifecycle
        markIfDisabled(masks, Feature.PLAYERS_DATA_SAVING, config.players.lifecycle.playerDataSaving);
        markIfDisabled(masks, Feature.PLAYERS_HUNGER, config.players.lifecycle.hunger);
        markIfDisabled(masks, Feature.PLAYERS_STATISTICS, config.players.lifecycle.statistics);

        // 9. World Time & Physics
        markIfDisabled(masks, Feature.WORLD_DAYLIGHT_CYCLE, config.world.daylightCycle);
        markIfDisabled(masks, Feature.PHYSICS_BLOCK_GRAVITY, config.world.physics.blockGravity);
        markIfDisabled(masks, Feature.PHYSICS_HOPPER_TICKING, config.world.physics.hopperTicking);
        markIfDisabled(masks, Feature.PHYSICS_BLOCK_ENTITY_TICKING, config.world.physics.blockEntityTicking);
        markIfDisabled(masks, Feature.PHYSICS_ITEM_PHYSICS, config.world.physics.itemPhysics);
        markIfDisabled(masks, Feature.PHYSICS_LEAF_DECAY, config.world.physics.leafDecay);
        markIfDisabled(masks, Feature.PHYSICS_FIRE_SPREAD, config.world.physics.fireSpread);

        // 10. Compatibility & Legacy
        markIfDisabled(masks, Feature.LEGACY_PLUGIN_BYTECODE_REROUTING, config.compatibility.legacy.pluginBytecodeRerouting);
        markIfDisabled(masks, Feature.LEGACY_BOOTSTRAP_DATA_TABLES, config.compatibility.legacy.bootstrapDataTables);
        markIfDisabled(masks, Feature.LEGACY_EAGER_MAP_COLOR_CACHE, config.compatibility.legacy.eagerMapColorCache);
        markIfDisabled(masks, Feature.LEGACY_VALIDATE_ENTITY_ATTRIBUTES, config.compatibility.legacy.validateEntityAttributes);

        disabledMask0 = masks[0];
        disabledMask1 = masks[1];

        disabledDimensionIds = normalizeIds(config.worlds.disabledDimensions);
        disabledTickDimensionIds = normalizeIds(config.worlds.disabledTickingDimensions);
        disabledAiEntityIds = normalizeIds(config.entities.ai.disabledTypes);

        projectileTrackingRange = config.network.tracking.projectileTrackingRange;
        final double radius = config.network.tracking.particleBroadcastRange;
        particleBroadcastRangeSq = radius * radius;
        armorEquipmentRange = config.network.tracking.armorEquipmentRange;
        registriesResolved = false;
    }

    /**
     * Phase 2: Resolves string IDs into O(1) identity sets once Minecraft registries exist.
     *
     * @param registryAccess server registry access (nullable in early tests)
     * @param logger         diagnostic logger
     */
    public static synchronized void resolveRegistries(final @Nullable RegistryAccess registryAccess, final Logger logger) {
        final Set<ResourceKey<Level>> dimSet = new ReferenceOpenHashSet<>();
        for (final String raw : disabledDimensionIds) {
            final ResourceKey<Level> key = parseDimensionKey(raw, logger);
            if (key == null) {
                continue;
            }
            if (Level.OVERWORLD.equals(key)) {
                logger.warn("Refusing to disable primary dimension minecraft:overworld; ignoring.");
                continue;
            }
            dimSet.add(key);
        }
        disabledDimensions = ReferenceSets.unmodifiable((ReferenceOpenHashSet<ResourceKey<Level>>) dimSet);

        final Set<ResourceKey<Level>> tickDimSet = new ReferenceOpenHashSet<>();
        for (final String raw : disabledTickDimensionIds) {
            final ResourceKey<Level> key = parseDimensionKey(raw, logger);
            if (key != null) {
                tickDimSet.add(key);
            }
        }
        disabledTickDimensions = ReferenceSets.unmodifiable((ReferenceOpenHashSet<ResourceKey<Level>>) tickDimSet);

        final Registry<EntityType<?>> entityRegistry = registryAccess != null
            ? registryAccess.lookupOrThrow(Registries.ENTITY_TYPE)
            : BuiltInRegistries.ENTITY_TYPE;

        final ReferenceOpenHashSet<EntityType<?>> aiSet = new ReferenceOpenHashSet<>();
        for (final String raw : disabledAiEntityIds) {
            final @Nullable Identifier id = Identifier.tryParse(normalizeNamespace(raw));
            if (id == null) {
                logger.warn("Invalid entity type ID in entities.ai.disabled-types: '{}'", raw);
                continue;
            }
            final Optional<EntityType<?>> type = entityRegistry.getOptional(id);
            if (type.isEmpty()) {
                logger.warn("Unknown entity type in entities.ai.disabled-types: '{}'", raw);
                continue;
            }
            aiSet.add(type.get());
        }
        disabledAiEntityTypes = ReferenceSets.unmodifiable(aiSet);
        registriesResolved = true;
    }

    /**
     * Returns all currently active kill-switches (every option set to {@code false} plus non-empty blocklists).
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
        if (!disabledDimensionIds.isEmpty()) {
            active.add("worlds.disabled-dimensions=" + disabledDimensionIds);
        }
        if (!disabledTickDimensionIds.isEmpty()) {
            active.add("worlds.disabled-ticking-dimensions=" + disabledTickDimensionIds);
        }
        if (!disabledAiEntityIds.isEmpty()) {
            active.add("entities.ai.disabled-types=" + disabledAiEntityIds);
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * Clears registry state between unit tests.
     */
    public static synchronized void resetForTesting() {
        disabledMask0 = 0L;
        disabledMask1 = 0L;
        disabledDimensionIds = Set.of();
        disabledTickDimensionIds = Set.of();
        disabledAiEntityIds = Set.of();
        disabledDimensions = Set.of();
        disabledTickDimensions = Set.of();
        disabledAiEntityTypes = Set.of();
        registriesResolved = false;
    }

    private static void markIfDisabled(final long[] masks, final Feature feature, final boolean enabled) {
        if (!enabled) {
            masks[feature.word] |= feature.bit;
        }
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

    private static @Nullable ResourceKey<Level> parseDimensionKey(final String raw, final Logger logger) {
        final @Nullable Identifier id = Identifier.tryParse(normalizeNamespace(raw));
        if (id == null) {
            logger.warn("Invalid dimension identifier: '{}'", raw);
            return null;
        }
        return ResourceKey.create(Registries.DIMENSION, id);
    }
}
