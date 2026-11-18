package org.apismc.apis.aot;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import org.apismc.apis.hooks.ApisHooks;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class AotCacheGeneratorTest {

    @AfterEach
    void tearDown() {
        ApisHooks.setAotInternalWarmup(false);
    }

    @Test
    void buildsExpectedRecordAndCreateCommands() {
        final Path javaBin = Path.of("bin", "java");
        final Path jarPath = Path.of("apis-bundler-26.3-release.jar");

        final List<String> recordCmd = AotCacheGenerator.buildRecordCommand(
            javaBin,
            jarPath,
            new String[] {"--generate-aot", "--port", "25565", "--nogui"}
        );
        assertEquals(
            List.of(
                javaBin.toString(),
                "-XX:AOTMode=record",
                "-XX:AOTConfiguration=cache/apis.aotconf",
                "-jar",
                "apis-bundler-26.3-release.jar",
                "--port",
                "25565",
                "--nogui",
                "--aot-internal-warmup"
            ),
            recordCmd
        );

        final List<String> createCmd = AotCacheGenerator.buildCreateCommand(javaBin, jarPath);
        assertEquals(
            List.of(
                javaBin.toString(),
                "-XX:AOTMode=create",
                "-XX:AOTConfiguration=cache/apis.aotconf",
                "-XX:AOTCache=cache/apis.aot",
                "-jar",
                "apis-bundler-26.3-release.jar"
            ),
            createCmd
        );

        final List<String> filteredJvmArgs = AotCacheGenerator.filterAotJvmArgs(
            List.of(
                "-Xms2G",
                "-Xmx3G",
                "-XX:+UseParallelGC",
                "-XX:AOTMode=off",
                "-XX:AOTConfiguration=old.aotconf",
                "-XX:AOTCache=old.aot",
                "-Djdk.graal.CompilerConfiguration=community",
                "--add-modules=jdk.incubator.vector"
            )
        );
        assertEquals(
            List.of(
                "-Xms2G",
                "-Xmx3G",
                "-XX:+UseParallelGC",
                "-Djdk.graal.CompilerConfiguration=community",
                "--add-modules=jdk.incubator.vector"
            ),
            filteredJvmArgs
        );

        final List<String> recordWithInherited = AotCacheGenerator.buildRecordCommand(
            javaBin,
            filteredJvmArgs,
            jarPath,
            new String[] {"--generate-aot", "--configs-dir=configs"}
        );
        assertEquals(
            List.of(
                javaBin.toString(),
                "-Xms2G",
                "-Xmx3G",
                "-XX:+UseParallelGC",
                "-Djdk.graal.CompilerConfiguration=community",
                "--add-modules=jdk.incubator.vector",
                "-XX:AOTMode=record",
                "-XX:AOTConfiguration=cache/apis.aotconf",
                "-jar",
                "apis-bundler-26.3-release.jar",
                "--configs-dir=configs",
                "--nogui",
                "--aot-internal-warmup"
            ),
            recordWithInherited
        );

        final List<String> createWithInherited = AotCacheGenerator.buildCreateCommand(
            javaBin,
            filteredJvmArgs,
            jarPath
        );
        assertEquals(
            List.of(
                javaBin.toString(),
                "-Xms2G",
                "-Xmx3G",
                "-XX:+UseParallelGC",
                "-Djdk.graal.CompilerConfiguration=community",
                "--add-modules=jdk.incubator.vector",
                "-XX:AOTMode=create",
                "-XX:AOTConfiguration=cache/apis.aotconf",
                "-XX:AOTCache=cache/apis.aot",
                "-jar",
                "apis-bundler-26.3-release.jar"
            ),
            createWithInherited
        );

        assertEquals(
            "java -Xms2G -Xmx3G -XX:+UseParallelGC -Djdk.graal.CompilerConfiguration=community --add-modules=jdk.incubator.vector -XX:AOTCache=cache/apis.aot -jar apis-bundler-26.3-release.jar --nogui",
            AotCacheGenerator.buildLaunchHint(filteredJvmArgs, jarPath)
        );
    }

    @Test
    void filtersAotJvmMessagesAndMutesServerNoise() {
        assertTrue(AotCacheGenerator.isAotLogLine("[0.241s][warning][aot] Skipping foo: failed verification"));
        assertTrue(AotCacheGenerator.isAotLogLine("[0.112s][info][aot] Number of classes 993"));
        assertTrue(AotCacheGenerator.isAotLogLine("AOTConfiguration recorded: cache/apis.aotconf"));
        assertTrue(AotCacheGenerator.isAotLogLine("Reading AOTConfiguration cache/apis.aotconf and writing AOTCache cache/apis.aot"));
        assertTrue(AotCacheGenerator.isAotLogLine("AOTCache creation is complete: cache/apis.aot 10878976 bytes"));

        assertFalse(AotCacheGenerator.isAotLogLine(""));
        assertFalse(AotCacheGenerator.isAotLogLine("[00:41:10] [ServerMain/INFO]: Preparing level \"world\""));
        assertFalse(AotCacheGenerator.isAotLogLine("[00:41:12 INFO]: Done (1.234s)! For help, type \"help\""));
        assertFalse(AotCacheGenerator.isAotLogLine("[MyPlugin] Loaded configuration"));
    }

    @Test
    void detectsAndStripsInternalFlags() {
        final String[] args = {"--nogui", "--aot-internal-warmup", "--port", "25565"};
        assertTrue(AotCacheGenerator.containsFlag(args, AotCacheGenerator.FLAG_INTERNAL_WARMUP));
        assertFalse(AotCacheGenerator.containsFlag(args, AotCacheGenerator.FLAG_GENERATE_AOT));
        assertArrayEquals(
            new String[] {"--nogui", "--port", "25565"},
            AotCacheGenerator.stripFlags(args, AotCacheGenerator.FLAG_INTERNAL_WARMUP)
        );

        ApisHooks.setAotInternalWarmup(true);
        assertTrue(ApisHooks.isAotInternalWarmup());
    }

    @Test
    void reformatsJvmAotLinesToUnifiedServerLogFormat() {
        final LocalTime ts = LocalTime.of(1, 2, 3);
        final String esc = String.valueOf((char) 27);

        // Plain / non-ANSI mode
        assertEquals(
            "[01:02:03] WARN [AOT] Preload Warning: Verification failed for org.mineskin.request.GenerateRequest",
            AotCacheGenerator.formatAotLogLine(
                "[8.451s][warning][aot] Preload Warning: Verification failed for org.mineskin.request.GenerateRequest",
                ts,
                false
            )
        );
        assertEquals(
            "[01:02:03] WARN [AOT] Skipping jdk/internal/event/Event: JFR event class",
            AotCacheGenerator.formatAotLogLine(
                "[8.711s][warning][aot] Skipping jdk/internal/event/Event: JFR event class",
                ts,
                false
            )
        );
        assertEquals(
            "[01:02:03] INFO [AOT] Number of classes 993",
            AotCacheGenerator.formatAotLogLine("[0.112s][info][aot] Number of classes 993", ts, false)
        );
        assertEquals(
            "[01:02:03] INFO [AOT] AOTConfiguration recorded: cache/apis.aotconf",
            AotCacheGenerator.formatAotLogLine("AOTConfiguration recorded: cache/apis.aotconf", ts, false)
        );
        assertEquals(
            "[01:02:03] ERROR [AOT] Unrecognized VM option 'BadOption'",
            AotCacheGenerator.formatAotLogLine("Unrecognized VM option 'BadOption'", ts, false)
        );

        // ANSI mode matches ApisLevelBadgeConverter + %highlightError
        assertEquals(
            "[01:02:03] INFO Recording AOT profile with loaded plugins...",
            AotCacheGenerator.formatServerStyleLine("INFO", false, "Recording AOT profile with loaded plugins...", ts, true)
        );
        assertEquals(
            "[01:02:03] INFO [AOT] Number of classes 993",
            AotCacheGenerator.formatAotLogLine("[0.112s][info][aot] Number of classes 993", ts, true)
        );
        assertEquals(
            esc + "[0;33;1m[01:02:03] " + esc + "[0;1;30;103m WARN " + esc + "[0;33;1m [AOT] Preload Warning: x" + esc + "[0m",
            AotCacheGenerator.formatAotLogLine("[8.451s][warning][aot] Preload Warning: x", ts, true)
        );
        assertEquals(
            esc + "[0;31;1m[01:02:03] " + esc + "[0;1;30;101m ERROR " + esc + "[0;31;1m Failed" + esc + "[0m",
            AotCacheGenerator.formatServerStyleLine("ERROR", false, "Failed", ts, true)
        );
    }
}
