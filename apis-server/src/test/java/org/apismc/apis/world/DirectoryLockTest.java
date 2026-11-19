// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import net.minecraft.util.DirectoryLock;
import org.apismc.apis.config.ApisConfig;
import org.apismc.apis.feature.FeatureRegistry;
import org.apismc.apis.hooks.ApisHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryLockTest {

    @AfterEach
    void tearDown() {
        FeatureRegistry.resetForTesting();
    }

    @Test
    void memoryOnlyWorldRemovesLeftoverLockAndReturnsValidNoOpLock(final @TempDir Path tempDir) throws IOException {
        final ApisConfig cfg = new ApisConfig();
        cfg.storage.worldSaving = false;
        FeatureRegistry.applyEarly(cfg);

        final Path worldDir = tempDir.resolve("arena");
        Files.createDirectories(worldDir);
        final Path lockPath = worldDir.resolve(DirectoryLock.LOCK_FILE);
        Files.writeString(lockPath, "stale");

        try (DirectoryLock lock = DirectoryLock.create(worldDir)) {
            assertTrue(lock.isValid());
            assertFalse(Files.exists(lockPath));
            assertFalse(DirectoryLock.isLocked(worldDir));
        }
    }

    @Test
    void memoryOnlyLockBecomesInvalidAfterClose(final @TempDir Path tempDir) throws IOException {
        final ApisConfig cfg = new ApisConfig();
        cfg.storage.worldSaving = false;
        FeatureRegistry.applyEarly(cfg);

        final Path worldDir = tempDir.resolve("lobby");
        final DirectoryLock lock = DirectoryLock.create(worldDir);
        assertTrue(lock.isValid());
        lock.close();
        assertFalse(lock.isValid());
    }

    @Test
    void forciblyBreaksStaleReadOnlyOrOverlappingLockWhenWorldSavingEnabled(final @TempDir Path tempDir)
        throws IOException {
        final ApisConfig cfg = new ApisConfig();
        cfg.storage.worldSaving = true;
        FeatureRegistry.applyEarly(cfg);

        final Path worldDir = tempDir.resolve("hub");
        Files.createDirectories(worldDir);
        final Path lockPath = worldDir.resolve(DirectoryLock.LOCK_FILE);
        Files.writeString(lockPath, "☃\n999999999\n");

        try {
            Files.setPosixFilePermissions(lockPath, Set.of(PosixFilePermission.OWNER_READ));
        } catch (UnsupportedOperationException _) {
            // Non-POSIX filesystem fallback
        }

        try (DirectoryLock lock = DirectoryLock.create(worldDir)) {
            assertTrue(lock.isValid());
            final String content = Files.readString(lockPath);
            assertTrue(content.contains(Long.toString(ProcessHandle.current().pid())));
        }

        // Overlapping lock within same JVM should be broken as stale if left open accidentally
        try (
            FileChannel existingChannel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            );
            FileLock _ = existingChannel.tryLock();
            DirectoryLock recovered = DirectoryLock.create(worldDir)
        ) {
            assertTrue(recovered.isValid());
        }
    }

    @Test
    void respectsLiveExternalProcessLockAndBreaksDeadOrCurrentPidLocks(final @TempDir Path tempDir)
        throws Exception {
        final ApisConfig cfg = new ApisConfig();
        cfg.storage.worldSaving = true;
        FeatureRegistry.applyEarly(cfg);

        final Path worldDir = tempDir.resolve("livecheck");
        Files.createDirectories(worldDir);
        final Path lockPath = worldDir.resolve(DirectoryLock.LOCK_FILE);

        // Current PID (e.g., PID 1 inside container restart) must NOT block breaking a stale lock
        Files.writeString(lockPath, "☃\n" + ProcessHandle.current().pid() + "\n");
        assertFalse(ApisHooks.isSessionLockOwnerAlive(lockPath));

        // Plain vanilla marker without PID must be treated as stale/unknown
        Files.writeString(lockPath, "☃");
        assertFalse(ApisHooks.isSessionLockOwnerAlive(lockPath));

        // Spawn a live external child process to verify live-owner detection
        final Process liveChild = new ProcessBuilder("/bin/sh", "-c", "sleep 10").start();
        try {
            Files.writeString(lockPath, "☃\n" + liveChild.pid() + "\n");
            assertTrue(ApisHooks.isSessionLockOwnerAlive(lockPath));
            assertFalse(ApisHooks.tryBreakStaleSessionLock(worldDir, lockPath));
            assertEquals(true, Files.exists(lockPath));
        } finally {
            liveChild.destroyForcibly();
            liveChild.waitFor();
        }

        // Once the child process terminates, lock is detected as stale
        assertFalse(ApisHooks.isSessionLockOwnerAlive(lockPath));
        assertTrue(ApisHooks.tryBreakStaleSessionLock(worldDir, lockPath));
        assertFalse(Files.exists(lockPath));
    }

    @Test
    void throwsAlreadyLockedWhenLiveProcessHoldsFileLock(final @TempDir Path tempDir) throws Exception {
        final ApisConfig cfg = new ApisConfig();
        cfg.storage.worldSaving = true;
        FeatureRegistry.applyEarly(cfg);

        final Path worldDir = tempDir.resolve("contended");
        Files.createDirectories(worldDir);
        final Path lockPath = worldDir.resolve(DirectoryLock.LOCK_FILE);

        // Spawn a second JVM holding FileLock and writing its live PID into session.lock
        final Process holder = new ProcessBuilder("/bin/sh", "-c", "sleep 10").start();
        try {
            try (
                FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock _ = channel.tryLock()
            ) {
                channel.truncate(0L);
                channel.write(ByteBuffer.wrap(("☃\n" + holder.pid() + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
                assertThrows(DirectoryLock.LockException.class, () -> DirectoryLock.create(worldDir));
            }
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
    }
}
