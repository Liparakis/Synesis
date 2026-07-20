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
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

/**
 * Link-owned synchronous onboarding façade.
 *
 * <p>This type owns the existing identity, invitation, candidate, Netty,
 * handshake, admission, liveness, work, and cleanup lifecycle. It emits facts
 * to the supplied listener and never writes terminal output or maps process
 * exit codes. Each operation is bounded and cleans up all resources before it
 * returns or throws.
 *
 * <p>The type is thread-confined: one operation may be active at a time, and
 * the listener is called synchronously on the operation thread.
 *
 * @since 1.0
 */
public final class Onboarding {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 16;
    private final Path profileDirectory;
    private final Consumer<OnboardingEvent> events;

    /**
     * Creates an onboarding façade for one profile.
     *
     * @param profileDirectory profile directory; it may be created by host or
     *                         join identity bootstrap
     * @param events synchronous operational-event listener
     */
    public Onboarding(Path profileDirectory, Consumer<OnboardingEvent> events) {
        this.profileDirectory = Objects.requireNonNull(profileDirectory, "profile directory");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Creates a façade using the platform-local default profile directory.
     *
     * @param events synchronous operational-event listener
     * @return configured façade
     */
    public static Onboarding defaults(Consumer<OnboardingEvent> events) {
        return new Onboarding(IdentityBootstrap.defaultDirectory(), events);
    }

    /**
     * Loads or creates the local identity and emits its status and node ID.
     *
     * @return stable local node ID
     * @throws OnboardingFailure if identity storage is invalid or inaccessible
     */
    public String showIdentity() throws OnboardingFailure {
        try {
            IdentityBootstrap.Result result = bootstrap().loadOrCreate();
            emit(result.created() ? OnboardingEventType.IDENTITY_CREATED : OnboardingEventType.IDENTITY_LOADED, "");
            emit(OnboardingEventType.NODE_ID, result.identity().nodeId());
            return result.identity().nodeId();
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.IDENTITY_FAILED, failure);
        }
    }

