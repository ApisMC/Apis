package dev.ultracore.config;

import dev.ultracore.feature.Feature;
import dev.ultracore.feature.FeatureRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class UltraCoreConfigTest {

    @AfterEach
    void tearDown() {
        FeatureRegistry.resetForTesting();
    }

    @Test
    void writesBilingualEngUaCommentsAndRoundTripsPositiveFlags(final @TempDir Path tempDir) throws IOException {
        final Path cfgFile = tempDir.resolve("ultracore.yml");
        final UltraCoreConfig initial = UltraCoreConfig.loadEarly(cfgFile);
        assertTrue(initial.startup.datafixers);
        assertTrue(initial.storage.ephemeral.worldSaving);
        assertTrue(initial.world.physics.blockEntityTicking);
        assertTrue(Files.exists(cfgFile));

        final String yamlText = Files.readString(cfgFile);
        assertTrue(yamlText.contains("# Bootstrap & cold-start") && yamlText.contains(" | "),
            "Generated config must contain ENG | UA bilingual comments");
        assertTrue(yamlText.contains("true = standard Vanilla/Paper behavior enabled"),
            "Generated header must explain positive true/false semantics");
        assertTrue(yamlText.contains("datafixers: true"));
        assertTrue(yamlText.contains("world-saving: true"));
        assertTrue(yamlText.contains("player-collisions: true"));
        assertTrue(yamlText.contains("block-entity-ticking: true"));
        assertFalse(yamlText.contains("disable-portals"), "Legacy disable-* keys must not exist");

        final String updated = yamlText
            .replace("datafixers: true", "datafixers: false")
            .replace("world-saving: true", "world-saving: false")
            .replace("graceful-shutdown: true", "graceful-shutdown: false")
            .replace("player-collisions: true", "player-collisions: false")
            .replace("projectile-tracking-range: 32", "projectile-tracking-range: 9999");
        Files.writeString(cfgFile, updated);

        final UltraCoreConfig reloaded = UltraCoreConfig.loadEarly(cfgFile);
        assertFalse(reloaded.startup.datafixers);
        assertFalse(reloaded.storage.ephemeral.worldSaving);
        assertFalse(reloaded.storage.ephemeral.gracefulShutdown);
        assertFalse(reloaded.network.tracking.playerCollisions);
        assertEquals(256, reloaded.network.tracking.projectileTrackingRange, "Out-of-bounds tracking range must be clamped");
        assertTrue(FeatureRegistry.isDisabled(Feature.STARTUP_DATAFIXERS));
        assertTrue(FeatureRegistry.isDisabled(Feature.STORAGE_WORLD_SAVING));
        assertEquals(4, FeatureRegistry.activeKillSwitches().size());
    }

    @Test
    void malformedYamlBacksUpFileAndFallsBackToDefaults(final @TempDir Path tempDir) throws IOException {
        final Path cfgFile = tempDir.resolve("ultracore.yml");
        Files.writeString(cfgFile, "worlds:\n  portals: [unclosed\n");

        final UltraCoreConfig fallback = UltraCoreConfig.loadOrCreate(cfgFile, LoggerFactory.getLogger("UltraCoreTest"));
        assertTrue(fallback.worlds.portals);
        assertTrue(Files.exists(tempDir.resolve("ultracore.yml.broken")));
        assertTrue(Files.exists(cfgFile));
    }
}
