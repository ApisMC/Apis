package dev.ultracore.hooks;

import dev.ultracore.config.UltraCoreConfig;
import dev.ultracore.config.UltraCorePaths;
import dev.ultracore.feature.Feature;
import dev.ultracore.feature.FeatureRegistry;
import io.papermc.paper.adventure.PaperAdventure;
import java.nio.file.Path;
import java.util.Locale;
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
 * Minimal static guard facade invoked from 1-3 line upstream micro-guards.
 * Keeps all UltraCore logic decoupled from Vanilla/Paper patches for painless rebases.
 */
public final class UltraCoreHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("UltraCore");

    private UltraCoreHooks() {
    }

    public static void bootstrapEarly() {
        UltraCorePaths.ensureGitIgnore(Path.of("."));
        final @Nullable String overridePath = System.getProperty("ultracore.config");
        final Path resolved = overridePath != null && !overridePath.isBlank()
            ? Path.of(overridePath)
            : UltraCorePaths.resolveConfigPath(UltraCoreConfig.DEFAULT_FILE_NAME);
        UltraCoreConfig.loadEarly(resolved);
    }

    public static void resolveRegistries(final RegistryAccess registryAccess) {
        FeatureRegistry.resolveRegistries(registryAccess, LOGGER);
        if (!FeatureRegistry.activeKillSwitches().isEmpty()) {
            LOGGER.info("[UltraCore] Active kill-switches: {}", String.join(", ", FeatureRegistry.activeKillSwitches()));
        }
    }

    // 1. Worlds & Dimensions
    public static boolean isDimensionDisabled(final ResourceKey<Level> dimensionKey) {
        return FeatureRegistry.isDimensionDisabled(dimensionKey);
    }

    public static boolean isWorldCreationBlocked(final WorldCreator creator) {
        if (FeatureRegistry.isDisabled(Feature.WORLDS_DISABLE_CUSTOM_WORLDS)) {
            UltraCoreDiagnostics.warnBlocked(
                Feature.WORLDS_DISABLE_CUSTOM_WORLDS.configKey(),
                "CraftServer.createWorld",
                creator.name()
            );
            return true;
        }
        final NamespacedKey key = creator.key();
        final ResourceKey<Level> dimensionKey = CraftNamespacedKey.toResourceKey(
            Registries.DIMENSION,
            key
        );
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
            Feature.CHUNKS_DISABLE_STRUCTURES.configKey(),
            "World.locateNearestStructure@" + level.dimension().identifier(),
            structure
        );
    }

    public static boolean isPortalsDisabled() {
        return FeatureRegistry.isDisabled(Feature.WORLDS_DISABLE_PORTALS);
    }

    public static boolean isWorldTickDisabled(final Level level) {
        return FeatureRegistry.isWorldTickDisabled(level.dimension());
    }

    // 2. Entities & AI
    public static boolean isAiDisabled(final EntityType<?> entityType) {
        return FeatureRegistry.isEntityAiDisabled(entityType);
    }

    public static boolean isAiDisabled(final Entity entity) {
        return FeatureRegistry.isEntityAiDisabled(entity.getType());
    }

    public static boolean isGoalSelectorDisabled() {
        return FeatureRegistry.isDisabled(Feature.AI_DISABLE_GLOBALLY)
            || FeatureRegistry.isDisabled(Feature.AI_DISABLE_GOAL_SELECTOR);
    }

    public static boolean isBrainDisabled() {
        return FeatureRegistry.isDisabled(Feature.AI_DISABLE_GLOBALLY)
            || FeatureRegistry.isDisabled(Feature.AI_DISABLE_BRAIN);
    }

    public static boolean isPathfindingDisabled() {
        return FeatureRegistry.isDisabled(Feature.AI_DISABLE_GLOBALLY)
            || FeatureRegistry.isDisabled(Feature.AI_DISABLE_PATHFINDING);
    }

    public static boolean isSensorsDisabled() {
        return FeatureRegistry.isDisabled(Feature.AI_DISABLE_GLOBALLY)
            || FeatureRegistry.isDisabled(Feature.AI_DISABLE_SENSORS);
    }

    public static boolean isEntityCollisionDisabled(final Entity a, final Entity b) {
        if (FeatureRegistry.isDisabled(Feature.ENTITIES_DISABLE_COLLISIONS)) {
            return true;
        }
        return FeatureRegistry.isDisabled(Feature.NETWORK_DISABLE_PLAYER_COLLISIONS)
            && a instanceof Player
            && b instanceof Player;
    }

    public static boolean isEntityPushEntitiesDisabled(final Entity entity) {
        if (FeatureRegistry.isDisabled(Feature.ENTITIES_DISABLE_COLLISIONS)) {
            return true;
        }
        return FeatureRegistry.isDisabled(Feature.NETWORK_DISABLE_PLAYER_COLLISIONS)
            && entity instanceof Player;
    }

    public static boolean isPlayerCollisionDisabled() {
        return FeatureRegistry.isDisabled(Feature.NETWORK_DISABLE_PLAYER_COLLISIONS)
            || FeatureRegistry.isDisabled(Feature.ENTITIES_DISABLE_COLLISIONS);
    }

    // 3. Chunks & Generation
    public static boolean isStructuresDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_DISABLE_STRUCTURES);
    }

    public static boolean isBiomeDecorationDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_DISABLE_BIOME_DECORATION);
    }

    public static boolean isChunkTickingDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_DISABLE_TICKING);
    }

    public static boolean isRandomTicksDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_DISABLE_RANDOM_TICKS);
    }

    public static boolean isBlockTicksDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_DISABLE_BLOCK_TICKS);
    }

    public static boolean isFluidTicksDisabled() {
        return FeatureRegistry.isDisabled(Feature.CHUNKS_DISABLE_FLUID_TICKS);
    }

    // 4. Gameplay & Environment
    public static boolean isWeatherDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_WEATHER);
    }

    public static boolean isRaidsDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_RAIDS);
    }

    public static boolean isRedstoneDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_REDSTONE);
    }

    public static boolean isNaturalSpawningDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_NATURAL_SPAWNING);
    }

    public static boolean isCustomSpawnersDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_CUSTOM_SPAWNERS);
    }

    public static boolean isMonstersDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_MONSTERS);
    }

    public static boolean isAnimalsDisabled() {
        return FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_ANIMALS);
    }

    public static boolean isMobCategoryDisabled(final MobCategory category) {
        if (category == MobCategory.MONSTER) {
            return FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_MONSTERS);
        }
        return category != MobCategory.MISC && FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_ANIMALS);
    }

    // 5. Network & Analytics
    public static boolean isMetricsAndProfilerDisabled() {
        return FeatureRegistry.isDisabled(Feature.ANALYTICS_DISABLE_METRICS_AND_PROFILER);
    }

    public static boolean isDebugSynchronizersDisabled() {
        return FeatureRegistry.isDisabled(Feature.ANALYTICS_DISABLE_DEBUG_SYNCHRONIZERS);
    }

    // 6. Ephemeral Storage & Lifecycle
    public static boolean isMemoryOnlyWorld() {
        return FeatureRegistry.isDisabled(Feature.STORAGE_EPHEMERAL_MEMORY_ONLY_WORLD);
    }

    public static boolean isFastShutdown() {
        return FeatureRegistry.isDisabled(Feature.STORAGE_EPHEMERAL_FAST_SHUTDOWN);
    }

    // 7. Network Tracking & Packet Filtering
    public static boolean isServerPingSampleDisabled() {
        return FeatureRegistry.isDisabled(Feature.NETWORK_DISABLE_SERVER_PING_SAMPLE);
    }

    public static int clampEntityTrackingRange(final Entity entity, final int vanillaRange) {
        if (!FeatureRegistry.isDisabled(Feature.NETWORK_FAST_PACKET_FILTERING)) {
            return vanillaRange;
        }
        if (entity instanceof Projectile) {
            return Math.min(vanillaRange, FeatureRegistry.projectileTrackingRange());
        }
        return vanillaRange;
    }

    public static boolean shouldDropParticlePacket(final double distSq) {
        return FeatureRegistry.isDisabled(Feature.NETWORK_FAST_PACKET_FILTERING)
            && distSq > FeatureRegistry.particleBroadcastRangeSq();
    }

    public static int equipmentTrackingRange(final int defaultRange) {
        return FeatureRegistry.isDisabled(Feature.NETWORK_FAST_PACKET_FILTERING)
            ? Math.min(defaultRange, FeatureRegistry.armorEquipmentRange())
            : defaultRange;
    }

    public static boolean shouldFilterEquipmentPacket(final Entity entity, final ServerPlayer viewer) {
        if (!FeatureRegistry.isDisabled(Feature.NETWORK_FAST_PACKET_FILTERING)) {
            return false;
        }
        if (entity == viewer) {
            return false;
        }
        final int range = FeatureRegistry.armorEquipmentRange();
        return entity.distanceToSqr(viewer) > (double) range * (double) range;
    }

    // 8. Players Lifecycle & Inventory
    public static boolean isPlayerDataSavingDisabled() {
        return FeatureRegistry.isDisabled(Feature.PLAYERS_DISABLE_DATA_SAVING)
            || FeatureRegistry.isDisabled(Feature.STORAGE_EPHEMERAL_MEMORY_ONLY_WORLD);
    }

    public static boolean isAdvancementsDisabled() {
        return FeatureRegistry.isDisabled(Feature.PLAYERS_DISABLE_ADVANCEMENTS);
    }

    public static boolean isRecipesDisabled() {
        return FeatureRegistry.isDisabled(Feature.PLAYERS_DISABLE_RECIPES);
    }

    // 9. World Physics & Containers
    public static boolean isBlockGravityDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_DISABLE_BLOCK_GRAVITY);
    }

    public static boolean isHopperTickingDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_DISABLE_HOPPER_TICKING);
    }

    public static boolean isLeafDecayDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_DISABLE_LEAF_DECAY);
    }

    public static boolean isFireSpreadDisabled() {
        return FeatureRegistry.isDisabled(Feature.PHYSICS_DISABLE_FIRE_SPREAD);
    }
}
