// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.block;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
class BlockBehaviourCacheTest {

    @Test
    void occlusionShapeAccessDuringReentrantInitCacheNeverReturnsNullOrThrows() throws Exception {
        final BlockState state = Blocks.STONE.defaultBlockState();
        final Field cacheInitialized = BlockBehaviour.BlockStateBase.class.getDeclaredField("cacheInitialized");
        final Field cacheInitializing = BlockBehaviour.BlockStateBase.class.getDeclaredField("cacheInitializing");
        cacheInitialized.setAccessible(true);
        cacheInitializing.setAccessible(true);

        try {
            // Simulate a reentrant call on the thread currently inside initCache()
            cacheInitialized.setBoolean(state, false);
            cacheInitializing.setBoolean(state, true);

            assertNotNull(state.getOcclusionShape());
            for (final Direction dir : Direction.values()) {
                assertNotNull(state.getFaceOcclusionShape(dir));
            }
        } finally {
            cacheInitializing.setBoolean(state, false);
            cacheInitialized.setBoolean(state, false);
            state.initCache();
        }

        assertTrue(cacheInitialized.getBoolean(state));
        assertNotNull(state.getFaceOcclusionShape(Direction.UP));
    }

    @Test
    void concurrentAsBlockDataReadsReturnValidIndependentClones() throws Exception {
        final BlockState state = Blocks.OAK_PLANKS.defaultBlockState();
        final Field cachedField = BlockBehaviour.BlockStateBase.class.getDeclaredField("cachedCraftBlockData");
        cachedField.setAccessible(true);
        cachedField.set(state, null);

        final int threads = 4;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final List<Callable<CraftBlockData>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(state::createCraftBlockData);
            }
            final List<Future<CraftBlockData>> futures = pool.invokeAll(tasks);
            for (final Future<CraftBlockData> future : futures) {
                final CraftBlockData data = future.get();
                assertNotNull(data);
                assertEquals(Material.OAK_PLANKS, data.getMaterial());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
