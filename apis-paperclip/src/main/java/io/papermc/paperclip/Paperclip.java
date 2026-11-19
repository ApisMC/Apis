// Copyright (c) PaperMC contributors
// Modifications Copyright (c) 2026 ApisMC contributors
// SPDX-License-Identifier: MIT

package io.papermc.paperclip;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Drop-in Paperclip bootstrapper with fast {@code cache/.paperclip-stamp} verification.
 */
public final class Paperclip {

    private Paperclip() {
    }

    public static void main(final String[] args) {
        if (Path.of("").toAbsolutePath().toString().contains("!")) {
            System.err.println("Paperclip may not run in a directory containing '!'. Please rename the affected folder.");
            System.exit(1);
        }

        final URL[] classpathUrls = setupClasspath();
        final ClassLoader parentLoader = Paperclip.class.getClassLoader().getParent();
        final URLClassLoader classLoader = new URLClassLoader(classpathUrls, parentLoader);

        final String mainClassName = findMainClass();
        System.out.println("Starting " + mainClassName);

        final Thread runThread = new Thread(() -> {
            try {
                final Class<?> mainClass = Class.forName(mainClassName, true, classLoader);
                final Method mainMethod = mainClass.getMethod("main", String[].class);
                mainMethod.invoke(null, (Object) args);
            } catch (final InvocationTargetException ex) {
                throw Util.sneakyThrow(ex.getCause() != null ? ex.getCause() : ex);
            } catch (final Throwable t) {
                throw Util.sneakyThrow(t);
            }
        }, "ServerMain");
        runThread.setContextClassLoader(classLoader);
        runThread.start();
    }

    private static URL[] setupClasspath() {
        final Path repoDir = Path.of(System.getProperty("bundlerRepoDir", ""));
        final PatchEntry[] patches = findPatches();
        final DownloadContext downloadContext = findDownloadContext();
        if (patches.length > 0 && downloadContext == null) {
            throw new IllegalArgumentException("patches.list file found without a corresponding original-url file");
        }

        final Path originalJar;
        if (downloadContext != null) {
            try {
                downloadContext.download(repoDir);
            } catch (final IOException ex) {
                throw Util.fail("Failed to download original jar", ex);
            }
            originalJar = downloadContext.getOutputFile(repoDir);
        } else {
            originalJar = null;
        }

        final Map<String, Map<String, URL>> urls = extractAndApplyPatches(originalJar, patches, repoDir);
        if (Boolean.getBoolean("paperclip.patchonly")) {
            System.exit(0);
        }

        final Collection<URL> versionUrls = urls.get("versions").values();
        final Collection<URL> libraryUrls = urls.get("libraries").values();
        final URL[] empty = new URL[0];
        final URL[] classpathUrls = new URL[versionUrls.size() + libraryUrls.size()];
        System.arraycopy(versionUrls.toArray(empty), 0, classpathUrls, 0, versionUrls.size());
        System.arraycopy(libraryUrls.toArray(empty), 0, classpathUrls, versionUrls.size(), libraryUrls.size());
        return classpathUrls;
    }

    private static PatchEntry[] findPatches() {
        try (InputStream in = Paperclip.class.getResourceAsStream("/META-INF/patches.list")) {
            if (in == null) {
                return new PatchEntry[0];
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return PatchEntry.parse(reader);
            }
        } catch (final IOException ex) {
            throw Util.fail("Failed to read patches.list file", ex);
        }
    }

    private static @Nullable DownloadContext findDownloadContext() {
        final String text;
        try {
            text = Util.readResourceText("/META-INF/download-context");
        } catch (final IOException ex) {
            throw Util.fail("Failed to read download-context file", ex);
        }
        return DownloadContext.parseLine(text);
    }

    private static FileEntry @Nullable [] findVersionEntries() {
        return findFileEntries("versions.list");
    }

    private static FileEntry @Nullable [] findLibraryEntries() {
        return findFileEntries("libraries.list");
    }

