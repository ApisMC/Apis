package org.apismc.apis.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apismc.apis.feature.Feature;
import org.apismc.apis.feature.FeatureRegistry;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ApisConfigTest {

    @AfterEach
    void tearDown() {
        FeatureRegistry.resetForTesting();
    }

    @Test
    void writesBilingualEngUaCommentsAndRoundTripsPositiveFlags(final @TempDir Path tempDir) throws IOException {
        final Path cfgFile = tempDir.resolve("apis.yml");
        final ApisConfig initial = ApisConfig.loadEarly(cfgFile);
        assertTrue(initial.startup.datafixers);
        assertTrue(initial.startup.eagerBlockstateCache);
        assertTrue(initial.startup.eagerCraftblockdataCache);
        assertTrue(initial.gameplay.lootTables);
        assertTrue(initial.compatibility.legacy.pluginBytecodeRerouting);
        assertTrue(initial.compatibility.legacy.bootstrapDataTables);
        assertTrue(initial.compatibility.legacy.eagerMapColorCache);
        assertTrue(initial.compatibility.legacy.validateEntityAttributes);
        assertTrue(initial.storage.ephemeral.worldSaving);
        assertTrue(initial.world.physics.blockEntityTicking);
        assertTrue(Files.exists(cfgFile));

        final String yamlText = Files.readString(cfgFile);
        assertTrue(yamlText.contains("# Bootstrap & cold-start") && yamlText.contains(" | "),
            "Generated config must contain ENG | UA bilingual comments");
        assertTrue(yamlText.contains("true = standard Vanilla/Paper behavior enabled"),
            "Generated header must explain positive true/false semantics");
        assertTrue(yamlText.contains("datafixers: true"));
        assertTrue(yamlText.contains("eager-blockstate-cache: true"));
        assertTrue(yamlText.contains("eager-craftblockdata-cache: true"));
        assertTrue(yamlText.contains("loot-tables: true"));
        assertTrue(yamlText.contains("plugin-bytecode-rerouting: true"));
        assertTrue(yamlText.contains("bootstrap-data-tables: true"));
        assertTrue(yamlText.contains("eager-map-color-cache: true"));
        assertTrue(yamlText.contains("validate-entity-attributes: true"));
        assertTrue(yamlText.contains("world-saving: true"));
        assertTrue(yamlText.contains("player-collisions: true"));
        assertTrue(yamlText.contains("block-entity-ticking: true"));
        assertFalse(yamlText.contains("disable-portals"), "Legacy disable-* keys must not exist");

        final String updated = yamlText
            .replace("datafixers: true", "datafixers: false")
            .replace("eager-blockstate-cache: true", "eager-blockstate-cache: false")
            .replace("plugin-bytecode-rerouting: true", "plugin-bytecode-rerouting: false")
            .replace("world-saving: true", "world-saving: false")
            .replace("graceful-shutdown: true", "graceful-shutdown: false")
            .replace("player-collisions: true", "player-collisions: false")
            .replace("projectile-tracking-range: 32", "projectile-tracking-range: 9999");
        Files.writeString(cfgFile, updated);

        final ApisConfig reloaded = ApisConfig.loadEarly(cfgFile);
        assertFalse(reloaded.startup.datafixers);
        assertFalse(reloaded.startup.eagerBlockstateCache);
        assertFalse(reloaded.compatibility.legacy.pluginBytecodeRerouting);
        assertFalse(reloaded.storage.ephemeral.worldSaving);
        assertFalse(reloaded.storage.ephemeral.gracefulShutdown);
        assertFalse(reloaded.network.tracking.playerCollisions);
        assertEquals(256, reloaded.network.tracking.projectileTrackingRange, "Out-of-bounds tracking range must be clamped");
        assertTrue(FeatureRegistry.isDisabled(Feature.STARTUP_DATAFIXERS));
        assertTrue(FeatureRegistry.isDisabled(Feature.STARTUP_EAGER_BLOCKSTATE_CACHE));
        assertTrue(FeatureRegistry.isDisabled(Feature.LEGACY_PLUGIN_BYTECODE_REROUTING));
        assertTrue(FeatureRegistry.isDisabled(Feature.STORAGE_WORLD_SAVING));
        assertEquals(6, FeatureRegistry.activeKillSwitches().size());
    }

    @Test
    void malformedYamlBacksUpFileAndFallsBackToDefaults(final @TempDir Path tempDir) throws IOException {
        final Path cfgFile = tempDir.resolve("apis.yml");
        Files.writeString(cfgFile, "worlds:\n  portals: [unclosed\n");

        final ApisConfig fallback = ApisConfig.loadOrCreate(cfgFile, LoggerFactory.getLogger("ApisTest"));
        assertTrue(fallback.worlds.portals);
        assertTrue(Files.exists(tempDir.resolve("apis.yml.broken")));
        assertTrue(Files.exists(cfgFile));
    }
}
