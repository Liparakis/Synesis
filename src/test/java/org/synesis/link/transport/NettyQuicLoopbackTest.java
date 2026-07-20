package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.NetUtil;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.candidate.CandidatePairs;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.demo.DemoWorkStatus;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.HandshakeException;
import org.synesis.link.session.HandshakeFailureCode;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.LivenessState;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;
import org.synesis.link.session.SessionCloseReason;

/** Proves the selected native QUIC implementation can connect locally. */
final class NettyQuicLoopbackTest {

    @Test
    void connectsTwoLocalQuicChannelsAndClosesDeterministically() throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        TlsMaterial certificate = TlsMaterial.create();
        Channel serverUdp = null;
        Channel clientUdp = null;
        QuicChannel client = null;
        QuicChannel server = null;
        try {
            CompletableFuture<QuicChannel> accepted = new CompletableFuture<>();
            QuicSslContext serverSsl = QuicSslContextBuilder.forServer(certificate.key, null, certificate.certificate)
                    .applicationProtocols(SynesisLink.ALPN).build();
            serverUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(serverSsl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext context) {
                                    accepted.complete((QuicChannel) context.channel());
                                }
                            }, new ChannelInboundHandlerAdapter()))
                    .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0)).sync().channel();

            // TLS trust is intentionally disabled only in this pre-identity transport test; Slice 5 binds it to NodeIdentity.
            QuicSslContext clientSsl = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(SynesisLink.ALPN).build();
            clientUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(clientSsl))
                    .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0)).sync().channel();

            InetSocketAddress serverAddress = (InetSocketAddress) serverUdp.localAddress();
            CandidatePair selectedPair = CandidatePairs.generate(
                    java.util.List.of(new Candidate(CandidateType.MANUAL, serverAddress.getAddress(),
                            serverAddress.getPort(), 0)),
                    java.util.List.of(new Candidate(CandidateType.MANUAL, serverAddress.getAddress(),
                            serverAddress.getPort(), 0)), 1).get(0);
            assertTrue(selectedPair.identifier().startsWith("MANUAL/MANUAL/h"));
            client = QuicChannel.newBootstrap(clientUdp)
                    .handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(new InetSocketAddress(selectedPair.remote().address(), selectedPair.remote().port()))
                    .connect().sync().getNow();
            server = accepted.get(10, TimeUnit.SECONDS);
            assertNotNull(client);
            assertNotNull(server);
            assertEquals(SynesisLink.ALPN, SynesisLink.ALPN);
            client.close(true, 0, io.netty.buffer.Unpooled.EMPTY_BUFFER).sync();
            server.close(true, 0, io.netty.buffer.Unpooled.EMPTY_BUFFER).sync();
        } finally {
            if (client != null && client.isOpen()) {
                client.close().syncUninterruptibly();
            }
            if (server != null && server.isOpen()) {
                server.close().syncUninterruptibly();
            }
            if (clientUdp != null) {
                clientUdp.close().syncUninterruptibly();
            }
            if (serverUdp != null) {
                serverUdp.close().syncUninterruptibly();
            }
            group.shutdownGracefully().syncUninterruptibly();
            certificate.close();
        }
    }

    @Test
    void establishesIdentityBoundSessionOnLocalQuicControlStream() throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        TlsMaterial certificate = TlsMaterial.create();
        NodeIdentity initiatorIdentity = NodeIdentity.generate();
        NodeIdentity responderIdentity = NodeIdentity.generate();
        HandshakeTranscript transcript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
                java.util.UUID.randomUUID(), 1, 1, new byte[] {1, 2, 3}, new byte[] {4, 5, 6},
                initiatorIdentity, responderIdentity);
        CompletableFuture<PeerSession> clientSession = new CompletableFuture<>();
        CompletableFuture<PeerSession> serverSession = new CompletableFuture<>();
        Channel serverUdp = null;
        Channel clientUdp = null;
        QuicChannel client = null;
        try {
            QuicSslContext serverSsl = QuicSslContextBuilder.forServer(certificate.key, null, certificate.certificate)
                    .applicationProtocols(SynesisLink.ALPN).build();
            serverUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(serverSsl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext context) {
                                    // QUIC connection acceptance is observed by the stream handshake below.
                                }
                    }, NettySessionHandshake.serverStreamHandler(responderIdentity,
                                    initiatorIdentity.nodeId(), java.util.List.of(ProtocolVersion.V1),
                                    new ReplayGuard(), serverSession)))
                    .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0)).sync().channel();
            QuicSslContext clientSsl = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(SynesisLink.ALPN).build();
            clientUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(clientSsl))
                    .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0)).sync().channel();
            InetSocketAddress serverAddress = (InetSocketAddress) serverUdp.localAddress();
            client = QuicChannel.newBootstrap(clientUdp)
                    .handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .remoteAddress(serverAddress).connect().sync().getNow();
            HandshakeProof localProof = SessionAuthenticator.createProof(initiatorIdentity, transcript,
                    HandshakeRole.INITIATOR);
            QuicStreamChannel stream = client.createStream(QuicStreamType.BIDIRECTIONAL,
                    NettySessionHandshake.clientStreamHandler(initiatorIdentity, responderIdentity.nodeId(),
                            transcript, localProof, new ReplayGuard(), clientSession)).sync().getNow();
            assertNotNull(stream);
            PeerSession clientResult = clientSession.get(10, TimeUnit.SECONDS);
            PeerSession serverResult = serverSession.get(10, TimeUnit.SECONDS);
            assertEquals(responderIdentity.nodeId(), clientResult.remoteNodeId());
            assertEquals(initiatorIdentity.nodeId(), serverResult.remoteNodeId());
            assertEquals(transcript.sessionId(), clientResult.sessionId());
            assertEquals(clientResult.sessionId(), serverResult.sessionId());
            assertTrue(clientResult.isUsable());
            assertEquals(LivenessState.LIVE, clientResult.livenessState());
            assertEquals(LivenessState.LIVE, serverResult.livenessState());
            awaitHeartbeat(clientResult);
            awaitHeartbeat(serverResult);
            assertTrue(clientResult.livenessMetrics().heartbeatSentCount() > 0);
            assertTrue(clientResult.livenessMetrics().heartbeatAcknowledgedCount() > 0);
            assertTrue(serverResult.livenessMetrics().heartbeatSentCount() > 0);
            assertTrue(serverResult.livenessMetrics().heartbeatAcknowledgedCount() > 0);
            var work = clientResult.requestDemoWork(new DemoWorkRequest(java.util.UUID.randomUUID(),
                    DemoWorkRequest.DESCRIBE_SESSION)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(DemoWorkStatus.OK, work.status());
            assertEquals("accepted", work.message());
            CompletableFuture<PeerSession> duplicate = new CompletableFuture<>();
            client.createStream(QuicStreamType.BIDIRECTIONAL,
                    NettySessionHandshake.clientStreamHandler(initiatorIdentity, responderIdentity.nodeId(),
                            transcript, localProof, new ReplayGuard(), duplicate)).sync();
            ExecutionException duplicateFailure = assertThrows(ExecutionException.class,
                    () -> duplicate.get(5, TimeUnit.SECONDS));
            assertEquals(HandshakeFailureCode.DUPLICATE_CONTROL_STREAM,
                    ((HandshakeException) duplicateFailure.getCause()).code());
            QuicStreamChannel data = client.createStream(QuicStreamType.BIDIRECTIONAL,
                    new ChannelInboundHandlerAdapter()).sync().getNow();
            for (int index = 0; index < 64; index++) {
                io.netty.buffer.ByteBuf chunk = io.netty.buffer.Unpooled.buffer(4 + 4_096);
                chunk.writeInt(4_096).writeZero(4_096);
                data.writeAndFlush(chunk);
            }
            var firstClose = clientResult.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
            var secondClose = clientResult.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
            assertSame(firstClose, secondClose);
            firstClose.toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            serverResult.terminalCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(SessionCloseReason.REMOTE_REQUEST, serverResult.closeReason());
            assertEquals(LivenessState.CLOSED_GRACEFULLY, clientResult.livenessState());
            assertEquals(LivenessState.CLOSED_BY_PEER, serverResult.livenessState());
        } finally {
            if (client != null && client.isOpen()) client.close().syncUninterruptibly();
            if (clientUdp != null) clientUdp.close().syncUninterruptibly();
            if (serverUdp != null) serverUdp.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
            certificate.close();
        }
    }

    @Test
    void connectsTwoSeparateJavaProcesses() throws Exception {
        Path directory = Files.createTempDirectory("synesis-link-process");
        Path clientMaterial = directory.resolve("client.material");
        Path serverReady = directory.resolve("server.ready");
        Path clientResult = directory.resolve("client.result");
        Path serverResult = directory.resolve("server.result");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        Process client = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                NettyQuicPeerProcess.class.getName(), "client", clientMaterial.toString(), serverReady.toString(),
                clientResult.toString())
                .redirectErrorStream(true).start();
        Process server = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(clientMaterial) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertTrue(Files.exists(clientMaterial), "client did not publish identity material");
            server = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                    NettyQuicPeerProcess.class.getName(), "server", clientMaterial.toString(), serverReady.toString(),
                    serverResult.toString())
                    .redirectErrorStream(true).start();
            assertTrue(client.waitFor(20, TimeUnit.SECONDS), "client did not exit");
            assertTrue(server.waitFor(20, TimeUnit.SECONDS), "server did not exit");
            assertEquals(0, client.exitValue(), readProcessOutput(client));
            assertEquals(0, server.exitValue(), readProcessOutput(server));
            assertEquals("OK", Files.readString(clientResult).split("\\|", -1)[0]);
            assertTrue(Files.readString(clientResult).contains("|WORK|OK|"));
            assertEquals("OK", Files.readString(serverResult).split("\\|", -1)[0]);
            String[] clientFields = Files.readString(clientResult).split("\\|", -1);
            String[] serverFields = Files.readString(serverResult).split("\\|", -1);
            assertEquals(clientFields[1], serverFields[2]);
            assertEquals(clientFields[2], serverFields[1]);
            assertEquals("1.0", clientFields[3]);
            assertEquals(clientFields[3], serverFields[3]);
            assertEquals(clientFields[4], serverFields[4]);
            assertEquals("LIVE", clientFields[7]);
            assertEquals(clientFields[7], serverFields[7]);
            assertTrue(Long.parseLong(clientFields[8]) > 0);
            assertTrue(Long.parseLong(clientFields[9]) > 0);
            assertTrue(Long.parseLong(serverFields[8]) > 0);
            assertTrue(Long.parseLong(serverFields[9]) > 0);
        } finally {
            if (client.isAlive()) client.destroyForcibly();
            if (server != null && server.isAlive()) server.destroyForcibly();
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void reportsAbruptProcessLossWithDocumentedTerminalCategory() throws Exception {
        Path directory = Files.createTempDirectory("synesis-link-abrupt-process");
        Path clientMaterial = directory.resolve("client.material");
        Path serverReady = directory.resolve("server.ready");
        Path clientResult = directory.resolve("client.result");
        Path serverResult = directory.resolve("server.result");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("java.class.path");
        Process client = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                NettyQuicPeerProcess.class.getName(), "client-hold", clientMaterial.toString(), serverReady.toString(),
                clientResult.toString()).redirectErrorStream(true).start();
        Process server = null;
        try {
            waitForPath(clientMaterial);
            server = new ProcessBuilder(javaExecutable, "--enable-native-access=ALL-UNNAMED", "-cp", classpath,
                    NettyQuicPeerProcess.class.getName(), "server", clientMaterial.toString(), serverReady.toString(),
                    serverResult.toString()).redirectErrorStream(true).start();
            waitForPath(clientResult);
            assertTrue(Files.readString(clientResult).contains("|LIVE|"));
            client.destroyForcibly();
            assertTrue(server.waitFor(10, TimeUnit.SECONDS), "server did not classify abrupt loss");
            assertEquals(0, server.exitValue(), readProcessOutput(server));
            String terminal = Files.readString(serverResult);
            assertTrue(terminal.contains("|TERMINAL|TRANSPORT_CLOSED")
                    || terminal.contains("|TERMINAL|LIVENESS_EXPIRED"));
        } finally {
            if (client.isAlive()) client.destroyForcibly();
            if (server != null && server.isAlive()) server.destroyForcibly();
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void rejectsWrongIdentityAndIncompatibleVersionBeforeSessionExposure() throws Exception {
        assertRejectedHandshake(NodeIdentity.generate().nodeId(), java.util.List.of(ProtocolVersion.V1),
                HandshakeFailureCode.IDENTITY_MISMATCH);
        NodeIdentity responder = NodeIdentity.generate();
        assertRejectedHandshake(responder.nodeId(), java.util.List.of(new ProtocolVersion(2, 0)),
                HandshakeFailureCode.UNSUPPORTED_PROTOCOL_VERSION);
    }

    private static void assertRejectedHandshake(String serverExpectedNodeId,
            java.util.List<ProtocolVersion> serverVersions, HandshakeFailureCode expected) throws Exception {
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        TlsMaterial certificate = TlsMaterial.create();
        NodeIdentity initiator = NodeIdentity.generate();
        NodeIdentity responder = NodeIdentity.generate();
        HandshakeTranscript transcript = HandshakeTranscript.forIdentities(ProtocolVersion.V1,
                java.util.UUID.randomUUID(), 1, 1, new byte[] {1}, new byte[] {2}, initiator, responder);
        CompletableFuture<PeerSession> clientSession = new CompletableFuture<>();
        CompletableFuture<PeerSession> serverSession = new CompletableFuture<>();
        Channel serverUdp = null;
        Channel clientUdp = null;
        QuicChannel client = null;
        try {
            QuicSslContext serverSsl = QuicSslContextBuilder.forServer(certificate.key, null, certificate.certificate)
                    .applicationProtocols(SynesisLink.ALPN).build();
            serverUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(serverSsl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter(), NettySessionHandshake.serverStreamHandler(responder,
                                    serverExpectedNodeId, serverVersions, new ReplayGuard(), serverSession)))
                    .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0)).sync().channel();
            QuicSslContext clientSsl = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(SynesisLink.ALPN).build();
            clientUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(clientSsl))
                    .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0)).sync().channel();
            client = QuicChannel.newBootstrap(clientUdp).handler(new ChannelInboundHandlerAdapter())
                    .streamHandler(new ChannelInboundHandlerAdapter())
                    .remoteAddress((InetSocketAddress) serverUdp.localAddress()).connect().sync().getNow();
            HandshakeProof proof = SessionAuthenticator.createProof(initiator, transcript, HandshakeRole.INITIATOR);
            client.createStream(QuicStreamType.BIDIRECTIONAL,
                    NettySessionHandshake.clientStreamHandler(initiator, responder.nodeId(), transcript, proof,
                            new ReplayGuard(), clientSession)).sync();
            ExecutionException clientFailure = assertThrows(ExecutionException.class,
                    () -> clientSession.get(10, TimeUnit.SECONDS));
            assertEquals(expected, ((HandshakeException) clientFailure.getCause()).code());
            assertTrue(serverSession.isCompletedExceptionally());
        } finally {
            if (client != null) client.close().syncUninterruptibly();
            if (clientUdp != null) clientUdp.close().syncUninterruptibly();
            if (serverUdp != null) serverUdp.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
            certificate.close();
        }
    }

    private static String readProcessOutput(Process process) throws java.io.IOException {
        return new String(process.getInputStream().readAllBytes());
    }

    private static void awaitHeartbeat(PeerSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (session.livenessMetrics().heartbeatAcknowledgedCount() == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(session.livenessMetrics().heartbeatAcknowledgedCount() > 0,
                "heartbeat acknowledgement did not arrive");
    }

    private static void waitForPath(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(path), "timed out waiting for " + path.getFileName());
    }

    private static final class TlsMaterial implements AutoCloseable {
        private static final String PASSWORD = "changeit";
        private final Path directory;
        private final PrivateKey key;
        private final X509Certificate certificate;

        private TlsMaterial(Path directory, PrivateKey key, X509Certificate certificate) {
            this.directory = directory;
            this.key = key;
            this.certificate = certificate;
        }

        private static TlsMaterial create() throws Exception {
            Path directory = Files.createTempDirectory("synesis-link-tls");
            Path store = directory.resolve("identity.p12");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toString();
            Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "quic", "-keyalg", "RSA",
                    "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD,
                    "-keypass", PASSWORD, "-dname", "CN=localhost", "-validity", "1", "-noprompt")
                    .redirectErrorStream(true).start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool failed: " + new String(process.getInputStream().readAllBytes()));
            }
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(store)) {
                keyStore.load(input, PASSWORD.toCharArray());
            }
            return new TlsMaterial(directory, (PrivateKey) keyStore.getKey("quic", PASSWORD.toCharArray()),
                    (X509Certificate) keyStore.getCertificate("quic"));
        }

        @Override
        public void close() throws java.io.IOException {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
            } catch (java.io.UncheckedIOException exception) {
                throw exception.getCause();
            }
        }
    }
}
