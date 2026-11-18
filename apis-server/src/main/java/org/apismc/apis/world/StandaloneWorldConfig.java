package org.apismc.apis.world;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Per-world metadata stored at {@code worlds/<world_name>/world.yml}.
 */
@ConfigSerializable
public final class StandaloneWorldConfig {

    public static final String FILE_NAME = "world.yml";
    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft");

    @Setting("spawn")
    public SpawnPoint spawn = new SpawnPoint();

    @Setting("force-spawn")
    public boolean forceSpawn = true;

    /**
     * Loads {@code world.yml} inside {@code worldDir}, creating it atomically with defaults if absent
     * or backing up malformed files to {@code world.yml.broken}.
     *
     * @param worldDir world directory under {@code worlds/}
     * @return loaded and sanitized configuration instance
     */
    public static StandaloneWorldConfig loadOrCreate(final Path worldDir) {
        return loadOrCreate(worldDir, 0.5d, 60.0d, 0.5d, 0.0f, 0.0f, true);
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
                final @Nullable StandaloneWorldConfig parsed = root.get(StandaloneWorldConfig.class);
                if (parsed != null) {
                    parsed.sanitize();
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
        fresh.sanitize();
        saveAtomic(worldDir, fresh);
        return fresh;
    }

    /**
     * Atomically persists {@code world.yml} inside {@code worldDir}.
     *
     * @param worldDir target world directory
     * @param config   configuration to serialize
     * @throws IllegalStateException if writing to disk fails
     */
    public static void saveAtomic(final Path worldDir, final StandaloneWorldConfig config) {
        config.sanitize();
        final Path target = worldDir.resolve(FILE_NAME);
        final Path temp = worldDir.resolve(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(worldDir);
            final StringWriter writer = new StringWriter();
            final YamlConfigurationLoader bufferLoader = YamlConfigurationLoader.builder()
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .sink(() -> new BufferedWriter(writer))
                .build();
            final CommentedConfigurationNode root = bufferLoader.createNode();
            root.set(StandaloneWorldConfig.class, config);
            bufferLoader.save(root);

            Files.writeString(temp, writer.toString(), StandardCharsets.UTF_8);
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
     * Returns an immutable defensive copy of this configuration.
     *
     * @return cloned configuration
     */
    public StandaloneWorldConfig copy() {
        final StandaloneWorldConfig clone = new StandaloneWorldConfig();
        clone.forceSpawn = this.forceSpawn;
        clone.spawn.x = this.spawn.x;
        clone.spawn.y = this.spawn.y;
        clone.spawn.z = this.spawn.z;
        clone.spawn.yaw = this.spawn.yaw;
        clone.spawn.pitch = this.spawn.pitch;
        clone.sanitize();
        return clone;
    }

    public void sanitize() {
        if (this.spawn == null) {
            this.spawn = new SpawnPoint();
        }
        if (!Double.isFinite(this.spawn.x)) {
            this.spawn.x = 0.5d;
        }
        if (!Double.isFinite(this.spawn.y)) {
            this.spawn.y = 60.0d;
        }
        if (!Double.isFinite(this.spawn.z)) {
            this.spawn.z = 0.5d;
        }
        if (!Float.isFinite(this.spawn.yaw)) {
            this.spawn.yaw = 0.0f;
        }
        if (!Float.isFinite(this.spawn.pitch)) {
            this.spawn.pitch = 0.0f;
        }
    }

    private static YamlConfigurationLoader buildLoader(final Path path) {
        return YamlConfigurationLoader.builder()
            .path(path)
            .nodeStyle(NodeStyle.BLOCK)
            .indent(2)
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
        public double y = 60.0d;

        @Setting("z")
        public double z = 0.5d;

        @Setting("yaw")
        public float yaw = 0.0f;

        @Setting("pitch")
        public float pitch = 0.0f;
    }
}
