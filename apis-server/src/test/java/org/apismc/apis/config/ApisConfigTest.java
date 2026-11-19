package org.apismc.apis.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
        assertTrue(initial.caches.eagerBlockstateCache);
        assertTrue(initial.caches.eagerCraftblockdataCache);
        assertTrue(initial.caches.eagerMapColorCache);
        assertTrue(initial.gameplay.lootTables);
        assertTrue(initial.compatibility.legacy.pluginBytecodeRerouting);
        assertTrue(initial.compatibility.legacy.bootstrapDataTables);
        assertTrue(initial.compatibility.legacy.validateEntityAttributes);
        assertTrue(initial.storage.worldSaving);
        assertTrue(initial.system.lifecycle.gracefulShutdown);
        assertTrue(initial.blockEntities.ticking.enabled);
        assertEquals(ApisConfig.MODE_STANDALONE, initial.dimensions.mode);
        assertEquals(ApisConfig.DEFAULT_FALLBACK_WORLD, initial.dimensions.fallbackWorld);
        assertTrue(Files.exists(cfgFile));

        final String yamlText = Files.readString(cfgFile);
        assertTrue(
            yamlText.contains("# Bootstrap, discovery & validation") && yamlText.contains(" | "),
            "Generated config must contain ENG | UA bilingual comments"
        );
        assertTrue(
            yamlText.contains("[MASTER SWITCH]") && yamlText.contains("[ГОЛОВНИЙ РУБИЛЬНИК]"),
            "Master switches must carry explicit ENG | UA master-switch warnings"
        );
        assertTrue(
            yamlText.contains("true = standard Vanilla/Paper behavior enabled"),
            "Generated header must explain positive true/false semantics"
        );
        assertTrue(yamlText.contains("datafixers: true"));
        assertTrue(yamlText.contains("eager-blockstate-cache: true"));
        assertTrue(yamlText.contains("eager-craftblockdata-cache: true"));
        assertTrue(yamlText.contains("eager-map-color-cache: true"));
        assertTrue(yamlText.contains("type-filter: []"));
        assertTrue(yamlText.contains("sub-features:"));
        assertTrue(yamlText.contains("loot-tables: true"));
        assertTrue(yamlText.contains("plugin-bytecode-rerouting: true"));
        assertTrue(yamlText.contains("bootstrap-data-tables: true"));
        assertTrue(yamlText.contains("validate-entity-attributes: true"));
        assertTrue(yamlText.contains("world-saving: true"));
        assertTrue(yamlText.contains("warden-tracker: true"));
        assertTrue(yamlText.contains("sleep-cycle: true"));
        assertTrue(yamlText.contains("end-dragon-fight: true"));
        assertTrue(yamlText.contains("bstats: true"));
        assertTrue(yamlText.contains("spark: true"));
        assertTrue(yamlText.contains("tick-monitor: true"));
        assertTrue(yamlText.contains("mode: standalone"));
        assertTrue(yamlText.contains("fallback-world: hub"));
        assertTrue(yamlText.contains("messages:"));
        assertTrue(yamlText.contains("setworldspawn-success:"));
        assertTrue(yamlText.contains("invalid-syntax-world:"));
        assertTrue(yamlText.contains("world-load-success:"));
        assertTrue(yamlText.contains("world-unload-fallback-forbidden:"));
        assertTrue(yamlText.contains("world-fallback-not-configured:"));
        assertTrue(yamlText.contains("world-reload-fallback-notice:"));
        assertTrue(yamlText.contains("world-reload-all-success:"));
        assertFalse(yamlText.contains("disable-portals"), "Legacy disable-* keys must not exist");
        assertFalse(yamlText.contains("disabled-dimensions"), "Legacy disabled-dimensions key must not exist");
        assertFalse(yamlText.contains("disabled-types"), "Legacy disabled-types key must not exist");
        assertFalse(yamlText.contains("warden-and-sleep"), "Legacy combo key warden-and-sleep must not exist");
        assertFalse(yamlText.contains("metrics-and-profiler"), "Legacy combo key metrics-and-profiler must not exist");

        final String updated = yamlText
            .replace("datafixers: true", "datafixers: false")
            .replace("eager-blockstate-cache: true", "eager-blockstate-cache: false")
            .replace("plugin-bytecode-rerouting: true", "plugin-bytecode-rerouting: false")
            .replace("world-saving: true", "world-saving: false")
            .replace("graceful-shutdown: true", "graceful-shutdown: false")
            .replace("players: true", "players: false")
            .replace("projectile-tracking-range: 32", "projectile-tracking-range: 9999");
        Files.writeString(cfgFile, updated);

        final ApisConfig reloaded = ApisConfig.loadEarly(cfgFile);
        assertFalse(reloaded.startup.datafixers);
        assertFalse(reloaded.caches.eagerBlockstateCache);
        assertFalse(reloaded.compatibility.legacy.pluginBytecodeRerouting);
        assertFalse(reloaded.storage.worldSaving);
        assertFalse(reloaded.system.lifecycle.gracefulShutdown);
        assertFalse(reloaded.entities.collisions.players);
        assertEquals(
            256,
            reloaded.network.packetRangeClamping.projectileTrackingRange,
            "Out-of-bounds tracking range must be clamped"
        );
        assertTrue(FeatureRegistry.isDisabled(Feature.STARTUP_DATAFIXERS));
        assertTrue(FeatureRegistry.isDisabled(Feature.CACHES_EAGER_BLOCKSTATE));
        assertTrue(FeatureRegistry.isDisabled(Feature.LEGACY_PLUGIN_BYTECODE_REROUTING));
        assertTrue(FeatureRegistry.isDisabled(Feature.STORAGE_WORLD_SAVING));
        assertTrue(FeatureRegistry.isDisabled(Feature.SYSTEM_GRACEFUL_SHUTDOWN));
        assertTrue(FeatureRegistry.isDisabled(Feature.ENTITIES_PLAYER_COLLISIONS));
        assertEquals(6, FeatureRegistry.activeKillSwitches().size());
    }

    @Test
    void malformedYamlBacksUpFileAndFallsBackToDefaults(final @TempDir Path tempDir) throws IOException {
        final Path cfgFile = tempDir.resolve("apis.yml");
        Files.writeString(cfgFile, "dimensions:\n  portals: [unclosed\n");

        final ApisConfig fallback = ApisConfig.loadOrCreate(cfgFile, LoggerFactory.getLogger("ApisTest"));
        assertTrue(fallback.dimensions.portals);
        assertTrue(Files.exists(tempDir.resolve("apis.yml.broken")));
        assertTrue(Files.exists(cfgFile));
    }

    @Test
    void sanitizesInvalidModeAndFallbackWorldWhilePreservingEmptyMuteStrings(final @TempDir Path tempDir) throws IOException {
        final Path cfgFile = tempDir.resolve("apis.yml");
        Files.writeString(
            cfgFile,
            """
            dimensions:
              mode: invalid_mode
              fallback-world: ../escape
            messages:
              commands:
                world-not-found: ''
            """
        );

        final ApisConfig loaded = ApisConfig.loadEarly(cfgFile);
        assertEquals(ApisConfig.MODE_STANDALONE, loaded.dimensions.mode);
        assertEquals(ApisConfig.DEFAULT_FALLBACK_WORLD, loaded.dimensions.fallbackWorld);
        assertEquals("", loaded.messages.commands.worldNotFound);
        assertEquals(
            ApisConfig.CommandMessagesSection.DEFAULT_PLAYER_NOT_FOUND,
            loaded.messages.commands.playerNotFound
        );
    }

    @Test
    void doesNotRewriteCompleteExistingConfigAndRewritesOnlyWhenKeysAreMissing(
        final @TempDir Path tempDir
    ) throws IOException {
        final Path cfgFile = tempDir.resolve("apis.yml");
        final var logger = LoggerFactory.getLogger("ApisTest");
        ApisConfig.loadOrCreate(cfgFile, logger);

        final FileTime sentinelTime = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(cfgFile, sentinelTime);

        ApisConfig.loadOrCreate(cfgFile, logger);
        assertEquals(
            sentinelTime.toMillis(),
            Files.getLastModifiedTime(cfgFile).toMillis(),
            "Unchanged config with all keys present must not be rewritten"
        );

        final String stripped = Files.readString(cfgFile).replace("  spark: true\n", "");
        Files.writeString(cfgFile, stripped);
        Files.setLastModifiedTime(cfgFile, sentinelTime);

        final ApisConfig repaired = ApisConfig.loadOrCreate(cfgFile, logger);
        assertTrue(repaired.network.analytics.spark);
        assertTrue(
            Files.readString(cfgFile).contains("spark: true"),
            "Config missing a default key must be rewritten with the restored default"
        );
    }
}
