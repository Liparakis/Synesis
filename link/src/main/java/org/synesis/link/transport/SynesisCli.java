package org.synesis.link.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
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
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.SessionInvitation;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;
import org.synesis.link.session.SessionCloseReason;

/** Terminal-first zero-configuration host and join workflow. */
public final class SynesisCli {
    private static final SecureRandom RANDOM = new SecureRandom();
    private SynesisCli() { }

    /** Runs {@code host}, {@code join}, or {@code identity show}.
     * @param arguments command arguments
     * @throws Exception when bootstrap, parsing, transport, or authentication fails
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0 || "--help".equals(arguments[0])) {
            System.out.println("host | join <synesis://join/...> | identity show");
            return;
        }
        try {
            switch (arguments[0]) {
                case "host" -> host(options(arguments));
                case "join" -> {
                    if (arguments.length != 2) throw new IllegalArgumentException("join requires one share link");
                    join(arguments[1]);
                }
                case "identity" -> identityShow();
                default -> throw new IllegalArgumentException("unknown command");
            }
        } catch (Exception failure) {
            String code = failureCode(failure);
            System.out.println("FAILURE=" + code);
            throw failure;
        }
    }

    private static void identityShow() throws Exception {
        IdentityBootstrap.Result result = new IdentityBootstrap(IdentityBootstrap.defaultDirectory()).loadOrCreate();
        System.out.println(result.created() ? "IDENTITY_CREATED" : "IDENTITY_LOADED");
        System.out.println("NODE_ID=" + result.identity().nodeId());
    }

    private static void host(Map<String, String> options) throws Exception {
        IdentityBootstrap.Result local = new IdentityBootstrap(IdentityBootstrap.defaultDirectory()).loadOrCreate();
        System.out.println(local.created() ? "IDENTITY_CREATED" : "IDENTITY_LOADED");
        NodeIdentity identity = local.identity();
        UUID sessionId = UUID.randomUUID();
        byte[] capability = new byte[SessionInvitation.CAPABILITY_BYTES];
        RANDOM.nextBytes(capability);
        InvitationAdmission admission = new InvitationAdmission(sessionId, capability);
        MultiThreadIoEventLoopGroup group = null;
        Channel udp = null;
        EphemeralTlsMaterial tls = null;
        try {
            tls = EphemeralTlsMaterial.create();
            group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
            CompletableFuture<PeerSession> established = new CompletableFuture<>();
            QuicSslContext ssl = QuicSslContextBuilder.forServer(tls.key, null, tls.certificate)
                    .applicationProtocols(SynesisLink.ALPN).build();
            String expectedPeer = options.get("expect-peer");
            udp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(ssl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter(), NettySessionHandshake.serverInvitationStreamHandler(
                                    identity, expectedPeer, List.of(ProtocolVersion.V1), new ReplayGuard(), established,
                                    org.synesis.link.session.LivenessConfiguration.DEFAULT, admission)))
                    .bind(new InetSocketAddress(0)).sync().channel();
            int port = ((InetSocketAddress) udp.localAddress()).getPort();
            System.out.println("SESSION_CREATED");
            System.out.println("LISTENER_READY");
            List<Candidate> candidates = gather("host", port);
            System.out.println("CANDIDATES_GATHERED=" + candidates.size());
            Instant issued = Instant.now();
            CandidateDescriptor descriptor = CandidateDescriptor.create(identity, issued,
                    issued.plus(SessionInvitation.DEFAULT_LIFETIME), candidates);
            SessionInvitation invitation = SessionInvitation.create(identity, sessionId, ProtocolVersion.V1,
                    descriptor.issuedAt(), descriptor.expiresAt(), capability, descriptor);
            System.out.println("DESCRIPTOR_CREATED");
            System.out.println("INVITE_CREATED");
            String shareLink = invitation.shareLink();
            System.out.println("SHARE_LINK=" + shareLink);
            try {
                String qr = new CompactQrRenderer().render(shareLink);
                System.out.println("QR_RENDERED=COMPACT");
                System.out.println(qr);
            } catch (IllegalArgumentException unsupported) {
                String reason = "TERMINAL_TOO_NARROW".equals(unsupported.getMessage())
                        ? "TERMINAL_TOO_NARROW" : "UNAVAILABLE";
                System.out.println("QR_SKIPPED=" + reason);
            }
            System.out.println("Waiting for peer...");
            PeerSession session = established.get(SessionInvitation.DEFAULT_LIFETIME.toSeconds() + 30,
                    TimeUnit.SECONDS);
            printSession(session);
            session.terminalCompletion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            System.out.println("SESSION_CLOSED");
        } finally {
            if (udp != null) udp.close().syncUninterruptibly();
            if (group != null) group.shutdownGracefully().syncUninterruptibly();
            admission.close();
            if (tls != null) tls.close();
        }
    }

    private static void join(String link) throws Exception {
        SessionInvitation invitation;
        try {
            invitation = SessionInvitation.fromShareLink(link);
            System.out.println("INVITE_PARSED");
            if (!invitation.protocolVersion().equals(ProtocolVersion.V1)
                    || !invitation.verifyAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW)) {
                throw new IllegalArgumentException("INVITE_INVALID");
            }
            System.out.println("INVITE_VERIFIED");
        } catch (Exception exception) {
            throw new IllegalArgumentException("INVITE_INVALID", exception);
        }
        CandidateDescriptor host = CandidateDescriptor.decode(invitation.descriptorEncoded());
        IdentityBootstrap.Result local = new IdentityBootstrap(IdentityBootstrap.defaultDirectory()).loadOrCreate();
        System.out.println(local.created() ? "IDENTITY_CREATED" : "IDENTITY_LOADED");
        System.out.println("HOST_IDENTITY_PINNED=" + host.nodeId());
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel udp = null;
        try {
            QuicSslContext ssl = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(SynesisLink.ALPN).build();
            udp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(ssl)).bind(new InetSocketAddress(0)).sync().channel();
            List<Candidate> localCandidates = gather("join", ((InetSocketAddress) udp.localAddress()).getPort());
            System.out.println("LOCAL_DESCRIPTOR_CREATED");
            System.out.println("CANDIDATES_GATHERED=" + localCandidates.size());
            List<CandidatePair> pairs = CandidatePairs.generate(localCandidates, host.candidates(), 8);
            if (pairs.isEmpty()) throw new IllegalStateException("NO_USABLE_CANDIDATE");
            Channel clientUdp = udp;
            byte[] initiatorNonce = new byte[16];
            byte[] responderNonce = new byte[16];
            RANDOM.nextBytes(initiatorNonce);
            RANDOM.nextBytes(responderNonce);
            HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN,
                    invitation.sessionId(), 1, 1, initiatorNonce, responderNonce, invitation.capability(),
                    local.identity().nodeId(), local.identity().publicKeyEncoded(), host.nodeId(),
                    host.publicKeyEncoded());
            try (CandidateRacer racer = new CandidateRacer(new ConnectionPolicy(8, 8, 2, Duration.ofMillis(100),
                    Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2), 16))) {
                DirectConnectionResult result = racer.race(pairs, host.nodeId(), pair -> attempt(local.identity(), host,
                        transcript, clientUdp, pair)).completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
                if (result.session() == null) throw new IllegalStateException("CONNECTION_FAILED");
                PeerSession session = result.session();
                if (!session.hasRemotePublicKey(host.publicKeyEncoded())) {
                    throw new IllegalStateException("HOST_IDENTITY_MISMATCH");
                }
                System.out.println("PATH_SELECTED=" + pairs.get(0).identifier());
                printSession(session);
                var work = session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(),
                        DemoWorkRequest.DESCRIBE_SESSION)).toCompletableFuture().get(10, TimeUnit.SECONDS);
                System.out.println("WORK_RESULT=" + work.status());
                session.closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                System.out.println("SESSION_CLOSED");
            }
        } finally {
            if (udp != null) udp.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static ConnectionAttempt attempt(NodeIdentity identity, CandidateDescriptor host,
            HandshakeTranscript transcript, Channel udp, CandidatePair pair) {
        return new ConnectionAttempt() {
            private volatile QuicChannel connection;
            @Override
            public CompletionStage<PeerSession> connect(org.synesis.link.candidate.CandidateCancellation cancellation) {
                CompletableFuture<PeerSession> result = new CompletableFuture<>();
                QuicChannel.newBootstrap(udp).handler(new ChannelInboundHandlerAdapter())
                        .streamHandler(new ChannelInboundHandlerAdapter())
                        .remoteAddress(new InetSocketAddress(pair.remote().address(), pair.remote().port())).connect()
                        .addListener(future -> {
                            if (!future.isSuccess()) { result.completeExceptionally(future.cause()); return; }
                            connection = (QuicChannel) future.getNow();
                            CompletableFuture<PeerSession> established = new CompletableFuture<>();
                            try {
                                HandshakeProof proof = SessionAuthenticator.createProof(identity, transcript,
                                        HandshakeRole.INITIATOR);
                                connection.createStream(QuicStreamType.BIDIRECTIONAL,
                                        NettySessionHandshake.clientStreamHandler(identity, host.nodeId(), transcript,
                                                proof, new ReplayGuard(), established));
                                established.whenComplete((session, failure) -> {
                                    if (failure == null) result.complete(session); else result.completeExceptionally(failure);
                                });
                            } catch (Exception exception) { result.completeExceptionally(exception); }
                        });
                return result;
            }
            @Override public void cancel() {
                if (connection != null) connection.close().addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        };
    }

    private static List<Candidate> gather(String id, int port) throws Exception {
        CandidateProvider provider = new LocalInterfaceCandidateProvider(id, port, 0);
        try (CandidateGatherer gatherer = new CandidateGatherer(CandidateGatheringPolicy.defaults())) {
            return gatherer.gather(List.of(provider)).completion().toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .candidates();
        }
    }

    private static void printSession(PeerSession session) {
        System.out.println("PEER_CONNECTED");
        System.out.println("PEER_IDENTITY_VERIFIED=" + session.remoteNodeId());
        System.out.println("CONTROL_READY=" + session.isUsable());
        System.out.println("LIVENESS=" + session.livenessState());
    }

    private static String failureCode(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getMessage() == null) current = current.getCause();
        String message = current.getMessage();
        if (message != null && message.matches("[A-Z][A-Z0-9_]+")) return message;
        if (failure instanceof java.util.concurrent.TimeoutException) return "HOST_TIMEOUT";
        return "ONBOARDING_FAILED";
    }

    private static Map<String, String> options(String[] arguments) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (int i = 1; i + 1 < arguments.length; i++) {
            if (arguments[i].startsWith("--")) values.put(arguments[i].substring(2), arguments[++i]);
        }
        return values;
    }
}
