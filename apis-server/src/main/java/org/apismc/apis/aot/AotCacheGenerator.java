package org.apismc.apis.aot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Transparent two-phase Java 25 AOT cache runner invoked via {@code --generate-aot}.
 */
public final class AotCacheGenerator {

    public static final String FLAG_GENERATE_AOT = "--generate-aot";
    public static final String FLAG_INTERNAL_WARMUP = "--aot-internal-warmup";

    private static final String CACHE_DIR_NAME = "cache";
    private static final String AOT_CONF_PATH = "cache/apis.aotconf";
    private static final String AOT_CACHE_PATH = "cache/apis.aot";
    private static final long WARMUP_DELAY_MS = 2_000L;
    private static final String BANNER_DIVIDER = "============================================================";
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;\\d]*[A-Za-z]");

    private AotCacheGenerator() {
    }

    /**
     * Checks whether a specific CLI flag appears in the argument array.
     *
     * @param args CLI arguments
     * @param flag flag to look up
     * @return {@code true} if present
     */
    public static boolean containsFlag(final String @Nullable [] args, final String flag) {
        if (args == null) {
            return false;
        }
        for (final String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a copy of CLI arguments with specific internal/orchestration flags stripped.
     *
     * @param args         original CLI arguments
     * @param flagsToStrip flags to omit
     * @return filtered argument array
     */
    public static String[] stripFlags(final String @Nullable [] args, final String... flagsToStrip) {
        if (args == null || args.length == 0) {
            return new String[0];
        }
        final List<String> filtered = new ArrayList<>(args.length);
        for (final String arg : args) {
            boolean strip = false;
            for (final String candidate : flagsToStrip) {
                if (candidate.equals(arg)) {
                    strip = true;
                    break;
                }
            }
            if (!strip) {
                filtered.add(arg);
            }
        }
        return filtered.toArray(new String[0]);
    }

    /**
     * Runs the transparent two-phase AOT record &amp; create pipeline and returns the exit code.
     *
     * @param originalArgs CLI arguments passed to the entry point
     * @param entryClass   bootstrap entry class for fallback JAR lookup
     * @return {@code 0} on success, non-zero on failure
     */
    public static int generate(final String[] originalArgs, final Class<?> entryClass) {
        final Path cacheDir = Path.of(CACHE_DIR_NAME);
        final Path aotConf = Path.of(AOT_CONF_PATH);
        final Path aotCache = Path.of(AOT_CACHE_PATH);

        final Path javaBin;
        final Path jarPath;
        try {
            javaBin = resolveJavaExecutable();
            jarPath = resolveCurrentJarPath(entryClass);
        } catch (final IllegalStateException ex) {
            System.err.println(ex.getMessage());
            return 1;
        }

        try {
            Files.createDirectories(cacheDir);
            Files.deleteIfExists(aotConf);
        } catch (final IOException ex) {
            System.err.println("Failed to prepare AOT cache directory '" + cacheDir + "': " + ex.getMessage());
            return 1;
        }

        final List<String> inheritedJvmArgs = resolveInheritedJvmArgs();

        try {
            final List<String> recordCmd = buildRecordCommand(javaBin, inheritedJvmArgs, jarPath, originalArgs);
            final RecordResult recordResult = runRecordPhase(recordCmd);
            if (recordResult.eulaMissing) {
                System.err.println("Cannot record AOT profile: please accept eula.txt first.");
                return 1;
            }
            if (recordResult.exitCode != 0 || !Files.isRegularFile(aotConf) || fileSizeSafe(aotConf) <= 0L) {
                System.err.println("AOT profile recording failed (exit code " + recordResult.exitCode + ").");
                return 1;
            }

            final List<String> createCmd = buildCreateCommand(javaBin, inheritedJvmArgs, jarPath);
            final int createExitCode = runCreatePhase(createCmd);
            if (createExitCode != 0 || !Files.isRegularFile(aotCache) || fileSizeSafe(aotCache) <= 0L) {
                System.err.println("AOT cache compilation failed (exit code " + createExitCode + ").");
                return 1;
            }

            System.out.println();
            System.out.println(buildSuccessBanner(inheritedJvmArgs, jarPath));
            return 0;
        } catch (final IOException ex) {
            System.err.println("I/O error during AOT generation: " + ex.getMessage());
            return 1;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            System.err.println("AOT generation interrupted.");
            return 1;
        } finally {
            deleteQuietly(aotConf);
        }
    }

    /**
     * Schedules a graceful server halt 2 seconds after reaching {@code Done} during {@code --aot-internal-warmup}.
     *
     * @param server running server instance
     */
    public static void scheduleWarmupShutdown(final MinecraftServer server) {
        final Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(WARMUP_DELAY_MS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            server.halt(false);
        }, "Apis-AOT-Warmup");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }

    static List<String> resolveInheritedJvmArgs() {
        return filterAotJvmArgs(ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    static List<String> filterAotJvmArgs(final @Nullable List<String> inputArgs) {
        if (inputArgs == null || inputArgs.isEmpty()) {
            return List.of();
        }
        final List<String> filtered = new ArrayList<>(inputArgs.size());
        for (final String arg : inputArgs) {
            if (arg == null) {
                continue;
            }
            final String trimmed = arg.trim();
            if (!trimmed.isEmpty() && !isAotSpecificFlag(trimmed)) {
                filtered.add(trimmed);
            }
        }
        return List.copyOf(filtered);
    }

    static List<String> buildRecordCommand(final Path javaBin, final Path jarPath, final String[] originalArgs) {
        return buildRecordCommand(javaBin, List.of(), jarPath, originalArgs);
    }

    static List<String> buildRecordCommand(
        final Path javaBin,
        final List<String> inheritedJvmArgs,
        final Path jarPath,
        final String[] originalArgs
    ) {
        final List<String> cmd = new ArrayList<>(inheritedJvmArgs.size() + 8);
        cmd.add(javaBin.toString());
        cmd.addAll(inheritedJvmArgs);
        cmd.add("-XX:AOTMode=record");
        cmd.add("-XX:AOTConfiguration=" + AOT_CONF_PATH);
        cmd.add("-jar");
        cmd.add(jarPath.toString());
        for (final String arg : stripFlags(originalArgs, FLAG_GENERATE_AOT, FLAG_INTERNAL_WARMUP, "--nogui", "--noconsole")) {
            cmd.add(arg);
        }
        cmd.add("--nogui");
        return List.copyOf(cmd);
    }

    static List<String> buildCreateCommand(final Path javaBin, final Path jarPath) {
        return buildCreateCommand(javaBin, List.of(), jarPath);
    }

    static List<String> buildCreateCommand(
        final Path javaBin,
        final List<String> inheritedJvmArgs,
        final Path jarPath
    ) {
        final List<String> cmd = new ArrayList<>(inheritedJvmArgs.size() + 6);
        cmd.add(javaBin.toString());
        cmd.addAll(inheritedJvmArgs);
        cmd.add("-XX:AOTMode=create");
        cmd.add("-XX:AOTConfiguration=" + AOT_CONF_PATH);
        cmd.add("-XX:AOTCache=" + AOT_CACHE_PATH);
        cmd.add("-jar");
        cmd.add(jarPath.toString());
        return List.copyOf(cmd);
    }

    static String buildLaunchHint(final List<String> inheritedJvmArgs, final Path jarPath) {
        final List<String> parts = new ArrayList<>(inheritedJvmArgs.size() + 5);
        parts.add("java");
        for (final String arg : inheritedJvmArgs) {
            parts.add(formatShellToken(arg));
        }
        parts.add("-XX:AOTCache=" + AOT_CACHE_PATH);
        parts.add("-jar");
        parts.add(formatShellToken(jarPath.getFileName().toString()));
        parts.add("--nogui");
        return String.join(" ", parts);
    }

    static String buildSuccessBanner(final List<String> inheritedJvmArgs, final Path jarPath) {
        return String.join(
            System.lineSeparator(),
            BANNER_DIVIDER,
            "AOT cache generated: " + AOT_CACHE_PATH,
            "CRITICAL: You MUST use the exact same JVM flags for running",
            "the server as used during generation (memory, GC, modules),",
            "otherwise JVM will reject the cache.",
            "",
            "Run server using:",
            buildLaunchHint(inheritedJvmArgs, jarPath),
            BANNER_DIVIDER
        );
    }

    static boolean isServerReadyLine(final @Nullable String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return false;
        }
        final String clean = ANSI_ESCAPE_PATTERN.matcher(rawLine).replaceAll("");
        return clean.contains("Done (") && clean.contains(")!");
    }

    private static boolean isAotSpecificFlag(final String arg) {
        return "-XX:AOTMode".equals(arg) || arg.startsWith("-XX:AOTMode=")
            || "-XX:AOTConfiguration".equals(arg) || arg.startsWith("-XX:AOTConfiguration=")
            || "-XX:AOTCache".equals(arg) || arg.startsWith("-XX:AOTCache=");
    }

    private static String formatShellToken(final String token) {
        if (token.indexOf(' ') >= 0 || token.indexOf('\t') >= 0) {
            return '"' + token.replace("\"", "\\\"") + '"';
        }
        return token;
    }

    static Path resolveJavaExecutable() {
        final Optional<String> currentCmd = ProcessHandle.current().info().command();
        if (currentCmd.isPresent()) {
            final Path cmdPath = Path.of(currentCmd.get());
            if (Files.isRegularFile(cmdPath)) {
                return cmdPath;
            }
        }
        final String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            final Path bin = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
            if (Files.isRegularFile(bin)) {
                return bin;
            }
        }
        throw new IllegalStateException("Could not locate current JVM executable for AOT generation.");
    }

    static Path resolveCurrentJarPath(final Class<?> entryClass) {
        final @Nullable String classpath = System.getProperty("java.class.path");
        if (classpath != null && !classpath.isBlank() && !classpath.contains(File.pathSeparator)) {
            if (classpath.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                final Path cpPath = Path.of(classpath);
                if (Files.isRegularFile(cpPath)) {
                    return cpPath;
                }
            }
        }

        final @Nullable String sunJavaCommand = System.getProperty("sun.java.command");
        if (sunJavaCommand != null && !sunJavaCommand.isBlank()) {
            final @Nullable Path cmdJar = extractJarFromCommand(sunJavaCommand);
            if (cmdJar != null) {
                return cmdJar;
            }
        }

        final @Nullable CodeSource source = entryClass.getProtectionDomain().getCodeSource();
        if (source != null) {
            final @Nullable URL location = source.getLocation();
            if (location != null) {
                try {
                    final Path codePath = Path.of(location.toURI());
                    if (codePath.toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(codePath)) {
                        return codePath;
                    }
                } catch (URISyntaxException | IllegalArgumentException _) {
                    // Fall through to error
                }
            }
        }

        throw new IllegalStateException("Could not resolve server JAR path. Launch the server using 'java -jar <jar> --generate-aot'.");
    }

    private static @Nullable Path extractJarFromCommand(final String sunJavaCommand) {
        final int firstSpace = sunJavaCommand.indexOf(' ');
        final String firstToken = firstSpace > 0 ? sunJavaCommand.substring(0, firstSpace) : sunJavaCommand;
        if (firstToken.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            final Path candidate = Path.of(firstToken);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        final int jarIdx = sunJavaCommand.toLowerCase(Locale.ROOT).indexOf(".jar");
        if (jarIdx > 0) {
            final Path candidate = Path.of(sunJavaCommand.substring(0, jarIdx + 4).trim());
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static RecordResult runRecordPhase(final List<String> command)
        throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        final Process process = pb.start();

        boolean eulaMissing = false;
        boolean stopSent = false;
        try (
            BufferedWriter childInput = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader childOutput = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = childOutput.readLine()) != null) {
                System.out.println(line);
                if (line.contains("You need to agree to the EULA")) {
                    eulaMissing = true;
                }
                if (!stopSent && isServerReadyLine(line)) {
                    stopSent = true;
                    try {
                        childInput.write("stop");
                        childInput.newLine();
                        childInput.flush();
                    } catch (IOException _) {
                        // Child may already be shutting down
                    }
                }
            }
        }

        final int exitCode = process.waitFor();
        return new RecordResult(exitCode, eulaMissing);
    }

    private static int runCreatePhase(final List<String> command) throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        final Process process = pb.start();
        return process.waitFor();
    }

    private static long fileSizeSafe(final Path path) {
        try {
            return Files.size(path);
        } catch (IOException _) {
            return -1L;
        }
    }

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ex) {
            System.err.println("Could not delete temporary AOT file '" + path + "': " + ex.getMessage());
        }
    }

    private record RecordResult(int exitCode, boolean eulaMissing) {
    }
}
