package org.synesis.link.transport.quic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalOffer;

/** Verifies signed traversal probes over real UDP parent channels with QUIC codecs. */
final class NettyTraversalRealSocketTest {

    @Test
    void exchangesOfferAndAnswerOnTheSameBoundUdpParents() throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel initiatorChannel = null;
        Channel responderChannel = null;
        NettyTraversalExchange initiatorExchange = null;
        NettyTraversalExchange responderExchange = null;
        try {
            QuicSslContext initiatorSsl = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols("synesis-link/1")
                    .build();
            QuicSslContext responderSsl = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols("synesis-link/1")
                    .build();
            initiatorChannel = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(initiatorSsl))
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .sync()
                    .channel();
            responderChannel = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(responderSsl))
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .sync()
                    .channel();
            int initiatorPort = ((InetSocketAddress) initiatorChannel.localAddress()).getPort();
            int responderPort = ((InetSocketAddress) responderChannel.localAddress()).getPort();

            NodeIdentity initiator = NodeIdentity.generate();
            NodeIdentity responder = NodeIdentity.generate();
            Instant issued = Instant.now();
            Instant expires = issued.plusSeconds(30);
            CandidateDescriptor initiatorDescriptor = descriptor(initiator, issued, expires, initiatorPort);
            CandidateDescriptor responderDescriptor = descriptor(responder, issued, expires, responderPort);
            TraversalOffer offer = TraversalOffer.create(initiator, responder.nodeId(), UUID.randomUUID(),
                    new byte[TraversalOffer.DIGEST_BYTES], issued, expires, new byte[TraversalOffer.NONCE_BYTES],
                    initiatorDescriptor);
            initiatorExchange = new NettyTraversalExchange(initiatorChannel, Duration.ofSeconds(2), null);
            responderExchange = new NettyTraversalExchange(responderChannel, Duration.ofSeconds(2), received -> {
                try {
                    if (!received.verifyAt(Instant.now(), TraversalOffer.DEFAULT_CLOCK_SKEW, responder.nodeId())) {
                        return null;
                    }
                    return TraversalAnswer.create(responder, received, responderDescriptor,
                            new byte[TraversalAnswer.NONCE_BYTES]);
                } catch (Exception invalidOffer) {
                    return null;
                }
            });

            TraversalAnswer answer = initiatorExchange.sendOffer(offer, List.of(new Candidate(CandidateType.MANUAL,
                            ((InetSocketAddress) responderChannel.localAddress()).getAddress(),
                            responderPort, 1)))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(offer.digest().length, answer.offerDigest().length);
            assertArrayEquals(offer.digest(), answer.offerDigest());
            assertEquals(initiatorPort, ((InetSocketAddress) initiatorChannel.localAddress()).getPort());
            assertEquals(responderPort, ((InetSocketAddress) responderChannel.localAddress()).getPort());
        } finally {
            if (initiatorExchange != null) {
                initiatorExchange.close();
            }
            if (responderExchange != null) {
                responderExchange.close();
            }
            if (initiatorChannel != null) {
                initiatorChannel.close().syncUninterruptibly();
            }
            if (responderChannel != null) {
                responderChannel.close().syncUninterruptibly();
            }
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static CandidateDescriptor descriptor(NodeIdentity identity, Instant issued, Instant expires, int port)
            throws Exception {
        return CandidateDescriptor.create(identity, issued, expires,
                List.of(new Candidate(CandidateType.MANUAL, InetAddress.getLoopbackAddress(), port, 1)));
    }
}
