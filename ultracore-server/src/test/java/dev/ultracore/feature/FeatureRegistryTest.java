package dev.ultracore.feature;

import dev.ultracore.config.UltraCoreConfig;
import dev.ultracore.hooks.UltraCoreHooks;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
class FeatureRegistryTest {

    @AfterEach
    void tearDown() {
        FeatureRegistry.resetForTesting();
    }

    @Test
    void allFeaturesHaveUniqueBitPositions() {
        final Set<String> seen = new HashSet<>();
        for (final Feature feature : Feature.values()) {
            final String coord = feature.word + ":" + Long.numberOfTrailingZeros(feature.bit);
            assertTrue(seen.add(coord), "Duplicate bit position for " + feature.name());
            assertEquals(1, Long.bitCount(feature.bit), "Single bit expected for " + feature.name());
        }
    }

    @Test
    void appliesAndQueriesAllNineCategoriesInO1() {
        final UltraCoreConfig cfg = new UltraCoreConfig();
        cfg.worlds.disableCustomWorlds = true;
        cfg.worlds.disablePortals = true;
        cfg.entities.ai.disableGlobally = true;
        cfg.chunks.disableStructures = true;
        cfg.gameplay.disableRedstone = true;
        cfg.gameplay.disableMonsters = true;
        cfg.gameplay.disableAnimals = true;
        cfg.network.analytics.disableMetricsAndProfiler = true;
        cfg.storage.ephemeral.memoryOnlyWorld = true;
        cfg.storage.ephemeral.fastShutdown = true;
        cfg.network.tracking.disablePlayerCollisions = true;
        cfg.network.tracking.fastPacketFiltering = true;
        cfg.network.tracking.disableServerPingSample = true;
        cfg.players.lifecycle.disablePlayerDataSaving = true;
        cfg.players.lifecycle.disableAdvancements = true;
        cfg.players.lifecycle.disableRecipes = true;
        cfg.world.physics.disableBlockGravity = true;
        cfg.world.physics.disableHopperTicking = true;
        cfg.world.physics.disableLeafDecay = true;
        cfg.world.physics.disableFireSpread = true;

        FeatureRegistry.applyEarly(cfg);

        assertTrue(FeatureRegistry.isDisabled(Feature.WORLDS_DISABLE_CUSTOM_WORLDS));
        assertTrue(FeatureRegistry.isDisabled(Feature.WORLDS_DISABLE_PORTALS));
        assertTrue(FeatureRegistry.isDisabled(Feature.AI_DISABLE_GLOBALLY));
        assertTrue(FeatureRegistry.isDisabled(Feature.CHUNKS_DISABLE_STRUCTURES));
        assertTrue(FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_REDSTONE));
        assertTrue(FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_MONSTERS));
        assertTrue(FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_ANIMALS));
        assertTrue(FeatureRegistry.isDisabled(Feature.ANALYTICS_DISABLE_METRICS_AND_PROFILER));
        assertTrue(FeatureRegistry.isDisabled(Feature.STORAGE_EPHEMERAL_MEMORY_ONLY_WORLD));
        assertTrue(FeatureRegistry.isDisabled(Feature.STORAGE_EPHEMERAL_FAST_SHUTDOWN));
        assertTrue(FeatureRegistry.isDisabled(Feature.NETWORK_DISABLE_PLAYER_COLLISIONS));
        assertTrue(FeatureRegistry.isDisabled(Feature.NETWORK_FAST_PACKET_FILTERING));
        assertTrue(FeatureRegistry.isDisabled(Feature.NETWORK_DISABLE_SERVER_PING_SAMPLE));
        assertTrue(FeatureRegistry.isDisabled(Feature.PLAYERS_DISABLE_DATA_SAVING));
        assertTrue(FeatureRegistry.isDisabled(Feature.PLAYERS_DISABLE_ADVANCEMENTS));
        assertTrue(FeatureRegistry.isDisabled(Feature.PLAYERS_DISABLE_RECIPES));
        assertTrue(FeatureRegistry.isDisabled(Feature.PHYSICS_DISABLE_BLOCK_GRAVITY));
        assertTrue(FeatureRegistry.isDisabled(Feature.PHYSICS_DISABLE_HOPPER_TICKING));
        assertTrue(FeatureRegistry.isDisabled(Feature.PHYSICS_DISABLE_LEAF_DECAY));
        assertTrue(FeatureRegistry.isDisabled(Feature.PHYSICS_DISABLE_FIRE_SPREAD));

        assertFalse(FeatureRegistry.isDisabled(Feature.GAMEPLAY_DISABLE_WEATHER));
        assertTrue(FeatureRegistry.isEnabled(Feature.GAMEPLAY_DISABLE_WEATHER));
        assertEquals(20, FeatureRegistry.activeKillSwitches().size());
        assertTrue(UltraCoreHooks.isMobCategoryDisabled(MobCategory.MONSTER));
        assertTrue(UltraCoreHooks.isMobCategoryDisabled(MobCategory.CREATURE));
        assertFalse(UltraCoreHooks.isMobCategoryDisabled(MobCategory.MISC));
    }

    @Test
    void dimensionAndEntityFiltersWorkBeforeAndAfterRegistryResolution() {
        final UltraCoreConfig cfg = new UltraCoreConfig();
        cfg.worlds.disabledDimensions = List.of("minecraft:overworld", "minecraft:the_nether", "custom:arena");
        cfg.entities.ai.disabledTypes = List.of("zombie", "minecraft:villager");

        FeatureRegistry.applyEarly(cfg);

        final ResourceKey<Level> customArena = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("custom", "arena"));
        assertTrue(FeatureRegistry.isDimensionDisabled(Level.NETHER));
        assertTrue(FeatureRegistry.isDimensionDisabled(customArena));
        assertFalse(FeatureRegistry.isDimensionDisabled(Level.END));

        FeatureRegistry.resolveRegistries(null, LoggerFactory.getLogger("UltraCoreTest"));

        assertFalse(FeatureRegistry.isDimensionDisabled(Level.OVERWORLD), "Overworld must never be disabled");
        assertTrue(FeatureRegistry.isDimensionDisabled(Level.NETHER));
        assertTrue(FeatureRegistry.isDimensionDisabled(customArena));
        assertTrue(FeatureRegistry.isEntityAiDisabled(EntityTypes.ZOMBIE));
        assertTrue(FeatureRegistry.isEntityAiDisabled(EntityTypes.VILLAGER));
        assertFalse(FeatureRegistry.isEntityAiDisabled(EntityTypes.CREEPER));
    }
}
