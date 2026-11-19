// Copyright (c) PaperMC contributors
// Modifications Copyright (c) 2026 ApisMC contributors
// SPDX-License-Identifier: MIT

package io.papermc.paperclip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Paperclip I/O and SHA-256 verification helpers (binary-compatible with upstream Paperclip).
 */
final class Util {

    public static MessageDigest sha256Digest = getSha256Digest();

    private Util() {
    }

    private static MessageDigest getSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw fail("Could not create hashing instance", ex);
        }
    }

    static byte[] readBytes(final Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException ex) {
            throw fail("Failed to read file " + file, ex);
        }
    }

    static byte[] readFully(final InputStream in) throws IOException {
        return in.readAllBytes();
    }

    static @Nullable String readResourceText(final String path) throws IOException {
        try (InputStream in = Util.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static boolean isDataValid(final byte[] data, final byte[] hash) {
        return Arrays.equals(hash, sha256(data));
    }

    static boolean isFileValid(final Path file, final byte[] hash) {
        try (InputStream in = Files.newInputStream(file)) {
            final MessageDigest digest = getSha256Digest();
            final byte[] buffer = new byte[65_536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return Arrays.equals(hash, digest.digest());
        } catch (IOException _) {
            return false;
        }
    }

    static byte[] sha256(final byte[] data) {
        return getSha256Digest().digest(data);
    }

    static byte[] fromHex(final String s) {
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("Length of hex string " + s + " must be divisible by two");
        }
        try {
            final byte[] bytes = new byte[s.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                final char left = s.charAt(i * 2);
                final char right = s.charAt(i * 2 + 1);
                bytes[i] = (byte) ((getHexValue(left) << 4) | (getHexValue(right) & 0x0F));
            }
            return bytes;
        } catch (final Exception ex) {
            throw new IllegalArgumentException("Cannot convert non-hex string " + s + " to bytes", ex);
        }
    }

    static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >>> 4) & 0x0F, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }

    private static int getHexValue(final char c) {
        final int val = Character.digit(c, 16);
        if (val < 0) {
            throw new IllegalArgumentException("Invalid hex char: " + c);
        }
        return val;
    }

    static RuntimeException fail(final String message, final @Nullable Throwable err) {
        System.err.println(message);
        if (err != null) {
            err.printStackTrace();
        }
        System.exit(1);
        throw new InternalError();
    }

    @SuppressWarnings("unchecked")
    static <X extends Throwable> RuntimeException sneakyThrow(final Throwable ex) throws X {
        throw (X) ex;
    }

    static String endingSlash(final String dir) {
        return dir.endsWith("/") ? dir : dir + "/";
    }
}
