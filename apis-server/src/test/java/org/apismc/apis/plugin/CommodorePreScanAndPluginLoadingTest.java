// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.plugin;

import com.google.common.base.Predicates;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Server;
import org.bukkit.Warning;
import org.bukkit.craftbukkit.util.ApiVersion;
import org.bukkit.craftbukkit.util.Commodore;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
class CommodorePreScanAndPluginLoadingTest {

    @Test
    void skipsNonBukkitShadedClassesWithZeroAsmRewrite() {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/shaded/vendor/FastCache", null, "java/lang/Object", null);
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "size", "()I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        final byte[] raw = cw.toByteArray();
        assertFalse(Commodore.mayNeedConversion(raw, ApiVersion.CURRENT, Set.of("enum-compatibility-mode")));

        final Commodore commodore = new Commodore(Predicates.alwaysTrue());
        final byte[] converted = commodore.convert(raw, "TestPlugin", ApiVersion.CURRENT, Set.of("enum-compatibility-mode"));
        assertSame(raw, converted, "Non-Bukkit class bytes must be returned unchanged without ASM pass");
    }

    @Test
    void remapsLegacyBukkitSpigotAndMysqlDriverWhileFailingSafeOnMalformedBytes() {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/legacy/OldHook", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "mount", "Lorg/spigotmc/event/entity/EntityMountEvent;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "cow", "Lorg/bukkit/entity/Cow;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "slime", "Lorg/bukkit/entity/Slime;", null, null).visitEnd();
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "driver", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("com.mysql.jdbc.Driver");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        final byte[] raw = cw.toByteArray();
        final ApiVersion legacyVer = ApiVersion.getOrCreateVersion("1.20.4");
        assertTrue(Commodore.mayNeedConversion(raw, legacyVer, Set.of("enum-compatibility-mode")));

        final Commodore commodore = new Commodore(Predicates.alwaysTrue());
        final byte[] converted = commodore.convert(raw, "LegacyPlugin", legacyVer, Set.of("enum-compatibility-mode"));
        assertNotSame(raw, converted);

        final StringBuilder seenFields = new StringBuilder();
        final StringBuilder seenLdc = new StringBuilder();
        new ClassReader(converted).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final Object value
            ) {
                seenFields.append(name).append('=').append(descriptor).append(';');
                return null;
            }

            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(final Object value) {
                        seenLdc.append(value);
                    }
                };
            }
        }, 0);

        final String fieldsStr = seenFields.toString();
        assertTrue(fieldsStr.contains("mount=Lorg/bukkit/event/entity/EntityMountEvent;"));
        assertTrue(fieldsStr.contains("cow=Lorg/bukkit/entity/AbstractCow;"));
        assertTrue(fieldsStr.contains("slime=Lorg/bukkit/entity/AbstractCubeMob;"));
        assertTrue(seenLdc.toString().contains("com.mysql.cj.jdbc.Driver"));

        // Method descriptor referencing org/bukkit/entity/Player must trigger pre-scan even without field refs
        final ClassWriter descOnly = new ClassWriter(0);
        descOnly.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/DescOnly", null, "java/lang/Object", null);
        descOnly.visitMethod(Opcodes.ACC_PUBLIC, "accept", "(Lorg/bukkit/entity/Player;)V", null, null).visitEnd();
        descOnly.visitEnd();
        assertTrue(Commodore.mayNeedConversion(descOnly.toByteArray(), ApiVersion.CURRENT, Set.of()));

        // Malformed / non-class bytes must fail safe to true
        assertTrue(Commodore.mayNeedConversion(new byte[] {1, 2, 3, 4}, ApiVersion.CURRENT, Set.of()));
    }

    @Test
    void respectsLegacyEnumCompatibilityGateInConstantPoolPreScan() {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/GuavaUser", null, "java/lang/Object", null);
        final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "make", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/google/common/collect/Maps", "newHashMap", "()Ljava/util/HashMap;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();

        final byte[] raw = cw.toByteArray();
        assertTrue(Commodore.mayNeedConversion(raw, ApiVersion.getOrCreateVersion("1.18.2"), Set.of("enum-compatibility-mode")));
        assertFalse(Commodore.mayNeedConversion(raw, ApiVersion.getOrCreateVersion("1.21.5"), Set.of("enum-compatibility-mode")));
    }

    @Test
    void pluginInitializerCachesUpdateDirectoryEmptyCheckUntilInvalidated(final @TempDir Path tempDir) throws Exception {
        final Path pluginsDir = Files.createDirectory(tempDir.resolve("plugins"));
        final Path addonsDir = Files.createDirectory(tempDir.resolve("addons"));
        final Path updateDir = Files.createDirectory(addonsDir.resolve("update"));

        final Class<?> mgrClass = Class.forName("io.papermc.paper.plugin.PluginInitializerManager");
        final Constructor<?> ctor = mgrClass.getDeclaredConstructor(Path.class, Path.class, Path.class);
        ctor.setAccessible(true);
        final Object mgr = ctor.newInstance(pluginsDir, addonsDir, updateDir);

        final Method hasUpdates = mgrClass.getMethod("hasPendingUpdateFiles");
        final Method invalidate = mgrClass.getMethod("invalidatePendingUpdatesCache");

        assertFalse((Boolean) hasUpdates.invoke(mgr));

        Files.writeString(updateDir.resolve("plugin-update.jar"), "dummy");
        assertFalse((Boolean) hasUpdates.invoke(mgr), "Empty result remains cached within same boot scan");

        invalidate.invoke(mgr);
        assertTrue((Boolean) hasUpdates.invoke(mgr), "After cache invalidation new update file is visible");
    }

    @Test
    @SuppressWarnings("unchecked")
    void paperEventManagerDeduplicatesPublicOverriddenMethodsAndCachesHandlerLookup() throws Exception {
        final Server server = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(),
            new Class<?>[] {Server.class},
            (proxy, method, args) -> "getWarningState".equals(method.getName()) ? Warning.WarningState.DEFAULT : null
        );
        final Plugin plugin = (Plugin) Proxy.newProxyInstance(
            Plugin.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, args) -> null
        );

        final Class<?> pemClass = Class.forName("io.papermc.paper.plugin.manager.PaperEventManager");
        final Constructor<?> ctor = pemClass.getDeclaredConstructor(Server.class);
        ctor.setAccessible(true);
        final Object eventManager = ctor.newInstance(server);

        final Method createListeners = pemClass.getMethod("createRegisteredListeners", Listener.class, Plugin.class);
        createListeners.setAccessible(true);
        final Method getRegClass = pemClass.getDeclaredMethod("getRegistrationClass", Class.class);
        getRegClass.setAccessible(true);

        assertSame(DummyBaseEvent.class, getRegClass.invoke(eventManager, DummySubEvent.class));
        assertSame(DummyBaseEvent.class, getRegClass.invoke(eventManager, DummySubEvent.class));

        final AtomicInteger calls = new AtomicInteger();
        final ChildListener listener = new ChildListener(calls);
        final Map<Class<? extends Event>, Set<RegisteredListener>> registered =
            (Map<Class<? extends Event>, Set<RegisteredListener>>) createListeners.invoke(eventManager, listener, plugin);

        final Set<RegisteredListener> set = registered.get(DummySubEvent.class);
        assertTrue(set != null && set.size() == 2, "Must register both ChildListener#onPublic and BaseListener#onInherited exactly once");
        for (final RegisteredListener rl : set) {
            rl.callEvent(new DummySubEvent());
        }
        assertTrue(calls.get() == 2);
    }

    private static class DummyBaseEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    private static final class DummySubEvent extends DummyBaseEvent {
    }

    private static class BaseListener implements Listener {
        private final AtomicInteger counter;

        private BaseListener(final AtomicInteger counter) {
            this.counter = counter;
        }

        @EventHandler
        public void onInherited(final DummySubEvent event) {
            this.counter.incrementAndGet();
        }
    }

    private static final class ChildListener extends BaseListener {
        private ChildListener(final AtomicInteger counter) {
            super(counter);
        }

        @EventHandler
        @Override
        public void onInherited(final DummySubEvent event) {
            super.counter.incrementAndGet();
        }

        @EventHandler
        public void onPublic(final DummySubEvent event) {
            super.counter.incrementAndGet();
        }
    }
}
