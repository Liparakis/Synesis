package org.synesis.link.transport.quic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.StunBindingMessage;
import org.synesis.link.candidate.StunCandidateProvider;

/** Verifies real UDP STUN mapping on a parent channel carrying the QUIC codec. */
final class NettyStunRealSocketTest {

    @Test
    void derivesMappingFromTheSameBoundChannelInstalledWithQuicCodec() throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel server = null;
        Channel client = null;
        NettyStunBindingTransport transport = null;
        try {
            server = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(new BindingResponder())
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .sync()
                    .channel();
            QuicSslContext ssl = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols("synesis-link/1")
                    .build();
            client = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(ssl))
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .sync()
                    .channel();
            int localPort = ((InetSocketAddress) client.localAddress()).getPort();
            transport = new NettyStunBindingTransport(client, Duration.ofSeconds(2));
            StunCandidateProvider provider = new StunCandidateProvider(
                    (InetSocketAddress) server.localAddress(), 10, transport);

            Candidate candidate = provider.gather(() -> false)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS)
                    .getFirst();

            assertNotNull(candidate.address());
            assertEquals(localPort, candidate.port());
            assertEquals(localPort, ((InetSocketAddress) client.localAddress()).getPort());
        } finally {
            if (transport != null) {
                transport.close();
            }
            if (client != null) {
                client.close().syncUninterruptibly();
            }
            if (server != null) {
                server.close().syncUninterruptibly();
            }
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static final class BindingResponder extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
            ByteBuf content = packet.content();
            if (content.readableBytes() != 20) {
                return;
            }
            byte[] request = new byte[20];
            content.getBytes(content.readerIndex(), request);
            if ((ByteBuffer.wrap(request).getShort() & 0xffff) != 0x0001) {
                return;
            }
            InetSocketAddress sender = packet.sender();
            byte[] response = successResponse(Arrays.copyOfRange(request, 8, 20), sender.getAddress(),
                    sender.getPort());
            context.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(response), sender));
        }

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
            response.writeShort(0x0101).writeShort(4 + value.length).writeInt(cookie).writeBytes(transactionId)
                    .writeShort(0x0020).writeShort(value.length).writeBytes(value);
            byte[] bytes = new byte[response.readableBytes()];
            response.readBytes(bytes).release();
            return bytes;
        }
    }
}
