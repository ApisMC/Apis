package org.apismc.apis.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.apismc.apis.command.ApisMessageRenderer;
import org.apismc.apis.config.ApisConfig;
import org.bukkit.block.Biome;
import org.bukkit.support.environment.AllFeatures;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
class StandaloneWorldTest {

    @AfterEach
    void tearDown() {
        StandaloneWorldManager.resetForTesting();
    }

    @Test
    void loadsCreatesAndAtomicallySavesWorldYml(final @TempDir Path tempDir) throws IOException {
        final Path worldDir = tempDir.resolve("worlds").resolve("hub");
        final StandaloneWorldConfig created = StandaloneWorldConfig.loadOrCreate(worldDir);
        assertTrue(Files.isRegularFile(worldDir.resolve(StandaloneWorldConfig.FILE_NAME)));
        assertEquals(0.5d, created.spawn.x);
        assertEquals(60.0d, created.spawn.y);
        assertEquals(0.5d, created.spawn.z);
        assertEquals(0.0f, created.spawn.yaw);
        assertEquals(0.0f, created.spawn.pitch);
        assertTrue(created.forceSpawn);

        created.spawn.x = 12.25d;
        created.spawn.y = 64.5d;
        created.spawn.z = -4.75d;
        created.spawn.yaw = 90.0f;
        created.spawn.pitch = 15.0f;
        created.forceSpawn = false;
        StandaloneWorldConfig.saveAtomic(worldDir, created);

        assertFalse(Files.exists(worldDir.resolve("world.yml.tmp")));
        final StandaloneWorldConfig reloaded = StandaloneWorldConfig.loadOrCreate(worldDir);
        assertEquals(12.25d, reloaded.spawn.x);
        assertEquals(64.5d, reloaded.spawn.y);
        assertEquals(-4.75d, reloaded.spawn.z);
        assertEquals(90.0f, reloaded.spawn.yaw);
        assertEquals(15.0f, reloaded.spawn.pitch);
        assertFalse(reloaded.forceSpawn);

        Files.writeString(worldDir.resolve("world.yml"), "spawn: [broken\n");
        final StandaloneWorldConfig recovered = StandaloneWorldConfig.loadOrCreate(worldDir);
        assertTrue(Files.exists(worldDir.resolve("world.yml.broken")));
        assertTrue(recovered.forceSpawn);
    }

    @Test
    void resolvesStandaloneWorldFoldersDirectlyInsideWorldsDir(final @TempDir Path tempDir) throws IOException {
        final Path cfgPath = tempDir.resolve("config").resolve("apis.yml");
        ApisConfig.loadEarly(cfgPath);

        final Path worldsRoot = tempDir.resolve("worlds");
        final Path overworldDir = worldsRoot.resolve("hub");
        final Path vanillaCustomPath = overworldDir.resolve("dimensions").resolve("minecraft").resolve("mine1");
        final Path vanillaOverworldPath = overworldDir.resolve("dimensions").resolve("minecraft").resolve("overworld");
        Files.createDirectories(vanillaOverworldPath);

        final ResourceKey<Level> mineKey = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("minecraft", "mine1")
        );
        StandaloneWorldManager.registerDimensionFolder(mineKey, "mine1");

        assertEquals(
            vanillaOverworldPath,
            StandaloneWorldManager.resolveDimensionPath(Level.OVERWORLD, overworldDir, vanillaOverworldPath)
        );
        assertEquals(
            worldsRoot.resolve("mine1"),
            StandaloneWorldManager.resolveDimensionPath(mineKey, overworldDir, vanillaCustomPath)
        );

        // Pre-built 1.21 world placed directly in worlds/hub/region resolves directly without subfolder
        Files.createDirectories(overworldDir.resolve("region"));
        assertEquals(
            overworldDir,
            StandaloneWorldManager.resolveDimensionPath(Level.OVERWORLD, overworldDir, vanillaOverworldPath)
        );

        // Migrated 26.3 copy placed in worlds/mine2/dimensions/minecraft/overworld/region is also detected cleanly
        final Path mine2Migrated = worldsRoot.resolve("mine2").resolve("dimensions").resolve("minecraft").resolve("overworld");
        Files.createDirectories(mine2Migrated.resolve("region"));
        final ResourceKey<Level> mine2Key = ResourceKey.create(
            Registries.DIMENSION,
            Identifier.fromNamespaceAndPath("minecraft", "mine2")
        );
        StandaloneWorldManager.registerDimensionFolder(mine2Key, "mine2");
        assertEquals(
            mine2Migrated,
            StandaloneWorldManager.resolveDimensionPath(mine2Key, overworldDir, vanillaCustomPath)
        );
    }

    @Test
    void voidGeneratorDisablesAllTerrainNoiseAndProvidesSingleVoidBiome() {
        final ApisVoidChunkGenerator gen = ApisVoidChunkGenerator.INSTANCE;
        assertTrue(gen.isParallelCapable());
        assertFalse(gen.shouldGenerateNoise());
        assertFalse(gen.shouldGenerateSurface());
        assertFalse(gen.shouldGenerateCaves());
        assertFalse(gen.shouldGenerateDecorations());
        assertFalse(gen.shouldGenerateMobs());
        assertFalse(gen.shouldGenerateStructures());
        assertEquals(List.of(Biome.THE_VOID), ApisVoidChunkGenerator.VOID_BIOME_PROVIDER.getBiomes(null));
    }

    @Test
    void miniMessageRendererSupportsHexGradientsDefaultsAndEmptyMuting() {
        assertNull(ApisMessageRenderer.render("", "<red>Fallback"));
        assertNull(ApisMessageRenderer.render("   ", "<red>Fallback"));

        final @Nullable Component fallback = ApisMessageRenderer.render(
            null,
            "<#20233a><gradient:#ff00aa:#00ffcc>World <world></gradient>",
            Placeholder.unparsed("world", "mine_1")
        );
        assertNotNull(fallback);
        assertEquals("World mine_1", PlainTextComponentSerializer.plainText().serialize(fallback));

        assertNull(ApisMessageRenderer.parseFiniteDouble("NaN"));
        assertNull(ApisMessageRenderer.parseFiniteDouble("Infinity"));
        assertNull(ApisMessageRenderer.parseFiniteDouble("not_a_number"));
        assertEquals(42.5d, ApisMessageRenderer.parseFiniteDouble("42.5"));
        assertEquals(-90.0f, ApisMessageRenderer.parseFiniteFloat("-90"));
    }
}
