// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import joptsimple.OptionSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves organized paths for server configs ({@code configs/}, or {@code --configs-dir}),
 * worlds ({@code worlds/}, or {@code --worlds-dir}), plugin/addon JARs ({@code addons/}, or {@code --addons-dir}),
 * player files ({@code users/}), and default {@code .gitignore}.
 */
public final class ApisPaths {

    public static final String DEFAULT_CONFIGS_DIR = "configs";
    public static final String DEFAULT_WORLDS_DIR = "worlds";
    public static final String DEFAULT_ADDONS_DIR = "addons";
    public static final String USERS_DIR = "users";
    private static final Logger LOGGER = LoggerFactory.getLogger("Minecraft");

    private static final List<String> DEFAULT_GITIGNORE_LINES = List.of(
        "*.jar",
        "libraries/",
        "versions/",
        "logs/",
        "crash-reports/",
        "debug/",
        "session.lock",
        "world*/",
        "plugins/",
        "users/usercache.json",
        "",
        "!configs/",
        "!configs/**",
        "!.gitignore"
    );

    private static volatile Path configsDir = Path.of(DEFAULT_CONFIGS_DIR).toAbsolutePath().normalize();
    private static volatile Path worldsDir = Path.of(DEFAULT_WORLDS_DIR).toAbsolutePath().normalize();
    private static volatile Path addonsDir = Path.of(DEFAULT_ADDONS_DIR).toAbsolutePath().normalize();

    private ApisPaths() {
    }

    /**
     * Applies CLI {@code --configs-dir}, {@code --worlds-dir}, and {@code --addons-dir} overrides (or defaults)
     * and ensures all directories exist on disk.
     *
     * @param options parsed server CLI options
     */
    public static synchronized void configureFromOptions(final OptionSet options) {
        final @Nullable Path explicitConfigs = options.has("configs-dir")
            ? (Path) options.valueOf("configs-dir")
            : null;
        final @Nullable Path explicitWorlds = options.has("worlds-dir")
            ? (Path) options.valueOf("worlds-dir")
            : null;
        final @Nullable Path explicitAddons = options.has("addons-dir")
            ? (Path) options.valueOf("addons-dir")
            : null;
        configureDirectories(explicitConfigs, explicitWorlds, explicitAddons);
    }

    /**
     * Sets normalized base directories for configs and worlds, creating them if absent.
     *
     * @param explicitConfigs custom configs directory (nullable -> {@code ./configs})
     * @param explicitWorlds  custom worlds directory (nullable -> {@code ./worlds})
     */
    public static synchronized void configureDirectories(
        final @Nullable Path explicitConfigs,
        final @Nullable Path explicitWorlds
    ) {
        configureDirectories(explicitConfigs, explicitWorlds, null);
    }

    /**
     * Sets normalized base directories for configs, worlds, and addons, creating them if absent.
     *
     * @param explicitConfigs custom configs directory (nullable -> {@code ./configs})
     * @param explicitWorlds  custom worlds directory (nullable -> {@code ./worlds})
     * @param explicitAddons  custom addons directory (nullable -> {@code ./addons})
     */
    public static synchronized void configureDirectories(
        final @Nullable Path explicitConfigs,
        final @Nullable Path explicitWorlds,
        final @Nullable Path explicitAddons
    ) {
        final Path resolvedConfigs = (explicitConfigs != null ? explicitConfigs : Path.of(DEFAULT_CONFIGS_DIR))
            .toAbsolutePath()
            .normalize();
        final Path resolvedWorlds = (explicitWorlds != null ? explicitWorlds : Path.of(DEFAULT_WORLDS_DIR))
            .toAbsolutePath()
            .normalize();
        final Path resolvedAddons = (explicitAddons != null ? explicitAddons : Path.of(DEFAULT_ADDONS_DIR))
            .toAbsolutePath()
            .normalize();
        ensureDirectory(resolvedConfigs);
        ensureDirectory(resolvedWorlds);
        ensureDirectory(resolvedAddons);
        configsDir = resolvedConfigs;
        worldsDir = resolvedWorlds;
        addonsDir = resolvedAddons;
    }

    public static Path configsDir() {
        return configsDir;
    }

    public static Path worldsDir() {
        return worldsDir;
    }

    public static Path addonsDir() {
        return addonsDir;
    }

    /**
     * Resolves the addon JAR scanning directory, honoring {@code --addons-dir} if provided.
     *
     * @param options parsed CLI options
     * @return normalized addons directory path
     */
    public static Path resolveAddonsDir(final OptionSet options) {
        if (options.has("addons-dir")) {
            final Path resolved = ((Path) options.valueOf("addons-dir")).toAbsolutePath().normalize();
            ensureDirectory(resolved);
            return resolved;
        }
        ensureDirectory(addonsDir);
        return addonsDir;
    }

    /**
     * Resolves a configuration file path strictly inside the active configs directory.
     *
     * @param fileName relative configuration filename
     * @return resolved path inside {@code --configs-dir}
     */
    public static Path resolveConfigPath(final String fileName) {
        ensureDirectory(configsDir);
        return configsDir.resolve(fileName).normalize();
    }

    /**
     * Resolves a configuration file path strictly inside {@code <baseDir>/configs/} without root fallback.
     *
     * @param baseDir  server working directory
     * @param fileName relative configuration filename
     * @return resolved path inside {@code configs/}
     */
    public static Path resolveConfigPath(final Path baseDir, final String fileName) {
        final Path targetDir = baseDir.resolve(DEFAULT_CONFIGS_DIR).toAbsolutePath().normalize();
        ensureDirectory(targetDir);
        return targetDir.resolve(fileName).normalize();
    }

