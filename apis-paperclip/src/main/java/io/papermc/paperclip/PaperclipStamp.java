// SPDX-License-Identifier: GPL-3.0-only

package io.papermc.paperclip;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Persistent stamp index ({@code cache/.paperclip-stamp}) caching verified JAR sizes,
 * modification timestamps, and manifest hashes to avoid re-hashing ~100 MB on every startup.
 */
final class PaperclipStamp {

    private static final String STAMP_FILE_NAME = ".paperclip-stamp";
    private static final String FORMAT_HEADER = "v1";

    private final String manifestHash;
    private final Map<String, StampedFile> entries;

    private PaperclipStamp(final String manifestHash, final Map<String, StampedFile> entries) {
        this.manifestHash = manifestHash;
        this.entries = entries;
    }

    static Path resolveStampPath(final Path repoDir) {
        return repoDir.resolve("cache").resolve(STAMP_FILE_NAME);
    }

    static String computeManifestHash() {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateManifestResource(digest, "/META-INF/versions.list");
            updateManifestResource(digest, "/META-INF/libraries.list");
            updateManifestResource(digest, "/META-INF/patches.list");
            updateManifestResource(digest, "/META-INF/download-context");
            return Util.toHex(digest.digest());
        } catch (Exception _) {
            return "";
        }
    }

    private static void updateManifestResource(final MessageDigest digest, final String resourcePath) throws IOException {
        digest.update(resourcePath.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        final String text = Util.readResourceText(resourcePath);
        if (text != null) {
            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            digest.update(bytes);
        }
        digest.update((byte) 0);
    }

    static @Nullable PaperclipStamp tryLoad(final Path stampPath, final String expectedManifestHash) {
        if (expectedManifestHash.isEmpty() || !Files.isRegularFile(stampPath)) {
            return null;
        }
        try {
            final List<String> lines = Files.readAllLines(stampPath, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            final String[] header = lines.getFirst().split("\t", -1);
            if (header.length != 2 || !FORMAT_HEADER.equals(header[0]) || !expectedManifestHash.equals(header[1])) {
                return null;
            }
            final Map<String, StampedFile> map = new HashMap<>(Math.max(16, lines.size() * 2));
            for (int i = 1; i < lines.size(); i++) {
                final String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                final String[] parts = line.split("\t", -1);
                if (parts.length != 5) {
                    return null;
                }
                final String key = key(parts[0], parts[1]);
                final long size = Long.parseLong(parts[2]);
                final long mtime = Long.parseLong(parts[3]);
                final byte[] sha256 = Util.fromHex(parts[4]);
                map.put(key, new StampedFile(size, mtime, sha256));
            }
            return new PaperclipStamp(expectedManifestHash, map);
        } catch (Exception _) {
            return null;
        }
    }

    boolean matches(final String section, final String relativePath, final byte[] expectedHash, final Path fileOnDisk) {
        final StampedFile stamped = this.entries.get(key(section, relativePath));
        if (stamped == null || !Arrays.equals(stamped.hash(), expectedHash)) {
            return false;
        }
        try {
            final BasicFileAttributes attrs = Files.readAttributes(fileOnDisk, BasicFileAttributes.class);
            return attrs.isRegularFile()
                && attrs.size() == stamped.size()
                && attrs.lastModifiedTime().toMillis() == stamped.mtimeMillis();
        } catch (IOException _) {
            return false;
        }
    }

    static void writeAtomic(
        final Path stampPath,
        final String manifestHash,
        final Map<String, byte[]> expectedHashes,
        final Map<String, Map<String, URL>> urls
    ) {
        if (manifestHash.isEmpty()) {
            return;
        }
        final StringBuilder out = new StringBuilder(4096);
        out.append(FORMAT_HEADER).append('\t').append(manifestHash).append('\n');

        for (final Map.Entry<String, Map<String, URL>> sectionEntry : urls.entrySet()) {
            final String section = sectionEntry.getKey();
            for (final Map.Entry<String, URL> fileEntry : sectionEntry.getValue().entrySet()) {
                final String relativePath = fileEntry.getKey();
                final byte[] hash = expectedHashes.get(key(section, relativePath));
                if (hash == null) {
                    continue;
                }
                try {
                    final Path onDisk = Path.of(fileEntry.getValue().toURI());
                    final BasicFileAttributes attrs = Files.readAttributes(onDisk, BasicFileAttributes.class);
                    if (!attrs.isRegularFile()) {
                        continue;
                    }
                    out.append(section)
                        .append('\t')
                        .append(relativePath)
                        .append('\t')
                        .append(attrs.size())
                        .append('\t')
                        .append(attrs.lastModifiedTime().toMillis())
                        .append('\t')
                        .append(Util.toHex(hash))
                        .append('\n');
                } catch (Exception _) {
                    // Skip entries that cannot be statted cleanly
                }
            }
        }

        try {
            final Path parent = stampPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            final Path tmp = Files.createTempFile(parent, "paperclip-stamp-", ".tmp");
            try {
                Files.writeString(tmp, out.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, stampPath, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException _) {
            // Non-fatal: next startup falls back to SHA-256 verification
        }
    }

    static String key(final String section, final String relativePath) {
        return section + '\0' + relativePath;
    }

    private record StampedFile(long size, long mtimeMillis, byte[] hash) {
    }
}
