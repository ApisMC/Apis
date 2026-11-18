package io.papermc.paperclip;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Paperclip archive entry record with optional fast {@link PaperclipStamp} validation.
 *
 * @param hash expected SHA-256 digest of the entry
 * @param id   entry identifier from the Paperclip manifest
 * @param path relative extraction path within the section directory
 */
record FileEntry(byte[] hash, String id, String path) {

    static FileEntry[] parse(final BufferedReader reader) throws IOException {
        FileEntry[] entries = new FileEntry[8];
        int count = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            final FileEntry entry = parseLine(line);
            if (count == entries.length) {
                entries = Arrays.copyOf(entries, count * 2);
            }
            entries[count++] = entry;
        }
        return count == entries.length ? entries : Arrays.copyOf(entries, count);
    }

    private static FileEntry parseLine(final String line) {
        final String[] parts = line.split("\t");
        if (parts.length != 3) {
            throw new IllegalStateException("Malformed file entry line: " + line);
        }
        return new FileEntry(Util.fromHex(parts[0]), parts[1], parts[2]);
    }

    boolean extractFile(
        final Map<String, URL> urls,
        final PatchEntry[] patches,
        final String targetName,
        final @Nullable Path originalRoot,
        final String baseDir,
        final Path targetDir,
        final @Nullable PaperclipStamp stamp
    ) throws IOException {
        for (final PatchEntry patch : patches) {
            if (patch.location().equals(targetName) && patch.outputPath().equals(this.path)) {
                return false;
            }
        }

        final Path outputFile = targetDir.resolve(this.path);
        if (stamp != null && stamp.matches(targetName, this.path, this.hash, outputFile)) {
            urls.put(this.path, outputFile.toUri().toURL());
            return false;
        }

        if (Files.exists(outputFile) && Util.isFileValid(outputFile, this.hash)) {
            urls.put(this.path, outputFile.toUri().toURL());
            return true;
        }

        final String resourcePath = Util.endingSlash(baseDir) + this.path;
        InputStream resourceStream = FileEntry.class.getResourceAsStream(resourcePath);
        if (resourceStream == null) {
            if (originalRoot == null) {
                throw new IllegalStateException(this.path + " not found in jar, and no original jar provided");
            }
            final Path fallback = originalRoot.resolve(resourcePath);
            if (Files.notExists(fallback)) {
                throw new IllegalStateException(this.path + " not found in jar or original jar");
            }
            resourceStream = Files.newInputStream(fallback);
        }

        if (!Files.isDirectory(outputFile.getParent())) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.deleteIfExists(outputFile);

        try (
            InputStream stream = resourceStream;
            ReadableByteChannel source = Channels.newChannel(stream);
            FileChannel channel = FileChannel.open(
                outputFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        ) {
            channel.transferFrom(source, 0L, Long.MAX_VALUE);
        }

        if (!Util.isFileValid(outputFile, this.hash)) {
            throw new IllegalStateException("Hash check failed for extracted file " + outputFile);
        }

        urls.put(this.path, outputFile.toUri().toURL());
        return true;
    }
}
