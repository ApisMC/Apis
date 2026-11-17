package dev.ultracore.hooks;

import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.types.templates.TypeTemplate;
import dev.ultracore.config.UltraCoreConfig;
import dev.ultracore.config.UltraCorePaths;
import dev.ultracore.feature.Feature;
import dev.ultracore.feature.FeatureRegistry;
import io.papermc.paper.adventure.PaperAdventure;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.bukkit.NamespacedKey;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal static guard facade invoked from 1-line upstream micro-guards.
 * Encapsulates all UltraCore capability checks so Vanilla/Paper patches stay rebase-friendly.
 */
public final class UltraCoreHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft");

    private UltraCoreHooks() {
    }

    /**
     * Generates default .gitignore (if absent) and loads config/ultracore.yml early before Bootstrap.
     */
    public static void bootstrapEarly() {
        UltraCorePaths.ensureGitIgnore(Path.of("."));
        final @Nullable String overridePath = System.getProperty("ultracore.config");
        final Path resolved = overridePath != null && !overridePath.isBlank()
            ? Path.of(overridePath)
            : UltraCorePaths.resolveConfigPath(UltraCoreConfig.DEFAULT_FILE_NAME);
        UltraCoreConfig.loadEarly(resolved);
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

    public static boolean isValidateRegistriesEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_VALIDATE_REGISTRIES);
    }

    public static boolean isMojangServiceDiscoveryEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_MOJANG_SERVICE_DISCOVERY);
    }

    public static boolean isPaperVersionCheckEnabled() {
        return FeatureRegistry.isEnabled(Feature.STARTUP_PAPER_VERSION_CHECK);
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
        };
    }

    // 2. Worlds & Dimensions
    public static boolean isDimensionDisabled(final ResourceKey<Level> dimensionKey) {
        return FeatureRegistry.isDimensionDisabled(dimensionKey);
    }

    public static boolean isWorldCreationBlocked(final WorldCreator creator) {
        if (FeatureRegistry.isDisabled(Feature.WORLDS_CUSTOM_WORLDS)) {
            UltraCoreDiagnostics.warnBlocked(
                Feature.WORLDS_CUSTOM_WORLDS.configKey(),
                "CraftServer.createWorld",
                creator.name()
            );
            return true;
        }
        final NamespacedKey key = creator.key();
        final ResourceKey<Level> dimensionKey = CraftNamespacedKey.toResourceKey(Registries.DIMENSION, key);
        if (FeatureRegistry.isDimensionDisabled(dimensionKey)) {
            UltraCoreDiagnostics.warnBlocked(
                "worlds.disabled-dimensions",
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
            UltraCoreDiagnostics.warnBlocked("worlds.disabled-dimensions", "Bukkit.getWorld", worldName);
            return;
        }
        if ((lower.equals(baseLevel + "_the_end") || lower.equals("minecraft:the_end") || lower.equals("the_end"))
            && FeatureRegistry.isDimensionDisabled(Level.END)) {
            UltraCoreDiagnostics.warnBlocked("worlds.disabled-dimensions", "Bukkit.getWorld", worldName);
            return;
        }
        final @Nullable Identifier parsedId = Identifier.tryParse(lower.contains(":") ? lower : "minecraft:" + lower);
        final ResourceKey<Level> guessed = ResourceKey.create(
            Registries.DIMENSION,
            parsedId != null ? parsedId : Level.OVERWORLD.identifier()
        );
        if (!guessed.equals(Level.OVERWORLD) && FeatureRegistry.isDimensionDisabled(guessed)) {
            UltraCoreDiagnostics.warnBlocked("worlds.disabled-dimensions", "Bukkit.getWorld", worldName);
        }
    }

    public static void warnIfDisabledWorldKeyLookup(final Key worldKey) {
        final ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, PaperAdventure.asVanilla(worldKey));
        if (FeatureRegistry.isDimensionDisabled(dimensionKey)) {
            UltraCoreDiagnostics.warnBlocked("worlds.disabled-dimensions", "Bukkit.getWorld", worldKey.asString());
        }
    }

    public static void warnDisabledStructureLookup(final Level level, final String structure) {
        UltraCoreDiagnostics.warnBlocked(
            Feature.CHUNKS_STRUCTURES.configKey(),
            "World.locateNearestStructure@" + level.dimension().identifier(),
            structure
        );
    }

    public static boolean isPortalsDisabled() {
        return FeatureRegistry.isDisabled(Feature.WORLDS_PORTALS);
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
        return FeatureRegistry.isDisabled(Feature.AI_ENABLED)
            || FeatureRegistry.isDisabled(Feature.AI_GOAL_SELECTOR);
    }

    public static boolean isBrainDisabled() {
        return FeatureRegistry.isDisabled(Feature.AI_ENABLED)
            || FeatureRegistry.isDisabled(Feature.AI_BRAIN);
    }

    public static boolean isPathfindingDisabled() {
        return FeatureRegistry.isDisabled(Feature.AI_ENABLED)
            || FeatureRegistry.isDisabled(Feature.AI_PATHFINDING);
    }

    public static boolean isSensorsDisabled() {
        return FeatureRegistry.isDisabled(Feature.AI_ENABLED)
            || FeatureRegistry.isDisabled(Feature.AI_SENSORS);
    }

    public static boolean isEntityCollisionDisabled(final Entity a, final Entity b) {
        if (FeatureRegistry.isDisabled(Feature.ENTITIES_COLLISIONS)) {
            return true;
        }
        return FeatureRegistry.isDisabled(Feature.NETWORK_PLAYER_COLLISIONS)
            && a instanceof Player
            && b instanceof Player;
    }

    public static boolean isEntityPushEntitiesDisabled(final Entity entity) {
        if (FeatureRegistry.isDisabled(Feature.ENTITIES_COLLISIONS)) {
            return true;
        }
        return FeatureRegistry.isDisabled(Feature.NETWORK_PLAYER_COLLISIONS)
            && entity instanceof Player;
    }

    public static boolean isPlayerCollisionDisabled() {
        return FeatureRegistry.isDisabled(Feature.NETWORK_PLAYER_COLLISIONS)
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
        return FeatureRegistry.isDisabled(Feature.CHUNKS_RANDOM_TICKS);
    }

    public static boolean isBlockTicksDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_BLOCK_TICKS);
    }

    public static boolean isFluidTicksDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_FLUID_TICKS);
    }

    // 5. Gameplay & Environment
    public static boolean isWeatherDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_WEATHER);
    }

    public static boolean isRaidsDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_RAIDS);
    }

    public static boolean isRedstoneDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_REDSTONE);
    }

    public static boolean isNaturalSpawningDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_NATURAL_SPAWNING);
    }

    public static boolean isCustomSpawnersDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_CUSTOM_SPAWNERS);
    }

    public static boolean isMonstersDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_MONSTERS);
    }

    public static boolean isAnimalsDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_ANIMALS);
    }

    public static boolean isMobCategoryDisabled(final MobCategory category) {
        if (category == MobCategory.MONSTER) {
            return FeatureRegistry.isDisabled(Feature.GAMEPLAY_MONSTERS);
        }
        return category != MobCategory.MISC && FeatureRegistry.isDisabled(Feature.GAMEPLAY_ANIMALS);
    }

    public static boolean isAdvancementsDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_ADVANCEMENTS);
    }

    public static boolean isRecipesDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_RECIPES);
    }

    public static boolean isWardenAndSleepEnabled() {
        return FeatureRegistry.isEnabled(Feature.GAMEPLAY_WARDEN_AND_SLEEP);
    }

    public static boolean isCommandFunctionsEnabled() {
        return FeatureRegistry.isEnabled(Feature.GAMEPLAY_COMMAND_FUNCTIONS);
    }

    // 6. Network & Analytics
    public static boolean isGenerateKeypairEnabled() {
        return FeatureRegistry.isEnabled(Feature.NETWORK_GENERATE_KEYPAIR);
    }

    public static boolean isMetricsAndProfilerDisabled() {
        return FeatureRegistry.isDisabled(Feature.ANALYTICS_METRICS_AND_PROFILER);
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

    public static int equipmentTrackingRange(final int defaultRange) {
        return FeatureRegistry.isDisabled(Feature.NETWORK_FULL_PACKET_BROADCAST)
            ? Math.min(defaultRange, FeatureRegistry.armorEquipmentRange())
            : defaultRange;
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

    // 7. Ephemeral Storage & Shutdown
    public static boolean isMemoryOnlyWorld() {
        return FeatureRegistry.isDisabled(Feature.STORAGE_WORLD_SAVING);
    }

    public static boolean isFastShutdown() {
        return FeatureRegistry.isDisabled(Feature.STORAGE_GRACEFUL_SHUTDOWN);
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

    // 9. World Time & Physics
    public static boolean isDaylightCycleEnabled() {
        return FeatureRegistry.isEnabled(Feature.WORLD_DAYLIGHT_CYCLE);
    }

    public static boolean isBlockGravityDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_BLOCK_GRAVITY);
    }

    public static boolean isHopperTickingDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_HOPPER_TICKING);
    }

    public static boolean isBlockEntityTickingEnabled() {
        return FeatureRegistry.isEnabled(Feature.PHYSICS_BLOCK_ENTITY_TICKING);
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
}
