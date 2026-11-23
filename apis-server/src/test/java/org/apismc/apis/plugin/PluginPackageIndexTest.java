// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.plugin;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.entrypoint.classloader.group.SimpleListPluginClassLoaderGroup;
import io.papermc.paper.plugin.provider.classloader.ClassLoaderAccess;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import io.papermc.paper.plugin.provider.classloader.PluginPackageIndex;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class PluginPackageIndexTest {

    @Test
    void indexesPrimaryMultiReleaseLibraryAndAddedUrls(final @TempDir Path tempDir) throws Exception {
        final Path pluginJarPath = writeDummyJar(
            tempDir.resolve("plugin.jar"),
            "com/example/plugin/Main.class",
            "RootClass.class",
            "META-INF/MANIFEST.MF",
            "META-INF/services/com.example.Service",
            "META-INF/versions/21/com/example/mr/ModernHelper.class",
            "assets/plugin/config.yml"
        );
        final Path libJarPath = writeDummyJar(
            tempDir.resolve("library.jar"),
            "org/example/lib/FastMath.class"
        );
        final Path addedJarPath = writeDummyJar(
            tempDir.resolve("runtime-added.jar"),
            "com/example/dynamic/RuntimeCodec.class"
        );

        try (
            JarFile pluginJar = new JarFile(pluginJarPath.toFile());
            URLClassLoader libLoader = new URLClassLoader(new URL[] {libJarPath.toUri().toURL()})
        ) {
            final PluginPackageIndex index = PluginPackageIndex.fromJar(pluginJar, libLoader);

            assertTrue(index.mayContainPackage("com.example.plugin", false));
            assertTrue(index.mayContainPackage("", false));
            assertTrue(index.mayContainPackage("com.example.mr", false));
            assertFalse(index.mayContainPackage("META-INF.services", false));
            assertFalse(index.mayContainPackage("assets.plugin", false));
            assertFalse(index.mayContainPackage("org.unrelated.pkg", true));

            // Library packages must only match when checkLibraries == true
            assertFalse(index.mayContainPackage("org.example.lib", false));
            assertTrue(index.mayContainPackage("org.example.lib", true));

            // Dynamic URL additions (e.g., Libby URLClassLoader.addURL) must update index immediately
            assertFalse(index.mayContainPackage("com.example.dynamic", true));
            index.indexAddedUrl(addedJarPath.toUri().toURL());
            assertTrue(index.mayContainPackage("com.example.dynamic", false));
        }
    }

    @Test
    void groupSkipsLoadClassCallsForUnrelatedPackages() {
        final AtomicInteger requesterLookups = new AtomicInteger();
        final AtomicInteger skippedLookups = new AtomicInteger();
        final AtomicInteger matchingLookups = new AtomicInteger();

        final ConfiguredPluginClassLoader requester = new StubPluginClassLoader("com.requester", requesterLookups, null);
        final ConfiguredPluginClassLoader unrelated = new StubPluginClassLoader("com.other", skippedLookups, null);
        final ConfiguredPluginClassLoader target = new StubPluginClassLoader("com.target.api", matchingLookups, String.class);

        final SimpleListPluginClassLoaderGroup group = new SimpleListPluginClassLoaderGroup() {
            @Override
            public ClassLoaderAccess getAccess() {
                return ignored -> true;
            }
        };
        group.add(unrelated);
        group.add(target);

        final @Nullable Class<?> resolved = group.getClassByName("com.target.api.Exported", false, requester);
        assertSame(String.class, resolved);
        assertEquals(0, skippedLookups.get(), "Unrelated classloader must be skipped without calling loadClass");
        assertEquals(1, matchingLookups.get());

        final @Nullable Class<?> missing = group.getClassByName("org.nonexistent.Missing", false, requester);
        assertNull(missing);
    }

    @Test
    void safeFallbackResolvesDynamicUnindexedClassesAndRecordsPackage(final @TempDir Path tempDir) throws Exception {
        final Path unrelatedJarPath = writeDummyJar(tempDir.resolve("unrelated.jar"), "com/unrelated/Alpha.class");
        final Path targetJarPath = writeDummyJar(tempDir.resolve("target.jar"), "com/staticpkg/StaticOnly.class");

        try (
            JarFile unrelatedJar = new JarFile(unrelatedJarPath.toFile());
            JarFile targetJar = new JarFile(targetJarPath.toFile())
        ) {
            final AtomicInteger unrelatedLookups = new AtomicInteger();
            final AtomicInteger targetLookups = new AtomicInteger();
            final StubPluginClassLoader requester = new StubPluginClassLoader("com.requester", new AtomicInteger(), null);
            final IndexedDynamicClassLoader unrelated = new IndexedDynamicClassLoader(
                PluginPackageIndex.fromJar(unrelatedJar, null),
                unrelatedLookups,
                Map.of()
            );
            final IndexedDynamicClassLoader target = new IndexedDynamicClassLoader(
                PluginPackageIndex.fromJar(targetJar, null),
                targetLookups,
                Map.of(
                    "com.dynamic.bytebuddy.Generated$1", String.class,
                    "com.dynamic.bytebuddy.Generated$2", Integer.class
                )
            );

            final SimpleListPluginClassLoaderGroup group = new SimpleListPluginClassLoaderGroup() {
                @Override
                public ClassLoaderAccess getAccess() {
                    return ignored -> true;
                }
            };
            group.add(unrelated);
            group.add(target);

            // First lookup: "com.dynamic.bytebuddy" is absent from all JAR indexes -> Pass 2 legacy scan must find it
            assertSame(String.class, group.getClassByName("com.dynamic.bytebuddy.Generated$1", false, requester));
            assertEquals(1, unrelatedLookups.get(), "Fallback scan must check skipped loaders safely");
            assertEquals(1, targetLookups.get());

            // Second lookup in the same dynamic package: must resolve via Pass 1 and skip unrelated loader
            assertSame(Integer.class, group.getClassByName("com.dynamic.bytebuddy.Generated$2", false, requester));
            assertEquals(1, unrelatedLookups.get(), "Dynamic package must now resolve on indexed fast pass");
            assertEquals(2, targetLookups.get());
        }
    }

    @Test
    void splitPackagesPreservePaperOrderWithoutOverwritingEachOther() {
        final AtomicInteger requesterLookups = new AtomicInteger();
        final AtomicInteger firstLookups = new AtomicInteger();
        final AtomicInteger secondLookups = new AtomicInteger();

        final ConfiguredPluginClassLoader requester = new StubPluginClassLoader("com.requester", requesterLookups, null);
        final Map<String, Class<?>> firstClasses = Map.of(
            "com.shared.shaded.InBoth", String.class,
            "com.shared.shaded.OnlyInFirst", Long.class
        );
        final Map<String, Class<?>> secondClasses = Map.of(
            "com.shared.shaded.InBoth", Integer.class,
            "com.shared.shaded.OnlyInSecond", Double.class
        );

        final ConfiguredPluginClassLoader first = new MapBackedStubLoader("com.shared.shaded", firstLookups, firstClasses);
        final ConfiguredPluginClassLoader second = new MapBackedStubLoader("com.shared.shaded", secondLookups, secondClasses);

        final SimpleListPluginClassLoaderGroup group = new SimpleListPluginClassLoaderGroup() {
            @Override
            public ClassLoaderAccess getAccess() {
                return ignored -> true;
            }
        };
        group.add(first);
        group.add(second);

        assertSame(String.class, group.getClassByName("com.shared.shaded.InBoth", false, requester));
        assertEquals(1, firstLookups.get());
        assertEquals(0, secondLookups.get(), "First loader in Paper order must win split-package overlap");

        assertSame(Double.class, group.getClassByName("com.shared.shaded.OnlyInSecond", false, requester));
        assertEquals(2, firstLookups.get());
        assertEquals(1, secondLookups.get(), "Second loader must still resolve non-overlapping classes in shared package");
    }

    @Test
    void concurrentAddUrlAndLibrarySyncAreThreadSafeWithConservativeFallback(final @TempDir Path tempDir) throws Exception {
        final Path primaryJarPath = writeDummyJar(tempDir.resolve("primary.jar"), "com/plugin/Core.class");
        final List<Path> extraJars = new ArrayList<>();
        final List<Path> libJars = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            extraJars.add(writeDummyJar(tempDir.resolve("added-" + i + ".jar"), "com/added/p" + i + "/Item.class"));
            libJars.add(writeDummyJar(tempDir.resolve("lib-" + i + ".jar"), "org/lib/p" + i + "/LibItem.class"));
        }

        try (
            JarFile primaryJar = new JarFile(primaryJarPath.toFile());
            MutableUrlClassLoader libLoader = new MutableUrlClassLoader(new URL[] {libJars.getFirst().toUri().toURL()})
        ) {
            final PluginPackageIndex index = PluginPackageIndex.fromJar(primaryJar, libLoader);
            final int threads = 8;
            final CountDownLatch ready = new CountDownLatch(threads);
            final CountDownLatch go = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                final List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(go.await(5, TimeUnit.SECONDS));
                        for (int iter = 0; iter < 50; iter++) {
                            final int idx = (tid + iter) % extraJars.size();
                            index.indexAddedUrl(extraJars.get(idx).toUri().toURL());
                            libLoader.appendUrl(libJars.get(idx).toUri().toURL());
                            assertTrue(index.mayContainPackage("com.plugin", true));
                            index.mayContainPackage("com.added.p" + idx, false);
                            index.mayContainPackage("org.lib.p" + idx, true);
                        }
                        return null;
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                go.countDown();
                for (final Future<?> future : futures) {
                    future.get(15, TimeUnit.SECONDS);
                }
            }

            for (int i = 0; i < extraJars.size(); i++) {
                assertTrue(index.mayContainPackage("com.added.p" + i, false));
                assertTrue(index.mayContainPackage("org.lib.p" + i, true));
            }

            // Null or non-file URLs must flip safely to conservative fallback rather than throwing
            final URL nonFileUrl = URI.create("https://example.invalid/plugin.jar").toURL();
            index.indexAddedUrl(nonFileUrl);
            assertTrue(index.mayContainPackage("com.anything.Missing", false));
        }

        // Null primary JAR must also construct safely with conservative fallback
        final PluginPackageIndex nullJarIndex = PluginPackageIndex.fromJar(null, null);
        assertTrue(nullJarIndex.mayContainPackage("any.pkg", true));
    }

    private static Path writeDummyJar(final Path jarPath, final String... entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (final String entry : entries) {
                out.putNextEntry(new JarEntry(entry));
                out.write(new byte[] {1, 2, 3, 4});
                out.closeEntry();
            }
        }
        return jarPath;
    }

    private static final class MutableUrlClassLoader extends URLClassLoader {
        private MutableUrlClassLoader(final URL[] urls) {
            super(urls, null);
        }

        private synchronized void appendUrl(final URL url) {
            super.addURL(url);
        }
    }

    private static final class IndexedDynamicClassLoader extends ClassLoader implements ConfiguredPluginClassLoader {
        private final PluginPackageIndex index;
        private final AtomicInteger lookupCounter;
        private final Map<String, Class<?>> classes = new ConcurrentHashMap<>();

        private IndexedDynamicClassLoader(
            final PluginPackageIndex index,
            final AtomicInteger lookupCounter,
            final Map<String, Class<?>> dynamicClasses
        ) {
            super(null);
            this.index = index;
            this.lookupCounter = lookupCounter;
            this.classes.putAll(dynamicClasses);
        }

        @Override
        public PluginMeta getConfiguration() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayContainClassInPackage(final String packageName, final boolean checkLibraries) {
            return this.index.mayContainPackage(packageName, checkLibraries);
        }

        @Override
        public void recordDynamicPackage(final String packageName, final @Nullable ClassLoader resolvedClassLoader) {
            this.index.recordPackage(packageName, resolvedClassLoader);
        }

        @Override
        public Class<?> loadClass(
            final String name,
            final boolean resolve,
            final boolean checkGlobal,
            final boolean checkLibraries
        ) throws ClassNotFoundException {
            this.lookupCounter.incrementAndGet();
            final @Nullable Class<?> resolved = this.classes.get(name);
            if (resolved != null) {
                return resolved;
            }
            throw new ClassNotFoundException(name);
        }

        @Override
        public void init(final JavaPlugin plugin) {
        }

        @Override
        public @Nullable JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public @Nullable PluginClassLoaderGroup getGroup() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static final class MapBackedStubLoader implements ConfiguredPluginClassLoader {
        private final String ownedPackage;
        private final AtomicInteger lookupCounter;
        private final Map<String, Class<?>> classes;

        private MapBackedStubLoader(
            final String ownedPackage,
            final AtomicInteger lookupCounter,
            final Map<String, Class<?>> classes
        ) {
            this.ownedPackage = ownedPackage;
            this.lookupCounter = lookupCounter;
            this.classes = classes;
        }

        @Override
        public PluginMeta getConfiguration() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayContainClassInPackage(final String packageName, final boolean checkLibraries) {
            return this.ownedPackage.equals(packageName);
        }

        @Override
        public Class<?> loadClass(
            final String name,
            final boolean resolve,
            final boolean checkGlobal,
            final boolean checkLibraries
        ) throws ClassNotFoundException {
            this.lookupCounter.incrementAndGet();
            final @Nullable Class<?> resolved = this.classes.get(name);
            if (resolved != null) {
                return resolved;
            }
            throw new ClassNotFoundException(name);
        }

        @Override
        public void init(final JavaPlugin plugin) {
        }

        @Override
        public @Nullable JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public @Nullable PluginClassLoaderGroup getGroup() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static final class StubPluginClassLoader implements ConfiguredPluginClassLoader {
        private final String ownedPackage;
        private final AtomicInteger lookupCounter;
        private final @Nullable Class<?> result;

        private StubPluginClassLoader(
            final String ownedPackage,
            final AtomicInteger lookupCounter,
            final @Nullable Class<?> result
        ) {
            this.ownedPackage = ownedPackage;
            this.lookupCounter = lookupCounter;
            this.result = result;
        }

        @Override
        public PluginMeta getConfiguration() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayContainClassInPackage(final String packageName, final boolean checkLibraries) {
            return this.ownedPackage.equals(packageName);
        }

        @Override
        public Class<?> loadClass(
            final String name,
            final boolean resolve,
            final boolean checkGlobal,
            final boolean checkLibraries
        ) throws ClassNotFoundException {
            this.lookupCounter.incrementAndGet();
            if (this.result != null && name.startsWith(this.ownedPackage + ".")) {
                return this.result;
            }
            throw new ClassNotFoundException(name);
        }

        @Override
        public void init(final JavaPlugin plugin) {
        }

        @Override
        public @Nullable JavaPlugin getPlugin() {
            return null;
        }

        @Override
        public @Nullable PluginClassLoaderGroup getGroup() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
