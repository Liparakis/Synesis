package org.synesis.link.transport.quic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.StunBindingMessage;

/**
 * Verifies STUN traffic shares the caller-provided Netty datagram channel.
 */
final class NettyStunBindingTransportTest {

  private static byte[] successResponse(byte[] transactionId, InetAddress address, int port) {
    byte[] addressBytes = address.getAddress();
    byte[] value = new byte[4 + addressBytes.length];
    value[1] = 0x01;
    int cookie = StunBindingMessage.MAGIC_COOKIE;
    value[2] = (byte) ((port ^ (cookie >>> 16)) >>> 8);
    value[3] = (byte) (port ^ (cookie >>> 16));
    byte[] mask = ByteBuffer.allocate(16).putInt(cookie).put(transactionId).array();
    for (int index = 0; index < addressBytes.length; index++) {
      value[4 + index] = (byte) (addressBytes[index] ^ mask[index]);
    }
    ByteBuf response = Unpooled.buffer(20 + 4 + value.length);
    response.writeShort(0x0101).writeShort(4 + value.length).writeInt(cookie)
        .writeBytes(transactionId)
        .writeShort(0x0020).writeShort(value.length).writeBytes(value);
    byte[] bytes = new byte[response.readableBytes()];
    response.readBytes(bytes).release();
    return bytes;
  }

  @Test
  void sendsAndReceivesOnTheProvidedChannelWhileForwardingOtherDatagrams() throws Exception {
    EmbeddedChannel channel = new EmbeddedChannel();
    NettyStunBindingTransport transport = new NettyStunBindingTransport(channel,
        Duration.ofSeconds(1));
    InetSocketAddress server = new InetSocketAddress("192.0.2.10", 3478);
    byte[] transactionId = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    byte[] request = StunBindingMessage.bindingRequest(transactionId);

    CompletableFuture<byte[]> response = transport.request(server, request, () -> false)
        .toCompletableFuture();
    channel.runPendingTasks();
    DatagramPacket outbound = channel.readOutbound();
    assertNotNull(outbound);
    assertEquals(server, outbound.recipient());
    outbound.release();

    DatagramPacket unrelated = new DatagramPacket(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}),
        server);
    channel.writeInbound(unrelated);
    DatagramPacket forwarded = channel.readInbound();
    assertNotNull(forwarded);
    forwarded.release();

    byte[] mappedResponse = successResponse(transactionId, InetAddress.getByName("203.0.113.7"),
        51_234);
    channel.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(mappedResponse),
        new InetSocketAddress("192.0.2.20", 40_000), server));
    assertArrayEquals(mappedResponse, response.get());
    transport.close();
    assertTrue(channel.isOpen());
    channel.finishAndReleaseAll();
  }
}
