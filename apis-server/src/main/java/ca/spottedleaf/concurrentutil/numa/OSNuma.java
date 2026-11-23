// Copyright (c) Spottedleaf / Tuinity LeafPile contributors
// Modifications Copyright (c) 2026 ApisMC contributors
// SPDX-License-Identifier: GPL-3.0-only

package ca.spottedleaf.concurrentutil.numa;

import ca.spottedleaf.concurrentutil.util.FlatBitsetUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Locale;
import org.apismc.apis.hooks.ApisHooks;

/**
 * Overridden {@link OSNuma} entry point that bypasses {@link LinuxNuma} and JNA classloading
 * on non-Linux operating systems while preserving full NUMA behavior on Linux.
 */
public interface OSNuma {

    int NUMA_DISTANCE_CANNOT_DETERMINE = 255;

    static OSNuma getNativeInstance() {
        if (!ApisHooks.isLinuxOs()
            && !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return NoOp.INSTANCE;
        }
        final LinuxNuma linux = LinuxNuma.INSTANCE;
        if (linux != null && linux.isAvailable()) {
            return linux;
        }
        return NoOp.INSTANCE;
    }

    boolean isAvailable();

    int getNumaDistance(int nodeA, int nodeB);

    int getTotalNumaNodes();

    int[][] getNodeDistances();

    int getNumaNode(int core);

    int getTotalCores();

    int[] getCores(int node);

    int getCurrentNumaNode();

    int getCurrentCore();

    long[] getCurrentThreadAffinity();

    void setCurrentThreadAffinity(long[] affinity);

    default void setCurrentNumaAffinity(final long[] nodes) {
        this.setCurrentNumaAffinity(FlatBitsetUtil.bitsetToInts(nodes));
    }

    default void setCurrentNumaAffinity(final int[] nodes) {
        final IntArrayList cores = new IntArrayList();
        for (final int node : nodes) {
            cores.addAll(IntArrayList.wrap(this.getCores(node)));
        }
        this.setCurrentThreadAffinity(FlatBitsetUtil.intsToBitset(cores.toIntArray()));
    }

    abstract class PreCalculatedNuma implements OSNuma {

        private final int[] coreToNuma;
        private final int[][] costArray;
        private final int[][] numaToCore;

        public PreCalculatedNuma(final int[] coreToNuma, final int[][] costArray) {
            this.coreToNuma = coreToNuma;
            this.costArray = costArray;
            final IntArrayList[] byNode = new IntArrayList[this.costArray.length];
            for (int i = 0; i < byNode.length; i++) {
                byNode[i] = new IntArrayList();
            }
            for (int core = 0; core < coreToNuma.length; core++) {
                byNode[coreToNuma[core]].add(core);
            }
            this.numaToCore = new int[this.costArray.length][];
            for (int i = 0; i < this.numaToCore.length; i++) {
                this.numaToCore[i] = byNode[i].toIntArray();
            }
        }

        @Override
        public int getNumaDistance(final int nodeA, final int nodeB) {
            if (nodeA < 0 || nodeA >= this.costArray.length) {
                return NUMA_DISTANCE_CANNOT_DETERMINE;
            }
            final int[] row = this.costArray[nodeA];
            if (nodeB < 0 || nodeB >= row.length) {
                return NUMA_DISTANCE_CANNOT_DETERMINE;
            }
            return row[nodeB];
        }

        @Override
        public int getTotalNumaNodes() {
            return this.costArray.length;
        }

        @Override
        public int[][] getNodeDistances() {
            final int[][] copy = new int[this.costArray.length][];
            for (int i = 0; i < copy.length; i++) {
                copy[i] = this.costArray[i].clone();
            }
            return copy;
        }

        @Override
        public int getNumaNode(final int core) {
            if (core < 0 || core >= this.coreToNuma.length) {
                return -1;
            }
            return this.coreToNuma[core];
        }

        @Override
        public int getTotalCores() {
            return this.coreToNuma.length;
        }

        @Override
        public int[] getCores(final int node) {
            if (node < 0 || node >= this.numaToCore.length) {
                return null;
            }
            return this.numaToCore[node].clone();
        }

        @Override
        public int getCurrentNumaNode() {
            return this.coreToNuma[this.getCurrentCore()];
        }
    }

    final class NoOp extends PreCalculatedNuma {

        public static final NoOp INSTANCE = new NoOp();

        private final long[] currentThreadAffinity;

        private NoOp() {
            super(new int[Runtime.getRuntime().availableProcessors()], new int[][] {new int[] {10}});
            this.currentThreadAffinity = FlatBitsetUtil.intsToBitset(this.getCores(0));
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public int getCurrentCore() {
            return 0;
        }

        @Override
        public long[] getCurrentThreadAffinity() {
            return this.currentThreadAffinity.clone();
        }

        @Override
        public void setCurrentThreadAffinity(final long[] affinity) {
        }
    }
}
