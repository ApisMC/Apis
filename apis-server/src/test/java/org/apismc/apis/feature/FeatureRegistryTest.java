// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.feature;

import ca.spottedleaf.concurrentutil.numa.OSNuma;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerBuilder;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.codec.RegistryCodecs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.slot.SlotSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.hooks.ApisHooks;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        final ApisConfig cfg = new ApisConfig();
        FeatureRegistry.applyEarly(cfg);
        assertEquals(0, FeatureRegistry.activeKillSwitches().size());
        assertTrue(ApisHooks.isDataFixersEnabled());
        assertTrue(ApisHooks.isDaylightCycleEnabled());

        cfg.startup.datafixers = false;
        cfg.startup.searchSpawnPoint = false;
        cfg.startup.initialChunkLoad = false;
        cfg.startup.mojangServiceDiscovery = false;
        cfg.startup.paperVersionCheck = false;
        cfg.startup.validateCreativeTabs = false;
        cfg.startup.validateLoot = false;
        cfg.startup.testRegistries = false;
        cfg.caches.eagerBlockstateCache = false;
        cfg.caches.eagerCraftblockdataCache = false;
        cfg.caches.eagerMapColorCache = false;
        cfg.dimensions.customWorlds = false;
        cfg.dimensions.portals = false;
        cfg.chunks.structures = false;
        cfg.chunks.biomeDecoration = false;
        cfg.chunks.ticking.subFeatures.poiTicking = false;
        cfg.blockEntities.ticking.enabled = false;
        cfg.blocksAndPhysics.redstone = false;
        cfg.blocksAndPhysics.blockGravity = false;
        cfg.blocksAndPhysics.leafDecay = false;
        cfg.blocksAndPhysics.fireSpread = false;
        cfg.blocksAndPhysics.itemPhysics = false;
        cfg.blocksAndPhysics.blockEvents = false;
        cfg.worldEnvironment.daylightCycle = false;
        cfg.worldEnvironment.raids = false;
        cfg.worldEnvironment.sleepCycle = false;
        cfg.worldEnvironment.endDragonFight = false;
        cfg.worldEnvironment.worldBorderTick = false;
        cfg.worldEnvironment.environmentAttributes = false;
        cfg.worldEnvironment.gameEvents = false;
        cfg.spawning.natural.subFeatures.monsters = false;
        cfg.spawning.natural.subFeatures.animals = false;
        cfg.entities.ai.enabled = false;
        cfg.entities.collisions.players = false;
        cfg.entities.despawnChecks = false;
        cfg.entities.insideBlockChecks = false;
        cfg.entities.fluidInteraction = false;
        cfg.entities.sprintParticles = false;
        cfg.gameplay.advancements = false;
        cfg.gameplay.recipes = false;
        cfg.gameplay.lootTables = false;
        cfg.gameplay.commandFunctions = false;
        cfg.players.dataSaving = false;
        cfg.players.hunger = false;
        cfg.players.statistics = false;
        cfg.players.wardenTracker = false;
        cfg.players.locatorBar = false;
        cfg.players.inventorySpecialItemUpdates = false;
        cfg.players.vanillaScoreboardCriteria = false;
        cfg.players.floatingKick = false;
        cfg.network.generateKeypair = false;
        cfg.network.analytics.bstats = false;
        cfg.network.analytics.spark = false;
        cfg.network.analytics.tickMonitor = false;
        cfg.network.playerList.serverPingSample = false;
        cfg.network.playerList.tabLatencyUpdates = false;
        cfg.network.packetRangeClamping.fullPacketBroadcast = false;
        cfg.storage.worldSaving = false;
        cfg.storage.periodicAutosave = false;
        cfg.system.lifecycle.gracefulShutdown = false;
        cfg.compatibility.legacy.pluginBytecodeRerouting = false;
        cfg.compatibility.legacy.bootstrapDataTables = false;
        cfg.compatibility.legacy.validateEntityAttributes = false;

        FeatureRegistry.applyEarly(cfg);

        assertFalse(ApisHooks.isDataFixersEnabled());
        assertFalse(ApisHooks.isSearchSpawnPointEnabled());
        assertFalse(ApisHooks.isInitialChunkLoadEnabled());
        assertFalse(ApisHooks.isValidateCreativeTabsEnabled());
        assertFalse(ApisHooks.isValidateLootEnabled());
        assertFalse(ApisHooks.isMojangServiceDiscoveryEnabled());
        assertFalse(ApisHooks.isPaperVersionCheckEnabled());
        assertFalse(ApisHooks.isEagerBlockStateCacheEnabled());
        assertFalse(ApisHooks.isEagerCraftBlockDataCacheEnabled());
        assertFalse(ApisHooks.isEagerMapColorCacheEnabled());
        assertFalse(ApisHooks.isGenerateKeypairEnabled());
        assertFalse(ApisHooks.isWardenTrackerEnabled());
        assertFalse(ApisHooks.isSleepCycleEnabled());
        assertFalse(ApisHooks.isEndDragonFightEnabled());
        assertFalse(ApisHooks.isCommandFunctionsEnabled());
        assertFalse(ApisHooks.isTabLatencyUpdatesEnabled());
        assertFalse(ApisHooks.isHungerEnabled());
        assertFalse(ApisHooks.isStatisticsEnabled());
        assertFalse(ApisHooks.isDaylightCycleEnabled());
        assertFalse(ApisHooks.isBlockEntityTickingEnabled());
        assertFalse(ApisHooks.isItemPhysicsEnabled());
        assertFalse(ApisHooks.isBlockEventsEnabled());
        assertFalse(ApisHooks.isWorldBorderTickEnabled());
        assertFalse(ApisHooks.isEnvironmentAttributesTickEnabled());
        assertFalse(ApisHooks.isGameEventsEnabled());
        assertTrue(ApisHooks.isPoiTickingDisabled());
        assertFalse(ApisHooks.isDespawnChecksEnabled());
        assertFalse(ApisHooks.isInsideBlockChecksEnabled());
        assertFalse(ApisHooks.isFluidInteractionEnabled());
        assertFalse(ApisHooks.isSprintParticlesEnabled());
        assertFalse(ApisHooks.isLocatorBarEnabled());
        assertFalse(ApisHooks.isInventorySpecialUpdatesEnabled());
        assertFalse(ApisHooks.isVanillaScoreboardCriteriaEnabled());
        assertFalse(ApisHooks.isFloatingKickEnabled());
        assertFalse(ApisHooks.isPeriodicAutosaveEnabled());
        assertFalse(ApisHooks.isPluginBytecodeReroutingEnabled());
        assertFalse(ApisHooks.isBootstrapDataTablesEnabled());
        assertFalse(ApisHooks.isValidateEntityAttributesEnabled());
        assertTrue(ApisHooks.isBstatsDisabled());
        assertTrue(ApisHooks.isSparkDisabled());
        assertTrue(ApisHooks.isTickMonitorDisabled());

        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.RECIPE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.ADVANCEMENT));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.LOOT_TABLE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.PLACED_FEATURE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.STRUCTURE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.TEST_INSTANCE));
        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.BIOME));
        assertEquals(Biome.NETWORK_CODEC, ApisHooks.resolveRegistryDecoder(Registries.BIOME, Biome.DIRECT_CODEC));
        assertTrue(DefaultAttributes.hasSupplier(EntityTypes.ZOMBIE));
        assertNotNull(DefaultAttributes.getSupplier(EntityTypes.PLAYER));

        assertTrue(ApisHooks.isMemoryOnlyWorld());
        assertTrue(ApisHooks.isFastShutdown());
        assertTrue(ApisHooks.isAdvancementsDisabled());
        assertTrue(ApisHooks.isRecipesDisabled());
        assertTrue(ApisHooks.isMobCategoryDisabled(MobCategory.MONSTER));
        assertTrue(ApisHooks.isMobCategoryDisabled(MobCategory.CREATURE));
        assertFalse(ApisHooks.isMobCategoryDisabled(MobCategory.MISC));

        assertTrue(FeatureRegistry.isEnabled(Feature.ENV_WEATHER));
        assertFalse(FeatureRegistry.isDisabled(Feature.ENV_WEATHER));
        assertEquals(63, FeatureRegistry.activeKillSwitches().size());
    }

    @Test
    void masterSwitchesBypassSubFeaturesAndSplitSwitchesRemainIndependent() {
        final ApisConfig cfg = new ApisConfig();
        cfg.entities.ai.enabled = false;
        cfg.chunks.ticking.enabled = false;
        cfg.spawning.natural.enabled = false;
        cfg.blockEntities.ticking.enabled = false;
        cfg.entities.collisions.entities = false;
        cfg.storage.worldSaving = false;

        FeatureRegistry.applyEarly(cfg);

        assertTrue(ApisHooks.isGoalSelectorDisabled());
        assertTrue(ApisHooks.isBrainDisabled());
        assertTrue(ApisHooks.isPathfindingDisabled());
        assertTrue(ApisHooks.isSensorsDisabled());
        assertTrue(ApisHooks.isAiDisabled(EntityTypes.CREEPER));

        assertTrue(ApisHooks.isChunkTickingDisabled());
        assertTrue(ApisHooks.isRandomTicksDisabled());
        assertTrue(ApisHooks.isBlockTicksDisabled());
        assertTrue(ApisHooks.isFluidTicksDisabled());
        assertTrue(ApisHooks.isPoiTickingDisabled());
        assertFalse(ApisHooks.isPeriodicAutosaveEnabled());

        assertTrue(ApisHooks.isNaturalSpawningDisabled());
        assertTrue(ApisHooks.isMonstersDisabled());
        assertTrue(ApisHooks.isAnimalsDisabled());
        assertTrue(ApisHooks.isCustomSpawnersDisabled());
        assertTrue(ApisHooks.isMobCategoryDisabled(MobCategory.MONSTER));
        assertTrue(ApisHooks.isMobCategoryDisabled(MobCategory.CREATURE));

        assertFalse(ApisHooks.isBlockEntityTickingEnabled());
        assertTrue(ApisHooks.isHopperTickingDisabled());

        assertTrue(ApisHooks.isPlayerCollisionDisabled());

        // Split switches must operate independently
        final ApisConfig splitCfg = new ApisConfig();
        splitCfg.players.wardenTracker = false;
        splitCfg.network.analytics.bstats = false;
        splitCfg.startup.validateCreativeTabs = false;
        FeatureRegistry.applyEarly(splitCfg);

        assertFalse(ApisHooks.isWardenTrackerEnabled());
        assertTrue(ApisHooks.isSleepCycleEnabled());
        assertTrue(ApisHooks.isEndDragonFightEnabled());

        assertTrue(ApisHooks.isBstatsDisabled());
        assertFalse(ApisHooks.isSparkDisabled());
        assertFalse(ApisHooks.isTickMonitorDisabled());

        assertFalse(ApisHooks.isValidateCreativeTabsEnabled());
        assertTrue(ApisHooks.isValidateLootEnabled());
        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.TEST_INSTANCE));
    }

    @Test
    void dimensionAndEntityWhitelistsWorkBeforeAndAfterRegistryResolution() {
        final ApisConfig cfg = new ApisConfig();
        cfg.dimensions.enabled = List.of("minecraft:the_end");
        cfg.dimensions.ticking = List.of("minecraft:overworld", "custom:arena");
        cfg.entities.ai.typeFilter = List.of("zombie", "minecraft:villager");

        FeatureRegistry.applyEarly(cfg);

        final ResourceKey<Level> customArena = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("custom", "arena")
        );
        assertFalse(FeatureRegistry.isDimensionDisabled(Level.OVERWORLD), "Overworld must never be disabled");
        assertFalse(FeatureRegistry.isDimensionDisabled(Level.END));
        assertTrue(FeatureRegistry.isDimensionDisabled(Level.NETHER));
        assertTrue(FeatureRegistry.isDimensionDisabled(customArena));

        assertFalse(FeatureRegistry.isWorldTickDisabled(Level.OVERWORLD));
        assertFalse(FeatureRegistry.isWorldTickDisabled(customArena));
        assertTrue(FeatureRegistry.isWorldTickDisabled(Level.NETHER));

        FeatureRegistry.resolveRegistries(null, LoggerFactory.getLogger("ApisTest"));

        assertFalse(FeatureRegistry.isDimensionDisabled(Level.OVERWORLD), "Overworld must never be disabled");
        assertFalse(FeatureRegistry.isDimensionDisabled(Level.END));
        assertTrue(FeatureRegistry.isDimensionDisabled(Level.NETHER));
        assertTrue(FeatureRegistry.isDimensionDisabled(customArena));
        assertFalse(FeatureRegistry.isWorldTickDisabled(Level.OVERWORLD));
        assertTrue(FeatureRegistry.isWorldTickDisabled(Level.NETHER));
        assertTrue(FeatureRegistry.isEntityAiDisabled(EntityTypes.ZOMBIE));
        assertTrue(FeatureRegistry.isEntityAiDisabled(EntityTypes.VILLAGER));
        assertFalse(FeatureRegistry.isEntityAiDisabled(EntityTypes.CREEPER));

        cfg.dimensions.enabled = List.of("*");
        FeatureRegistry.applyEarly(cfg);
        assertFalse(FeatureRegistry.isDimensionDisabled(Level.NETHER));
        assertFalse(FeatureRegistry.isDimensionDisabled(customArena));
    }

    @Test
    void emptyDfuSchemaReturnsPassthroughTypesWithoutExceptions() {
        final DataFixerBuilder builder = new DataFixerBuilder(5016);
        final Schema schema = builder.addSchema(0, ApisHooks::createEmptyDataFixerSchema);
        builder.addFixer(ApisHooks.createNoopDataFix(schema));
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

    @Test
    void prunedRegistryReturnsEmptyBoundTagsForHolderSetCodecAndFallbackHolders() {
        final ApisConfig cfg = new ApisConfig();
        cfg.chunks.structures = false;
        cfg.gameplay.lootTables = false;
        FeatureRegistry.applyEarly(cfg);

        final MappedRegistry<Structure> structureRegistry = new MappedRegistry<>(Registries.STRUCTURE, Lifecycle.stable());
        structureRegistry.bindAllTagsToEmpty();
        structureRegistry.freeze();

        final TagKey<Structure> treasureMaps = TagKey.create(
            Registries.STRUCTURE,
            Identifier.withDefaultNamespace("on_treasure_maps")
        );
        final HolderSet.Named<Structure> emptyTag = structureRegistry.get(treasureMaps).orElseThrow();
        assertTrue(emptyTag.isBound());
        assertEquals(0, emptyTag.size());

        final RegistryOps<JsonElement> ops = RegistryOps.create(
            JsonOps.INSTANCE,
            HolderLookup.Provider.create(Stream.of(structureRegistry))
        );
        final DataResult<HolderSet<Structure>> decoded = RegistryCodecs
            .holderSet(Registries.STRUCTURE)
            .parse(ops, new JsonPrimitive("#minecraft:on_treasure_maps"));
        assertTrue(decoded.isSuccess(), () -> "Expected empty HolderSet but got error: " + decoded.error());
        assertEquals(0, decoded.getOrThrow().size());

        final MappedRegistry<LootTable> lootRegistry = new MappedRegistry<>(Registries.LOOT_TABLE, Lifecycle.stable());
        final ResourceKey<LootTable> chestKey = ResourceKey.create(
            Registries.LOOT_TABLE,
            Identifier.withDefaultNamespace("chests/shipwreck_map")
        );
        lootRegistry.createRegistrationLookup().getOrThrow(chestKey);
        lootRegistry.bindAllTagsToEmpty();
        ApisHooks.bindPrunedFallbackHolders(lootRegistry);
        lootRegistry.freeze();
        assertEquals(LootTable.EMPTY, lootRegistry.getValue(chestKey));

        final MappedRegistry<LootItemCondition> predRegistry = new MappedRegistry<>(
            Registries.PREDICATE,
            Lifecycle.stable()
        );
        final ResourceKey<LootItemCondition> predKey = ResourceKey.create(
            Registries.PREDICATE,
            Identifier.withDefaultNamespace("block/fast_cooking")
        );
        predRegistry.createRegistrationLookup().getOrThrow(predKey);
        predRegistry.bindAllTagsToEmpty();
        ApisHooks.bindPrunedFallbackHolders(predRegistry);
        predRegistry.freeze();
        assertNotNull(predRegistry.getValue(predKey));

        final MappedRegistry<LootItemFunction> modRegistry = new MappedRegistry<>(
            Registries.ITEM_MODIFIER,
            Lifecycle.stable()
        );
        final ResourceKey<LootItemFunction> modKey = ResourceKey.create(
            Registries.ITEM_MODIFIER,
            Identifier.withDefaultNamespace("test_modifier")
        );
        modRegistry.createRegistrationLookup().getOrThrow(modKey);
        modRegistry.bindAllTagsToEmpty();
        ApisHooks.bindPrunedFallbackHolders(modRegistry);
        modRegistry.freeze();
        assertNotNull(modRegistry.getValue(modKey));

        final MappedRegistry<SlotSource> slotRegistry = new MappedRegistry<>(
            Registries.SLOT_SOURCE,
            Lifecycle.stable()
        );
        final ResourceKey<SlotSource> slotKey = ResourceKey.create(
            Registries.SLOT_SOURCE,
            Identifier.withDefaultNamespace("test_slot")
        );
        slotRegistry.createRegistrationLookup().getOrThrow(slotKey);
        slotRegistry.bindAllTagsToEmpty();
        ApisHooks.bindPrunedFallbackHolders(slotRegistry);
        slotRegistry.freeze();
        assertNotNull(slotRegistry.getValue(slotKey));
    }

    @Test
    void prunesLootSubRegistriesAndGeneratorPresetsBasedOnConfig() {
        final ApisConfig vanillaNoLoot = new ApisConfig();
        vanillaNoLoot.dimensions.mode = ApisConfig.MODE_VANILLA;
        vanillaNoLoot.gameplay.lootTables = false;
        FeatureRegistry.applyEarly(vanillaNoLoot);

        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.LOOT_TABLE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.PREDICATE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.ITEM_MODIFIER));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.SLOT_SOURCE));
        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.WORLD_PRESET));
        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.FLAT_LEVEL_GENERATOR_PRESET));

        final ApisConfig standaloneWithLoot = new ApisConfig();
        standaloneWithLoot.dimensions.mode = ApisConfig.MODE_STANDALONE;
        standaloneWithLoot.gameplay.lootTables = true;
        FeatureRegistry.applyEarly(standaloneWithLoot);

        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.LOOT_TABLE));
        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.PREDICATE));
        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.ITEM_MODIFIER));
        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.SLOT_SOURCE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.WORLD_PRESET));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.FLAT_LEVEL_GENERATOR_PRESET));
    }

    @Test
    void numaGuardRespectsHostOperatingSystem() {
        assertTrue(ApisHooks.resolveNumaTotalCores() >= 1);
        final String osName = System.getProperty("os.name", "");
        final boolean expectedLinux = osName.regionMatches(true, 0, "Linux", 0, 5);
        assertEquals(expectedLinux, ApisHooks.isLinuxOs());
        if (!expectedLinux) {
            assertEquals(
                "NoOp",
                OSNuma.getNativeInstance().getClass().getSimpleName()
            );
        }
    }
}
