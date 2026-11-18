package com.velocitypowered.natives.util;

import com.google.common.collect.ImmutableList;
import com.velocitypowered.natives.NativeSetupException;
import com.velocitypowered.natives.compression.JavaVelocityCompressor;
import com.velocitypowered.natives.compression.LibdeflateVelocityCompressor;
import com.velocitypowered.natives.compression.VelocityCompressorFactory;
import com.velocitypowered.natives.encryption.JavaVelocityCipher;
import com.velocitypowered.natives.encryption.NativeVelocityCipher;
import com.velocitypowered.natives.encryption.VelocityCipherFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Overridden Velocity native loader that caches extracted native binaries persistently
 * under {@code cache/natives/} and loads {@link #cipher} lazily so offline/proxy servers
 * avoid unnecessary cipher unpacking and dlopen overhead.
 */
public final class Natives {

    // CHECKSTYLE.OFF: ConstantName
    public static final NativeCodeLoader<VelocityCompressorFactory> compress = new NativeCodeLoader<>(
        ImmutableList.of(
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_X86_64,
                copyAndLoadNative("/linux_x86_64/velocity-compress.so"),
                "libdeflate (Linux x86_64)",
                LibdeflateVelocityCompressor.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_X86_64_MUSL,
                copyAndLoadNative("/linux_x86_64/velocity-compress-musl.so"),
                "libdeflate (Linux x86_64, musl)",
                LibdeflateVelocityCompressor.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_AARCH64,
                copyAndLoadNative("/linux_aarch64/velocity-compress.so"),
                "libdeflate (Linux aarch64)",
                LibdeflateVelocityCompressor.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_AARCH64_MUSL,
                copyAndLoadNative("/linux_aarch64/velocity-compress-musl.so"),
                "libdeflate (Linux aarch64, musl)",
                LibdeflateVelocityCompressor.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.MACOS_AARCH64,
                copyAndLoadNative("/macos_arm64/velocity-compress.dylib"),
                "libdeflate (macOS ARM64 / Apple Silicon)",
                LibdeflateVelocityCompressor.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeCodeLoader.ALWAYS,
                () -> { },
                "Java",
                JavaVelocityCompressor.FACTORY
            )
        )
    );

    public static final NativeCodeLoader<VelocityCipherFactory> cipher = new NativeCodeLoader<>(
        () -> ImmutableList.of(
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_X86_64,
                copyAndLoadNative("/linux_x86_64/velocity-cipher.so"),
                "OpenSSL local (Linux x86_64)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_X86_64,
                copyAndLoadNative("/linux_x86_64/velocity-cipher-ossl30x.so"),
                "OpenSSL 3.x.x (Linux x86_64)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_X86_64,
                copyAndLoadNative("/linux_x86_64/velocity-cipher-ossl11x.so"),
                "OpenSSL 1.1.x (Linux x86_64)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_X86_64_MUSL,
                copyAndLoadNative("/linux_x86_64/velocity-cipher-ossl30x-musl.so"),
                "OpenSSL 3.x.x (Linux x86_64, musl)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_AARCH64,
                copyAndLoadNative("/linux_aarch64/velocity-cipher.so"),
                "OpenSSL local (Linux aarch64)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_AARCH64,
                copyAndLoadNative("/linux_aarch64/velocity-cipher-ossl30x.so"),
                "OpenSSL 3.x.x (Linux aarch64)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_AARCH64,
                copyAndLoadNative("/linux_aarch64/velocity-cipher-ossl11x.so"),
                "OpenSSL 1.1.x (Linux aarch64)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.LINUX_AARCH64_MUSL,
                copyAndLoadNative("/linux_aarch64/velocity-cipher-ossl30x-musl.so"),
                "OpenSSL 3.x.x (Linux aarch64, musl)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeConstraints.MACOS_AARCH64,
                copyAndLoadNative("/macos_arm64/velocity-cipher.dylib"),
                "native (macOS ARM64 / Apple Silicon)",
                NativeVelocityCipher.FACTORY
            ),
            new NativeCodeLoader.Variant<>(
                NativeCodeLoader.ALWAYS,
                () -> { },
                "Java",
                JavaVelocityCipher.FACTORY
            )
        )
    );
    // CHECKSTYLE.ON: ConstantName

    private static final String DEFAULT_NATIVES_DIR = "cache/natives";

    private Natives() {
        throw new AssertionError();
    }

    private static Runnable copyAndLoadNative(final String resourcePath) {
        return () -> {
            try {
                final String relative = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
                final Path cacheRoot = resolveNativesCacheDir();
                final Path target = cacheRoot.resolve(relative).normalize();
                if (!target.startsWith(cacheRoot)) {
                    throw new IOException("Invalid native resource path: " + resourcePath);
                }
                Files.createDirectories(target.getParent());

                try (InputStream in = Natives.class.getResourceAsStream(resourcePath)) {
                    if (in == null) {
                        throw new IllegalStateException("Could not find native library " + resourcePath);
                    }
                    final byte[] bytes = in.readAllBytes();
                    if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                        final Path tmp = Files.createTempFile(target.getParent(), "velocity-", ".tmp");
                        try {
                            Files.write(tmp, bytes);
                            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                        } finally {
                            Files.deleteIfExists(tmp);
                        }
                    }
                }

                try {
                    System.load(target.toAbsolutePath().toString());
                } catch (final UnsatisfiedLinkError ex) {
                    throw new NativeSetupException("Unable to load native library " + target.toAbsolutePath(), ex);
                }
            } catch (final IOException ex) {
                throw new NativeSetupException("Unable to copy natives", ex);
            }
        };
    }

    private static Path resolveNativesCacheDir() {
        final String override = System.getProperty("velocity.natives-tmpdir");
        final Path dir = override != null && !override.isBlank()
            ? Path.of(override.trim())
            : Path.of(DEFAULT_NATIVES_DIR);
        return dir.toAbsolutePath().normalize();
    }
}
