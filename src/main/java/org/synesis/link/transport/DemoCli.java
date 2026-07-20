package org.synesis.link.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.NetUtil;

import java.io.Console;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateGatherer;
import org.synesis.link.candidate.CandidateGatheringPolicy;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.candidate.CandidatePairs;
import org.synesis.link.candidate.CandidateProvider;
import org.synesis.link.candidate.CandidateRacer;
import org.synesis.link.candidate.ConnectionAttempt;
import org.synesis.link.candidate.ConnectionPolicy;
import org.synesis.link.candidate.DirectConnectionResult;
import org.synesis.link.candidate.LocalInterfaceCandidateProvider;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.identity.FileIdentityStore;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionCloseReason;
import org.synesis.link.session.SessionAuthenticator;

/**
 * Source-run validation CLI for the first physical Synesis Link demonstration.
 *
 * <p>This is not a production management CLI. It uses explicit identity,
 * descriptor, and TLS-keystore files, performs one bounded direct race, sends
 * one fixed demo request, and prints only safe identifiers and statuses.
 */
public final class DemoCli {
    private DemoCli() { }

    /**
     * Runs {@code identity}, {@code server}, or {@code client} with explicit file arguments.
     * @param arguments command-line arguments
     * @throws Exception if files, TLS, transport, or authentication fail
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 1 && "--help".equals(arguments[0])) {
            System.out.println(usage());
            return;
        }
        Map<String, String> options = options(arguments);
        String mode = options.getOrDefault("mode", arguments.length == 0 ? "" : arguments[0]);
        if ("identity".equals(mode)) {
            NodeIdentity identity = identity(required(options, "identity"));
            System.out.println("NODE_ID=" + identity.nodeId());
        } else if ("server".equals(mode)) runServer(options);
        else if ("client".equals(mode)) runClient(options);
        else throw new IllegalArgumentException(usage());
    }

    private static void runServer(Map<String, String> options) throws Exception {
        NodeIdentity identity = identity(options.get("identity"));
        Path descriptorPath = Path.of(required(options, "descriptor"));
        String expectedClient = required(options, "expected-client");
        TlsMaterial tls = TlsMaterial.load(Path.of(required(options, "tls-keystore")), password(options));
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel udp = null;
        try {
            CompletableFuture<PeerSession> established = new CompletableFuture<>();
            QuicSslContext ssl = QuicSslContextBuilder.forServer(tls.key, null, tls.certificate)
                    .applicationProtocols(SynesisLink.ALPN).build();
            udp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(ssl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter(), NettySessionHandshake.serverStreamHandler(identity,
                                    expectedClient, List.of(ProtocolVersion.V1), new ReplayGuard(), established)))
                    .bind(new InetSocketAddress(Integer.parseInt(options.getOrDefault("port", "0")))).sync().channel();
            int port = ((InetSocketAddress) udp.localAddress()).getPort();
            CandidateGatheringResultView gathered = gather("server", port);
            CandidateDescriptor descriptor = CandidateDescriptor.create(identity, Instant.now(),
                    Instant.now().plusSeconds(3_600), gathered.candidates);
            Files.write(descriptorPath, descriptor.encoded());
            System.out.println("NODE_ID=" + identity.nodeId());
            System.out.println("DESCRIPTOR_READY=true");
            System.out.println("CANDIDATES=" + gathered.candidates.size());
            PeerSession session = established.get(60, TimeUnit.SECONDS);
            printSession(session);
            session.terminalCompletion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            System.out.println("TERMINAL_REASON=" + session.closeReason());
            System.out.println("CLEANUP=true");
        } finally {
            if (udp != null) udp.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
            tls.close();
        }
    }

    private static void runClient(Map<String, String> options) throws Exception {
        NodeIdentity identity = identity(options.get("identity"));
        CandidateDescriptor descriptor = CandidateDescriptor.decode(
                Files.readAllBytes(Path.of(required(options, "descriptor"))));
        if (!descriptor.isValidAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW)) {
            throw new IllegalArgumentException("descriptor is not authentic and current");
        }
        String expected = required(options, "expected-node");
        if (!expected.equals(descriptor.nodeId())) throw new IllegalArgumentException("expected identity mismatch");
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel udp = null;
        try {
            QuicSslContext ssl = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(SynesisLink.ALPN).build();
            udp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(ssl)).bind(new InetSocketAddress(0)).sync().channel();
            int port = ((InetSocketAddress) udp.localAddress()).getPort();
            Channel clientUdp = udp;
            CandidateGatheringResultView local = gather("client", port);
            List<CandidatePair> pairs = CandidatePairs.generate(local.candidates, descriptor.candidates(), 8);
            System.out.println("NODE_ID=" + identity.nodeId());
            System.out.println("EXPECTED_NODE_ID=" + expected);
            System.out.println("CANDIDATES=" + local.candidates.size());
            System.out.println("COMPATIBLE_PAIRS=" + pairs.size());
            if (pairs.isEmpty()) throw new IllegalStateException("no compatible direct candidate pair");
            HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN,
                    UUID.randomUUID(), 1, 1, new byte[] {1, 2, 3, 4}, new byte[] {5, 6, 7, 8},
                    identity.nodeId(), identity.publicKeyEncoded(), descriptor.nodeId(), descriptor.publicKeyEncoded());
            try (CandidateRacer racer = new CandidateRacer(new ConnectionPolicy(8, 1, 1, Duration.ZERO,
                    Duration.ofSeconds(20), Duration.ofSeconds(30), Duration.ofSeconds(2), 16))) {
                CandidatePair pair = pairs.get(0);
                DirectConnectionResult connection = racer.race(pairs, expected,
                        value -> attempt(identity, descriptor, transcript, clientUdp, value))
                        .completion().toCompletableFuture().get(35, TimeUnit.SECONDS);
                if (connection.session() == null) throw new IllegalStateException("direct race failed: "
                        + connection.failureCategory());
                PeerSession session = connection.session();
                printSession(session);
                System.out.println("SELECTED_PAIR=" + pair.identifier());
                var work = session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(),
                        DemoWorkRequest.DESCRIBE_SESSION)).toCompletableFuture().get(10, TimeUnit.SECONDS);
                System.out.println("WORK_RESULT=" + work.status());
                session.closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                System.out.println("TERMINAL_REASON=" + session.closeReason());
                System.out.println("CLEANUP=true");
            }
        } finally {
            if (udp != null) udp.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static ConnectionAttempt attempt(NodeIdentity identity, CandidateDescriptor descriptor,
            HandshakeTranscript transcript, Channel udp, CandidatePair pair) {
        return new ConnectionAttempt() {
            private volatile QuicChannel connection;

            @Override
            public CompletionStage<PeerSession> connect(org.synesis.link.candidate.CandidateCancellation cancellation) {
                CompletableFuture<PeerSession> result = new CompletableFuture<>();
                io.netty.util.concurrent.Future<QuicChannel> connected = QuicChannel.newBootstrap(udp)
                        .handler(new ChannelInboundHandlerAdapter()).streamHandler(new ChannelInboundHandlerAdapter())
                        .remoteAddress(new InetSocketAddress(pair.remote().address(), pair.remote().port())).connect();
                connected.addListener(future -> {
                    if (!future.isSuccess()) { result.completeExceptionally(future.cause()); return; }
                    connection = (QuicChannel) future.getNow();
                    CompletableFuture<PeerSession> established = new CompletableFuture<>();
                    try {
                        HandshakeProof proof = SessionAuthenticator.createProof(identity, transcript, HandshakeRole.INITIATOR);
                        connection.createStream(QuicStreamType.BIDIRECTIONAL,
                                NettySessionHandshake.clientStreamHandler(identity, descriptor.nodeId(),
                                        List.of(ProtocolVersion.V1), transcript, proof, new ReplayGuard(), established))
                                .addListener(stream -> {
                                    if (!stream.isSuccess()) established.completeExceptionally(stream.cause());
                                });
                        established.whenComplete((session, failure) -> {
                            if (failure == null) result.complete(session); else result.completeExceptionally(failure);
                        });
                    } catch (RuntimeException exception) { result.completeExceptionally(exception); }
                });
                return result;
            }

            @Override
            public void cancel() {
                if (connection != null) connection.close().addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        };
    }

    private static CandidateGatheringResultView gather(String id, int port) throws Exception {
        CandidateProvider provider = new LocalInterfaceCandidateProvider(id, port, 0);
        try (CandidateGatherer gatherer = new CandidateGatherer(CandidateGatheringPolicy.defaults())) {
            return new CandidateGatheringResultView(gatherer.gather(List.of(provider)).completion()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).candidates());
        }
    }

    private static NodeIdentity identity(String value) throws Exception {
        if (value == null) throw new IllegalArgumentException("--identity is required");
        FileIdentityStore store = new FileIdentityStore(Path.of(value));
        try { return store.load(); } catch (java.io.IOException missing) {
            NodeIdentity created = NodeIdentity.generate();
            store.save(created);
            return created;
        }
    }

    private static void printSession(PeerSession session) {
        System.out.println("AUTHENTICATED_REMOTE=" + session.remoteNodeId());
        System.out.println("SESSION_ID=" + session.sessionId());
        System.out.println("CONTROL_READY=" + session.isUsable());
        System.out.println("LIVENESS=" + session.livenessState());
    }

    private static String password(Map<String, String> options) {
        String variable = options.get("tls-password-env");
        if (variable != null) {
            String value = System.getenv(variable);
            if (value == null || value.isEmpty()) throw new IllegalArgumentException("TLS password environment variable is empty");
            return value;
        }
        Console console = System.console();
        if (console == null) throw new IllegalStateException("interactive TLS password prompt unavailable");
        return new String(console.readPassword("TLS keystore password: "));
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + key + " is required");
        return value;
    }

    private static Map<String, String> options(String[] arguments) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.length) continue;
            values.put(arguments[index].substring(2), arguments[++index]);
        }
        if (arguments.length > 0 && !arguments[0].startsWith("--")) values.put("mode", arguments[0]);
        return values;
    }

    private static String usage() {
        return "identity --identity FILE | server --identity FILE --descriptor FILE --expected-client ID "
                + "--tls-keystore FILE [--tls-password-env NAME] [--port PORT] | client --identity FILE "
                + "--descriptor FILE --expected-node ID";
    }

    private record CandidateGatheringResultView(List<Candidate> candidates) { }

    private static final class TlsMaterial implements AutoCloseable {
        private final PrivateKey key;
        private final X509Certificate certificate;

        private TlsMaterial(PrivateKey key, X509Certificate certificate) {
            this.key = key; this.certificate = certificate;
        }

        private static TlsMaterial load(Path path, String password) throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(path)) { store.load(input, password.toCharArray()); }
            String alias = store.aliases().nextElement();
            return new TlsMaterial((PrivateKey) store.getKey(alias, password.toCharArray()),
                    (X509Certificate) store.getCertificate(alias));
        }

        @Override public void close() { }
    }
}
