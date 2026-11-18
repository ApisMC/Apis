package org.apismc.apis.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.apismc.apis.config.ApisConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftWorld;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates standalone world discovery under {@code worlds/}, {@code world.yml} spawn metadata,
 * initial void bedrock placement, and spawn resolution.
 */
public final class StandaloneWorldManager {

    public static final String WORLDS_CONTAINER = "worlds";
    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft");
    private static final Map<ResourceKey<Level>, String> DIMENSION_FOLDERS = new ConcurrentHashMap<>();
    private static final Map<String, WorldEntry> WORLD_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> INTERNAL_DISCOVERY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static volatile Path containerRoot = Path.of(WORLDS_CONTAINER);
    private static volatile boolean fallbackWorldNewOnBoot;

    private StandaloneWorldManager() {
    }

    /**
     * Returns whether standalone world architecture is enabled in {@code config/apis.yml}.
     *
     * @return {@code true} if {@code dimensions.mode == standalone}
     */
    public static boolean isStandaloneMode() {
        return ApisConfig.MODE_STANDALONE.equals(ApisConfig.get().dimensions.mode);
    }

    /**
     * Checks whether the current call stack is performing startup auto-discovery under {@code worlds/}.
     *
     * @return {@code true} during boot auto-discovery
     */
    public static boolean isInternalDiscovery() {
        return Boolean.TRUE.equals(INTERNAL_DISCOVERY.get());
    }

    /**
     * Returns the configured {@code dimensions.fallback-world} name.
     *
     * @return fallback world folder name
     */
    public static String fallbackWorldName() {
        return ApisConfig.get().dimensions.fallbackWorld;
    }

    /**
     * Returns {@code worlds/} container directory when standalone mode is active and ensures it exists.
     *
     * @param vanillaUniverse standard server universe path
     * @return resolved universe directory
     */
    public static File resolveUniversePath(final File vanillaUniverse) {
        if (!isStandaloneMode()) {
            return vanillaUniverse;
        }
        final Path path = Path.of(WORLDS_CONTAINER);
        try {
            Files.createDirectories(path);
        } catch (final IOException ex) {
            LOGGER.error("Failed to create standalone worlds directory '{}': {}", path, ex.getMessage());
        }
        containerRoot = path;
        return path.toFile();
    }

    /**
     * Records whether the fallback world directory is fresh/uninitialized before {@code LevelStorageAccess}
     * touches disk.
     *
     * @param universeDir server universe directory
     * @param levelId     primary world folder name
     */
    public static void markFallbackWorldNewBeforeAccess(final Path universeDir, final String levelId) {
        if (!isStandaloneMode()) {
            fallbackWorldNewOnBoot = false;
            return;
        }
        containerRoot = universeDir;
        DIMENSION_FOLDERS.put(Level.OVERWORLD, levelId);
        final Path worldDir = universeDir.resolve(levelId);
        final boolean hasData = Files.isDirectory(worldDir)
            && (Files.isRegularFile(worldDir.resolve("level.dat"))
            || Files.isRegularFile(worldDir.resolve("level.dat_old"))
            || Files.isDirectory(worldDir.resolve("region"))
            || Files.isDirectory(worldDir.resolve("dimensions")));
        fallbackWorldNewOnBoot = !hasData;
    }

