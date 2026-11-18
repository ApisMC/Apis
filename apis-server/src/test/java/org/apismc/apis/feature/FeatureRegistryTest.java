package org.apismc.apis.feature;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.loot.LootTable;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.hooks.ApisHooks;
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
        final ApisConfig cfg = new ApisConfig();
        FeatureRegistry.applyEarly(cfg);
        assertEquals(0, FeatureRegistry.activeKillSwitches().size());
        assertTrue(ApisHooks.isDataFixersEnabled());
        assertTrue(ApisHooks.isDaylightCycleEnabled());

        cfg.startup.datafixers = false;
        cfg.startup.searchSpawnPoint = false;
        cfg.startup.initialChunkLoad = false;
        cfg.startup.validateRegistries = false;
        cfg.startup.mojangServiceDiscovery = false;
        cfg.startup.paperVersionCheck = false;
        cfg.startup.eagerBlockstateCache = false;
        cfg.startup.eagerCraftblockdataCache = false;
        cfg.worlds.customWorlds = false;
        cfg.worlds.portals = false;
        cfg.entities.ai.enabled = false;
        cfg.chunks.structures = false;
        cfg.chunks.biomeDecoration = false;
        cfg.gameplay.redstone = false;
        cfg.gameplay.monsters = false;
        cfg.gameplay.animals = false;
        cfg.gameplay.advancements = false;
        cfg.gameplay.recipes = false;
        cfg.gameplay.lootTables = false;
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
        cfg.compatibility.legacy.pluginBytecodeRerouting = false;
        cfg.compatibility.legacy.bootstrapDataTables = false;
        cfg.compatibility.legacy.eagerMapColorCache = false;
        cfg.compatibility.legacy.validateEntityAttributes = false;

        FeatureRegistry.applyEarly(cfg);

        assertFalse(ApisHooks.isDataFixersEnabled());
        assertFalse(ApisHooks.isSearchSpawnPointEnabled());
        assertFalse(ApisHooks.isInitialChunkLoadEnabled());
        assertFalse(ApisHooks.isValidateRegistriesEnabled());
        assertFalse(ApisHooks.isMojangServiceDiscoveryEnabled());
        assertFalse(ApisHooks.isPaperVersionCheckEnabled());
        assertFalse(ApisHooks.isEagerBlockStateCacheEnabled());
        assertFalse(ApisHooks.isEagerCraftBlockDataCacheEnabled());
        assertFalse(ApisHooks.isGenerateKeypairEnabled());
        assertFalse(ApisHooks.isWardenAndSleepEnabled());
        assertFalse(ApisHooks.isCommandFunctionsEnabled());
        assertFalse(ApisHooks.isTabLatencyUpdatesEnabled());
        assertFalse(ApisHooks.isHungerEnabled());
        assertFalse(ApisHooks.isStatisticsEnabled());
        assertFalse(ApisHooks.isDaylightCycleEnabled());
        assertFalse(ApisHooks.isBlockEntityTickingEnabled());
        assertFalse(ApisHooks.isItemPhysicsEnabled());
        assertFalse(ApisHooks.isPluginBytecodeReroutingEnabled());
        assertFalse(ApisHooks.isBootstrapDataTablesEnabled());
        assertFalse(ApisHooks.isEagerMapColorCacheEnabled());
        assertFalse(ApisHooks.isValidateEntityAttributesEnabled());

        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.RECIPE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.ADVANCEMENT));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.LOOT_TABLE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.PLACED_FEATURE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.STRUCTURE));
        assertTrue(ApisHooks.shouldPruneRegistryAtLoad(Registries.TEST_INSTANCE));
        assertFalse(ApisHooks.shouldPruneRegistryAtLoad(Registries.BIOME));
        assertEquals(Biome.NETWORK_CODEC, ApisHooks.resolveRegistryDecoder(Registries.BIOME, Biome.DIRECT_CODEC));
        assertTrue(DefaultAttributes.hasSupplier(EntityTypes.ZOMBIE));
        assertTrue(DefaultAttributes.getSupplier(EntityTypes.PLAYER) != null);

        assertTrue(ApisHooks.isMemoryOnlyWorld());
        assertTrue(ApisHooks.isFastShutdown());
        assertTrue(ApisHooks.isAdvancementsDisabled());
        assertTrue(ApisHooks.isRecipesDisabled());
        assertTrue(ApisHooks.isMobCategoryDisabled(MobCategory.MONSTER));
        assertTrue(ApisHooks.isMobCategoryDisabled(MobCategory.CREATURE));
        assertFalse(ApisHooks.isMobCategoryDisabled(MobCategory.MISC));

        assertTrue(FeatureRegistry.isEnabled(Feature.GAMEPLAY_WEATHER));
        assertFalse(FeatureRegistry.isDisabled(Feature.GAMEPLAY_WEATHER));
        assertEquals(43, FeatureRegistry.activeKillSwitches().size());
    }

    @Test
    void dimensionAndEntityFiltersWorkBeforeAndAfterRegistryResolution() {
        final ApisConfig cfg = new ApisConfig();
        cfg.worlds.disabledDimensions = List.of("minecraft:overworld", "minecraft:the_nether", "custom:arena");
        cfg.entities.ai.disabledTypes = List.of("zombie", "minecraft:villager");

        FeatureRegistry.applyEarly(cfg);

        final ResourceKey<Level> customArena = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("custom", "arena"));
        assertTrue(FeatureRegistry.isDimensionDisabled(Level.NETHER));
        assertTrue(FeatureRegistry.isDimensionDisabled(customArena));
        assertFalse(FeatureRegistry.isDimensionDisabled(Level.END));

        FeatureRegistry.resolveRegistries(null, LoggerFactory.getLogger("ApisTest"));

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

        final TagKey<Structure> treasureMaps = TagKey.create(Registries.STRUCTURE, Identifier.withDefaultNamespace("on_treasure_maps"));
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
        final ResourceKey<LootTable> chestKey = ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace("chests/shipwreck_map"));
        lootRegistry.createRegistrationLookup().getOrThrow(chestKey);
        lootRegistry.bindAllTagsToEmpty();
        ApisHooks.bindPrunedFallbackHolders(lootRegistry);
        lootRegistry.freeze();
        assertEquals(LootTable.EMPTY, lootRegistry.getValue(chestKey));
    }
}
