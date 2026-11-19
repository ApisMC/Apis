package org.apismc.apis.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import joptsimple.OptionSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
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
import org.apismc.apis.config.ApisPaths;
import org.apismc.apis.feature.Feature;
import org.apismc.apis.feature.FeatureRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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

    private static volatile Path containerRoot = ApisPaths.worldsDir();
    private static volatile boolean fallbackWorldNewOnBoot;

    private StandaloneWorldManager() {
    }

    /**
     * Returns whether standalone world architecture is enabled in {@code configs/apis.yml}.
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
     * Resolves the server world container directory using priority:
     * {@code --worlds-dir} &gt; explicit {@code -W/--world-dir/--universe} &gt; {@code bukkit.yml world-container}
     * (in vanilla mode) &gt; default {@code worlds/}, creating the target directory if missing.
     *
     * @param options             parsed CLI options
     * @param bukkitConfiguration loaded {@code bukkit.yml} (nullable)
     * @param vanillaUniverse     legacy vanilla universe candidate
     * @return resolved universe directory
     */
    public static File resolveUniversePath(
        final OptionSet options,
        final @Nullable YamlConfiguration bukkitConfiguration,
        final File vanillaUniverse
    ) {
        final Path resolved;
        if (options.has("worlds-dir")) {
            resolved = ((Path) options.valueOf("worlds-dir")).toAbsolutePath().normalize();
        } else if (options.has("universe")) {
            resolved = ((File) options.valueOf("universe")).toPath().toAbsolutePath().normalize();
        } else if (!isStandaloneMode()
            && bukkitConfiguration != null
            && bukkitConfiguration.contains("settings.world-container")) {
            final String configured = bukkitConfiguration.getString("settings.world-container", ".");
            resolved = configured == null || configured.isBlank() || ".".equals(configured.trim())
                ? ApisPaths.worldsDir()
                : Path.of(configured.trim()).toAbsolutePath().normalize();
        } else {
            resolved = ApisPaths.worldsDir();
        }
        try {
            Files.createDirectories(resolved);
        } catch (final IOException ex) {
            LOGGER.error("Failed to create worlds directory '{}': {}", resolved, ex.getMessage());
        }
        containerRoot = resolved;
        return resolved.toFile();
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
            if (folder.equalsIgnoreCase(fallbackName) || resolveLoadedStandaloneWorld(server, folder) != null) {
                continue;
            }
            if (!isValidWorldFolderName(folder)) {
                LOGGER.warn("Skipping invalid standalone world directory name '{}'.", folder);
                continue;
            }
            if (loadWorldFromDisk(server, root, folder) != null) {
                LOGGER.info("Auto-loaded standalone world '{}' from {}.", folder, root.resolve(folder));
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
     * Resolves the configured default {@link GameType} from {@code world.yml} for {@code level}.
     *
     * @param level target server level
     * @return configured {@link GameType}
     */
    public static GameType resolveWorldGameMode(final ServerLevel level) {
        return getOrLoadConfig(level).toGameType();
    }

    /**
     * Enforces {@code default-game-mode} from the player's current world {@code world.yml} in standalone mode.
     *
     * @param player            target player
     * @param sendNetworkUpdate whether to send game mode change packets ({@code false} before login packet)
     */
    public static void applyWorldGameMode(final ServerPlayer player, final boolean sendNetworkUpdate) {
        if (!isStandaloneMode()) {
            return;
        }
        final GameType targetMode = resolveWorldGameMode(player.level());
        if (!sendNetworkUpdate) {
            player.applySilentGameMode(targetMode);
            return;
        }
        if (player.gameMode.getGameModeForPlayer() != targetMode) {
            player.setGameMode(targetMode, PlayerGameModeChangeEvent.Cause.DEFAULT_GAMEMODE, null);
        }
    }

    /**
     * Checks whether {@code world} is the protected base fallback world ({@code Level.OVERWORLD}
     * or configured {@code dimensions.fallback-world}).
     *
     * @param server active server
     * @param world  candidate Bukkit world
     * @return {@code true} if base world
     */
    public static boolean isFallbackWorld(final MinecraftServer server, final World world) {
        if (world instanceof CraftWorld craftWorld && Level.OVERWORLD.equals(craftWorld.getHandle().dimension())) {
            return true;
        }
        final @Nullable String fallbackName = fallbackWorldName();
        return fallbackName != null && !fallbackName.isBlank() && world.getName().equalsIgnoreCase(fallbackName);
    }

    /**
     * Resolves the currently configured and loaded base fallback world, or {@code null} if unassigned or unloaded.
     *
     * @param server active server
     * @return loaded fallback {@link World}, or {@code null}
     */
    public static @Nullable World resolveValidFallbackWorld(final MinecraftServer server) {
        final @Nullable String fallbackName = fallbackWorldName();
        if (fallbackName == null || fallbackName.isBlank()) {
            return null;
        }
        final @Nullable World exact = server.server.getWorld(fallbackName);
        if (exact != null) {
            return exact;
        }
        for (final World world : server.server.getWorlds()) {
            if (world.getName().equalsIgnoreCase(fallbackName)) {
                return world;
            }
        }
        return null;
    }

    /**
     * Lists unloaded standalone world directory names present in {@code worlds/} that can be loaded.
     *
     * @param server active server
     * @return sorted list of unloaded world names
     */
    public static List<String> getUnloadedStandaloneWorldNames(final MinecraftServer server) {
        final Path root = containerRoot(server);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        final Set<String> loadedLower = new HashSet<>();
        for (final ServerLevel level : server.getAllLevels()) {
            loadedLower.add(level.getWorld().getName().toLowerCase(Locale.ROOT));
        }
        final @Nullable String fallbackName = fallbackWorldName();
        final List<String> unloaded = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.startsWith("."))
                .filter(StandaloneWorldManager::isValidWorldFolderName)
                .filter(name -> !loadedLower.contains(name.toLowerCase(Locale.ROOT)))
                .filter(name -> fallbackName == null || !name.equalsIgnoreCase(fallbackName))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(unloaded::add);
        } catch (IOException _) {
        }
        return List.copyOf(unloaded);
    }

    /**
     * Lists loaded standalone world names that are eligible for unloading (excluding the base fallback world).
     *
     * @param server active server
     * @return sorted list of unloadable world names
     */
    public static List<String> getUnloadableStandaloneWorldNames(final MinecraftServer server) {
        final List<String> result = new ArrayList<>();
        for (final String name : getLoadedStandaloneWorldNames(server)) {
            final @Nullable World world = resolveLoadedStandaloneWorld(server, name);
            if (world != null && !isFallbackWorld(server, world)) {
                result.add(world.getName());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Dynamically loads a standalone world folder from {@code worlds/} into memory.
     *
     * @param server    active server
     * @param worldName requested folder name
     * @return operation outcome
     */
    public static LoadResult loadStandaloneWorld(final MinecraftServer server, final String worldName) {
        if (resolveLoadedStandaloneWorld(server, worldName) != null) {
            return new LoadResult(LoadStatus.ALREADY_LOADED, worldName);
        }
        final Path root = containerRoot(server);
        final @Nullable String resolvedFolder = findDiskFolderCaseInsensitive(root, worldName);
        if (resolvedFolder == null || !isValidWorldFolderName(resolvedFolder)) {
            return new LoadResult(LoadStatus.NOT_FOUND_ON_DISK, worldName);
        }
        final @Nullable World loaded = loadWorldFromDisk(server, root, resolvedFolder);
        if (loaded == null) {
            return new LoadResult(LoadStatus.FAILED, resolvedFolder);
        }
        return new LoadResult(LoadStatus.LOADED, loaded.getName());
    }

    /**
     * Safely evacuates players to the base fallback world and unloads a standalone non-base world.
     *
     * @param server    active server
     * @param worldName target loaded world name
     * @return operation outcome
     */
    public static UnloadResult unloadStandaloneWorld(final MinecraftServer server, final String worldName) {
        final @Nullable World targetWorld = resolveLoadedWorldAnyCase(server, worldName);
        if (targetWorld == null) {
            final @Nullable String fallbackName = fallbackWorldName();
            if (fallbackName != null && fallbackName.equalsIgnoreCase(worldName)) {
                return new UnloadResult(UnloadStatus.CANNOT_UNLOAD_FALLBACK, worldName, fallbackName, 0);
            }
            return new UnloadResult(UnloadStatus.NOT_LOADED, worldName, "", 0);
        }
        if (isFallbackWorld(server, targetWorld)) {
            return new UnloadResult(UnloadStatus.CANNOT_UNLOAD_FALLBACK, targetWorld.getName(), targetWorld.getName(), 0);
        }
        final @Nullable World fallbackWorld = resolveValidFallbackWorld(server);
        if (fallbackWorld == null || targetWorld.equals(fallbackWorld)) {
            return new UnloadResult(UnloadStatus.NO_FALLBACK_WORLD, targetWorld.getName(), "", 0);
        }

        final List<UUID> evacuated = evacuatePlayers(targetWorld, fallbackWorld);
        if (!targetWorld.getPlayers().isEmpty()) {
            return new UnloadResult(UnloadStatus.FAILED, targetWorld.getName(), fallbackWorld.getName(), evacuated.size());
        }

        final boolean saveChunks = FeatureRegistry.isEnabled(Feature.STORAGE_WORLD_SAVING);
        if (!unloadWorldAndClearCache(server, targetWorld, saveChunks)) {
            return new UnloadResult(UnloadStatus.FAILED, targetWorld.getName(), fallbackWorld.getName(), evacuated.size());
        }
        return new UnloadResult(UnloadStatus.UNLOADED, targetWorld.getName(), fallbackWorld.getName(), evacuated.size());
    }

    /**
     * Reloads a single already-loaded standalone world.
     * <ul>
     *   <li>Base fallback world: reloads {@code world.yml}, spawn and player game modes only.</li>
     *   <li>Non-base world missing on disk: evacuates players and unloads it.</li>
     *   <li>Non-base world present on disk: evacuates players to fallback, unloads without saving,
     *       reloads world and {@code world.yml} from disk, and returns players to spawn.</li>
     * </ul>
     *
     * @param server    active server
     * @param worldName target loaded world name
     * @return operation outcome
     */
    public static ReloadSingleResult reloadSingleStandaloneWorld(final MinecraftServer server, final String worldName) {
        final @Nullable World loaded = resolveLoadedStandaloneWorld(server, worldName);
        if (loaded == null) {
            final @Nullable World rawLoaded = resolveLoadedWorldAnyCase(server, worldName);
            if (rawLoaded == null) {
                return new ReloadSingleResult(ReloadSingleStatus.NOT_LOADED, worldName, "", 0);
            }
            if (isFallbackWorld(server, rawLoaded)) {
                refreshFallbackWorldConfig(rawLoaded);
                return new ReloadSingleResult(
                    ReloadSingleStatus.FALLBACK_CONFIG_ONLY,
                    rawLoaded.getName(),
                    rawLoaded.getName(),
                    0
                );
            }
            // Loaded in memory, but directory removed on disk -> evacuate and unload
            return handleMissingFolderUnload(server, rawLoaded);
        }

        if (isFallbackWorld(server, loaded)) {
            refreshFallbackWorldConfig(loaded);
            return new ReloadSingleResult(ReloadSingleStatus.FALLBACK_CONFIG_ONLY, loaded.getName(), loaded.getName(), 0);
        }

        return reloadExtraWorldFromDisk(server, loaded);
    }

    /**
     * Performs a full sync across all standalone worlds:
     * unloads worlds whose directories were removed, reloads existing non-base worlds from disk,
     * loads newly discovered world folders in {@code worlds/}, and refreshes the base fallback world's config.
     *
     * @param server active server
     * @return aggregate reload summary
     */
    public static ReloadAllResult reloadAllStandaloneWorlds(final MinecraftServer server) {
        final @Nullable World fallbackWorld = resolveValidFallbackWorld(server);
        final String fallbackName = fallbackWorld != null ? fallbackWorld.getName() : String.valueOf(fallbackWorldName());

        final List<World> extraWorlds = new ArrayList<>();
        for (final World world : List.copyOf(server.server.getWorlds())) {
            if (!isFallbackWorld(server, world)) {
                extraWorlds.add(world);
            }
        }

        // Fail fast before mutating any world if players need evacuation and fallback is unconfigured
        for (final World extra : extraWorlds) {
            if (!extra.getPlayers().isEmpty() && (fallbackWorld == null || extra.equals(fallbackWorld))) {
                return new ReloadAllResult(ReloadAllStatus.NO_FALLBACK_WORLD, 0, 0, 0, fallbackName);
            }
        }

        int reloaded = 0;
        int unloaded = 0;
        int loaded = 0;

        for (final World extra : extraWorlds) {
            final ServerLevel handle = ((CraftWorld) extra).getHandle();
            final Path worldDir = resolveWorldConfigDir(handle);
            if (!Files.isDirectory(worldDir)) {
                final ReloadSingleResult missingRes = handleMissingFolderUnload(server, extra);
                if (missingRes.status() == ReloadSingleStatus.MISSING_FOLDER_UNLOADED) {
                    unloaded++;
                }
            } else {
                final ReloadSingleResult singleRes = reloadExtraWorldFromDisk(server, extra);
                if (singleRes.status() == ReloadSingleStatus.RELOADED) {
                    reloaded++;
                }
            }
        }

        final Path root = containerRoot(server);
        for (final String folder : getUnloadedStandaloneWorldNames(server)) {
            if (loadWorldFromDisk(server, root, folder) != null) {
                loaded++;
            }
        }

        if (fallbackWorld != null) {
            refreshFallbackWorldConfig(fallbackWorld);
        } else {
            refreshFallbackWorldConfig(((CraftWorld) server.overworld().getWorld()).getHandle().getWorld());
        }

        return new ReloadAllResult(ReloadAllStatus.SUCCESS, reloaded, loaded, unloaded, fallbackName);
    }

    /**
     * Clears in-memory state between unit tests.
     */
    public static void resetForTesting() {
        DIMENSION_FOLDERS.clear();
        WORLD_CACHE.clear();
        fallbackWorldNewOnBoot = false;
        containerRoot = ApisPaths.worldsDir();
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

    private static boolean isValidWorldFolderName(final String folderName) {
        final String normalizedKey = folderName.toLowerCase(Locale.ROOT).replace(' ', '_');
        return Identifier.tryParse("minecraft:" + normalizedKey) != null;
    }

    private static @Nullable String findDiskFolderCaseInsensitive(final Path root, final String requestedName) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        final Path direct = root.resolve(requestedName);
        if (Files.isDirectory(direct) && !requestedName.startsWith(".")) {
            return direct.getFileName().toString();
        }
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.startsWith("."))
                .filter(name -> name.equalsIgnoreCase(requestedName))
                .findFirst()
                .orElse(null);
        } catch (IOException _) {
            return null;
        }
    }

    private static @Nullable World resolveLoadedWorldAnyCase(final MinecraftServer server, final String worldName) {
        final @Nullable World exact = server.server.getWorld(worldName);
        if (exact != null) {
            return exact;
        }
        for (final World world : server.server.getWorlds()) {
            if (world.getName().equalsIgnoreCase(worldName)) {
                return world;
            }
        }
        return null;
    }

    private static @Nullable World loadWorldFromDisk(final MinecraftServer server, final Path root, final String folder) {
        final Path worldDir = root.resolve(folder);
        final StandaloneWorldConfig worldCfg = StandaloneWorldConfig.loadOrCreate(worldDir);
        cacheConfig(folder, worldDir, worldCfg);

        final String normalizedKey = folder.toLowerCase(Locale.ROOT).replace(' ', '_');
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
        }
        return loaded;
    }

    private static void refreshFallbackWorldConfig(final World fallbackWorld) {
        final CraftWorld craftWorld = (CraftWorld) fallbackWorld;
        final ServerLevel level = craftWorld.getHandle();
        final Path worldDir = resolveWorldConfigDir(level);
        final StandaloneWorldConfig refreshed = StandaloneWorldConfig.loadOrCreate(worldDir);
        cacheConfig(fallbackWorld.getName(), worldDir, refreshed);
        applySpawnToLevel(level, refreshed);
        for (final ServerPlayer player : List.copyOf(level.players())) {
            applyWorldGameMode(player, true);
        }
    }

    private static ReloadSingleResult handleMissingFolderUnload(final MinecraftServer server, final World world) {
        final ServerLevel handle = ((CraftWorld) world).getHandle();
        final Path worldDir = resolveWorldConfigDir(handle).toAbsolutePath().normalize();
        final Path worldsRoot = containerRoot(server).toAbsolutePath().normalize();
        final boolean existedBeforeUnload = Files.exists(worldDir);

        final List<Player> players = world.getPlayers();
        String fallbackName = "";
        if (!players.isEmpty()) {
            final @Nullable World fallbackWorld = resolveValidFallbackWorld(server);
            if (fallbackWorld == null || world.equals(fallbackWorld)) {
                return new ReloadSingleResult(ReloadSingleStatus.NO_FALLBACK_WORLD, world.getName(), "", 0);
            }
            fallbackName = fallbackWorld.getName();
            evacuatePlayers(world, fallbackWorld);
            if (!world.getPlayers().isEmpty()) {
                return new ReloadSingleResult(ReloadSingleStatus.FAILED, world.getName(), fallbackName, 0);
            }
        }
        if (!unloadWorldAndClearCache(server, world, false)) {
            return new ReloadSingleResult(ReloadSingleStatus.FAILED, world.getName(), fallbackName, 0);
        }
        // SavedDataStorage.close() inside unloadWorld can re-create data/ under the deleted world directory.
        // Clean it up when the folder was absent prior to unload so subsequent /world reload does not resurrect it.
        if (!existedBeforeUnload
            && !isFallbackWorld(server, world)
            && worldDir.getParent() != null
            && worldDir.getParent().equals(worldsRoot)
            && Files.isDirectory(worldDir)) {
            deleteDirectoryQuietly(worldDir);
        }
        return new ReloadSingleResult(ReloadSingleStatus.MISSING_FOLDER_UNLOADED, world.getName(), fallbackName, 0);
    }

    private static void deleteDirectoryQuietly(final Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException _) {
                }
            });
        } catch (IOException _) {
        }
    }

    private static ReloadSingleResult reloadExtraWorldFromDisk(final MinecraftServer server, final World world) {
        final ServerLevel handle = ((CraftWorld) world).getHandle();
        final Path worldDir = resolveWorldConfigDir(handle);
        if (!Files.isDirectory(worldDir)) {
            return handleMissingFolderUnload(server, world);
        }

        final String actualFolderName = worldDir.getFileName().toString();
        final Path root = containerRoot(server);
        final List<Player> players = world.getPlayers();
        List<UUID> evacuatedUuids = List.of();
        String fallbackName = "";

        if (!players.isEmpty()) {
            final @Nullable World fallbackWorld = resolveValidFallbackWorld(server);
            if (fallbackWorld == null || world.equals(fallbackWorld)) {
                return new ReloadSingleResult(ReloadSingleStatus.NO_FALLBACK_WORLD, world.getName(), "", 0);
            }
            fallbackName = fallbackWorld.getName();
            evacuatedUuids = evacuatePlayers(world, fallbackWorld);
            if (!world.getPlayers().isEmpty()) {
                return new ReloadSingleResult(ReloadSingleStatus.FAILED, world.getName(), fallbackName, 0);
            }
        }

        // Unload with save=false so stale in-memory chunks never overwrite replaced region files on disk.
        if (!unloadWorldAndClearCache(server, world, false)) {
            return new ReloadSingleResult(ReloadSingleStatus.FAILED, world.getName(), fallbackName, 0);
        }

        final @Nullable World reloadedWorld = loadWorldFromDisk(server, root, actualFolderName);
        if (reloadedWorld == null) {
            return new ReloadSingleResult(ReloadSingleStatus.FAILED, actualFolderName, fallbackName, 0);
        }

        int returned = 0;
        if (!evacuatedUuids.isEmpty()) {
            final Location targetSpawn = getSpawnLocation(reloadedWorld);
            for (final UUID uuid : evacuatedUuids) {
                final @Nullable Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) {
                    if (online.teleport(targetSpawn, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                        returned++;
                    }
                }
            }
        }
        return new ReloadSingleResult(ReloadSingleStatus.RELOADED, reloadedWorld.getName(), fallbackName, returned);
    }

    private static List<UUID> evacuatePlayers(final World fromWorld, final World fallbackWorld) {
        final List<Player> current = List.copyOf(fromWorld.getPlayers());
        if (current.isEmpty()) {
            return List.of();
        }
        final Location fallbackSpawn = getSpawnLocation(fallbackWorld);
        final List<UUID> moved = new ArrayList<>(current.size());
        for (final Player player : current) {
            if (player.isOnline() && player.teleport(fallbackSpawn, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                moved.add(player.getUniqueId());
            }
        }
        return moved;
    }

    private static boolean unloadWorldAndClearCache(
        final MinecraftServer server,
        final World world,
        final boolean save
    ) {
        final ServerLevel handle = ((CraftWorld) world).getHandle();
        final String key = world.getName().toLowerCase(Locale.ROOT);
        if (!server.server.unloadWorld(world, save)) {
            return false;
        }
        WORLD_CACHE.remove(key);
        DIMENSION_FOLDERS.remove(handle.dimension());
        return true;
    }

    public enum LoadStatus {
        LOADED,
        ALREADY_LOADED,
        NOT_FOUND_ON_DISK,
        FAILED
    }

    public enum UnloadStatus {
        UNLOADED,
        NOT_LOADED,
        CANNOT_UNLOAD_FALLBACK,
        NO_FALLBACK_WORLD,
        FAILED
    }

    public enum ReloadSingleStatus {
        RELOADED,
        FALLBACK_CONFIG_ONLY,
        MISSING_FOLDER_UNLOADED,
        NOT_LOADED,
        NO_FALLBACK_WORLD,
        FAILED
    }

    public enum ReloadAllStatus {
        SUCCESS,
        NO_FALLBACK_WORLD
    }

    public record LoadResult(LoadStatus status, String worldName) {
    }

    public record UnloadResult(UnloadStatus status, String worldName, String fallbackName, int movedPlayers) {
    }

    public record ReloadSingleResult(
        ReloadSingleStatus status,
        String worldName,
        String fallbackName,
        int returnedPlayers
    ) {
    }

    public record ReloadAllResult(
        ReloadAllStatus status,
        int reloaded,
        int loaded,
        int unloaded,
        String fallbackName
    ) {
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
