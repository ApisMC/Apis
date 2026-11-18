package org.apismc.apis.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves organized paths for server configs (config/), player data files (users/),
 * and generates a default root .gitignore if missing.
 */
public final class ApisPaths {

    public static final String CONFIG_DIR = "config";
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
        "!config/",
        "!config/**",
        "!.gitignore"
    );

    private ApisPaths() {
    }

    /**
     * Resolves a configuration file path strictly inside the {@code config/} directory.
     *
     * @param fileName relative configuration filename
     * @return resolved path inside config/
     */
    public static Path resolveConfigPath(final String fileName) {
        return resolveConfigPath(Path.of("."), fileName);
    }

    /**
     * Resolves a configuration file path strictly inside {@code <baseDir>/config/} without root fallback.
     *
     * @param baseDir  server working directory
     * @param fileName relative configuration filename
     * @return resolved path inside config/
     */
    public static Path resolveConfigPath(final Path baseDir, final String fileName) {
        final Path targetDir = baseDir.resolve(CONFIG_DIR);
        ensureDirectory(targetDir);
        return targetDir.resolve(fileName);
    }

    /**
     * Resolves a configuration file as {@link File} for JOptSimple OptionParser defaults.
     *
     * @param fileName relative configuration filename
     * @return resolved file
     */
    public static File resolveConfigFile(final String fileName) {
        return resolveConfigPath(fileName).toFile();
    }

    /**
     * Resolves a player-registry/cache file (ops.json, whitelist.json, bans, usercache.json):
     * keeps ./<fileName> if present, otherwise stores inside users/<fileName>.
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
        } catch (final IOException ex) {
            LOGGER.warn("Could not generate default {}: {}", gitIgnore, ex.getMessage());
        }
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

    private static void ensureDirectory(final Path dir) {
        if (Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (final IOException ex) {
            LOGGER.warn("Could not create directory {}: {}", dir.toAbsolutePath(), ex.getMessage());
        }
    }
}