    /**
     * Hosts one signed invitation and waits for the authenticated peer.
     *
     * @param expectedPeer optional expected peer node ID, or {@code null}
     * @throws OnboardingFailure when identity, listener, candidate, or session
     *                            setup fails or the bounded host wait expires
     */
    public void host(String expectedPeer) throws OnboardingFailure {
        IdentityBootstrap.Result local = loadIdentity();
        emit(local.created() ? OnboardingEventType.IDENTITY_CREATED : OnboardingEventType.IDENTITY_LOADED, "");
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
            udp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(ssl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter(), NettySessionHandshake.serverInvitationStreamHandler(
                                    local.identity(), expectedPeer, List.of(ProtocolVersion.V1), new ReplayGuard(),
                                    established, org.synesis.link.session.LivenessConfiguration.DEFAULT, admission)))
                    .bind(new InetSocketAddress(0)).sync().channel();
            int port = ((InetSocketAddress) udp.localAddress()).getPort();
            emit(OnboardingEventType.SESSION_CREATED, "");
            emit(OnboardingEventType.LISTENER_READY, "");
            List<Candidate> candidates = gather("host", port);
            emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
            Instant issued = Instant.now();
            CandidateDescriptor descriptor = CandidateDescriptor.create(local.identity(), issued,
                    issued.plus(SessionInvitation.DEFAULT_LIFETIME), candidates);
            SessionInvitation invitation = SessionInvitation.create(local.identity(), sessionId, ProtocolVersion.V1,
                    descriptor.issuedAt(), descriptor.expiresAt(), capability, descriptor);
            emit(OnboardingEventType.DESCRIPTOR_CREATED, "");
            emit(OnboardingEventType.INVITE_CREATED, "");
            emit(OnboardingEventType.SHARE_LINK, invitation.shareLink());
            PeerSession session;
            try {
                session = established.get(SessionInvitation.DEFAULT_LIFETIME.toSeconds() + 30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
            }
            emitSession(session);
            session.terminalCompletion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            emit(OnboardingEventType.SESSION_CLOSED, "");
        } catch (OnboardingFailure failure) {
            throw failure;
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
        } finally {
            if (udp != null) udp.close().syncUninterruptibly();
            if (group != null) group.shutdownGracefully().syncUninterruptibly();
            admission.close();
            if (tls != null) {
                try {
                    tls.close();
                } catch (java.io.IOException ignored) {
                    // The bounded transport resources have already been closed.
                }
            }
        }
    }

    /**
     * Verifies an invitation and completes one authenticated join operation.
     *
     * @param link exact signed share link supplied by the user
     * @throws OnboardingFailure for invalid invitation, unusable candidates,
     *                            identity mismatch, or bounded connection failure
     */
    public void join(String link) throws OnboardingFailure {
        SessionInvitation invitation;
        try {
            invitation = SessionInvitation.fromShareLink(link);
            emit(OnboardingEventType.INVITE_PARSED, "");
            if (!invitation.protocolVersion().equals(ProtocolVersion.V1)
                    || !invitation.verifyAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW)) {
                throw new IllegalArgumentException("invalid invitation");
            }
            emit(OnboardingEventType.INVITE_VERIFIED, "");
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.INVITE_INVALID, failure);
        }
        CandidateDescriptor host;
        try {
            host = CandidateDescriptor.decode(invitation.descriptorEncoded());
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.INVITE_INVALID, failure);
        }
        IdentityBootstrap.Result local = loadIdentity();
        emit(local.created() ? OnboardingEventType.IDENTITY_CREATED : OnboardingEventType.IDENTITY_LOADED, "");
        emit(OnboardingEventType.HOST_IDENTITY_PINNED, host.nodeId());
        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        Channel udp = null;
        try {
            QuicSslContext ssl = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(SynesisLink.ALPN).build();
            udp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(ssl)).bind(new InetSocketAddress(0)).sync().channel();
            List<Candidate> localCandidates = gather("join", ((InetSocketAddress) udp.localAddress()).getPort());
            emit(OnboardingEventType.LOCAL_DESCRIPTOR_CREATED, "");
            emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(localCandidates.size()));
            List<CandidatePair> pairs = CandidatePairs.generate(localCandidates, host.candidates(), 8);
            if (pairs.isEmpty()) throw failure(OnboardingFailureCode.NO_USABLE_CANDIDATE, null);
            Channel clientUdp = udp;
            byte[] initiatorNonce = randomBytes();
            byte[] responderNonce = randomBytes();
            HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN,
                    invitation.sessionId(), 1, 1, initiatorNonce, responderNonce, invitation.capability(),
                    local.identity().nodeId(), local.identity().publicKeyEncoded(), host.nodeId(),
                    host.publicKeyEncoded());
            try (CandidateRacer racer = new CandidateRacer(new ConnectionPolicy(8, 8, 2, Duration.ofMillis(100),
                    Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2), 16))) {
                DirectConnectionResult result = racer.race(pairs, host.nodeId(), pair -> attempt(local.identity(), host,
                        transcript, clientUdp, pair)).completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
                if (result.session() == null) throw failure(OnboardingFailureCode.CONNECTION_FAILED, null);
                PeerSession session = result.session();
                if (!session.hasRemotePublicKey(host.publicKeyEncoded())) {
                    throw failure(OnboardingFailureCode.HOST_IDENTITY_MISMATCH, null);
                }
                emit(OnboardingEventType.PATH_SELECTED, pairs.get(0).identifier());
                emitSession(session);
                var work = session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(),
                        DemoWorkRequest.DESCRIBE_SESSION)).toCompletableFuture().get(10, TimeUnit.SECONDS);
                emit(OnboardingEventType.WORK_RESULT, work.status().toString());
                session.closeGracefully(SessionCloseReason.LOCAL_REQUEST).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                emit(OnboardingEventType.SESSION_CLOSED, "");
            }
        } catch (OnboardingFailure failure) {
            throw failure;
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw failure(OnboardingFailureCode.CONNECTION_FAILED, timeout);
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
        } finally {
            if (udp != null) udp.close().syncUninterruptibly();
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private IdentityBootstrap bootstrap() { return new IdentityBootstrap(profileDirectory); }

    private IdentityBootstrap.Result loadIdentity() throws OnboardingFailure {
        try {
            return bootstrap().loadOrCreate();
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.IDENTITY_FAILED, failure);
        }
    }

    private void emitSession(PeerSession session) {
        emit(OnboardingEventType.PEER_CONNECTED, "");
        emit(OnboardingEventType.PEER_IDENTITY_VERIFIED, session.remoteNodeId());
        emit(OnboardingEventType.CONTROL_READY, Boolean.toString(session.isUsable()));
        emit(OnboardingEventType.LIVENESS, session.livenessState().toString());
    }

    private void emit(OnboardingEventType type, String value) { events.accept(new OnboardingEvent(type, value)); }

    private static byte[] randomBytes() {
        byte[] value = new byte[NONCE_BYTES];
        RANDOM.nextBytes(value);
        return value;
    }

    private static List<Candidate> gather(String id, int port) throws Exception {
        CandidateProvider provider = new LocalInterfaceCandidateProvider(id, port, 0);
        try (CandidateGatherer gatherer = new CandidateGatherer(CandidateGatheringPolicy.defaults())) {
            return gatherer.gather(List.of(provider)).completion().toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .candidates();
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
                            if (!future.isSuccess()) {
                                result.completeExceptionally(future.cause());
                                return;
                            }
                            connection = (QuicChannel) future.getNow();
                            CompletableFuture<PeerSession> established = new CompletableFuture<>();
                            try {
                                HandshakeProof proof = SessionAuthenticator.createProof(identity, transcript,
                                        HandshakeRole.INITIATOR);
                                connection.createStream(QuicStreamType.BIDIRECTIONAL,
                                        NettySessionHandshake.clientStreamHandler(identity, host.nodeId(), transcript,
                                                proof, new ReplayGuard(), established));
                                established.whenComplete((session, failure) -> {
                                    if (failure == null) result.complete(session);
                                    else result.completeExceptionally(failure);
                                });
                            } catch (Exception exception) {
                                result.completeExceptionally(exception);
                            }
                        });
                return result;
            }

            @Override
            public void cancel() {
                if (connection != null) connection.close().addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            }
        };
    }

    private static OnboardingFailure failure(OnboardingFailureCode code, Throwable cause) {
        return new OnboardingFailure(code, cause);
    }
}
