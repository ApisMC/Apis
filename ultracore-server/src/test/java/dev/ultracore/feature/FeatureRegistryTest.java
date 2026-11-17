package dev.ultracore.feature;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerBuilder;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import dev.ultracore.config.UltraCoreConfig;
import dev.ultracore.hooks.UltraCoreHooks;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.datafix.fixes.References;
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
    void positiveDefaultsLeaveZeroKillSwitchesAndFalseValuesActivateGuards() {
        final UltraCoreConfig cfg = new UltraCoreConfig();
        FeatureRegistry.applyEarly(cfg);
        assertEquals(0, FeatureRegistry.activeKillSwitches().size());
        assertTrue(UltraCoreHooks.isDataFixersEnabled());
        assertTrue(UltraCoreHooks.isDaylightCycleEnabled());

        cfg.startup.datafixers = false;
        cfg.startup.searchSpawnPoint = false;
        cfg.startup.initialChunkLoad = false;
        cfg.startup.validateRegistries = false;
        cfg.startup.mojangServiceDiscovery = false;
        cfg.startup.paperVersionCheck = false;
        cfg.worlds.customWorlds = false;
        cfg.worlds.portals = false;
        cfg.entities.ai.enabled = false;
        cfg.chunks.structures = false;
        cfg.gameplay.redstone = false;
        cfg.gameplay.monsters = false;
        cfg.gameplay.animals = false;
        cfg.gameplay.advancements = false;
        cfg.gameplay.recipes = false;
        cfg.gameplay.wardenAndSleep = false;
        cfg.gameplay.commandFunctions = false;
        cfg.network.generateKeypair = false;
        cfg.network.analytics.metricsAndProfiler = false;
        cfg.network.tracking.playerCollisions = false;
        cfg.network.tracking.fullPacketBroadcast = false;
        cfg.network.tracking.serverPingSample = false;
        cfg.network.tracking.tabLatencyUpdates = false;
        cfg.storage.ephemeral.worldSaving = false;
        cfg.storage.ephemeral.gracefulShutdown = false;
        cfg.players.lifecycle.playerDataSaving = false;
        cfg.players.lifecycle.hunger = false;
        cfg.players.lifecycle.statistics = false;
        cfg.world.daylightCycle = false;
        cfg.world.physics.blockGravity = false;
        cfg.world.physics.hopperTicking = false;
        cfg.world.physics.blockEntityTicking = false;
        cfg.world.physics.itemPhysics = false;
        cfg.world.physics.leafDecay = false;
        cfg.world.physics.fireSpread = false;

        FeatureRegistry.applyEarly(cfg);

        assertFalse(UltraCoreHooks.isDataFixersEnabled());
        assertFalse(UltraCoreHooks.isSearchSpawnPointEnabled());
        assertFalse(UltraCoreHooks.isInitialChunkLoadEnabled());
        assertFalse(UltraCoreHooks.isValidateRegistriesEnabled());
        assertFalse(UltraCoreHooks.isMojangServiceDiscoveryEnabled());
        assertFalse(UltraCoreHooks.isPaperVersionCheckEnabled());
        assertFalse(UltraCoreHooks.isGenerateKeypairEnabled());
        assertFalse(UltraCoreHooks.isWardenAndSleepEnabled());
        assertFalse(UltraCoreHooks.isCommandFunctionsEnabled());
        assertFalse(UltraCoreHooks.isTabLatencyUpdatesEnabled());
        assertFalse(UltraCoreHooks.isHungerEnabled());
        assertFalse(UltraCoreHooks.isStatisticsEnabled());
        assertFalse(UltraCoreHooks.isDaylightCycleEnabled());
        assertFalse(UltraCoreHooks.isBlockEntityTickingEnabled());
        assertFalse(UltraCoreHooks.isItemPhysicsEnabled());

        assertTrue(UltraCoreHooks.isMemoryOnlyWorld());
        assertTrue(UltraCoreHooks.isFastShutdown());
        assertTrue(UltraCoreHooks.isAdvancementsDisabled());
        assertTrue(UltraCoreHooks.isRecipesDisabled());
        assertTrue(UltraCoreHooks.isMobCategoryDisabled(MobCategory.MONSTER));
        assertTrue(UltraCoreHooks.isMobCategoryDisabled(MobCategory.CREATURE));
        assertFalse(UltraCoreHooks.isMobCategoryDisabled(MobCategory.MISC));

        assertTrue(FeatureRegistry.isEnabled(Feature.GAMEPLAY_WEATHER));
        assertFalse(FeatureRegistry.isDisabled(Feature.GAMEPLAY_WEATHER));
        assertEquals(35, FeatureRegistry.activeKillSwitches().size());
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

    @Test
    void emptyDfuSchemaReturnsPassthroughTypesWithoutExceptions() {
        final DataFixerBuilder builder = new DataFixerBuilder(5016);
        final Schema schema = builder.addSchema(0, UltraCoreHooks::createEmptyDataFixerSchema);
        builder.addFixer(UltraCoreHooks.createNoopDataFix(schema));
        final DataFixer fixer = builder.build().fixer();

        assertEquals(DSL.remainderType(), schema.getTypeRaw(References.BLOCK_STATE));
        assertEquals(DSL.remainderType(), schema.getType(References.BLOCK_STATE));
        assertEquals(DSL.remainderType(), schema.getChoiceType(References.ENTITY, "minecraft:pig"));

        final CompoundTag sample = new CompoundTag();
        sample.putString("Name", "minecraft:stone");
        final Dynamic<Tag> input = new Dynamic<>(NbtOps.INSTANCE, sample);
        final Dynamic<Tag> updated = fixer.update(References.BLOCK_STATE, input, 1343, 5016);
        assertEquals(sample, updated.getValue());
    }
}
