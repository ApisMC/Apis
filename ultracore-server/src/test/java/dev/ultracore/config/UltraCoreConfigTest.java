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
    void writesBilingualEngUaCommentsAndRoundTripsFlags(final @TempDir Path tempDir) throws IOException {
        final Path cfgFile = tempDir.resolve("ultracore.yml");
        final UltraCoreConfig initial = UltraCoreConfig.loadEarly(cfgFile);
        assertFalse(initial.storage.ephemeral.memoryOnlyWorld);
        assertTrue(Files.exists(cfgFile));

        final String yamlText = Files.readString(cfgFile);
        assertTrue(yamlText.contains("# Worlds & dimensions") && yamlText.contains(" | "),
            "Generated config must contain ENG | UA bilingual comments");
        assertTrue(yamlText.contains("# Disable falling sand") && yamlText.contains("Вимкнути падіння піску"),
            "Generated leaf settings must include ENG | UA comments");
        assertTrue(yamlText.contains("memory-only-world"));
        assertTrue(yamlText.contains("fast-shutdown"));
        assertTrue(yamlText.contains("disable-player-collisions"));
        assertTrue(yamlText.contains("disable-hopper-ticking"));

        final String updated = yamlText
            .replace("memory-only-world: false", "memory-only-world: true")
            .replace("fast-shutdown: false", "fast-shutdown: true")
            .replace("disable-player-collisions: false", "disable-player-collisions: true")
            .replace("projectile-tracking-range: 32", "projectile-tracking-range: 9999");
        Files.writeString(cfgFile, updated);

        final UltraCoreConfig reloaded = UltraCoreConfig.loadEarly(cfgFile);
        assertTrue(reloaded.storage.ephemeral.memoryOnlyWorld);
        assertTrue(reloaded.storage.ephemeral.fastShutdown);
        assertTrue(reloaded.network.tracking.disablePlayerCollisions);
        assertEquals(256, reloaded.network.tracking.projectileTrackingRange, "Out-of-bounds tracking range must be clamped");
        assertTrue(FeatureRegistry.isDisabled(Feature.STORAGE_EPHEMERAL_MEMORY_ONLY_WORLD));
    }

    @Test
    void malformedYamlBacksUpFileAndFallsBackToDefaults(final @TempDir Path tempDir) throws IOException {
        final Path cfgFile = tempDir.resolve("ultracore.yml");
        Files.writeString(cfgFile, "worlds:\n  disable-portals: [unclosed\n");

        final UltraCoreConfig fallback = UltraCoreConfig.loadOrCreate(cfgFile, LoggerFactory.getLogger("UltraCoreTest"));
        assertFalse(fallback.worlds.disablePortals);
        assertTrue(Files.exists(tempDir.resolve("ultracore.yml.broken")));
        assertTrue(Files.exists(cfgFile));
    }
}
