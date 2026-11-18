package org.apismc.apis.aot;

import java.nio.file.Path;
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
    void buildsExpectedRecordCreateAndBannerOutput() {
        final Path javaBin = Path.of("bin", "java");
        final Path jarPath = Path.of("apis-bundler-26.3-release.jar");

        final List<String> recordCmd = AotCacheGenerator.buildRecordCommand(
            javaBin,
            jarPath,
            new String[] {"--generate-aot", "--port", "25565", "--nogui", "--noconsole"}
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
                "--nogui"
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
                "--nogui"
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

        final String expectedLaunchHint =
            "java -Xms2G -Xmx3G -XX:+UseParallelGC -Djdk.graal.CompilerConfiguration=community "
                + "--add-modules=jdk.incubator.vector -XX:AOTCache=cache/apis.aot "
                + "-jar apis-bundler-26.3-release.jar --nogui";
        assertEquals(expectedLaunchHint, AotCacheGenerator.buildLaunchHint(filteredJvmArgs, jarPath));

        final String expectedBanner = String.join(
            System.lineSeparator(),
            "============================================================",
            "AOT cache generated: cache/apis.aot",
            "CRITICAL: You MUST use the exact same JVM flags for running",
            "the server as used during generation (memory, GC, modules),",
            "otherwise JVM will reject the cache.",
            "",
            "Run server using:",
            expectedLaunchHint,
            "============================================================"
        );
        assertEquals(expectedBanner, AotCacheGenerator.buildSuccessBanner(filteredJvmArgs, jarPath));
    }

    @Test
    void detectsReadyLogLineAndIgnoresAnsiAndOtherLines() {
        final String esc = String.valueOf((char) 27);
        assertTrue(AotCacheGenerator.isServerReadyLine("[00:03:52] INFO Done (3.152s)! For help, type \"help\""));
        assertTrue(AotCacheGenerator.isServerReadyLine(esc + "[0m[00:03:52] INFO Done (1.234s)! For help, type \"help\"" + esc + "[0m"));

        assertFalse(AotCacheGenerator.isServerReadyLine(null));
        assertFalse(AotCacheGenerator.isServerReadyLine(""));
        assertFalse(AotCacheGenerator.isServerReadyLine("[00:03:52] INFO Done preparing level \"hub\" (0.081s)"));
        assertFalse(AotCacheGenerator.isServerReadyLine("[MyPlugin] Loaded configuration"));
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
}