    /**
     * Resolves the storage root of a dimension inside {@code worlds/<world_name>/}.
     *
     * @param dimensionKey dimension resource key
     * @param overworldDir directory of the primary {@code Level.OVERWORLD}
     * @param vanillaPath  default Mojang 26.3 storage path
     * @return resolved world directory
     */
    public static Path resolveDimensionPath(
        final ResourceKey<Level> dimensionKey,
        final Path overworldDir,
        final Path vanillaPath
    ) {
        if (!isStandaloneMode()) {
            return vanillaPath;
        }
        final Path worldsRoot = overworldDir.getParent() != null ? overworldDir.getParent() : containerRoot;
        if (Level.OVERWORLD.equals(dimensionKey)) {
            if (Files.isDirectory(overworldDir.resolve("region"))
                && !Files.isDirectory(overworldDir.resolve("dimensions/minecraft/overworld/region"))) {
                return overworldDir;
            }
            return vanillaPath;
        }
        final String folderName = DIMENSION_FOLDERS.computeIfAbsent(
            dimensionKey,
            key -> deriveFolderName(key.identifier())
        );
        final Path worldDir = worldsRoot.resolve(folderName);
        if (Files.isDirectory(worldDir.resolve("dimensions/minecraft/overworld/region"))
            && !Files.isDirectory(worldDir.resolve("region"))) {
            return worldDir.resolve("dimensions/minecraft/overworld");
        }
        return worldDir;
    }

    /**
     * Registers the on-disk folder name for a loaded or about-to-load dimension key.
     *
     * @param dimensionKey dimension resource key
     * @param folderName   directory name inside {@code worlds/}
     */
    public static void registerDimensionFolder(final ResourceKey<Level> dimensionKey, final String folderName) {
        DIMENSION_FOLDERS.put(dimensionKey, folderName);
    }

    /**
     * Replaces world dimensions with lightweight {@code minecraft:the_void} {@link FlatLevelSource} presets
     * when initializing a new standalone server world so 3D noise generators are never allocated.
     *
     * @param registries registry provider
     * @param original   default generated dimensions
     * @return void-backed dimensions in standalone mode, or {@code original} in vanilla mode
     */
    public static WorldDimensions resolveInitialDimensions(
        final HolderLookup.Provider registries,
        final WorldDimensions original
    ) {
        if (!isStandaloneMode()) {
            return original;
        }
        return original.replaceOverworldGenerator(registries, createVoidFlatLevelSource(registries));
    }

