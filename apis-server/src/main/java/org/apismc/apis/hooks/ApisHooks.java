// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.hooks;

import ca.spottedleaf.concurrentutil.numa.OSNuma;
import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.types.templates.TypeTemplate;
import com.mojang.serialization.Decoder;
import io.papermc.paper.adventure.PaperAdventure;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import joptsimple.OptionSet;
import net.kyori.adventure.key.Key;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.slot.EmptySlotSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.SequenceFunction;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.config.ApisPaths;
import org.apismc.apis.feature.Feature;
import org.apismc.apis.feature.FeatureRegistry;
import org.apismc.apis.world.ApisVoidChunkGenerator;
import org.apismc.apis.world.StandaloneWorldManager;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.generator.CustomWorldChunkManager;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.generator.BiomeProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal static guard facade invoked from 1-line upstream micro-guards.
 * Encapsulates all Apis capability checks so Vanilla/Paper patches stay rebase-friendly.
 */
public final class ApisHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft");
    private static final boolean IS_LINUX = System.getProperty("os.name", "")
        .toLowerCase(Locale.ROOT)
        .contains("linux");

    private ApisHooks() {
    }

    public static boolean isLinuxOs() {
        return IS_LINUX;
    }

    public static int resolveNumaTotalCores() {
        if (!IS_LINUX) {
            return Runtime.getRuntime().availableProcessors();
        }
        return OSNuma.getNativeInstance().getTotalCores();
    }

    /**
     * Generates default .gitignore (if absent), configures native cache dir, and loads configs/apis.yml early.
     */
    public static void bootstrapEarly() {
        ApisPaths.ensureGitIgnore(Path.of("."));
        configureNettyNativesDir();
        final @Nullable String overridePath = System.getProperty("apis.config");
        final Path resolved = overridePath != null && !overridePath.isBlank()
            ? Path.of(overridePath)
            : ApisPaths.resolveConfigPath(ApisConfig.DEFAULT_FILE_NAME);
        ApisConfig.loadEarly(resolved);
    }

    private static void configureNettyNativesDir() {
        if (System.getProperty("io.netty.native.workdir") != null) {
            return;
        }
        final Path nativesDir = Path.of("cache", "natives").toAbsolutePath().normalize();
        try {
            Files.createDirectories(nativesDir);
            System.setProperty("io.netty.native.workdir", nativesDir.toString());
        } catch (final IOException ex) {
            LOGGER.warn("Failed to prepare Netty natives cache directory '{}': {}", nativesDir, ex.getMessage());
        }
    }

    /**
     * Phase 2 registry resolution and active kill-switch summary logging.
     *
     * @param registryAccess server registry access
     */
    public static void resolveRegistries(final RegistryAccess registryAccess) {
        FeatureRegistry.resolveRegistries(registryAccess, LOGGER);
        if (!FeatureRegistry.activeKillSwitches().isEmpty()) {
            LOGGER.info("Active kill-switches: {}", String.join(", ", FeatureRegistry.activeKillSwitches()));
        }
    }

    // 1. Startup & Bootstrap
    public static boolean isDataFixersEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_DATAFIXERS);
    }

    public static boolean isSearchSpawnPointEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_SEARCH_SPAWN_POINT);
    }

    public static boolean isInitialChunkLoadEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_INITIAL_CHUNK_LOAD);
    }

    public static boolean isValidateCreativeTabsEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_VALIDATE_CREATIVE_TABS);
    }

    public static boolean isValidateLootEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_VALIDATE_LOOT);
    }

    public static boolean isLootTablesEnabled() {
        return FeatureRegistry.isEnabled(Feature.GAMEPLAY_LOOT_TABLES);
    }

    public static boolean isMojangServiceDiscoveryEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_MOJANG_SERVICE_DISCOVERY);
    }

    public static boolean isPaperVersionCheckEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_PAPER_VERSION_CHECK);
    }

    public static boolean isEagerBlockStateCacheEnabled() {
        return FeatureRegistry.isEnabled(Feature.CACHES_EAGER_BLOCKSTATE);
    }

    public static boolean isEagerCraftBlockDataCacheEnabled() {
        return FeatureRegistry.isEnabled(Feature.CACHES_EAGER_CRAFTBLOCKDATA);
    }

    /**
     * Checks whether a data-driven registry can be pruned before scanning or parsing JSON resources.
     *
     * @param registryKey registry key being loaded
     * @return {@code true} if the registry can remain empty and frozen
     */
    public static boolean shouldPruneRegistryAtLoad(final ResourceKey<? extends Registry<?>> registryKey) {
        if (Registries.RECIPE.equals(registryKey)) {
            return FeatureRegistry.isDisabled(Feature.GAMEPLAY_RECIPES);
        }
        if (Registries.ADVANCEMENT.equals(registryKey)) {
            return FeatureRegistry.isDisabled(Feature.GAMEPLAY_ADVANCEMENTS);
        }
        if (Registries.LOOT_TABLE.equals(registryKey)
            || Registries.PREDICATE.equals(registryKey)
            || Registries.ITEM_MODIFIER.equals(registryKey)
            || Registries.SLOT_SOURCE.equals(registryKey)) {
            return FeatureRegistry.isDisabled(Feature.GAMEPLAY_LOOT_TABLES);
        }
        if (Registries.WORLD_PRESET.equals(registryKey) || Registries.FLAT_LEVEL_GENERATOR_PRESET.equals(registryKey)) {
            return FeatureRegistry.isStandaloneMode();
        }
        if (Registries.CARVER.equals(registryKey)
            || Registries.FEATURE.equals(registryKey)
            || Registries.PLACED_FEATURE.equals(registryKey)) {
            return FeatureRegistry.isDisabled(Feature.CHUNKS_BIOME_DECORATION);
        }
        if (Registries.STRUCTURE.equals(registryKey)
            || Registries.STRUCTURE_SET.equals(registryKey)
            || Registries.PROCESSOR_LIST.equals(registryKey)
            || Registries.TEMPLATE_POOL.equals(registryKey)) {
            return FeatureRegistry.isDisabled(Feature.CHUNKS_STRUCTURES);
        }
        if (Registries.TEST_ENVIRONMENT.equals(registryKey) || Registries.TEST_INSTANCE.equals(registryKey)) {
            return FeatureRegistry.isDisabled(Feature.STARTUP_TEST_REGISTRIES);
        }
        return false;
    }

    /**
     * Selects a lightweight decoder for registries whose heavy sub-fields belong to disabled capabilities.
     *
     * @param registryKey    registry key being loaded
     * @param vanillaDecoder default element decoder
     * @param <T>            registry element type
     * @return decoder to use during JSON deserialization
     */
    @SuppressWarnings("unchecked")
    public static <T> Decoder<T> resolveRegistryDecoder(
        final ResourceKey<? extends Registry<T>> registryKey,
        final Decoder<T> vanillaDecoder
    ) {
        if (Registries.BIOME.equals(registryKey) && FeatureRegistry.isDisabled(Feature.CHUNKS_BIOME_DECORATION)) {
            return (Decoder<T>) Biome.NETWORK_CODEC;
        }
        return vanillaDecoder;
    }

    /**
     * Binds any dangling reference holders created by cross-registry references to pruned registries.
     *
     * @param registry pruned registry about to freeze
     * @param <T>      registry element type
     */
    @SuppressWarnings("unchecked")
    public static <T> void bindPrunedFallbackHolders(final MappedRegistry<T> registry) {
        if (Registries.LOOT_TABLE.equals(registry.key())) {
            registry.bindUnboundHoldersToFallback((T) LootTable.EMPTY);
        } else if (Registries.PREDICATE.equals(registry.key())) {
            registry.bindUnboundHoldersToFallback((T) AllOfCondition.allOf(HolderSet.empty()));
        } else if (Registries.ITEM_MODIFIER.equals(registry.key())) {
            registry.bindUnboundHoldersToFallback((T) new SequenceFunction(HolderSet.empty()));
        } else if (Registries.SLOT_SOURCE.equals(registry.key())) {
            registry.bindUnboundHoldersToFallback((T) new EmptySlotSource());
        }
    }

    // 10. Compatibility & Legacy
    public static boolean isPluginBytecodeReroutingEnabled() {
        return FeatureRegistry.isEnabled(Feature.LEGACY_PLUGIN_BYTECODE_REROUTING);
    }

    public static boolean isBootstrapDataTablesEnabled() {
        return FeatureRegistry.isEnabled(Feature.LEGACY_BOOTSTRAP_DATA_TABLES);
    }

    public static boolean isEagerMapColorCacheEnabled() {
        return FeatureRegistry.isEnabled(Feature.CACHES_EAGER_MAP_COLOR);
    }

    public static boolean isValidateEntityAttributesEnabled() {
        return FeatureRegistry.isEnabled(Feature.LEGACY_VALIDATE_ENTITY_ATTRIBUTES);
    }

    public static Schema createEmptyDataFixerSchema(final int versionKey, final @Nullable Schema parent) {
        return new Schema(versionKey, parent) {
            @Override
            public Map<String, Supplier<TypeTemplate>> registerEntities(final Schema schema) {
                return new ConcurrentHashMap<>();
            }

            @Override
            public Map<String, Supplier<TypeTemplate>> registerBlockEntities(final Schema schema) {
                return new ConcurrentHashMap<>();
            }

            @Override
            public void registerTypes(
                final Schema schema,
                final Map<String, Supplier<TypeTemplate>> entityTypes,
                final Map<String, Supplier<TypeTemplate>> blockEntityTypes
            ) {
            }

            @Override
            protected Map<String, Type<?>> buildTypes() {
                return new ConcurrentHashMap<>();
            }

            @Override
            public Type<?> getTypeRaw(final DSL.TypeReference type) {
                return DSL.remainderType();
            }

            @Override
            public Type<?> getType(final DSL.TypeReference type) {
                return DSL.remainderType();
            }

            @Override
            public Type<?> getChoiceType(final DSL.TypeReference type, final String choiceName) {
                return DSL.remainderType();
            }

            @Override
            public TypeTemplate resolveTemplate(final String name) {
                return DSL.remainder();
            }
        };
    }

    public static DataFix createNoopDataFix(final Schema outputSchema) {
        return new DataFix(outputSchema, false) {
            @Override
            protected TypeRewriteRule makeRule() {
                return TypeRewriteRule.nop();
            }
        };
    }

    // 2. Dimensions
    public static boolean isDimensionDisabled(final ResourceKey<Level> dimensionKey) {
        return FeatureRegistry.isDimensionDisabled(dimensionKey);
    }

    public static boolean isWorldCreationBlocked(final WorldCreator creator) {
        if (StandaloneWorldManager.isInternalDiscovery()) {
            return false;
        }
        if (FeatureRegistry.isDisabled(Feature.DIMENSIONS_CUSTOM_WORLDS)) {
            ApisDiagnostics.warnBlocked(
                Feature.DIMENSIONS_CUSTOM_WORLDS.configKey(),
                "CraftServer.createWorld",
                creator.name()
            );
            return true;
        }
        final NamespacedKey key = creator.key();
        final ResourceKey<Level> dimensionKey = CraftNamespacedKey.toResourceKey(Registries.DIMENSION, key);
        if ((dimensionKey.equals(Level.NETHER) || dimensionKey.equals(Level.END))
            && FeatureRegistry.isDimensionDisabled(dimensionKey)) {
            ApisDiagnostics.warnBlocked(
                "dimensions.enabled",
                "CraftServer.createWorld",
                key.asString()
            );
            return true;
        }
        return false;
    }

    public static void warnIfDisabledWorldLookup(final @Nullable MinecraftServer server, final String worldName) {
        final String lower = worldName.toLowerCase(Locale.ROOT);
        final String baseLevel = server != null && server.getWorldData() != null
            ? server.getWorldData().getLevelName().toLowerCase(Locale.ROOT)
            : "world";
        if ((lower.equals(baseLevel + "_nether") || lower.equals("minecraft:the_nether") || lower.equals("the_nether"))
            && FeatureRegistry.isDimensionDisabled(Level.NETHER)) {
            ApisDiagnostics.warnBlocked("dimensions.enabled", "Bukkit.getWorld", worldName);
            return;
        }
        if ((lower.equals(baseLevel + "_the_end") || lower.equals("minecraft:the_end") || lower.equals("the_end"))
            && FeatureRegistry.isDimensionDisabled(Level.END)) {
            ApisDiagnostics.warnBlocked("dimensions.enabled", "Bukkit.getWorld", worldName);
            return;
        }
        final @Nullable Identifier parsedId = Identifier.tryParse(lower.contains(":") ? lower : "minecraft:" + lower);
        final ResourceKey<Level> guessed = ResourceKey.create(
            Registries.DIMENSION,
            parsedId != null ? parsedId : Level.OVERWORLD.identifier()
        );
        if (!guessed.equals(Level.OVERWORLD) && FeatureRegistry.isDisabled(Feature.DIMENSIONS_CUSTOM_WORLDS)) {
            ApisDiagnostics.warnBlocked(Feature.DIMENSIONS_CUSTOM_WORLDS.configKey(), "Bukkit.getWorld", worldName);
        }
    }

    public static void warnIfDisabledWorldKeyLookup(final Key worldKey) {
        final ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, PaperAdventure.asVanilla(worldKey));
        if ((dimensionKey.equals(Level.NETHER) || dimensionKey.equals(Level.END))
            && FeatureRegistry.isDimensionDisabled(dimensionKey)) {
            ApisDiagnostics.warnBlocked("dimensions.enabled", "Bukkit.getWorld", worldKey.asString());
            return;
        }
        if (!dimensionKey.equals(Level.OVERWORLD) && FeatureRegistry.isDisabled(Feature.DIMENSIONS_CUSTOM_WORLDS)) {
            ApisDiagnostics.warnBlocked(
                Feature.DIMENSIONS_CUSTOM_WORLDS.configKey(),
                "Bukkit.getWorld",
                worldKey.asString()
            );
        }
    }

    public static void warnDisabledStructureLookup(final Level level, final String structure) {
        ApisDiagnostics.warnBlocked(
            Feature.CHUNKS_STRUCTURES.configKey(),
            "World.locateNearestStructure@" + level.dimension().identifier(),
            structure
        );
    }

    public static boolean isPortalsDisabled() {
        return FeatureRegistry.isDisabled(Feature.DIMENSIONS_PORTALS);
    }

    public static boolean isWorldTickDisabled(final Level level) {
        return FeatureRegistry.isWorldTickDisabled(level.dimension());
    }

    // 3. Entities & AI
    public static boolean isAiDisabled(final EntityType<?> entityType) {
        return FeatureRegistry.isEntityAiDisabled(entityType);
    }

    public static boolean isAiDisabled(final Entity entity) {
        return FeatureRegistry.isEntityAiDisabled(entity.getType());
    }

    public static boolean isGoalSelectorDisabled() {
        return FeatureRegistry.isDisabled(Feature.ENTITIES_AI_ENABLED)
            || FeatureRegistry.isDisabled(Feature.ENTITIES_AI_GOAL_SELECTOR);
    }

    public static boolean isBrainDisabled() {
        return FeatureRegistry.isDisabled(Feature.ENTITIES_AI_ENABLED)
            || FeatureRegistry.isDisabled(Feature.ENTITIES_AI_BRAIN);
    }

    public static boolean isPathfindingDisabled() {
        return FeatureRegistry.isDisabled(Feature.ENTITIES_AI_ENABLED)
            || FeatureRegistry.isDisabled(Feature.ENTITIES_AI_PATHFINDING);
    }

    public static boolean isSensorsDisabled() {
        return FeatureRegistry.isDisabled(Feature.ENTITIES_AI_ENABLED)
            || FeatureRegistry.isDisabled(Feature.ENTITIES_AI_SENSORS);
    }

    public static boolean isEntityCollisionDisabled(final Entity a, final Entity b) {
        if (FeatureRegistry.isDisabled(Feature.ENTITIES_COLLISIONS)) {
            return true;
        }
        return FeatureRegistry.isDisabled(Feature.ENTITIES_PLAYER_COLLISIONS)
            && a instanceof Player
            && b instanceof Player;
    }

    public static boolean isEntityPushEntitiesDisabled(final Entity entity) {
        if (FeatureRegistry.isDisabled(Feature.ENTITIES_COLLISIONS)) {
            return true;
        }
        return FeatureRegistry.isDisabled(Feature.ENTITIES_PLAYER_COLLISIONS)
            && entity instanceof Player;
    }

    public static boolean isPlayerCollisionDisabled() {
        return FeatureRegistry.isDisabled(Feature.ENTITIES_PLAYER_COLLISIONS)
            || FeatureRegistry.isDisabled(Feature.ENTITIES_COLLISIONS);
    }

    // 4. Chunks & Generation
    public static boolean isStructuresDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_STRUCTURES);
    }

    public static boolean isBiomeDecorationDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_BIOME_DECORATION);
    }

    public static boolean isChunkTickingDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_TICKING);
    }

    public static boolean isRandomTicksDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_TICKING)
            || FeatureRegistry.isDisabled(Feature.CHUNKS_RANDOM_TICKS);
    }

    public static boolean isBlockTicksDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_TICKING)
            || FeatureRegistry.isDisabled(Feature.CHUNKS_BLOCK_TICKS);
    }

    public static boolean isFluidTicksDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_TICKING)
            || FeatureRegistry.isDisabled(Feature.CHUNKS_FLUID_TICKS);
    }

    // 5. World Environment, Spawning, Physics & Gameplay
    public static boolean isWeatherDisabled() {
        return FeatureRegistry.isDisabled(Feature.ENV_WEATHER);
    }

    public static boolean isRaidsDisabled() {
        return FeatureRegistry.isDisabled(Feature.ENV_RAIDS);
    }

    public static boolean isRedstoneDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_REDSTONE);
    }

    public static boolean isNaturalSpawningDisabled() {
        return FeatureRegistry.isDisabled(Feature.SPAWNING_NATURAL);
    }

    public static boolean isCustomSpawnersDisabled() {
        return FeatureRegistry.isDisabled(Feature.SPAWNING_NATURAL)
            || FeatureRegistry.isDisabled(Feature.SPAWNING_CUSTOM_SPAWNERS);
    }

    public static boolean isMonstersDisabled() {
        return FeatureRegistry.isDisabled(Feature.SPAWNING_NATURAL)
            || FeatureRegistry.isDisabled(Feature.SPAWNING_MONSTERS);
    }

    public static boolean isAnimalsDisabled() {
        return FeatureRegistry.isDisabled(Feature.SPAWNING_NATURAL)
            || FeatureRegistry.isDisabled(Feature.SPAWNING_ANIMALS);
    }

    public static boolean isMobCategoryDisabled(final MobCategory category) {
        if (category == MobCategory.MONSTER) {
            return isMonstersDisabled();
        }
        return category != MobCategory.MISC && isAnimalsDisabled();
    }

    public static boolean isAdvancementsDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_ADVANCEMENTS);
    }

    public static boolean isRecipesDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_RECIPES);
    }

    public static boolean isWardenTrackerEnabled() {
        return FeatureRegistry.isEnabled(Feature.PLAYERS_WARDEN_TRACKER);
    }

    public static boolean isSleepCycleEnabled() {
        return FeatureRegistry.isEnabled(Feature.ENV_SLEEP_CYCLE);
    }

    public static boolean isEndDragonFightEnabled() {
        return FeatureRegistry.isEnabled(Feature.ENV_END_DRAGON_FIGHT);
    }

    public static boolean isCommandFunctionsEnabled() {
        return FeatureRegistry.isEnabled(Feature.GAMEPLAY_COMMAND_FUNCTIONS);
    }

    // 6. Network & Analytics
    public static boolean isGenerateKeypairEnabled() {
        return FeatureRegistry.isEnabled(Feature.NETWORK_GENERATE_KEYPAIR);
    }

    public static boolean isBstatsDisabled() {
        return FeatureRegistry.isDisabled(Feature.ANALYTICS_BSTATS);
    }

    public static boolean isSparkDisabled() {
        return FeatureRegistry.isDisabled(Feature.ANALYTICS_SPARK);
    }

    public static boolean isTickMonitorDisabled() {
        return FeatureRegistry.isDisabled(Feature.ANALYTICS_TICK_MONITOR);
    }

    public static boolean isDebugSynchronizersDisabled() {
        return FeatureRegistry.isDisabled(Feature.ANALYTICS_DEBUG_SYNCHRONIZERS);
    }

    public static boolean isServerPingSampleDisabled() {
        return FeatureRegistry.isDisabled(Feature.NETWORK_SERVER_PING_SAMPLE);
    }

    public static boolean isTabLatencyUpdatesEnabled() {
        return FeatureRegistry.isEnabled(Feature.NETWORK_TAB_LATENCY_UPDATES);
    }

    public static boolean isFastPacketFilteringEnabled() {
        return FeatureRegistry.isDisabled(Feature.NETWORK_FULL_PACKET_BROADCAST);
    }

    public static int clampEntityTrackingRange(final Entity entity, final int vanillaRange) {
        if (FeatureRegistry.isEnabled(Feature.NETWORK_FULL_PACKET_BROADCAST)) {
            return vanillaRange;
        }
        if (entity instanceof Projectile) {
            return Math.min(vanillaRange, FeatureRegistry.projectileTrackingRange());
        }
        return vanillaRange;
    }

    public static boolean shouldDropParticlePacket(final double distSq) {
        return FeatureRegistry.isDisabled(Feature.NETWORK_FULL_PACKET_BROADCAST)
            && distSq > FeatureRegistry.particleBroadcastRangeSq();
    }

    public static boolean shouldFilterEquipmentPacket(final Entity entity, final ServerPlayer viewer) {
        if (FeatureRegistry.isEnabled(Feature.NETWORK_FULL_PACKET_BROADCAST)) {
            return false;
        }
        if (entity == viewer) {
            return false;
        }
        final int range = FeatureRegistry.armorEquipmentRange();
        return entity.distanceToSqr(viewer) > (double) range * (double) range;
    }

    // 7. Storage & Lifecycle
    public static boolean isMemoryOnlyWorld() {
        return FeatureRegistry.isDisabled(Feature.STORAGE_WORLD_SAVING);
    }

    public static boolean isFastShutdown() {
        return FeatureRegistry.isDisabled(Feature.SYSTEM_GRACEFUL_SHUTDOWN);
    }

    // 8. Players Lifecycle
    public static boolean isPlayerDataSavingDisabled() {
        return FeatureRegistry.isDisabled(Feature.PLAYERS_DATA_SAVING)
            || FeatureRegistry.isDisabled(Feature.STORAGE_WORLD_SAVING);
    }

    public static boolean isHungerEnabled() {
        return FeatureRegistry.isEnabled(Feature.PLAYERS_HUNGER);
    }

    public static boolean isStatisticsEnabled() {
        return FeatureRegistry.isEnabled(Feature.PLAYERS_STATISTICS);
    }

    // 9. World Time, Block Entities & Physics
    public static boolean isDaylightCycleEnabled() {
        return FeatureRegistry.isEnabled(Feature.ENV_DAYLIGHT_CYCLE);
    }

    public static boolean isBlockGravityDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_BLOCK_GRAVITY);
    }

    public static boolean isHopperTickingDisabled() {
        return FeatureRegistry.isDisabled(Feature.BLOCK_ENTITIES_TICKING)
            || FeatureRegistry.isDisabled(Feature.BLOCK_ENTITIES_HOPPERS);
    }

    public static boolean isBlockEntityTickingEnabled() {
        return FeatureRegistry.isEnabled(Feature.BLOCK_ENTITIES_TICKING);
    }

    public static boolean isItemPhysicsEnabled() {
        return FeatureRegistry.isEnabled(Feature.PHYSICS_ITEM_PHYSICS);
    }

    public static boolean isLeafDecayDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_LEAF_DECAY);
    }

    public static boolean isFireSpreadDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_FIRE_SPREAD);
    }

    // 10. Standalone Worlds & Organized Directories Architecture
    public static void configureCliDirectories(final OptionSet options) {
        ApisPaths.configureFromOptions(options);
    }

    public static File resolveCliConfigFile(
        final OptionSet options,
        final String optionName,
        final String defaultFileName
    ) {
        return ApisPaths.resolveOptionConfigFile(options, optionName, defaultFileName);
    }

    public static Path resolvePaperDir(final OptionSet options) {
        return ApisPaths.resolvePaperDir(options);
    }

    public static Path resolveAddonsDir(final OptionSet options) {
        return ApisPaths.resolveAddonsDir(options);
    }

    public static File resolveConfigRelativeFile(final @Nullable String configuredPath, final String defaultFileName) {
        return ApisPaths.resolveConfigRelativeFile(configuredPath, defaultFileName);
    }

    public static boolean isStandaloneWorldsMode() {
        return StandaloneWorldManager.isStandaloneMode();
    }

    public static File resolveUniversePath(
        final OptionSet options,
        final YamlConfiguration bukkitConfiguration,
        final File vanillaUniverse
    ) {
        return StandaloneWorldManager.resolveUniversePath(options, bukkitConfiguration, vanillaUniverse);
    }

    public static String resolveStartupLevelName(final String vanillaLevelName) {
        return StandaloneWorldManager.isStandaloneMode()
            ? StandaloneWorldManager.fallbackWorldName()
            : vanillaLevelName;
    }

    public static void markFallbackWorldNewBeforeAccess(final Path universeDir, final String levelName) {
        StandaloneWorldManager.markFallbackWorldNewBeforeAccess(universeDir, levelName);
    }

    public static Path resolveDimensionPath(
        final ResourceKey<Level> dimensionKey,
        final Path overworldDir,
        final Path vanillaPath
    ) {
        return StandaloneWorldManager.resolveDimensionPath(dimensionKey, overworldDir, vanillaPath);
    }

    public static void registerStandaloneWorldFolder(final ResourceKey<Level> dimensionKey, final String folderName) {
        StandaloneWorldManager.registerDimensionFolder(dimensionKey, folderName);
    }

    public static WorldDimensions createInitialWorldDimensions(
        final HolderLookup.Provider registries,
        final DedicatedServerProperties properties
    ) {
        if (!StandaloneWorldManager.isStandaloneMode()) {
            return properties.createDimensions(registries);
        }
        return createVoidBukkitWorldDimensions(registries, LevelStem.OVERWORLD);
    }

    public static WorldDimensions createVoidBukkitWorldDimensions(
        final HolderLookup.Provider registries,
        final ResourceKey<LevelStem> stemKey
    ) {
        final HolderLookup.RegistryLookup<DimensionType> dimensionTypes =
            registries.lookupOrThrow(Registries.DIMENSION_TYPE);
        final ResourceKey<DimensionType> dimensionTypeKey;
        if (LevelStem.NETHER.equals(stemKey)) {
            dimensionTypeKey = BuiltinDimensionTypes.NETHER;
        } else if (LevelStem.END.equals(stemKey)) {
            dimensionTypeKey = BuiltinDimensionTypes.END;
        } else {
            dimensionTypeKey = BuiltinDimensionTypes.OVERWORLD;
        }
        final LevelStem stem = new LevelStem(
            dimensionTypes.getOrThrow(dimensionTypeKey),
            StandaloneWorldManager.createVoidFlatLevelSource(registries)
        );
        return new WorldDimensions(ImmutableMap.of(stemKey, stem));
    }

    public static BiomeSource resolveLevelBiomeSource(
        final RegistryAccess registryAccess,
        final BiomeProvider biomeProvider,
        final BiomeSource fallbackSource,
        final World bukkitWorld
    ) {
        if (biomeProvider == ApisVoidChunkGenerator.VOID_BIOME_PROVIDER) {
            return new FixedBiomeSource(registryAccess.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID));
        }
        return new CustomWorldChunkManager(bukkitWorld, biomeProvider, fallbackSource);
    }

    public static WorldDimensions resolveInitialStandaloneDimensions(
        final HolderLookup.Provider registries,
        final WorldDimensions original
    ) {
        return StandaloneWorldManager.resolveInitialDimensions(registries, original);
    }

    public static ChunkGenerator resolveVoidLevelStemGenerator(
        final HolderLookup.Provider registries,
        final ChunkGenerator original
    ) {
        return StandaloneWorldManager.isStandaloneMode()
            ? StandaloneWorldManager.createVoidFlatLevelSource(registries)
            : original;
    }

    public static org.bukkit.generator.@Nullable ChunkGenerator resolveVoidBukkitGenerator(
        final org.bukkit.generator.@Nullable ChunkGenerator original
    ) {
        return StandaloneWorldManager.isStandaloneMode() ? ApisVoidChunkGenerator.INSTANCE : original;
    }

    public static @Nullable BiomeProvider resolveVoidBiomeProvider(final @Nullable BiomeProvider original) {
        return StandaloneWorldManager.isStandaloneMode() ? ApisVoidChunkGenerator.VOID_BIOME_PROVIDER : original;
    }

    public static void onPrimaryWorldPrepared(final ServerLevel overworld) {
        StandaloneWorldManager.initializePrimaryFallbackWorld(overworld);
    }

    public static void loadStandaloneExtraWorlds(final MinecraftServer server) {
        StandaloneWorldManager.discoverAndLoadExtraWorlds(server);
    }

    public static void ensureStandaloneWorldConfig(final ServerLevel level) {
        StandaloneWorldManager.ensureWorldConfigLoaded(level);
    }

    public static StandaloneWorldManager.@Nullable ResolvedSpawn resolveStandaloneJoinSpawn(
        final MinecraftServer server,
        final boolean newPlayer,
        final @Nullable ServerLevel savedLevel,
        final boolean hasSavedPos
    ) {
        return StandaloneWorldManager.resolveJoinSpawn(server, newPlayer, savedLevel, hasSavedPos);
    }

    public static TeleportTransition resolveStandaloneRespawnDefault(
        final MinecraftServer server,
        final TeleportTransition.PostTeleportTransition post
    ) {
        return StandaloneWorldManager.resolveRespawnDefault(server, post);
    }

    public static void applyStandaloneJoinGameMode(final ServerPlayer player) {
        StandaloneWorldManager.applyWorldGameMode(player, false);
    }

    public static void applyStandaloneWorldGameMode(final ServerPlayer player) {
        StandaloneWorldManager.applyWorldGameMode(player, true);
    }

    public static ByteBuffer sessionLockPayload(final ByteBuffer marker) {
        final ByteBuffer dup = marker.duplicate();
        final byte[] markerBytes = new byte[dup.remaining()];
        dup.get(markerBytes);
        final byte[] pidSuffix = ("\n" + ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.UTF_8);
        final byte[] combined = new byte[markerBytes.length + pidSuffix.length];
        System.arraycopy(markerBytes, 0, combined, 0, markerBytes.length);
        System.arraycopy(pidSuffix, 0, combined, markerBytes.length, pidSuffix.length);
        return ByteBuffer.wrap(combined);
    }

    public static void cleanupStaleSessionLockSilently(final Path lockPath) {
        try {
            Files.deleteIfExists(lockPath);
        } catch (final IOException ex) {
            LOGGER.debug("Failed to delete stale session.lock at '{}': {}", lockPath, ex.getMessage());
        }
    }

    public static boolean isSessionLockOwnerAlive(final Path lockPath) {
        try {
            if (!Files.isRegularFile(lockPath) || Files.size(lockPath) > 1024L) {
                return false;
            }
            final String content = Files.readString(lockPath, StandardCharsets.UTF_8);
            final long currentPid = ProcessHandle.current().pid();
            for (final String rawLine : content.split("\\R")) {
                final String line = rawLine.trim();
                if (line.isEmpty()) {
                    continue;
                }
                final long pid;
                try {
                    pid = Long.parseLong(line);
                } catch (NumberFormatException _) {
                    continue;
                }
                if (pid > 0L && pid != currentPid) {
                    return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent();
                }
            }
            return false;
        } catch (IOException _) {
            return false;
        }
    }

    public static boolean tryBreakStaleSessionLock(final Path worldDir, final Path lockPath) {
        if (isSessionLockOwnerAlive(lockPath)) {
            return false;
        }
        final Path fileName = worldDir.getFileName();
        final String worldName = fileName != null ? fileName.toString() : worldDir.toString();
        final Path lockName = lockPath.getFileName();
        try {
            if (Files.deleteIfExists(lockPath)) {
                ApisLog.ok(
                    LOGGER,
                    "Cleared stale {} for world '{}' (previous process not running)",
                    lockName != null ? lockName.toString() : "session.lock",
                    worldName
                );
            }
            return true;
        } catch (final IOException ex) {
            LOGGER.warn("Failed to delete stale session.lock at '{}': {}", lockPath, ex.getMessage());
            return false;
        }
    }
}
