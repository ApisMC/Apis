package dev.ultracore.aot;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Orchestrates two-phase Java 25 AOT cache generation via {@code --generate-aot}.
 */
public final class AotCacheGenerator {

    public static final String FLAG_GENERATE_AOT = "--generate-aot";
    public static final String FLAG_INTERNAL_WARMUP = "--aot-internal-warmup";

    private static final String CACHE_DIR_NAME = "cache";
    private static final String AOT_CONF_PATH = "cache/ultracore.aotconf";
    private static final String AOT_CACHE_PATH = "cache/ultracore.aot";
    private static final long WARMUP_DELAY_MS = 2_000L;
    private static final int TAIL_BUFFER_LIMIT = 30;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final char ESC = 27;
    private static final String ANSI_RESET = ESC + "[0m";
    private static final String WARN_LINE_COLOR = ESC + "[0;33;1m";
    private static final String WARN_BADGE = ESC + "[0;1;30;103m WARN " + WARN_LINE_COLOR;
    private static final String ERROR_LINE_COLOR = ESC + "[0;31;1m";
    private static final String ERROR_BADGE = ESC + "[0;1;30;101m ERROR " + ERROR_LINE_COLOR;
    private static final boolean ANSI_SUPPORTED = detectAnsiSupport();
    private static final Pattern JVM_AOT_PREFIX = Pattern.compile(
        "^(?:\\[[^\\]]+\\]\\s*)?\\[(warning|info|error|debug)\\]\\[aot[^\\]]*\\]\\s*(.*)$",
        Pattern.CASE_INSENSITIVE
    );

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
     * Runs the two-phase AOT record &amp; create pipeline and returns the suggested process exit code.
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
            logError(ex.getMessage());
            return 1;
        }

        try {
            Files.createDirectories(cacheDir);
            Files.deleteIfExists(aotConf);
        } catch (final IOException ex) {
            logError("Failed to prepare AOT cache directory '" + cacheDir.toAbsolutePath() + "': " + ex.getMessage());
            return 1;
        }

        try {
            logInfo("Recording AOT profile with loaded plugins...");
            final List<String> recordCmd = buildRecordCommand(javaBin, jarPath, originalArgs);
            final PhaseResult recordResult = runPhase(recordCmd, true);
            if (recordResult.eulaMissing) {
                logError("Cannot record AOT profile: please accept eula.txt first.");
                return 1;
            }
            if (recordResult.exitCode != 0 || !Files.isRegularFile(aotConf) || fileSizeSafe(aotConf) <= 0L) {
                logError("AOT profile recording failed (exit code " + recordResult.exitCode + ").");
                dumpFailureTail(recordResult.tail);
                return 1;
            }

            logInfo("Compiling AOT cache to " + AOT_CACHE_PATH + "...");
            final List<String> createCmd = buildCreateCommand(javaBin, jarPath);
            final PhaseResult createResult = runPhase(createCmd, false);
            if (createResult.exitCode != 0 || !Files.isRegularFile(aotCache) || fileSizeSafe(aotCache) <= 0L) {
                logError("AOT cache compilation failed (exit code " + createResult.exitCode + ").");
                dumpFailureTail(createResult.tail);
                return 1;
            }

            final double sizeMb = Files.size(aotCache) / (1024.0d * 1024.0d);
            logInfo(String.format(Locale.ROOT, "AOT cache generated successfully (%.1f MB).", sizeMb));
            logInfo("Run server using:");
            System.out.println("java -XX:AOTCache=" + AOT_CACHE_PATH + " -jar " + jarPath.getFileName() + " --nogui");
            return 0;
        } catch (final IOException ex) {
            logError("I/O error during AOT generation: " + ex.getMessage());
            return 1;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            logError("AOT generation interrupted.");
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
        }, "UltraCore-AOT-Warmup");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
    }

    static List<String> buildRecordCommand(final Path javaBin, final Path jarPath, final String[] originalArgs) {
        final List<String> cmd = new ArrayList<>();
        cmd.add(javaBin.toString());
        cmd.add("-XX:AOTMode=record");
        cmd.add("-XX:AOTConfiguration=" + AOT_CONF_PATH);
        cmd.add("-jar");
        cmd.add(jarPath.toString());
        for (final String arg : stripFlags(originalArgs, FLAG_GENERATE_AOT, FLAG_INTERNAL_WARMUP, "--nogui")) {
            cmd.add(arg);
        }
        cmd.add("--nogui");
        cmd.add(FLAG_INTERNAL_WARMUP);
        return cmd;
    }

    static List<String> buildCreateCommand(final Path javaBin, final Path jarPath) {
        return List.of(
            javaBin.toString(),
            "-XX:AOTMode=create",
            "-XX:AOTConfiguration=" + AOT_CONF_PATH,
            "-XX:AOTCache=" + AOT_CACHE_PATH,
            "-jar",
            jarPath.toString()
        );
    }

    static boolean isAotLogLine(final @Nullable String rawLine) {
        if (rawLine == null) {
            return false;
        }
        final String trimmed = rawLine.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        final String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("[warning][aot")
            || lower.contains("[info][aot")
            || lower.contains("[error][aot")
            || lower.contains("[debug][aot")
            || lower.contains("[aot]")) {
            return true;
        }
        return trimmed.startsWith("AOTConfiguration ")
            || trimmed.startsWith("Reading AOTConfiguration ")
            || trimmed.startsWith("AOTCache ")
            || trimmed.startsWith("An error has occurred")
            || trimmed.startsWith("Error: ")
            || trimmed.startsWith("Unrecognized VM option");
    }

    static String formatAotLogLine(final String trimmedLine, final LocalTime timestamp) {
        return formatAotLogLine(trimmedLine, timestamp, ANSI_SUPPORTED);
    }

    static String formatAotLogLine(final String trimmedLine, final LocalTime timestamp, final boolean ansiSupported) {
        final Matcher matcher = JVM_AOT_PREFIX.matcher(trimmedLine);
        if (matcher.matches()) {
            final String rawLevel = matcher.group(1).toLowerCase(Locale.ROOT);
            final String level = switch (rawLevel) {
                case "warning" -> "WARN";
                case "error" -> "ERROR";
                default -> "INFO";
            };
            final String body = matcher.group(2).stripLeading();
            return formatServerStyleLine(level, true, body, timestamp, ansiSupported);
        }
        if (trimmedLine.startsWith("An error has occurred")
            || trimmedLine.startsWith("Error: ")
            || trimmedLine.startsWith("Unrecognized VM option")) {
            return formatServerStyleLine("ERROR", true, trimmedLine, timestamp, ansiSupported);
        }
        return formatServerStyleLine("INFO", true, trimmedLine, timestamp, ansiSupported);
    }

    static String formatServerStyleLine(
        final String level,
        final boolean withAotTag,
        final String message,
        final LocalTime timestamp,
        final boolean ansiSupported
    ) {
        final String time = TIME_FORMATTER.format(timestamp);
        final String prefix = withAotTag ? "[AOT] " : "";
        if ("WARN".equals(level)) {
            if (!ansiSupported) {
                return "[" + time + "] WARN " + prefix + message;
            }
            return WARN_LINE_COLOR + "[" + time + "] " + WARN_BADGE + " " + prefix + message + ANSI_RESET;
        }
        if ("ERROR".equals(level)) {
            if (!ansiSupported) {
                return "[" + time + "] ERROR " + prefix + message;
            }
            return ERROR_LINE_COLOR + "[" + time + "] " + ERROR_BADGE + " " + prefix + message + ANSI_RESET;
        }
        return "[" + time + "] " + level + " " + prefix + message;
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

    private static PhaseResult runPhase(final List<String> command, final boolean filterServerNoise)
        throws IOException, InterruptedException {
        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        final Process process = pb.start();
        process.getOutputStream().close();

        final Deque<String> tail = new ArrayDeque<>(TAIL_BUFFER_LIMIT);
        boolean eulaMissing = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    if (tail.size() >= TAIL_BUFFER_LIMIT) {
                        tail.removeFirst();
                    }
                    tail.addLast(trimmed);
                    if (trimmed.contains("You need to agree to the EULA")) {
                        eulaMissing = true;
                    }
                    if (!filterServerNoise || isAotLogLine(trimmed)) {
                        System.out.println(formatAotLogLine(trimmed, LocalTime.now()));
                    }
                }
            }
        }

        final int exitCode = process.waitFor();
        return new PhaseResult(exitCode, tail, eulaMissing);
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
            if (Files.deleteIfExists(path)) {
                return;
            }
        } catch (final IOException ex) {
            logWarn("Could not delete temporary AOT file '" + path + "': " + ex.getMessage());
        }
    }

    private static void dumpFailureTail(final Deque<String> tail) {
        if (tail.isEmpty()) {
            return;
        }
        logError("Last child process output lines:");
        for (final String line : tail) {
            System.err.println("  " + line);
        }
    }

    private static boolean detectAnsiSupport() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        final @Nullable String term = System.getenv("TERM");
        if ("dumb".equalsIgnoreCase(term)) {
            return false;
        }
        final @Nullable String override = System.getProperty("terminal.ansi");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return System.console() != null;
    }

    private static void logInfo(final String message) {
        System.out.println(formatServerStyleLine("INFO", false, message, LocalTime.now(), ANSI_SUPPORTED));
    }

    private static void logWarn(final String message) {
        System.out.println(formatServerStyleLine("WARN", false, message, LocalTime.now(), ANSI_SUPPORTED));
    }

    private static void logError(final String message) {
        System.err.println(formatServerStyleLine("ERROR", false, message, LocalTime.now(), ANSI_SUPPORTED));
    }

    private record PhaseResult(int exitCode, Deque<String> tail, boolean eulaMissing) {
    }
}
