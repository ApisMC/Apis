package org.apismc.apis.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.util.PathConverter;
import org.apismc.apis.hooks.ApisDiagnostics;
import org.apismc.apis.world.StandaloneWorldManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class ApisPathsAndSyncTest {

    @AfterEach
    void tearDown() {
        ApisDiagnostics.resetForTesting();
        ApisPaths.resetForTesting();
        StandaloneWorldManager.resetForTesting();
        ApisConfig.resetForTesting();
    }

    @Test
    void resolvesNewSubdirsOrFallsBackToExistingRoot(final @TempDir Path tempDir) throws IOException {
        final File resolvedNew = ApisPaths.resolveUserFile(tempDir.toFile(), "usercache.json", true);
        assertEquals(tempDir.resolve("users").resolve("usercache.json").toFile(), resolvedNew);
        assertTrue(Files.isDirectory(tempDir.resolve("users")));

        final Path legacyRoot = tempDir.resolve("legacy-usercache.json");
        Files.writeString(legacyRoot, "[]");
        final File resolvedLegacy = ApisPaths.resolveUserFile(tempDir.toFile(), "legacy-usercache.json", true);
        assertEquals(legacyRoot.toFile(), resolvedLegacy);
    }

    @Test
    void resolvesConfigsStrictlyInsideConfigDirWithoutRootFallback(final @TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("server.properties"), "server-port=25565\n");
        Files.writeString(tempDir.resolve("bukkit.yml"), "settings: {}\n");

        final Path propsPath = ApisPaths.resolveConfigPath(tempDir, "server.properties");
        final Path bukkitPath = ApisPaths.resolveConfigPath(tempDir, "bukkit.yml");
        assertEquals(tempDir.resolve("configs").resolve("server.properties").toAbsolutePath().normalize(), propsPath);
        assertEquals(tempDir.resolve("configs").resolve("bukkit.yml").toAbsolutePath().normalize(), bukkitPath);
        assertTrue(Files.isDirectory(tempDir.resolve("configs")));
    }

    @Test
    void configuresCustomDirsPluginDataAndWorldsContainerPriority(final @TempDir Path tempDir) throws IOException {
        final Path customConfigs = tempDir.resolve("arena-1").resolve("configs");
        final Path customWorlds = tempDir.resolve("arena-1").resolve("worlds");
        final Path customAddons = tempDir.resolve("arena-1").resolve("addons");
        final OptionParser parser = new OptionParser();
        parser.accepts("configs-dir").withRequiredArg().withValuesConvertedBy(new PathConverter());
        parser.accepts("worlds-dir").withRequiredArg().withValuesConvertedBy(new PathConverter());
        parser.accepts("addons-dir").withRequiredArg().withValuesConvertedBy(new PathConverter());
        parser.accepts("bukkit-settings").withRequiredArg().ofType(File.class);
        parser.accepts("paper-settings-directory").withRequiredArg().ofType(File.class);
        parser.accepts("universe").withRequiredArg().ofType(File.class);

        final OptionSet opts = parser.parse(
            "--configs-dir=" + customConfigs,
            "--worlds-dir=" + customWorlds,
            "--addons-dir=" + customAddons
        );
        ApisPaths.configureFromOptions(opts);

        assertTrue(Files.isDirectory(customConfigs));
        assertTrue(Files.isDirectory(customWorlds));
        assertTrue(Files.isDirectory(customAddons));
        assertEquals(customConfigs.toAbsolutePath().normalize(), ApisPaths.configsDir());
        assertEquals(customWorlds.toAbsolutePath().normalize(), ApisPaths.worldsDir());
        assertEquals(customAddons.toAbsolutePath().normalize(), ApisPaths.addonsDir());
        assertEquals(customAddons.toAbsolutePath().normalize(), ApisPaths.resolveAddonsDir(opts));
        assertEquals(customConfigs.resolve("bukkit.yml").toFile(), ApisPaths.resolveOptionConfigFile(opts, "bukkit-settings", "bukkit.yml"));
        assertEquals(customConfigs, ApisPaths.resolvePaperDir(opts));
        assertEquals(customConfigs.resolve("permissions.yml").toFile(), ApisPaths.resolveConfigRelativeFile("permissions.yml", "permissions.yml"));
        assertEquals(customConfigs.resolve("help.yml").toFile(), ApisPaths.resolveConfigRelativeFile("help.yml", "help.yml"));

        final Path cfgFile = customConfigs.resolve("apis.yml");
        ApisConfig.loadEarly(cfgFile);
        final File resolvedWorlds = StandaloneWorldManager.resolveUniversePath(opts, new YamlConfiguration(), new File("."));
        assertEquals(customWorlds.toAbsolutePath().normalize().toFile(), resolvedWorlds);

        // Priority fallback in vanilla mode when --worlds-dir omitted
        final OptionSet legacyOpts = parser.parse("--universe=" + tempDir.resolve("legacy-universe"));
        final YamlConfiguration bukkitCfg = new YamlConfiguration();
        bukkitCfg.set("settings.world-container", tempDir.resolve("bukkit-container").toString());
        assertEquals(
            tempDir.resolve("legacy-universe").toAbsolutePath().normalize().toFile(),
            StandaloneWorldManager.resolveUniversePath(legacyOpts, bukkitCfg, new File("."))
        );

        final String vanillaYaml = Files.readString(cfgFile).replace("mode: standalone", "mode: vanilla");
        Files.writeString(cfgFile, vanillaYaml);
        ApisConfig.loadEarly(cfgFile);
        final OptionSet emptyOpts = parser.parse();
        assertEquals(
            tempDir.resolve("bukkit-container").toAbsolutePath().normalize().toFile(),
            StandaloneWorldManager.resolveUniversePath(emptyOpts, bukkitCfg, new File("."))
        );
    }

    @Test
    void generatesDefaultGitIgnoreWithoutOverwritingExisting(final @TempDir Path tempDir) throws IOException {
        ApisPaths.ensureGitIgnore(tempDir);
        final Path gitIgnore = tempDir.resolve(".gitignore");
        assertTrue(Files.exists(gitIgnore));
        final String content = Files.readString(gitIgnore);
        assertTrue(content.contains("users/usercache.json"));
        assertTrue(content.contains("!configs/"));
        assertTrue(content.contains("!configs/**"));
        assertTrue(content.contains("!.gitignore"));

        Files.writeString(gitIgnore, "# custom\n");
        ApisPaths.ensureGitIgnore(tempDir);
        assertEquals("# custom\n", Files.readString(gitIgnore));
    }

    @Test
    void stripsBinaryDuplicatesFromLegacyConfigs() {
        final YamlConfiguration bukkit = new YamlConfiguration();
        bukkit.set("settings.allow-end", true);
        bukkit.set("settings.warn-on-overload", true);
        ApisConfigSync.stripLegacyBukkitSettings(bukkit);
        assertFalse(bukkit.contains("settings.allow-end"));
        assertTrue(bukkit.contains("settings.warn-on-overload"));

        final YamlConfiguration spigot = new YamlConfiguration();
        spigot.set("advancements.disable-saving", false);
        spigot.set("players.disable-saving", false);
        spigot.set("settings.bungeecord", false);
        ApisConfigSync.stripLegacySpigotSettings(spigot);
        assertNull(spigot.get("advancements"));
        assertNull(spigot.get("players"));
        assertTrue(spigot.contains("settings.bungeecord"));

        final Properties props = new Properties();
        props.setProperty("allow-nether", "true");
        props.setProperty("spawn-monsters", "true");
        props.setProperty("server-port", "25565");
        ApisConfigSync.stripLegacyProperties(props);
        assertFalse(props.containsKey("allow-nether"));
        assertFalse(props.containsKey("spawn-monsters"));
        assertEquals("25565", props.getProperty("server-port"));

        final CommentedConfigurationNode globalNode = CommentedConfigurationNode.root();
        globalNode.node("misc", "enable-nether").raw(true);
        globalNode.node("collisions", "enable-player-collisions").raw(true);
        globalNode.node("misc", "max-joins-per-tick").raw(5);
        ApisConfigSync.stripLegacyPaperNode(globalNode, "paper-global.yml");
        assertTrue(globalNode.node("misc", "enable-nether").virtual());
        assertTrue(globalNode.node("collisions", "enable-player-collisions").virtual());
        assertEquals(5, globalNode.node("misc", "max-joins-per-tick").getInt());
    }
}
