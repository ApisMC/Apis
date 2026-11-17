package dev.ultracore.feature;

import dev.ultracore.config.UltraCoreConfig;
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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Lock-free O(1) bitmask registry for UltraCore kill-switches and fast reference sets for per-ID filters.
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
     * Checks in O(1) whether a feature kill-switch is active (disabled in config).
     *
     * @param feature feature flag to test
     * @return {@code true} if the feature is disabled
     */
    public static boolean isDisabled(final Feature feature) {
        return feature.word == 0
            ? (disabledMask0 & feature.bit) != 0L
            : (disabledMask1 & feature.bit) != 0L;
    }

    /**
     * Checks whether a feature is active (not disabled).
     *
     * @param feature feature flag to test
     * @return {@code true} if the feature is enabled
     */
    public static boolean isEnabled(final Feature feature) {
        return !isDisabled(feature);
    }

    public static boolean isDimensionDisabled(final ResourceKey<Level> dimensionKey) {
        if (registriesResolved) {
            return disabledDimensions.contains(dimensionKey);
        }
        return !disabledDimensionIds.isEmpty()
            && disabledDimensionIds.contains(dimensionKey.identifier().toString());
    }

    public static boolean isWorldTickDisabled(final ResourceKey<Level> dimensionKey) {
        if (registriesResolved) {
            return disabledTickDimensions.contains(dimensionKey);
        }
        return !disabledTickDimensionIds.isEmpty()
            && disabledTickDimensionIds.contains(dimensionKey.identifier().toString());
    }

    public static boolean isEntityAiDisabled(final EntityType<?> entityType) {
        if (isDisabled(Feature.AI_DISABLE_GLOBALLY)) {
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
     * Phase 1: Applies config flags to primitive bitmasks before Minecraft registries are initialized.
     *
     * @param config loaded UltraCore configuration
     */
    public static synchronized void applyEarly(final UltraCoreConfig config) {
        final long[] masks = new long[2];
        // 1. Worlds
        mark(masks, Feature.WORLDS_DISABLE_CUSTOM_WORLDS, config.worlds.disableCustomWorlds);
        mark(masks, Feature.WORLDS_DISABLE_PORTALS, config.worlds.disablePortals);

        // 2. Entities & AI
        mark(masks, Feature.AI_DISABLE_GLOBALLY, config.entities.ai.disableGlobally);
        mark(masks, Feature.AI_DISABLE_GOAL_SELECTOR, config.entities.ai.disableGoalSelector);
        mark(masks, Feature.AI_DISABLE_BRAIN, config.entities.ai.disableBrain);
        mark(masks, Feature.AI_DISABLE_PATHFINDING, config.entities.ai.disablePathfinding);
        mark(masks, Feature.AI_DISABLE_SENSORS, config.entities.ai.disableSensors);
        mark(masks, Feature.ENTITIES_DISABLE_COLLISIONS, config.entities.ai.disableCollisions);

        // 3. Chunks & Generation
        mark(masks, Feature.CHUNKS_DISABLE_STRUCTURES, config.chunks.disableStructures);
        mark(masks, Feature.CHUNKS_DISABLE_BIOME_DECORATION, config.chunks.disableBiomeDecoration);
        mark(masks, Feature.CHUNKS_DISABLE_TICKING, config.chunks.disableChunkTicking);
        mark(masks, Feature.CHUNKS_DISABLE_RANDOM_TICKS, config.chunks.disableRandomTicks);
        mark(masks, Feature.CHUNKS_DISABLE_BLOCK_TICKS, config.chunks.disableBlockTicks);
        mark(masks, Feature.CHUNKS_DISABLE_FLUID_TICKS, config.chunks.disableFluidTicks);

        // 4. Gameplay & Environment
        mark(masks, Feature.GAMEPLAY_DISABLE_WEATHER, config.gameplay.disableWeather);
        mark(masks, Feature.GAMEPLAY_DISABLE_RAIDS, config.gameplay.disableRaids);
        mark(masks, Feature.GAMEPLAY_DISABLE_REDSTONE, config.gameplay.disableRedstone);
        mark(masks, Feature.GAMEPLAY_DISABLE_NATURAL_SPAWNING, config.gameplay.disableNaturalSpawning);
        mark(masks, Feature.GAMEPLAY_DISABLE_CUSTOM_SPAWNERS, config.gameplay.disableCustomSpawners);
        mark(masks, Feature.GAMEPLAY_DISABLE_MONSTERS, config.gameplay.disableMonsters);
        mark(masks, Feature.GAMEPLAY_DISABLE_ANIMALS, config.gameplay.disableAnimals);

        // 5. Network & Analytics
        mark(masks, Feature.ANALYTICS_DISABLE_METRICS_AND_PROFILER, config.network.analytics.disableMetricsAndProfiler);
        mark(masks, Feature.ANALYTICS_DISABLE_DEBUG_SYNCHRONIZERS, config.network.analytics.disableDebugSynchronizers);

        // 6. Ephemeral Container Lifecycle & Storage
        mark(masks, Feature.STORAGE_EPHEMERAL_MEMORY_ONLY_WORLD, config.storage.ephemeral.memoryOnlyWorld);
        mark(masks, Feature.STORAGE_EPHEMERAL_FAST_SHUTDOWN, config.storage.ephemeral.fastShutdown);

        // 7. Network Tracking & Packet Filtering
        mark(masks, Feature.NETWORK_DISABLE_PLAYER_COLLISIONS, config.network.tracking.disablePlayerCollisions);
        mark(masks, Feature.NETWORK_FAST_PACKET_FILTERING, config.network.tracking.fastPacketFiltering);
        mark(masks, Feature.NETWORK_DISABLE_SERVER_PING_SAMPLE, config.network.tracking.disableServerPingSample);

        // 8. Players Lifecycle & Inventory
        mark(masks, Feature.PLAYERS_DISABLE_DATA_SAVING, config.players.lifecycle.disablePlayerDataSaving);
        mark(masks, Feature.PLAYERS_DISABLE_ADVANCEMENTS, config.players.lifecycle.disableAdvancements);
        mark(masks, Feature.PLAYERS_DISABLE_RECIPES, config.players.lifecycle.disableRecipes);

        // 9. World Physics & Containers
        mark(masks, Feature.PHYSICS_DISABLE_BLOCK_GRAVITY, config.world.physics.disableBlockGravity);
        mark(masks, Feature.PHYSICS_DISABLE_HOPPER_TICKING, config.world.physics.disableHopperTicking);
        mark(masks, Feature.PHYSICS_DISABLE_LEAF_DECAY, config.world.physics.disableLeafDecay);
        mark(masks, Feature.PHYSICS_DISABLE_FIRE_SPREAD, config.world.physics.disableFireSpread);

        disabledMask0 = masks[0];
        disabledMask1 = masks[1];

        disabledDimensionIds = normalizeIds(config.worlds.disabledDimensions);
        disabledTickDimensionIds = normalizeIds(config.worlds.disabledTickingDimensions);
        disabledAiEntityIds = normalizeIds(config.entities.ai.disabledTypes);
        registriesResolved = false;

        projectileTrackingRange = Math.max(0, config.network.tracking.projectileTrackingRange);
        final double particleRange = Math.max(0.0d, config.network.tracking.particleBroadcastRange);
        particleBroadcastRangeSq = particleRange * particleRange;
        armorEquipmentRange = Math.max(0, config.network.tracking.armorEquipmentRange);
    }

    /**
     * Phase 2: Resolves string identifiers against live Minecraft registries into O(1) ReferenceOpenHashSets.
     *
     * @param registryAccess server registry access, or {@code null} to fall back to built-in registries
     * @param logger         logger for invalid identifier warnings
     */
    public static synchronized void resolveRegistries(final @Nullable RegistryAccess registryAccess, final Logger logger) {
        final Set<ResourceKey<Level>> dimSet = new ReferenceOpenHashSet<>();
        for (final String raw : disabledDimensionIds) {
            final Identifier id = Identifier.tryParse(raw);
            if (id == null) {
                logger.warn("[UltraCore] Invalid dimension identifier in worlds.disabled-dimensions: '{}'", raw);
                continue;
            }
            final ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
            if (Level.OVERWORLD.equals(key)) {
                logger.warn("[UltraCore] Refusing to disable primary dimension minecraft:overworld; ignoring.");
                continue;
            }
            dimSet.add(key);
        }

        final Set<ResourceKey<Level>> tickDimSet = new ReferenceOpenHashSet<>();
        for (final String raw : disabledTickDimensionIds) {
            final Identifier id = Identifier.tryParse(raw);
            if (id == null) {
                logger.warn("[UltraCore] Invalid dimension identifier in worlds.disabled-ticking-dimensions: '{}'", raw);
                continue;
            }
            tickDimSet.add(ResourceKey.create(Registries.DIMENSION, id));
        }

        final Set<EntityType<?>> entitySet = new ReferenceOpenHashSet<>();
        final Registry<EntityType<?>> entityRegistry = registryAccess != null
            ? registryAccess.lookupOrThrow(Registries.ENTITY_TYPE)
            : BuiltInRegistries.ENTITY_TYPE;

        for (final String raw : disabledAiEntityIds) {
            final Identifier id = Identifier.tryParse(raw);
            if (id == null) {
                logger.warn("[UltraCore] Invalid entity type identifier in entities.ai.disabled-types: '{}'", raw);
                continue;
            }
            final Optional<EntityType<?>> resolved = entityRegistry.getOptional(id);
            if (resolved.isEmpty()) {
                logger.warn("[UltraCore] Unknown entity type in entities.ai.disabled-types: '{}'", raw);
                continue;
            }
            entitySet.add(resolved.get());
        }

        disabledDimensions = dimSet.isEmpty() ? Set.of() : ReferenceSets.unmodifiable((ReferenceOpenHashSet<ResourceKey<Level>>) dimSet);
        disabledTickDimensions = tickDimSet.isEmpty() ? Set.of() : ReferenceSets.unmodifiable((ReferenceOpenHashSet<ResourceKey<Level>>) tickDimSet);
        disabledAiEntityTypes = entitySet.isEmpty() ? Set.of() : ReferenceSets.unmodifiable((ReferenceOpenHashSet<EntityType<?>>) entitySet);
        registriesResolved = true;
    }

    /**
     * Returns an ordered list of currently disabled feature config keys for diagnostics.
     *
     * @return immutable list of disabled feature keys
     */
    public static List<String> activeKillSwitches() {
        final List<String> active = new ArrayList<>();
        for (final Feature feature : Feature.values()) {
            if (isDisabled(feature)) {
                active.add(feature.configKey());
            }
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * Resets all masks and sets (intended for unit tests).
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
        projectileTrackingRange = 32;
        particleBroadcastRangeSq = 256.0d;
        armorEquipmentRange = 32;
    }

    private static void mark(final long[] masks, final Feature feature, final boolean disabled) {
        if (disabled) {
            masks[feature.word] |= feature.bit;
        }
    }

    private static Set<String> normalizeIds(final List<String> rawIds) {
        if (rawIds.isEmpty()) {
            return Set.of();
        }
        final Set<String> normalized = new HashSet<>(rawIds.size());
        for (final String raw : rawIds) {
            if (raw == null) {
                continue;
            }
            final String trimmed = raw.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed.indexOf(':') >= 0 ? trimmed : "minecraft:" + trimmed);
            }
        }
        return normalized.isEmpty() ? Set.of() : Collections.unmodifiableSet(normalized);
    }
}
