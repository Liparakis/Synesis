package org.synesis.link.transport.quic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Verifies that a datagram exchange is active on its channel once it is installed.
 */
final class NettyDatagramExchangeTest {

  @Test
  void isActiveOnTheChannelWhenInstallReturns() throws Exception {
    MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1,
        NioIoHandler.newFactory());
    try {
      Channel channel = new Bootstrap().group(group)
          .channel(NioDatagramChannel.class)
          .handler(new ChannelInboundHandlerAdapter())
          .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
          .sync()
          .channel();
      // The event loop is held busy for a moment, so an activation that install only queued is
      // still pending when install returns. A datagram read in that gap skips the handler.
      CountDownLatch release = new CountDownLatch(1);
      channel.eventLoop().execute(() -> {
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      });
      CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(release::countDown);
      Probe probe = new Probe(channel);

      probe.install("probe");

      assertTrue(probe.active);
    } finally {
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  private static final class Probe extends NettyDatagramExchange<Void, Void> {

    private volatile boolean active;

    private Probe(Channel channel) {
      super(channel, Duration.ofSeconds(1), "probe", "probe closed");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext context) {
      active = true;
    }

    @Override
    boolean recognizes(ByteBuf content) {
      return false;
    }

    @Override
    void onRecognized(DatagramPacket packet) {
    }
  }
}
