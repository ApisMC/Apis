// SPDX-License-Identifier: GPL-3.0-only

package org.apismc.apis.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.papermc.paper.configuration.GlobalConfigTestingBase;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.server.MinecraftServer;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
class ConnectionBatchingTest {

    @BeforeAll
    static void initConfig() {
        GlobalConfigTestingBase.setupGlobalConfigForTest(RegistryHelper.registryAccess());
    }

    @Test
    void nonFlushedBatchPacketStaysBatchedUntilTickThenDrainsAndFlushes() throws Exception {
        try (TestChannelHarness harness = new TestChannelHarness()) {
            final Connection connection = harness.connection;
            assertFalse(harness.channel.eventLoop().inEventLoop());

            connection.send(new ClientboundKeepAlivePacket(42L), null, false);
            harness.awaitEventLoop();
            assertEquals(0, harness.recorder.written.size(), "flush=false packet should stay in outbound batch before tick");
            assertEquals(0, harness.recorder.flushedTotal.get());

            try (MockedStatic<MinecraftServer> _ = mockMainThreadServer()) {
                connection.tick();
            }
            harness.awaitEventLoop();
            assertEquals(1, harness.recorder.written.size(), "Connection.tick() must drain queued flush=false batch");
            assertEquals(1, harness.recorder.flushedTotal.get(), "Connection.tick() must flush drained batch");
            assertTrue(harness.recorder.flushCalls.get() >= 1);
        }
    }

    @Test
    void overflowAboveThresholdAutoDrains256AndTickFlushesRemainingPackets() throws Exception {
        try (TestChannelHarness harness = new TestChannelHarness()) {
            final Connection connection = harness.connection;

            for (int i = 0; i < 260; i++) {
                connection.send(new ClientboundKeepAlivePacket(i), null, false);
            }
            harness.awaitEventLoop();
            assertEquals(256, harness.recorder.written.size(), "Batch should auto-drain first 256 packets on threshold");
            assertEquals(0, harness.recorder.flushedTotal.get(), "Threshold drain writes without immediate flush");

            try (MockedStatic<MinecraftServer> _ = mockMainThreadServer()) {
                connection.tick();
            }
            harness.awaitEventLoop();
            assertEquals(260, harness.recorder.written.size(), "Remaining 4 packets must drain on tick");
            assertEquals(260, harness.recorder.flushedTotal.get(), "Tick must flush all 260 written packets");
        }
    }

    @Test
    void disconnectClearsBatchAndInvokesListenersWithoutRetainingPackets() throws Exception {
        try (TestChannelHarness harness = new TestChannelHarness()) {
            final Connection connection = harness.connection;
            final AtomicBoolean listenerInvoked = new AtomicBoolean(false);

            connection.send(new ClientboundKeepAlivePacket(1L), f -> listenerInvoked.set(true), false);
            connection.send(new ClientboundKeepAlivePacket(2L), null, false);

            connection.disconnect(Component.literal("closing"));
            harness.awaitEventLoop();

            final Field batchPacketsField = Connection.class.getDeclaredField("batchPackets");
            final Field batchCountField = Connection.class.getDeclaredField("batchCount");
            batchPacketsField.setAccessible(true);
            batchCountField.setAccessible(true);

            assertEquals(0, batchCountField.getInt(connection));
            assertNull(batchPacketsField.get(connection));
            assertEquals(0, harness.recorder.written.size());
            assertFalse(listenerInvoked.get(), "Cleared unsent packets do not succeed Netty write futures");

            // Sending after channel close must not allocate or retain batch arrays
            connection.send(new ClientboundKeepAlivePacket(3L), null, false);
            assertEquals(0, batchCountField.getInt(connection));
            assertNull(batchPacketsField.get(connection));
        }
    }

    private static final class RecordingOutboundHandler extends ChannelOutboundHandlerAdapter {
        private final List<Object> written = new CopyOnWriteArrayList<>();
        private final AtomicInteger pendingSinceFlush = new AtomicInteger();
        private final AtomicInteger flushedTotal = new AtomicInteger();
        private final AtomicInteger flushCalls = new AtomicInteger();

        @Override
        public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
            this.written.add(msg);
            this.pendingSinceFlush.incrementAndGet();
            if (!promise.isVoid()) {
                promise.trySuccess();
            }
        }

        @Override
        public void flush(final ChannelHandlerContext ctx) {
            this.flushedTotal.addAndGet(this.pendingSinceFlush.getAndSet(0));
            this.flushCalls.incrementAndGet();
            ctx.flush();
        }
    }

    private static final class TestChannelHarness implements AutoCloseable {
        private final EventLoopGroup group = new DefaultEventLoopGroup(1);
        private final Channel serverChannel;
        private final Channel channel;
        private final RecordingOutboundHandler recorder = new RecordingOutboundHandler();
        private final Connection connection = new Connection(PacketFlow.CLIENTBOUND);

        private TestChannelHarness() throws InterruptedException {
            final LocalAddress addr = new LocalAddress("apis-batch-" + UUID.randomUUID());
            this.serverChannel = new ServerBootstrap()
                .group(this.group)
                .channel(LocalServerChannel.class)
                .childHandler(new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(final LocalChannel ch) {
                    }
                })
                .bind(addr)
                .sync()
                .channel();

            this.channel = new Bootstrap()
                .group(this.group)
                .channel(LocalChannel.class)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(addr)
                .sync()
                .channel();

            this.channel.pipeline().addLast("recorder", this.recorder);
            this.connection.channel = this.channel;
            this.connection.preparing = false;
        }

        private void awaitEventLoop() throws Exception {
            this.channel.eventLoop().submit(() -> {
            }).get();
        }

        @Override
        public void close() {
            this.channel.close().awaitUninterruptibly();
            this.serverChannel.close().awaitUninterruptibly();
            this.group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static MockedStatic<MinecraftServer> mockMainThreadServer() {
        final MinecraftServer mockServer = Mockito.mock(MinecraftServer.class);
        Mockito.when(mockServer.isSameThread()).thenReturn(true);
        final MockedStatic<MinecraftServer> serverMock = Mockito.mockStatic(
            MinecraftServer.class,
            Mockito.CALLS_REAL_METHODS
        );
        serverMock.when(MinecraftServer::getServer).thenReturn(mockServer);
        return serverMock;
    }
}
