package org.synesis.link.onboarding;

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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.synesis.link.candidate.StunCandidateProvider;
import org.synesis.link.candidate.TraversalCoordinator;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.SessionInvitation;
import org.synesis.link.protocol.TraversalAnswer;
import org.synesis.link.protocol.TraversalInvitation;
import org.synesis.link.protocol.TraversalOffer;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;
import org.synesis.link.session.SessionCloseReason;
import org.synesis.link.transport.quic.EphemeralTlsMaterial;
import org.synesis.link.transport.quic.NettyQuicTransport;
import org.synesis.link.transport.quic.NettySessionHandshake;
import org.synesis.link.transport.quic.NettyStunBindingTransport;
import org.synesis.link.transport.quic.NettyTraversalExchange;

/**
 * Link-owned synchronous onboarding faÃ§ade.
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
    private final InetSocketAddress stunServer;

    /**
     * Creates an onboarding faÃ§ade for one profile.
     *
     * @param profileDirectory profile directory; it may be created by host or
     *                         join identity bootstrap
     * @param events           synchronous operational-event listener
     */
    public Onboarding(Path profileDirectory, Consumer<OnboardingEvent> events) {
        this(profileDirectory, events, null);
    }

    /**
     * Creates an onboarding facade with an explicitly configured optional STUN server.
     *
     * <p>The server is used only for bounded server-reflexive discovery. STUN
     * traffic is sent through the same bound parent UDP channel that carries
     * Link QUIC traffic. A {@code null} server preserves the ordinary
     * local/manual candidate path.
     *
     * @param profileDirectory profile directory; it may be created by host or
     *                         join identity bootstrap
     * @param events           synchronous operational-event listener
     * @param stunServer       configured STUN endpoint, or {@code null} to disable
     */
    public Onboarding(Path profileDirectory, Consumer<OnboardingEvent> events, InetSocketAddress stunServer) {
        this.profileDirectory = Objects.requireNonNull(profileDirectory, "profile directory");
        this.events = Objects.requireNonNull(events, "events");
        this.stunServer = stunServer;
    }

    /**
     * Creates a faÃ§ade using the platform-local default profile directory.
     *
     * @param events synchronous operational-event listener
     * @return configured faÃ§ade
     */
    public static Onboarding defaults(Consumer<OnboardingEvent> events) {
        return new Onboarding(IdentityBootstrap.defaultDirectory(), events);
    }

    /**
     * Opens a host session and creates a human-mediated SLO1 link.
     *
     * <p>The returned handle owns the bound UDP channel and must remain open
     * while the link is copied and while the answer is imported.
     *
     * @param expectedPeer optional expected responder node ID
     * @return open host handle
     * @throws OnboardingFailure if identity, transport, or candidate setup fails
     */
    public PreparedHost createInvitation(String expectedPeer) throws OnboardingFailure {
        return createInvitation(expectedPeer, null, _ -> {
        });
    }

    /**
     * Opens a host session and creates a human-mediated SLO1 link with callbacks.
     *
     * @param expectedPeer       optional expected responder node ID
     * @param applicationHandler optional application-stream handler
     * @param sessionAction      callback invoked after control readiness
     * @return open host handle
     * @throws OnboardingFailure if identity, transport, or candidate setup fails
     */
    public PreparedHost createInvitation(String expectedPeer, PeerSession.ApplicationStreamHandler applicationHandler,
            Consumer<PeerSession> sessionAction) throws OnboardingFailure {
        Objects.requireNonNull(sessionAction, "session action");
        IdentityBootstrap.Result local = loadIdentity();
        emit(local.created() ? OnboardingEventType.IDENTITY_CREATED : OnboardingEventType.IDENTITY_LOADED, "");
        UUID sessionId = UUID.randomUUID();
        byte[] capability = new byte[SessionInvitation.CAPABILITY_BYTES];
        RANDOM.nextBytes(capability);
        InvitationAdmission admission = new InvitationAdmission(sessionId, capability);
        MultiThreadIoEventLoopGroup group = null;
        Channel udp = null;
        EphemeralTlsMaterial tls = null;
        NettyStunBindingTransport stun = null;
        try {
            tls = EphemeralTlsMaterial.create();
            group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
            CompletableFuture<PeerSession> established = new CompletableFuture<>();
            QuicSslContext ssl = QuicSslContextBuilder.forServer(tls.key, null, tls.certificate)
                    .applicationProtocols(SynesisLink.ALPN)
                    .build();
            udp = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(ssl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter(), NettySessionHandshake.serverInvitationStreamHandler(
                                    local.identity(), expectedPeer, List.of(ProtocolVersion.V1), new ReplayGuard(),
                                    established, org.synesis.link.session.LivenessConfiguration.DEFAULT, admission,
                                    applicationHandler)))
                    .bind(new InetSocketAddress(0))
                    .sync()
                    .channel();
            if (stunServer != null) {
                stun = new NettyStunBindingTransport(udp, Duration.ofSeconds(2));
            }
            int port = ((InetSocketAddress) udp.localAddress()).getPort();
            emit(OnboardingEventType.SESSION_CREATED, "");
            emit(OnboardingEventType.LISTENER_READY, "");
            List<CandidateProvider> providers = stun == null ? List.of()
                    : List.of(new StunCandidateProvider(stunServer, 100, stun));
            List<Candidate> candidates = gather("host", port, providers);
            emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
            Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            Instant expires = issued.plus(SessionInvitation.DEFAULT_LIFETIME);
            CandidateDescriptor descriptor = CandidateDescriptor.create(local.identity(), issued, expires, candidates);
            SessionInvitation invitation = SessionInvitation.create(local.identity(), sessionId, ProtocolVersion.V1,
                    issued, expires, capability, descriptor);
            TraversalOffer offer = TraversalOffer.create(local.identity(), expectedPeer, sessionId,
                    digest(invitation.encoded()), issued, expires, randomTraversalBytes(), descriptor);
            TraversalInvitation link = TraversalInvitation.create(invitation, offer);
            emit(OnboardingEventType.DESCRIPTOR_CREATED, "");
            emit(OnboardingEventType.INVITE_CREATED, "");
            emit(OnboardingEventType.SHARE_LINK, link.shareLink());
            return new PreparedHost(local.identity(), expectedPeer, invitation, offer, established, group, udp, tls, stun,
                    admission, sessionAction);
        } catch (Exception failure) {
            closeResources(udp, group, tls, stun, admission);
            throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
        }
    }

    /**
     * Imports a human-mediated SLO1 link, creates an SLA2 answer, and keeps the
     * local QUIC endpoint ready for the direct attempt.
     *
     * @param link exact SLO1 link received from the host
     * @return open join handle containing the answer link
     * @throws OnboardingFailure if the link, identity, transport, or candidates are invalid
     */
    public PreparedJoin importInvitation(String link) throws OnboardingFailure {
        return importInvitation(link, null, session -> {
            try {
                var work = session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(),
                                DemoWorkRequest.DESCRIBE_SESSION))
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                emit(OnboardingEventType.WORK_RESULT, work.status().toString());
            } catch (Exception failure) {
                throw new IllegalStateException("demo work failed", failure);
            }
        });
    }

    /**
     * Imports a human-mediated SLO1 link with callbacks and creates its SLA2 answer.
     *
     * @param link               exact SLO1 link received from the host
     * @param applicationHandler optional application-stream handler
     * @param sessionAction      callback invoked after control readiness
     * @return open join handle containing the answer link
     * @throws OnboardingFailure if the link, identity, transport, or candidates are invalid
     */
    public PreparedJoin importInvitation(String link, PeerSession.ApplicationStreamHandler applicationHandler,
            Consumer<PeerSession> sessionAction) throws OnboardingFailure {
        Objects.requireNonNull(sessionAction, "session action");
        SessionInvitation invitation;
        TraversalOffer offer;
        try {
            TraversalInvitation mediated = TraversalInvitation.fromShareLink(link);
            IdentityBootstrap.Result local = loadIdentity();
            emit(local.created() ? OnboardingEventType.IDENTITY_CREATED : OnboardingEventType.IDENTITY_LOADED, "");
            if (!mediated.verifyAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW, local.identity().nodeId())) {
                throw new IllegalArgumentException("SLO1 is not valid for this identity");
            }
            invitation = mediated.invitation();
            offer = mediated.offer();
            CandidateDescriptor host = CandidateDescriptor.decode(invitation.descriptorEncoded());
            emit(OnboardingEventType.INVITE_PARSED, "");
            emit(OnboardingEventType.INVITE_VERIFIED, "");
            emit(OnboardingEventType.HOST_IDENTITY_PINNED, host.nodeId());
            MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
            Channel udp = null;
            NettyStunBindingTransport stun = null;
            try {
                QuicSslContext ssl = QuicSslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .applicationProtocols(SynesisLink.ALPN)
                        .build();
                udp = new Bootstrap().group(group)
                        .channel(NioDatagramChannel.class)
                        .handler(NettyQuicTransport.clientCodec(ssl))
                        .bind(new InetSocketAddress(0))
                        .sync()
                        .channel();
                if (stunServer != null) {
                    stun = new NettyStunBindingTransport(udp, Duration.ofSeconds(2));
                }
                List<CandidateProvider> providers = stun == null ? List.of()
                        : List.of(new StunCandidateProvider(stunServer, 100, stun));
                List<Candidate> candidates = gather("join", ((InetSocketAddress) udp.localAddress()).getPort(), providers);
                CandidateDescriptor localDescriptor = CandidateDescriptor.create(local.identity(),
                        Instant.now().truncatedTo(ChronoUnit.SECONDS),
                        invitation.expiresAt(), candidates);
                TraversalAnswer answer = TraversalAnswer.create(local.identity(), offer, localDescriptor,
                        randomTraversalBytes());
                emit(OnboardingEventType.LOCAL_DESCRIPTOR_CREATED, "");
                emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
                emit(OnboardingEventType.ANSWER_LINK, answer.shareLink());
                return new PreparedJoin(local.identity(), invitation, offer, answer, host, localDescriptor, group, udp,
                        stun, applicationHandler, sessionAction);
            } catch (Exception failure) {
                closeResources(udp, group, null, stun, null);
                throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
            }
        } catch (OnboardingFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.INVITE_INVALID, failure);
        }
    }

    private static void closeResources(Channel udp, MultiThreadIoEventLoopGroup group, EphemeralTlsMaterial tls,
            NettyStunBindingTransport stun, InvitationAdmission admission) {
        if (stun != null) {
            stun.close();
        }
        if (udp != null) {
            udp.close().syncUninterruptibly();
        }
        if (group != null) {
            group.shutdownGracefully().syncUninterruptibly();
        }
        if (admission != null) {
            admission.close();
        }
        if (tls != null) {
            try {
                tls.close();
            } catch (java.io.IOException ignored) {
                // All bounded network resources have already been closed.
            }
        }
    }

    /**
     * Open host-side state for one human-mediated invitation.
     *
     * <p>The instance is single-use: one valid answer may be imported before
     * the authenticated session is awaited.
     */
    public final class PreparedHost implements AutoCloseable {

        private final NodeIdentity identity;
        private final String expectedPeer;
        private final SessionInvitation invitation;
        private final TraversalOffer offer;
        private final CompletableFuture<PeerSession> established;
        private final MultiThreadIoEventLoopGroup group;
        private final Channel udp;
        private final EphemeralTlsMaterial tls;
        private final NettyStunBindingTransport stun;
        private final InvitationAdmission admission;
        private final Consumer<PeerSession> sessionAction;
        private final AtomicBoolean answerImported = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private PreparedHost(NodeIdentity identity, String expectedPeer, SessionInvitation invitation,
                TraversalOffer offer, CompletableFuture<PeerSession> established, MultiThreadIoEventLoopGroup group,
                Channel udp, EphemeralTlsMaterial tls, NettyStunBindingTransport stun, InvitationAdmission admission,
                Consumer<PeerSession> sessionAction) {
            this.identity = identity;
            this.expectedPeer = expectedPeer;
            this.invitation = invitation;
            this.offer = offer;
            this.established = established;
            this.group = group;
            this.udp = udp;
            this.tls = tls;
            this.stun = stun;
            this.admission = admission;
            this.sessionAction = sessionAction;
        }

        /**
         * Returns the exact SLO1 link to send to the other user.
         *
         * @return copyable signed SLO1 link
         */
        public String invitationLink() {
            return TraversalInvitation.create(invitation, offer).shareLink();
        }

        /**
         * Imports one exact SLA2 answer and waits for the authenticated session.
         *
         * @param link exact answer link returned by the other user
         * @throws OnboardingFailure if the answer is invalid, reused, or the session fails
         */
        public void importAnswer(String link) throws OnboardingFailure {
            importAnswer(link, false);
        }

        /**
         * Imports one exact answer and returns after authentication while
         * retaining the live Link endpoint and session for an owning runtime.
         *
         * @param link exact answer link returned by the other user
         * @return authenticated control-ready session
         * @throws OnboardingFailure if the answer is invalid, reused, or the session fails
         */
        public PeerSession importAnswerAndKeepAlive(String link) throws OnboardingFailure {
            return importAnswer(link, true);
        }

        private PeerSession importAnswer(String link, boolean keepAlive) throws OnboardingFailure {
            try {
                TraversalAnswer answer = TraversalAnswer.fromShareLink(link);
                if (!answer.verifyAt(Instant.now(), TraversalAnswer.DEFAULT_CLOCK_SKEW, offer)) {
                    throw new IllegalArgumentException("answer signature or offer binding is invalid");
                }
                if (expectedPeer != null && !expectedPeer.equals(answer.responderNodeId())) {
                    throw new IllegalArgumentException("answer responder is not the expected peer");
                }
                if (!answerImported.compareAndSet(false, true)) {
                    throw new IllegalStateException("answer has already been imported");
                }
                emit(OnboardingEventType.ANSWER_VERIFIED, "");
                emit(OnboardingEventType.TRAVERSAL_STARTED, "");
                PeerSession session = established.get(SessionInvitation.DEFAULT_LIFETIME.toSeconds() + 30,
                        TimeUnit.SECONDS);
                emitSession(session);
                sessionAction.accept(session);
                if (!keepAlive) {
                    session.terminalCompletion().toCompletableFuture().get(30, TimeUnit.SECONDS);
                    emit(OnboardingEventType.SESSION_CLOSED, "");
                }
                return session;
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
            } catch (Exception failure) {
                throw failure(OnboardingFailureCode.ANSWER_INVALID, failure);
            }
        }

        /** Releases the bound endpoint and host admission state. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeResources(udp, group, tls, stun, admission);
            }
        }
    }

    /**
     * Open join-side state for one human-mediated answer.
     *
     * <p>The instance is single-use: creating the answer does not destroy the
     * bound endpoint, and {@link #connect()} starts the direct race once.
     */
    public final class PreparedJoin implements AutoCloseable {

        private final NodeIdentity identity;
        private final SessionInvitation invitation;
        private final TraversalOffer offer;
        private final TraversalAnswer answer;
        private final CandidateDescriptor host;
        private final CandidateDescriptor localDescriptor;
        private final MultiThreadIoEventLoopGroup group;
        private final Channel udp;
        private final NettyStunBindingTransport stun;
        private final PeerSession.ApplicationStreamHandler applicationHandler;
        private final Consumer<PeerSession> sessionAction;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private PreparedJoin(NodeIdentity identity, SessionInvitation invitation, TraversalOffer offer,
                TraversalAnswer answer, CandidateDescriptor host, CandidateDescriptor localDescriptor,
                MultiThreadIoEventLoopGroup group, Channel udp, NettyStunBindingTransport stun,
                PeerSession.ApplicationStreamHandler applicationHandler, Consumer<PeerSession> sessionAction) {
            this.identity = identity;
            this.invitation = invitation;
            this.offer = offer;
            this.answer = answer;
            this.host = host;
            this.localDescriptor = localDescriptor;
            this.group = group;
            this.udp = udp;
            this.stun = stun;
            this.applicationHandler = applicationHandler;
            this.sessionAction = sessionAction;
        }

        /**
         * Returns the exact SLA2 link to send back to the host user.
         *
         * @return copyable signed SLA2 link
         */
        public String answerLink() {
            return answer.shareLink();
        }

        /**
         * Starts the existing bounded candidate race and authenticated Link session.
         *
         * @throws OnboardingFailure if the answer is reused, candidates are unusable, or connection fails
         */
        public void connect() throws OnboardingFailure {
            connect(false);
        }

        /**
         * Starts the bounded candidate race and returns after authentication
         * while retaining the live Link endpoint and session for an owning
         * runtime.
         *
         * @return authenticated control-ready session
         * @throws OnboardingFailure if the answer is reused, candidates are unusable, or connection fails
         */
        public PeerSession connectAndKeepAlive() throws OnboardingFailure {
            return connect(true);
        }

        private PeerSession connect(boolean keepAlive) throws OnboardingFailure {
            if (!started.compareAndSet(false, true)) {
                throw failure(OnboardingFailureCode.ANSWER_INVALID,
                        new IllegalStateException("answer has already been used"));
            }
            try {
                TraversalCoordinator coordinator = new TraversalCoordinator(identity, host.nodeId(),
                        invitation.sessionId(), 8);
                coordinator.acceptAsAnswerer(offer, answer, Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW);
                List<CandidatePair> pairs = coordinator.plan(CandidatePairs.generate(localDescriptor.candidates(),
                                host.candidates(), 8))
                        .stream()
                        .map(TraversalCoordinator.Attempt::pair)
                        .toList();
                if (pairs.isEmpty()) {
                    throw failure(OnboardingFailureCode.NO_USABLE_CANDIDATE, null);
                }
                byte[] initiatorNonce = randomBytes();
                byte[] responderNonce = randomBytes();
                HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN,
                        invitation.sessionId(), 1, 1, initiatorNonce, responderNonce, invitation.capability(),
                        identity.nodeId(), identity.publicKeyEncoded(), host.nodeId(), host.publicKeyEncoded());
                emit(OnboardingEventType.TRAVERSAL_STARTED, "");
                try (CandidateRacer racer = new CandidateRacer(new ConnectionPolicy(8, 8, 2,
                        Duration.ofMillis(100), Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2),
                        16))) {
                    DirectConnectionResult result = racer.race(pairs, host.nodeId(), pair -> attempt(identity, host,
                                    transcript, udp, pair, applicationHandler))
                            .completion()
                            .toCompletableFuture()
                            .get(25, TimeUnit.SECONDS);
                    if (result.session() == null) {
                        throw failure(OnboardingFailureCode.CONNECTION_FAILED, null);
                    }
                    PeerSession session = result.session();
                    if (!session.hasRemotePublicKey(host.publicKeyEncoded())) {
                        throw failure(OnboardingFailureCode.HOST_IDENTITY_MISMATCH, null);
                    }
                    emit(OnboardingEventType.PATH_SELECTED, pairs.getFirst().identifier());
                    emitSession(session);
                    sessionAction.accept(session);
                    if (!keepAlive) {
                        session.closeGracefully(SessionCloseReason.LOCAL_REQUEST)
                                .toCompletableFuture()
                                .get(10, TimeUnit.SECONDS);
                        emit(OnboardingEventType.SESSION_CLOSED, "");
                    }
                    return session;
                }
            } catch (OnboardingFailure failure) {
                throw failure;
            } catch (Exception failure) {
                throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
            }
        }

        /** Releases the bound endpoint and candidate resources. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeResources(udp, group, null, stun, null);
            }
        }
    }

    private static byte[] randomBytes() {
        byte[] value = new byte[NONCE_BYTES];
        RANDOM.nextBytes(value);
        return value;
    }

    private static byte[] randomTraversalBytes() {
        byte[] value = new byte[TraversalOffer.NONCE_BYTES];
        RANDOM.nextBytes(value);
        return value;
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static List<Candidate> gather(String id, int port, List<CandidateProvider> optionalProviders)
            throws Exception {
        List<CandidateProvider> providers = new ArrayList<>();
        providers.add(new LocalInterfaceCandidateProvider(id, port, 0));
        providers.addAll(optionalProviders);
        CandidateGatheringPolicy policy = optionalProviders.isEmpty()
                ? CandidateGatheringPolicy.defaults()
                : CandidateGatheringPolicy.directTraversalDefaults();
        try (CandidateGatherer gatherer = new CandidateGatherer(policy)) {
            return gatherer.gather(providers)
                    .completion()
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS)
                    .candidates();
        }
    }

    private static ConnectionAttempt attempt(NodeIdentity identity, CandidateDescriptor host,
            HandshakeTranscript transcript, Channel udp, CandidatePair pair,
            PeerSession.ApplicationStreamHandler applicationHandler) {
        return new ConnectionAttempt() {
            private volatile QuicChannel connection;

            @Override
            public CompletionStage<PeerSession> connect(org.synesis.link.candidate.CandidateCancellation cancellation) {
                CompletableFuture<PeerSession> result = new CompletableFuture<>();
                QuicChannel.newBootstrap(udp)
                        .handler(new ChannelInboundHandlerAdapter())
                        .streamHandler(new ChannelInboundHandlerAdapter())
                        .remoteAddress(new InetSocketAddress(pair.remote()
                                .address(),
                                pair.remote()
                                        .port()))
                        .connect()
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
                                                proof, new ReplayGuard(), established,
                                                org.synesis.link.session.LivenessConfiguration.DEFAULT,
                                                applicationHandler));
                                established.whenComplete((session, failure) -> {
                                    if (failure == null) {
                                        result.complete(session);
                                    } else {
                                        result.completeExceptionally(failure);
                                    }
                                });
                            } catch (Exception exception) {
                                result.completeExceptionally(exception);
                            }
                        });
                return result;
            }

            @Override
            public void cancel() {
                if (connection != null) {
                    connection.close()
                            .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                }
            }
        };
    }

    private static OnboardingFailure failure(OnboardingFailureCode code, Throwable cause) {
        return new OnboardingFailure(code, cause);
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
            emit(OnboardingEventType.NODE_ID,
                    result.identity()
                            .nodeId());
            return result.identity()
                    .nodeId();
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.IDENTITY_FAILED, failure);
        }
    }

    /**
     * Hosts one signed invitation and waits for the authenticated peer.
     *
     * @param expectedPeer optional expected peer node ID, or {@code null}
     * @throws OnboardingFailure when identity, listener, candidate, or session
     *                           setup fails or the bounded host wait expires
     */
    public void host(String expectedPeer) throws OnboardingFailure {
        host(expectedPeer, null, _ -> {
        });
    }

    /**
     * Hosts one invitation and runs a bounded callback on the authenticated session.
     *
     * @param expectedPeer       optional expected peer node ID, or {@code null}
     * @param applicationHandler optional bounded application-stream handler
     * @param sessionAction      callback invoked after control readiness
     * @throws OnboardingFailure if setup, callback, or cleanup fails
     */
    public void host(String expectedPeer, PeerSession.ApplicationStreamHandler applicationHandler,
            Consumer<PeerSession> sessionAction) throws OnboardingFailure {
        Objects.requireNonNull(sessionAction, "session action");
        IdentityBootstrap.Result local = loadIdentity();
        emit(local.created() ? OnboardingEventType.IDENTITY_CREATED : OnboardingEventType.IDENTITY_LOADED, "");
        UUID sessionId = UUID.randomUUID();
        byte[] capability = new byte[SessionInvitation.CAPABILITY_BYTES];
        RANDOM.nextBytes(capability);
        InvitationAdmission admission = new InvitationAdmission(sessionId, capability);
        MultiThreadIoEventLoopGroup group = null;
        Channel udp = null;
        EphemeralTlsMaterial tls = null;
        NettyStunBindingTransport stun = null;
        NettyTraversalExchange traversal = null;
        try {
            tls = EphemeralTlsMaterial.create();
            group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
            CompletableFuture<PeerSession> established = new CompletableFuture<>();
            QuicSslContext ssl = QuicSslContextBuilder.forServer(tls.key, null, tls.certificate)
                    .applicationProtocols(SynesisLink.ALPN)
                    .build();
            udp = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.serverCodec(ssl, InsecureQuicTokenHandler.INSTANCE,
                            new ChannelInboundHandlerAdapter(), NettySessionHandshake.serverInvitationStreamHandler(
                                    local.identity(), expectedPeer, List.of(ProtocolVersion.V1), new ReplayGuard(),
                                    established, org.synesis.link.session.LivenessConfiguration.DEFAULT, admission,
                                    applicationHandler)))
                    .bind(new InetSocketAddress(0))
                    .sync()
                    .channel();
            if (stunServer != null) {
                stun = new NettyStunBindingTransport(udp, Duration.ofSeconds(2));
            }
            int port = ((InetSocketAddress) udp.localAddress()).getPort();
            emit(OnboardingEventType.SESSION_CREATED, "");
            emit(OnboardingEventType.LISTENER_READY, "");
            List<CandidateProvider> optionalProviders = stun == null
                    ? List.of()
                    : List.of(new StunCandidateProvider(stunServer, 100, stun));
            List<Candidate> candidates = gather("host", port, optionalProviders);
            emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(candidates.size()));
            Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            CandidateDescriptor descriptor = CandidateDescriptor.create(local.identity(), issued,
                    issued.plus(SessionInvitation.DEFAULT_LIFETIME), candidates);
            SessionInvitation invitation = SessionInvitation.create(local.identity(), sessionId, ProtocolVersion.V1,
                    descriptor.issuedAt(), descriptor.expiresAt(), capability, descriptor);
            emit(OnboardingEventType.DESCRIPTOR_CREATED, "");
            emit(OnboardingEventType.INVITE_CREATED, "");
            emit(OnboardingEventType.SHARE_LINK, invitation.shareLink());
            byte[] invitationDigest = digest(invitation.encoded());
            traversal = new NettyTraversalExchange(udp, Duration.ofSeconds(10), offer -> {
                try {
                    if (!sessionId.equals(offer.sessionId())
                            || !Arrays.equals(invitationDigest, offer.invitationDigest())
                            || (expectedPeer != null && !expectedPeer.equals(offer.initiatorNodeId()))
                            || !offer.verifyAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW,
                            local.identity().nodeId())) {
                        return null;
                    }
                    return TraversalAnswer.create(local.identity(), offer, descriptor,
                            randomTraversalBytes());
                } catch (Exception invalidOffer) {
                    return null;
                }
            });
            PeerSession session;
            try {
                session = established.get(SessionInvitation.DEFAULT_LIFETIME.toSeconds() + 30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
            }
            emitSession(session);
            sessionAction.accept(session);
            session.terminalCompletion()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
            emit(OnboardingEventType.SESSION_CLOSED, "");
        } catch (OnboardingFailure failure) {
            throw failure;
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw failure(OnboardingFailureCode.HOST_TIMEOUT, timeout);
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
        } finally {
            if (traversal != null) {
                traversal.close();
            }
            if (stun != null) {
                stun.close();
            }
            if (udp != null) {
                udp.close()
                        .syncUninterruptibly();
            }
            if (group != null) {
                group.shutdownGracefully()
                        .syncUninterruptibly();
            }
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
     *                           identity mismatch, or bounded connection failure
     */
    public void join(String link) throws OnboardingFailure {
        join(link, null, session -> {
            try {
                var work = session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(),
                                DemoWorkRequest.DESCRIBE_SESSION))
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                emit(OnboardingEventType.WORK_RESULT,
                        work.status()
                                .toString());
            } catch (Exception failure) {
                throw new IllegalStateException("demo work failed", failure);
            }
        });
    }

    /**
     * Joins one invitation and runs a bounded callback on the authenticated session.
     *
     * @param link               exact signed share link
     * @param applicationHandler optional bounded application-stream handler
     * @param sessionAction      callback invoked after control readiness
     * @throws OnboardingFailure if setup, callback, or cleanup fails
     */
    public void join(String link, PeerSession.ApplicationStreamHandler applicationHandler,
            Consumer<PeerSession> sessionAction) throws OnboardingFailure {
        Objects.requireNonNull(sessionAction, "session action");
        SessionInvitation invitation;
        try {
            invitation = SessionInvitation.fromShareLink(link);
            emit(OnboardingEventType.INVITE_PARSED, "");
            if (!invitation.protocolVersion()
                    .equals(ProtocolVersion.V1)
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
        NettyStunBindingTransport stun = null;
        NettyTraversalExchange traversal = null;
        try {
            QuicSslContext ssl = QuicSslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .applicationProtocols(SynesisLink.ALPN)
                    .build();
            udp = new Bootstrap().group(group)
                    .channel(NioDatagramChannel.class)
                    .handler(NettyQuicTransport.clientCodec(ssl))
                    .bind(new InetSocketAddress(0))
                    .sync()
                    .channel();
            if (stunServer != null) {
                stun = new NettyStunBindingTransport(udp, Duration.ofSeconds(2));
            }
            List<CandidateProvider> optionalProviders = stun == null
                    ? List.of()
                    : List.of(new StunCandidateProvider(stunServer, 100, stun));
            List<Candidate> localCandidates = gather("join", ((InetSocketAddress) udp.localAddress()).getPort(),
                    optionalProviders);
            CandidateDescriptor localDescriptor = CandidateDescriptor.create(local.identity(),
                    Instant.now().truncatedTo(ChronoUnit.SECONDS),
                    invitation.expiresAt(), localCandidates);
            CandidateDescriptor remoteDescriptor = host;
            TraversalCoordinator coordinator = null;
            traversal = new NettyTraversalExchange(udp, Duration.ofSeconds(10), null);
            try {
                TraversalOffer offer = TraversalOffer.create(local.identity(), host.nodeId(), invitation.sessionId(),
                        digest(invitation.encoded()), localDescriptor.issuedAt(), localDescriptor.expiresAt(),
                        randomTraversalBytes(), localDescriptor);
                TraversalAnswer answer = traversal.sendOffer(offer, host.candidates())
                        .toCompletableFuture()
                        .get(12, TimeUnit.SECONDS);
                coordinator = new TraversalCoordinator(local.identity(), host.nodeId(), invitation.sessionId(), 8);
                coordinator.accept(offer, answer, Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW);
                remoteDescriptor = CandidateDescriptor.decode(answer.descriptorEncoded());
            } catch (java.util.concurrent.TimeoutException timeout) {
                // Existing invitation candidates remain a compatible fallback.
            } catch (java.util.concurrent.ExecutionException failure) {
                if (!(failure.getCause() instanceof java.util.concurrent.TimeoutException)) {
                    throw failure;
                }
            }
            emit(OnboardingEventType.LOCAL_DESCRIPTOR_CREATED, "");
            emit(OnboardingEventType.CANDIDATES_GATHERED, Integer.toString(localCandidates.size()));
            List<CandidatePair> pairs = coordinator == null
                    ? CandidatePairs.generate(localCandidates, remoteDescriptor.candidates(), 8)
                    : coordinator.plan(CandidatePairs.generate(localCandidates, remoteDescriptor.candidates(), 8))
                    .stream()
                    .map(TraversalCoordinator.Attempt::pair)
                    .toList();
            if (pairs.isEmpty()) {
                throw failure(OnboardingFailureCode.NO_USABLE_CANDIDATE, null);
            }
            Channel clientUdp = udp;
            byte[] initiatorNonce = randomBytes();
            byte[] responderNonce = randomBytes();
            HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1,
                    SynesisLink.ALPN,
                    invitation.sessionId(),
                    1,
                    1,
                    initiatorNonce,
                    responderNonce,
                    invitation.capability(),
                    local.identity()
                            .nodeId(),
                    local.identity()
                            .publicKeyEncoded(),
                    host.nodeId(),
                    host.publicKeyEncoded());
            try (CandidateRacer racer = new CandidateRacer(new ConnectionPolicy(8, 8, 2, Duration.ofMillis(100),
                    Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(2), 16))) {
                DirectConnectionResult result = racer.race(pairs, host.nodeId(), pair -> attempt(local.identity(), host,
                                transcript, clientUdp, pair, applicationHandler))
                        .completion()
                        .toCompletableFuture()
                        .get(25, TimeUnit.SECONDS);
                if (result.session() == null) {
                    throw failure(OnboardingFailureCode.CONNECTION_FAILED, null);
                }
                PeerSession session = result.session();
                if (!session.hasRemotePublicKey(host.publicKeyEncoded())) {
                    throw failure(OnboardingFailureCode.HOST_IDENTITY_MISMATCH, null);
                }
                emit(OnboardingEventType.PATH_SELECTED,
                        pairs.getFirst()
                                .identifier());
                emitSession(session);
                sessionAction.accept(session);
                session.closeGracefully(SessionCloseReason.LOCAL_REQUEST)
                        .toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
                emit(OnboardingEventType.SESSION_CLOSED, "");
            }
        } catch (OnboardingFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw failure(OnboardingFailureCode.CONNECTION_FAILED, failure);
        } finally {
            if (traversal != null) {
                traversal.close();
            }
            if (stun != null) {
                stun.close();
            }
            if (udp != null) {
                udp.close()
                        .syncUninterruptibly();
            }
            group.shutdownGracefully()
                    .syncUninterruptibly();
        }
    }

    private IdentityBootstrap bootstrap() {
        return new IdentityBootstrap(profileDirectory);
    }

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
        emit(OnboardingEventType.LIVENESS,
                session.livenessState()
                        .toString());
    }

    private void emit(OnboardingEventType type, String value) {
        events.accept(new OnboardingEvent(type, value));
    }
}
