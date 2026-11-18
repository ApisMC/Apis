package org.apismc.apis.world;

import java.util.List;
import java.util.Random;
import org.apismc.apis.config.ApisConfig;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jspecify.annotations.Nullable;

/**
 * Zero-overhead pure void {@link ChunkGenerator} used in Apis {@code standalone} mode.
 * Existing region chunks load untouched from disk while ungenerated chunks return empty air immediately.
 */
public final class ApisVoidChunkGenerator extends ChunkGenerator {

    public static final ApisVoidChunkGenerator INSTANCE = new ApisVoidChunkGenerator();
    public static final BiomeProvider VOID_BIOME_PROVIDER = new VoidBiomeProvider();

    private ApisVoidChunkGenerator() {
    }

    @Override
    public void generateNoise(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ,
        final ChunkData chunkData
    ) {
        // Pure void: never allocate or write blocks.
    }

    @Override
    public void generateSurface(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ,
        final ChunkData chunkData
    ) {
    }

    @Override
    public void generateBedrock(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ,
        final ChunkData chunkData
    ) {
    }

    @Override
    public void generateCaves(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ,
        final ChunkData chunkData
    ) {
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(final WorldInfo worldInfo) {
        return VOID_BIOME_PROVIDER;
    }

    @Override
    public int getBaseHeight(
        final WorldInfo worldInfo,
        final Random random,
        final int x,
        final int z,
        final HeightMap heightMap
    ) {
        return worldInfo.getMinHeight();
    }

    @Override
    public @Nullable Location getFixedSpawnLocation(final World world, final Random random) {
        final StandaloneWorldConfig cfg = StandaloneWorldManager.getConfigIfLoaded(world.getName());
        if (cfg != null) {
            return new Location(world, cfg.spawn.x, cfg.spawn.y, cfg.spawn.z, cfg.spawn.yaw, cfg.spawn.pitch);
        }
        return new Location(world, 0.5d, 60.0d, 0.5d, 0.0f, 0.0f);
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateNoise(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ
    ) {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ
    ) {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ
    ) {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ
    ) {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ
    ) {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures(
        final WorldInfo worldInfo,
        final Random random,
        final int chunkX,
        final int chunkZ
    ) {
        return false;
    }

    /**
     * Checks whether the current config uses {@code dimensions.mode: standalone}.
     *
     * @return {@code true} if standalone mode is active
     */
    public static boolean isStandaloneActive() {
        return ApisConfig.MODE_STANDALONE.equals(ApisConfig.get().dimensions.mode);
    }

    private static final class VoidBiomeProvider extends BiomeProvider {

        private static final List<Biome> SINGLE_VOID = List.of(Biome.THE_VOID);

        @Override
        public Biome getBiome(final WorldInfo worldInfo, final int x, final int y, final int z) {
            return Biome.THE_VOID;
        }

        @Override
        public List<Biome> getBiomes(final WorldInfo worldInfo) {
            return SINGLE_VOID;
        }
    }
}
