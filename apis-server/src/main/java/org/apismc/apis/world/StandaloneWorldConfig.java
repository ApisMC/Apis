package org.apismc.apis.world;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.level.GameType;
import org.apismc.apis.config.ApisConfig;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Per-world metadata stored at {@code worlds/<world_name>/world.yml}.
 */
@ConfigSerializable
public final class StandaloneWorldConfig {

    public static final String FILE_NAME = "world.yml";
    public static final double DEFAULT_SPAWN_Y = 61.0d;
    public static final String DEFAULT_GAME_MODE = "survival";

    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft");
    private static final String HEADER = """
        Apis Standalone World Configuration | Конфігурація окремого світу Apis
        Defines world spawn, join enforcement, and default player game mode | Визначає спавн світу, примусовий спавн і режим гри за замовчуванням
        """;

    @Setting("spawn")
    @Comment(
        "World spawn coordinates and camera angles used on join and /tpworld"
            + " | Координати спавну світу та кути камери при вході й /tpworld"
    )
    public SpawnPoint spawn = new SpawnPoint();

    @Setting("force-spawn")
    @Comment(
        "true = always teleport joining players to spawn; false = resume at saved position if available"
            + " | true = завжди телепортувати на спавн при вході; false = повертати на збережену позицію за наявності"
    )
    public boolean forceSpawn = true;

    @Setting("default-game-mode")
    @Comment(
        "Default game mode applied whenever a player enters this world"
            + " | Ігровий режим за замовчуванням при потраплянні гравця в цей світ\n"
            + "Available modes: survival, creative, adventure, spectator"
            + " | Доступні режими: survival, creative, adventure, spectator"
    )
    public String defaultGameMode = DEFAULT_GAME_MODE;

    /**
     * Loads {@code world.yml} inside {@code worldDir}, creating it atomically with defaults if absent,
     * completing any newly added default keys, or backing up malformed files to {@code world.yml.broken}.
     *
     * @param worldDir world directory under {@code worlds/}
     * @return loaded and sanitized configuration instance
     */
    public static StandaloneWorldConfig loadOrCreate(final Path worldDir) {
        return loadOrCreate(worldDir, 0.5d, DEFAULT_SPAWN_Y, 0.5d, 0.0f, 0.0f, true);
    }

    /**
     * Loads {@code world.yml} inside {@code worldDir}, using supplied initial spawn coordinates if the file
     * does not exist yet.
     *
     * @param worldDir     world directory
     * @param defaultX     default spawn X
     * @param defaultY     default spawn Y
     * @param defaultZ     default spawn Z
     * @param defaultYaw   default yaw
     * @param defaultPitch default pitch
     * @param forceSpawn   default force-spawn flag
     * @return loaded and sanitized configuration
     */
    public static StandaloneWorldConfig loadOrCreate(
        final Path worldDir,
        final double defaultX,
        final double defaultY,
        final double defaultZ,
        final float defaultYaw,
        final float defaultPitch,
        final boolean forceSpawn
    ) {
        final Path file = worldDir.resolve(FILE_NAME);
        final YamlConfigurationLoader loader = buildLoader(file);
        if (Files.exists(file)) {
            try {
                final CommentedConfigurationNode root = loader.load();
                final Set<String> keysBefore = ApisConfig.collectNodePaths(root);
                final @Nullable StandaloneWorldConfig parsed = root.get(StandaloneWorldConfig.class);
                if (parsed != null) {
                    final boolean normalized = parsed.sanitize(file);
                    root.set(StandaloneWorldConfig.class, parsed);
                    final Set<String> keysAfter = ApisConfig.collectNodePaths(root);
                    if (normalized || !keysBefore.containsAll(keysAfter)) {
                        saveAtomic(worldDir, parsed);
                    }
                    return parsed;
                }
            } catch (final Exception ex) {
                LOGGER.error("Failed to parse {}. Backing up broken config and using defaults.", file, ex);
                backupBrokenFile(file);
            }
        }

        final StandaloneWorldConfig fresh = new StandaloneWorldConfig();
        fresh.spawn.x = defaultX;
        fresh.spawn.y = defaultY;
        fresh.spawn.z = defaultZ;
        fresh.spawn.yaw = defaultYaw;
        fresh.spawn.pitch = defaultPitch;
        fresh.forceSpawn = forceSpawn;
        fresh.defaultGameMode = DEFAULT_GAME_MODE;
        fresh.sanitize(file);
        saveAtomic(worldDir, fresh);
        return fresh;
    }