    /**
     * Resolves a configuration file as {@link File}.
     *
     * @param fileName relative configuration filename
     * @return resolved file
     */
    public static File resolveConfigFile(final String fileName) {
        return resolveConfigPath(fileName).toFile();
    }

    /**
     * Resolves a CLI config file option, honoring an explicitly provided CLI override or falling back to
     * {@code <configs-dir>/<defaultFileName>}.
     *
     * @param options         parsed CLI options
     * @param optionName      CLI flag name (e.g. {@code "bukkit-settings"})
     * @param defaultFileName filename inside {@code <configs-dir>} when flag was omitted
     * @return resolved configuration file
     */
    public static File resolveOptionConfigFile(
        final OptionSet options,
        final String optionName,
        final String defaultFileName
    ) {
        if (options.has(optionName)) {
            final Object raw = options.valueOf(optionName);
            if (raw instanceof File file) {
                return file.toPath().toAbsolutePath().normalize().toFile();
            }
            if (raw instanceof Path path) {
                return path.toAbsolutePath().normalize().toFile();
            }
        }
        return resolveConfigFile(defaultFileName);
    }

    /**
     * Resolves the Paper configuration directory, honoring an explicit {@code --paper-dir} flag or falling
     * back to the active {@code --configs-dir}.
     *
     * @param options parsed CLI options
     * @return resolved Paper configs directory path
     */
    public static Path resolvePaperDir(final OptionSet options) {
        if (options.has("paper-dir") || options.has("paper-settings-directory")) {
            final File dir = (File) options.valueOf("paper-settings-directory");
            final Path resolved = dir.toPath().toAbsolutePath().normalize();
            ensureDirectory(resolved);
            return resolved;
        }
        ensureDirectory(configsDir);
        return configsDir;
    }

    /**
     * Resolves a relative config path (such as {@code settings.permissions-file} or {@code help.yml}) inside
     * the active configs directory while keeping explicit absolute paths untouched.
     *
     * @param configuredPath  raw configured path string (nullable)
     * @param defaultFileName default filename if {@code configuredPath} is null/blank
     * @return resolved {@link File}
     */
    public static File resolveConfigRelativeFile(final @Nullable String configuredPath, final String defaultFileName) {
        final String chosen = configuredPath == null || configuredPath.isBlank() ? defaultFileName : configuredPath.trim();
        final Path candidate = Path.of(chosen);
        if (candidate.isAbsolute()) {
            return candidate.normalize().toFile();
        }
        ensureDirectory(configsDir);
        if (candidate.getNameCount() > 1 && DEFAULT_CONFIGS_DIR.equals(candidate.getName(0).toString())) {
            return configsDir.resolve(candidate.subpath(1, candidate.getNameCount())).normalize().toFile();
        }
        return configsDir.resolve(candidate).normalize().toFile();
    }

    /**
     * Resolves a player-registry/cache file (ops.json, whitelist.json, bans, usercache.json):
     * keeps ./&lt;fileName&gt; if present, otherwise stores inside users/&lt;fileName&gt;.
     *
     * @param fileName relative user JSON filename
     * @return resolved file
     */
    public static File resolveUserFile(final String fileName) {
        return resolveWithRootFallback(Path.of("."), USERS_DIR, fileName).toFile();
    }

    /**
     * Resolves usercache.json respecting custom --world-dir while keeping root compatibility.
     *
     * @param baseDir          base world container directory
     * @param fileName         relative usercache filename
     * @param explicitUniverse whether --world-dir / --universe was explicitly set
     * @return resolved usercache file
     */
    public static File resolveUserFile(final File baseDir, final String fileName, final boolean explicitUniverse) {
        final Path root = explicitUniverse ? baseDir.toPath() : Path.of(".");
        return resolveWithRootFallback(root, USERS_DIR, fileName).toFile();
    }

    /**
     * Generates a default .gitignore in server root if none exists.
     *
     * @param rootDir server working directory
     */
    public static void ensureGitIgnore(final Path rootDir) {
        final Path gitIgnore = rootDir.resolve(".gitignore");
        if (Files.exists(gitIgnore)) {
            return;
        }
        try {
            Files.write(gitIgnore, DEFAULT_GITIGNORE_LINES, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("Could not generate default {}: {}", gitIgnore, ex.getMessage());
        }
    }

    /**
     * Restores default relative directory paths between unit tests.
     */
    public static synchronized void resetForTesting() {
        configsDir = Path.of(DEFAULT_CONFIGS_DIR).toAbsolutePath().normalize();
        worldsDir = Path.of(DEFAULT_WORLDS_DIR).toAbsolutePath().normalize();
        addonsDir = Path.of(DEFAULT_ADDONS_DIR).toAbsolutePath().normalize();
    }

    private static Path resolveWithRootFallback(final Path baseDir, final String subDir, final String fileName) {
        final Path legacyRootPath = baseDir.resolve(fileName);
        if (Files.exists(legacyRootPath)) {
            return legacyRootPath;
        }
        final Path targetDir = baseDir.resolve(subDir);
        ensureDirectory(targetDir);
        return targetDir.resolve(fileName);
    }

    static void ensureDirectory(final Path dir) {
        if (Files.exists(dir)) {
            if (!Files.isDirectory(dir)) {
                throw new IllegalStateException("Configured directory path exists and is not a directory: " + dir);
            }
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            LOGGER.warn("Could not create directory {}: {}", dir, ex.getMessage());
        }
    }
}
