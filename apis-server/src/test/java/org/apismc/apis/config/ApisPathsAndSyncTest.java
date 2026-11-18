package org.apismc.apis.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.apismc.apis.hooks.ApisDiagnostics;
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
        assertEquals(tempDir.resolve("config").resolve("server.properties"), propsPath);
        assertEquals(tempDir.resolve("config").resolve("bukkit.yml"), bukkitPath);
        assertTrue(Files.isDirectory(tempDir.resolve("config")));
    }

    @Test
    void generatesDefaultGitIgnoreWithoutOverwritingExisting(final @TempDir Path tempDir) throws IOException {
        ApisPaths.ensureGitIgnore(tempDir);
        final Path gitIgnore = tempDir.resolve(".gitignore");
        assertTrue(Files.exists(gitIgnore));
        final String content = Files.readString(gitIgnore);
        assertTrue(content.contains("users/usercache.json"));
        assertTrue(content.contains("!config/"));
        assertTrue(content.contains("!config/**"));
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