    private static FileEntry @Nullable [] findFileEntries(final String name) {
        try (InputStream in = Paperclip.class.getResourceAsStream("/META-INF/" + name)) {
            if (in == null) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return FileEntry.parse(reader);
            }
        } catch (final IOException ex) {
            throw Util.fail("Failed to read " + name + " file", ex);
        }
    }

    private static String findMainClass() {
        try {
            final String mainClass = Util.readResourceText("/META-INF/main-class");
            if (mainClass == null) {
                throw new IllegalStateException("main-class file not found");
            }
            return mainClass.trim();
        } catch (final IOException ex) {
            throw Util.fail("Failed to read main-class file", ex);
        }
    }

    private static Map<String, Map<String, URL>> extractAndApplyPatches(
        final @Nullable Path originalJar,
        final PatchEntry[] patches,
        final Path repoDir
    ) {
        final FileEntry @Nullable [] versionEntries = findVersionEntries();
        final FileEntry @Nullable [] libraryEntries = findLibraryEntries();
        final String manifestHash = PaperclipStamp.computeManifestHash();
        final Path stampPath = PaperclipStamp.resolveStampPath(repoDir);
        final PaperclipStamp stamp = PaperclipStamp.tryLoad(stampPath, manifestHash);

        final Map<String, Map<String, URL>> urls = new HashMap<>();
        boolean needsStampWrite = false;

        try (FileSystem originalFs = originalJar == null ? null : FileSystems.newFileSystem(originalJar)) {
            final Path originalRoot = originalFs == null ? null : originalFs.getPath("/");
            final ExtractionResult extracted = extractFiles(patches, originalRoot, repoDir, versionEntries, libraryEntries, stamp);
            urls.putAll(extracted.urls());
            needsStampWrite |= extracted.needsStampWrite();

            if (patches.length > 0) {
                if (originalRoot == null) {
                    throw new IllegalStateException("patches.list requires an original jar");
                }
                needsStampWrite |= applyPatches(urls, patches, originalRoot, repoDir, stamp);
            }
        } catch (final IOException ex) {
            throw Util.fail("Failed to extract or patch jar files", ex);
        }

        if (stamp == null || needsStampWrite) {
            final Map<String, byte[]> expectedHashes = collectExpectedHashes(versionEntries, libraryEntries, patches);
            final Map<String, Map<String, URL>> stampUrls = buildStampUrls(urls, patches, repoDir);
            PaperclipStamp.writeAtomic(stampPath, manifestHash, expectedHashes, stampUrls);
        }
        return urls;
    }

    private static ExtractionResult extractFiles(
        final PatchEntry[] patches,
        final @Nullable Path originalRoot,
        final Path repoDir,
        final FileEntry @Nullable [] versionEntries,
        final FileEntry @Nullable [] libraryEntries,
        final @Nullable PaperclipStamp stamp
    ) throws IOException {
        final Map<String, Map<String, URL>> urls = new HashMap<>();
        boolean needsStampWrite = false;

        final Map<String, URL> versionsMap = new HashMap<>();
        urls.put("versions", versionsMap);
        needsStampWrite |= extractEntries(versionsMap, patches, originalRoot, repoDir, versionEntries, "versions", stamp);

        final Map<String, URL> librariesMap = new HashMap<>();
        urls.put("libraries", librariesMap);
        needsStampWrite |= extractEntries(librariesMap, patches, originalRoot, repoDir, libraryEntries, "libraries", stamp);

        return new ExtractionResult(urls, needsStampWrite);
    }

    private static boolean extractEntries(
        final Map<String, URL> urls,
        final PatchEntry[] patches,
        final @Nullable Path originalRoot,
        final Path repoDir,
        final FileEntry @Nullable [] entries,
        final String targetName,
        final @Nullable PaperclipStamp stamp
    ) throws IOException {
        if (entries == null) {
            return false;
        }
        final String baseResourceDir = "/META-INF/" + targetName;
        final Path outputDir = repoDir.resolve(targetName);
        boolean anyRevalidated = false;
        for (final FileEntry entry : entries) {
            anyRevalidated |= entry.extractFile(urls, patches, targetName, originalRoot, baseResourceDir, outputDir, stamp);
        }
        return anyRevalidated;
    }

    private static boolean applyPatches(
        final Map<String, Map<String, URL>> urls,
        final PatchEntry[] patches,
        final Path originalRoot,
        final Path repoDir,
        final @Nullable PaperclipStamp stamp
    ) {
        boolean anyPatched = false;
        try {
            final Method applyMethod = PatchEntry.class.getDeclaredMethod(
                "applyPatch",
                Map.class,
                Path.class,
                Path.class
            );
            applyMethod.setAccessible(true);
            for (final PatchEntry patch : patches) {
                final Path outputFile = repoDir.resolve(patch.location()).resolve(patch.outputPath());
                if (stamp != null && stamp.matches(patch.location(), patch.outputPath(), patch.outputHash(), outputFile)) {
                    urls.computeIfAbsent(patch.location(), _ -> new HashMap<>())
                        .put(patch.originalPath(), outputFile.toUri().toURL());
                    continue;
                }
                applyMethod.invoke(patch, urls, originalRoot, repoDir);
                anyPatched = true;
            }
        } catch (final Exception ex) {
            throw Util.fail("Failed to apply patches", ex);
        }
        return anyPatched;
    }

    private static Map<String, Map<String, URL>> buildStampUrls(
        final Map<String, Map<String, URL>> urls,
        final PatchEntry[] patches,
        final Path repoDir
    ) {
        if (patches.length == 0) {
            return urls;
        }
        final Map<String, Map<String, URL>> stampUrls = new HashMap<>();
        for (final Map.Entry<String, Map<String, URL>> entry : urls.entrySet()) {
            stampUrls.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        for (final PatchEntry patch : patches) {
            final Path outputFile = repoDir.resolve(patch.location()).resolve(patch.outputPath());
            try {
                stampUrls.computeIfAbsent(patch.location(), _ -> new HashMap<>())
                    .put(patch.outputPath(), outputFile.toUri().toURL());
            } catch (IOException _) {
                // Non-fatal: next startup falls back to SHA-256 verification
            }
        }
        return stampUrls;
    }

    private static Map<String, byte[]> collectExpectedHashes(
        final FileEntry @Nullable [] versionEntries,
        final FileEntry @Nullable [] libraryEntries,
        final PatchEntry[] patches
    ) {
        final Map<String, byte[]> hashes = new HashMap<>();
        if (versionEntries != null) {
            for (final FileEntry entry : versionEntries) {
                hashes.put(PaperclipStamp.key("versions", entry.path()), entry.hash());
            }
        }
        if (libraryEntries != null) {
            for (final FileEntry entry : libraryEntries) {
                hashes.put(PaperclipStamp.key("libraries", entry.path()), entry.hash());
            }
        }
        for (final PatchEntry patch : patches) {
            hashes.put(PaperclipStamp.key(patch.location(), patch.outputPath()), patch.outputHash());
        }
        return hashes;
    }

    private record ExtractionResult(Map<String, Map<String, URL>> urls, boolean needsStampWrite) {
    }
}