    /**
     * Creates a zero-structure, zero-layer {@link FlatLevelSource} bound to {@link Biomes#THE_VOID}.
     *
     * @param registries server registry lookup provider
     * @return void flat level source
     */
    public static FlatLevelSource createVoidFlatLevelSource(final HolderLookup.Provider registries) {
        final FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(
            Optional.of(HolderSet.direct()),
            registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID),
            List.of()
        );
        return new FlatLevelSource(settings);
    }

    /**
     * Initializes bedrock spawn platform (when new) and loads/creates {@code world.yml} for the fallback world.
     *
     * @param overworld primary {@link ServerLevel}
     */
    public static void initializePrimaryFallbackWorld(final ServerLevel overworld) {
        if (!isStandaloneMode()) {
            return;
        }
        final Path worldDir = resolveWorldConfigDir(overworld);
        final String name = overworld.getWorld().getName();
        DIMENSION_FOLDERS.put(overworld.dimension(), name);

        final StandaloneWorldConfig config;
        if (fallbackWorldNewOnBoot) {
            overworld.setBlock(new BlockPos(0, 60, 0), Blocks.BEDROCK.defaultBlockState(), 3);
            config = StandaloneWorldConfig.loadOrCreate(worldDir, 0.5d, 61.0d, 0.5d, 0.0f, 0.0f, true);
            LOGGER.info(
                "Initialized standalone fallback world '{}' at {} with bedrock platform at (0, 60, 0).",
                name,
                worldDir
            );
        } else {
            config = StandaloneWorldConfig.loadOrCreate(worldDir);
        }
        fallbackWorldNewOnBoot = false;
        cacheConfig(name, worldDir, config);
        applySpawnToLevel(overworld, config);
    }

    /**
     * Scans {@code worlds/} and automatically registers all discovered world folders as loaded {@link ServerLevel}s.
     *
     * @param server active {@link MinecraftServer}
     */
    public static void discoverAndLoadExtraWorlds(final MinecraftServer server) {
        if (!isStandaloneMode()) {
            return;
        }
        final Path root = containerRoot(server);
        if (!Files.isDirectory(root)) {
            return;
        }
        final List<String> discoveredFolders = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.startsWith("."))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(discoveredFolders::add);
        } catch (final IOException ex) {
            LOGGER.error("Failed to scan standalone worlds directory '{}': {}", root, ex.getMessage());
            return;
        }

        final String fallbackName = fallbackWorldName();
        for (final String folder : discoveredFolders) {
            if (folder.equalsIgnoreCase(fallbackName) || server.server.getWorld(folder) != null) {
                continue;
            }
            final String normalizedKey = folder.toLowerCase(Locale.ROOT).replace(' ', '_');
            if (Identifier.tryParse("minecraft:" + normalizedKey) == null) {
                LOGGER.warn("Skipping invalid standalone world directory name '{}'.", folder);
                continue;
            }
            final Path worldDir = root.resolve(folder);
            final StandaloneWorldConfig worldCfg = StandaloneWorldConfig.loadOrCreate(worldDir);
            cacheConfig(folder, worldDir, worldCfg);

            final WorldCreator creator = new WorldCreator(folder)
                .generator(ApisVoidChunkGenerator.INSTANCE)
                .biomeProvider(ApisVoidChunkGenerator.VOID_BIOME_PROVIDER)
                .generateStructures(false);
            final ResourceKey<Level> dimensionKey = ResourceKey.create(
                Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("minecraft", normalizedKey)
            );
            DIMENSION_FOLDERS.put(dimensionKey, folder);

            INTERNAL_DISCOVERY.set(Boolean.TRUE);
            final @Nullable World loaded;
            try {
                loaded = server.server.createWorld(creator);
            } finally {
                INTERNAL_DISCOVERY.set(Boolean.FALSE);
            }
            if (loaded instanceof CraftWorld craftWorld) {
                applySpawnToLevel(craftWorld.getHandle(), worldCfg);
                LOGGER.info("Auto-loaded standalone world '{}' from {}.", folder, worldDir);
            }
        }
    }

    /**
     * Ensures {@code world.yml} is loaded/cached when a standalone world is loaded via {@link WorldCreator}.
     *
     * @param level newly created {@link ServerLevel}
     */
    public static void ensureWorldConfigLoaded(final ServerLevel level) {
        if (!isStandaloneMode()) {
            return;
        }
        final String name = level.getWorld().getName();
        final Path worldDir = resolveWorldConfigDir(level);
        final StandaloneWorldConfig cfg = StandaloneWorldConfig.loadOrCreate(worldDir);
        cacheConfig(name, worldDir, cfg);
        applySpawnToLevel(level, cfg);
    }

    /**
     * Resolves join spawn position in {@code standalone} mode before plugin spawn events run.
     *
     * @param server      active server
     * @param newPlayer   whether player has no existing {@code playerdata}
     * @param savedLevel  saved logout level (nullable)
     * @param hasSavedPos whether saved position exists in {@code playerdata}
     * @return override spawn or {@code null} to use vanilla saved position
     */
    public static @Nullable ResolvedSpawn resolveJoinSpawn(
        final MinecraftServer server,
        final boolean newPlayer,
        final @Nullable ServerLevel savedLevel,
        final boolean hasSavedPos
    ) {
        if (!isStandaloneMode()) {
            return null;
        }
        final ServerLevel fallbackLevel = server.overworld();
        final StandaloneWorldConfig fallbackCfg = getOrLoadConfig(fallbackLevel);
        if (fallbackCfg.forceSpawn || newPlayer || savedLevel == null || !hasSavedPos) {
            return new ResolvedSpawn(
                fallbackLevel,
                new Vec3(fallbackCfg.spawn.x, fallbackCfg.spawn.y, fallbackCfg.spawn.z),
                new Vec2(fallbackCfg.spawn.yaw, fallbackCfg.spawn.pitch)
            );
        }
        final StandaloneWorldConfig savedCfg = getOrLoadConfig(savedLevel);
        if (savedCfg.forceSpawn) {
            return new ResolvedSpawn(
                savedLevel,
                new Vec3(savedCfg.spawn.x, savedCfg.spawn.y, savedCfg.spawn.z),
                new Vec2(savedCfg.spawn.yaw, savedCfg.spawn.pitch)
            );
        }
        return null;
    }

    /**
     * Resolves default respawn transition in {@code standalone} mode when player has no valid bed/anchor.
     *
     * @param server active server
     * @param post   post-teleport transition
     * @return standalone fallback spawn transition
     */
    public static TeleportTransition resolveRespawnDefault(
        final MinecraftServer server,
        final TeleportTransition.PostTeleportTransition post
    ) {
        final ServerLevel fallbackLevel = server.overworld();
        final StandaloneWorldConfig cfg = getOrLoadConfig(fallbackLevel);
        return new TeleportTransition(
            fallbackLevel,
            new Vec3(cfg.spawn.x, cfg.spawn.y, cfg.spawn.z),
            Vec3.ZERO,
            cfg.spawn.yaw,
            cfg.spawn.pitch,
            post
        );
    }

    /**
     * Updates cached spawn coordinates and atomically writes {@code worlds/<world>/world.yml}.
     *
     * @param world target Bukkit world
     * @param x     spawn X
     * @param y     spawn Y
     * @param z     spawn Z
     * @param yaw   spawn yaw
     * @param pitch spawn pitch
     */
    public static void updateWorldSpawn(
        final World world,
        final double x,
        final double y,
        final double z,
        final float yaw,
        final float pitch
    ) {
        final CraftWorld craftWorld = (CraftWorld) world;
        final ServerLevel level = craftWorld.getHandle();
        final Path worldDir = resolveWorldConfigDir(level);
        final StandaloneWorldConfig current = getOrLoadConfig(level).copy();
        current.spawn.x = x;
        current.spawn.y = y;
        current.spawn.z = z;
        current.spawn.yaw = yaw;
        current.spawn.pitch = pitch;
        StandaloneWorldConfig.saveAtomic(worldDir, current);
        cacheConfig(world.getName(), worldDir, current);
        applySpawnToLevel(level, current);
    }

    /**
     * Returns configured spawn location from {@code world.yml} for a loaded world.
     *
     * @param world target world
     * @return spawn {@link Location}
     */
    public static Location getSpawnLocation(final World world) {
        if (!isStandaloneMode()) {
            return world.getSpawnLocation();
        }
        final CraftWorld craftWorld = (CraftWorld) world;
        final StandaloneWorldConfig cfg = getOrLoadConfig(craftWorld.getHandle());
        return new Location(world, cfg.spawn.x, cfg.spawn.y, cfg.spawn.z, cfg.spawn.yaw, cfg.spawn.pitch);
    }

    /**
     * Lists loaded standalone world names backed by existing directories in {@code worlds/}.
     *
     * @param server active server
     * @return sorted list of loaded world names
     */
    public static List<String> getLoadedStandaloneWorldNames(final MinecraftServer server) {
        final Set<String> onDisk = listDiskWorldFolders(containerRoot(server));
        final List<String> names = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            final String name = level.getWorld().getName();
            if (!isStandaloneMode() || onDisk.contains(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    /**
     * Resolves a loaded standalone world by exact or case-insensitive name, verifying its directory exists in
     * {@code worlds/} when standalone mode is active.
     *
     * @param server    active server
     * @param worldName requested world name
     * @return loaded {@link World}, or {@code null} if missing/unloaded
     */
    public static @Nullable World resolveLoadedStandaloneWorld(
        final MinecraftServer server,
        final String worldName
    ) {
        final @Nullable World exact = server.server.getWorld(worldName);
        if (exact != null) {
            return isAllowedTargetWorld(server, exact) ? exact : null;
        }
        for (final World world : server.server.getWorlds()) {
            if (world.getName().equalsIgnoreCase(worldName)) {
                return isAllowedTargetWorld(server, world) ? world : null;
            }
        }
        return null;
    }

    /**
     * Returns cached configuration for a world name without doing disk I/O.
     *
     * @param worldName world name
     * @return cached config or {@code null}
     */
    public static @Nullable StandaloneWorldConfig getConfigIfLoaded(final String worldName) {
        final @Nullable WorldEntry entry = WORLD_CACHE.get(worldName.toLowerCase(Locale.ROOT));
        return entry != null ? entry.config() : null;
    }

    /**
     * Clears in-memory state between unit tests.
     */
    public static void resetForTesting() {
        DIMENSION_FOLDERS.clear();
        WORLD_CACHE.clear();
        fallbackWorldNewOnBoot = false;
        containerRoot = Path.of(WORLDS_CONTAINER);
    }

    private static boolean isAllowedTargetWorld(final MinecraftServer server, final World world) {
        if (!isStandaloneMode()) {
            return true;
        }
        final Path dir = resolveWorldConfigDir(((CraftWorld) world).getHandle());
        return Files.isDirectory(dir);
    }

    private static StandaloneWorldConfig getOrLoadConfig(final ServerLevel level) {
        final String key = level.getWorld().getName().toLowerCase(Locale.ROOT);
        final @Nullable WorldEntry cached = WORLD_CACHE.get(key);
        if (cached != null) {
            return cached.config();
        }
        final Path worldDir = resolveWorldConfigDir(level);
        final StandaloneWorldConfig loaded = StandaloneWorldConfig.loadOrCreate(worldDir);
        cacheConfig(level.getWorld().getName(), worldDir, loaded);
        return loaded;
    }

    private static void cacheConfig(final String worldName, final Path worldDir, final StandaloneWorldConfig config) {
        WORLD_CACHE.put(worldName.toLowerCase(Locale.ROOT), new WorldEntry(worldDir, config));
    }

    private static Path resolveWorldConfigDir(final ServerLevel level) {
        final String name = level.getWorld().getName();
        final @Nullable WorldEntry cached = WORLD_CACHE.get(name.toLowerCase(Locale.ROOT));
        if (cached != null) {
            return cached.worldDir();
        }
        if (Level.OVERWORLD.equals(level.dimension())) {
            return level.getServer().storageSource.getLevelDirectory().path();
        }
        return containerRoot(level.getServer()).resolve(name);
    }

    private static Path containerRoot(final MinecraftServer server) {
        final @Nullable Path parent = server.storageSource.getLevelDirectory().path().getParent();
        return parent != null ? parent : containerRoot;
    }

    private static Set<String> listDiskWorldFolders(final Path root) {
        if (!Files.isDirectory(root)) {
            return Set.of();
        }
        final Set<String> folders = ConcurrentHashMap.newKeySet();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                .filter(name -> !name.startsWith("."))
                .forEach(folders::add);
        } catch (IOException _) {
        }
        return folders;
    }

    private static void applySpawnToLevel(final ServerLevel level, final StandaloneWorldConfig cfg) {
        final BlockPos pos = BlockPos.containing(cfg.spawn.x, cfg.spawn.y, cfg.spawn.z);
        level.setRespawnData(LevelData.RespawnData.of(level.dimension(), pos, cfg.spawn.yaw, cfg.spawn.pitch));
    }

    private static String deriveFolderName(final Identifier id) {
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.getNamespace() + "_" + id.getPath();
    }

    private record WorldEntry(Path worldDir, StandaloneWorldConfig config) {
    }

    /**
     * Resolved join target level, coordinates, and camera angles.
     *
     * @param level    target {@link ServerLevel}
     * @param position target position
     * @param angle    yaw/pitch vector
     */
    public record ResolvedSpawn(ServerLevel level, Vec3 position, Vec2 angle) {
    }
}