    /**
     * Atomically persists {@code world.yml} inside {@code worldDir} with full ENG | UA comments.
     *
     * @param worldDir target world directory
     * @param config   configuration to serialize
     * @throws IllegalStateException if writing to disk fails
     */
    public static void saveAtomic(final Path worldDir, final StandaloneWorldConfig config) {
        final Path target = worldDir.resolve(FILE_NAME);
        config.sanitize(target);
        final Path temp = worldDir.resolve(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(worldDir);
            final StringWriter writer = new StringWriter();
            final YamlConfigurationLoader bufferLoader = YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .defaultOptions(opts -> opts.shouldCopyDefaults(true).header(HEADER))
                .sink(() -> new BufferedWriter(writer))
                .build();
            final CommentedConfigurationNode root = bufferLoader.createNode();
            root.set(StandaloneWorldConfig.class, config);
            bufferLoader.save(root);

            final String commented = ApisConfig.renderNodeComments(writer.toString(), root);
            Files.writeString(temp, commented, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException ex) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException _) {
            }
            throw new IllegalStateException("Failed to write " + target + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Resolves {@link #defaultGameMode} to a vanilla {@link GameType}.
     *
     * @return configured {@link GameType}, defaulting to {@link GameType#SURVIVAL}
     */
    public GameType toGameType() {
        final @Nullable String normalized = normalizeGameMode(this.defaultGameMode);
        if (normalized == null) {
            return GameType.SURVIVAL;
        }
        return switch (normalized) {
            case "creative" -> GameType.CREATIVE;
            case "adventure" -> GameType.ADVENTURE;
            case "spectator" -> GameType.SPECTATOR;
            default -> GameType.SURVIVAL;
        };
    }

    /**
     * Normalizes a user-provided game mode string against the 4 vanilla mode names.
     *
     * @param raw raw string from configuration
     * @return canonical lowercase name ({@code survival}, {@code creative}, {@code adventure}, {@code spectator}),
     *         or {@code null} if invalid
     */
    public static @Nullable String normalizeGameMode(final @Nullable String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "survival" -> "survival";
            case "creative" -> "creative";
            case "adventure" -> "adventure";
            case "spectator" -> "spectator";
            default -> null;
        };
    }

    /**
     * Returns an immutable defensive copy of this configuration.
     *
     * @return cloned configuration
     */
    public StandaloneWorldConfig copy() {
        final StandaloneWorldConfig clone = new StandaloneWorldConfig();
        clone.forceSpawn = this.forceSpawn;
        clone.defaultGameMode = this.defaultGameMode;
        clone.spawn.x = this.spawn.x;
        clone.spawn.y = this.spawn.y;
        clone.spawn.z = this.spawn.z;
        clone.spawn.yaw = this.spawn.yaw;
        clone.spawn.pitch = this.spawn.pitch;
        clone.sanitize(null);
        return clone;
    }

    public void sanitize() {
        this.sanitize(null);
    }

    private boolean sanitize(final @Nullable Path sourceFile) {
        boolean modified = false;
        if (this.spawn == null) {
            this.spawn = new SpawnPoint();
            modified = true;
        }
        if (!Double.isFinite(this.spawn.x)) {
            this.spawn.x = 0.5d;
            modified = true;
        }
        if (!Double.isFinite(this.spawn.y)) {
            this.spawn.y = DEFAULT_SPAWN_Y;
            modified = true;
        }
        if (!Double.isFinite(this.spawn.z)) {
            this.spawn.z = 0.5d;
            modified = true;
        }
        if (!Float.isFinite(this.spawn.yaw)) {
            this.spawn.yaw = 0.0f;
            modified = true;
        }
        if (!Float.isFinite(this.spawn.pitch)) {
            this.spawn.pitch = 0.0f;
            modified = true;
        }

        final @Nullable String normalizedMode = normalizeGameMode(this.defaultGameMode);
        if (normalizedMode == null) {
            if (sourceFile != null) {
                LOGGER.warn(
                    "Invalid default-game-mode '{}' in {}; defaulting to '{}'.",
                    this.defaultGameMode,
                    sourceFile,
                    DEFAULT_GAME_MODE
                );
            }
            this.defaultGameMode = DEFAULT_GAME_MODE;
            modified = true;
        } else if (!normalizedMode.equals(this.defaultGameMode)) {
            this.defaultGameMode = normalizedMode;
            modified = true;
        }
        return modified;
    }

    private static YamlConfigurationLoader buildLoader(final Path path) {
        return YamlConfigurationLoader.builder()
            .path(path)
            .nodeStyle(NodeStyle.BLOCK)
            .indent(2)
            .defaultOptions(opts -> opts.shouldCopyDefaults(true).header(HEADER))
            .build();
    }

    private static void backupBrokenFile(final Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            LOGGER.warn("Could not back up broken config {}: {}", path, ex.getMessage());
        }
    }

    @ConfigSerializable
    public static final class SpawnPoint {
        @Setting("x")
        public double x = 0.5d;

        @Setting("y")
        public double y = DEFAULT_SPAWN_Y;

        @Setting("z")
        public double z = 0.5d;

        @Setting("yaw")
        public float yaw = 0.0f;

        @Setting("pitch")
        public float pitch = 0.0f;
    }
}
