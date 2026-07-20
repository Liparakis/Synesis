package org.synesis.link.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.NetUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidatePairs;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionCloseReason;

/** Test-only independent JVM endpoint for authenticated-session evidence. */
final class NettyQuicPeerProcess {

    private NettyQuicPeerProcess() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("expected mode, identity-file, ready-file, result-file");
        }
        if ("client".equals(arguments[0])) {
            client(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]), true);
        } else if ("client-hold".equals(arguments[0])) {
            client(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]), false);
        } else if ("server".equals(arguments[0])) {
            server(Path.of(arguments[1]), Path.of(arguments[2]), Path.of(arguments[3]));
        } else {
            throw new IllegalArgumentException("unknown peer mode");
        }
    }

    private static void client(Path identityFile, Path serverReady, Path resultFile, boolean graceful) throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        writeIdentity(identityFile, identity);
        waitForFile(serverReady);
        List<String> material = Files.readAllLines(serverReady);
        int port = Integer.parseInt(material.get(0));
        String remoteNodeId = material.get(1);
        byte[] remotePublicKey = Base64.getDecoder().decode(material.get(2));
        HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN,
                UUID.randomUUID(), 1, 1, new byte[] {1, 2, 3, 4}, new byte[] {5, 6, 7, 8},
                identity.nodeId(), identity.publicKeyEncoded(), remoteNodeId, remotePublicKey);
        runClient(identity, remoteNodeId, transcript, serverReady, resultFile, graceful);
    }

    private static void runClient(NodeIdentity identity, String remoteNodeId, HandshakeTranscript transcript,
            Path serverReady, Path resultFile, boolean graceful) throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        TestTlsMaterial tls = TestTlsMaterial.create();
        Channel udp = null;
        QuicChannel connection = null;
        try {
            QuicSslContext ssl = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(SynesisLink.ALPN).build();
            udp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(ssl))
                    .bind(NetUtil.LOCALHOST4, 0).sync().channel();
            int port = Integer.parseInt(Files.readAllLines(serverReady).get(0));
            var selectedPair = CandidatePairs.generate(
                    List.of(new Candidate(CandidateType.MANUAL, NetUtil.LOCALHOST4, port, 0)),
                    List.of(new Candidate(CandidateType.MANUAL, NetUtil.LOCALHOST4, port, 0)), 1).get(0);
            connection = QuicChannel.newBootstrap(udp).handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(new InetSocketAddress(selectedPair.remote().address(), selectedPair.remote().port()))
                    .connect().sync().getNow();
            CompletableFuture<PeerSession> session = new CompletableFuture<>();
            HandshakeProof proof = org.synesis.link.session.SessionAuthenticator.createProof(identity, transcript,
                    HandshakeRole.INITIATOR);
            connection.createStream(QuicStreamType.BIDIRECTIONAL,
                    NettySessionHandshake.clientStreamHandler(identity, remoteNodeId, List.of(ProtocolVersion.V1),
                            transcript, proof, new ReplayGuard(), session)).sync();
            PeerSession established = session.get(20, TimeUnit.SECONDS);
            awaitHeartbeat(established);
            writeResult(resultFile, established);
            var work = established.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(),
                    DemoWorkRequest.DESCRIBE_SESSION)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            Files.writeString(resultFile, "|WORK|" + work.status() + "|" + work.requestId(),
                    StandardOpenOption.APPEND);
            waitForLine(serverReady, "LIVE_RECORDED");
            if (graceful) {
                established.closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
            } else {
                while (true) Thread.sleep(1_000);
            }
        } finally {
            if (connection != null) connection.close().syncUninterruptibly();
            if (udp != null) udp.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
            tls.close();
        }
    }

    private static void server(Path clientMaterial, Path serverReady, Path resultFile) throws Exception {
        waitForFile(clientMaterial);
        String expectedClient = Files.readAllLines(clientMaterial).get(0);
        NodeIdentity identity = NodeIdentity.generate();
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        TestTlsMaterial tls = TestTlsMaterial.create();
        Channel udp = null;
        QuicChannel connection = null;
        try {
            CompletableFuture<QuicChannel> accepted = new CompletableFuture<>();
            CompletableFuture<PeerSession> session = new CompletableFuture<>();
            QuicSslContext ssl = QuicSslContextBuilder.forServer(tls.key, null, tls.certificate)
                    .applicationProtocols(SynesisLink.ALPN).build();
            udp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(ssl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext context) {
                                    accepted.complete((QuicChannel) context.channel());
                                }
                            }, NettySessionHandshake.serverStreamHandler(identity, expectedClient,
                                    List.of(ProtocolVersion.V1), new ReplayGuard(), session)))
                    .bind(NetUtil.LOCALHOST4, 0).sync().channel();
            Files.write(serverReady, List.of(Integer.toString(((java.net.InetSocketAddress) udp.localAddress()).getPort()),
                    identity.nodeId(), Base64.getEncoder().encodeToString(identity.publicKeyEncoded())));
            PeerSession established = session.get(20, TimeUnit.SECONDS);
            awaitHeartbeat(established);
            writeResult(resultFile, established);
            Files.writeString(serverReady, "LIVE_RECORDED\n", StandardOpenOption.APPEND);
            established.terminalCompletion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            Files.writeString(resultFile, "|TERMINAL|" + established.closeReason(), StandardOpenOption.APPEND);
            connection = accepted.get(5, TimeUnit.SECONDS);
            connection.close().sync();
        } finally {
            if (connection != null) connection.close().syncUninterruptibly();
            if (udp != null) udp.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
            tls.close();
        }
    }

    private static void writeIdentity(Path file, NodeIdentity identity) throws Exception {
        Files.write(file, List.of(identity.nodeId(), Base64.getEncoder().encodeToString(identity.publicKeyEncoded())));
    }

    private static void writeResult(Path file, PeerSession session) throws Exception {
        Files.writeString(file, String.join("|", "OK", session.localNodeId(), session.remoteNodeId(),
                session.negotiatedVersion().major() + "." + session.negotiatedVersion().minor(),
                session.sessionId().toString(), Long.toString(session.localEpoch()),
                Long.toString(session.remoteEpoch()), session.livenessState().name(),
                Long.toString(session.livenessMetrics().heartbeatSentCount()),
                Long.toString(session.livenessMetrics().heartbeatAcknowledgedCount())));
    }

    private static void awaitHeartbeat(PeerSession session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (session.livenessMetrics().heartbeatAcknowledgedCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        if (session.livenessMetrics().heartbeatAcknowledgedCount() == 0) {
            throw new IllegalStateException("heartbeat acknowledgement did not arrive");
        }
    }

    private static void waitForLine(Path file, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.readAllLines(file).contains(expected)) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("timed out waiting for " + expected);
    }

    private static void waitForFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        if (!Files.exists(file)) throw new IllegalStateException("timed out waiting for " + file.getFileName());
    }
}
