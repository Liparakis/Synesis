package org.synesis.workspace.transport.control;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingEvent;
import org.synesis.link.onboarding.OnboardingEventType;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.onboarding.OnboardingFailureCode;
import org.synesis.link.overlay.OverlayDuplicateGuard;
import org.synesis.link.overlay.OverlayForwarder;
import org.synesis.link.overlay.OverlayMembershipPropagation;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayPeerRegistry;
import org.synesis.link.overlay.OverlayPeerSessionBridge;
import org.synesis.link.overlay.OverlayTopologyAdvertisement;
import org.synesis.link.overlay.OverlayTopologyPropagation;
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.session.LivenessTransition;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

import org.synesis.workspace.application.project.ProjectIdentity;
import org.synesis.workspace.application.sync.ProjectContextExchange;
import org.synesis.workspace.transport.control.network.LinkState;
import org.synesis.workspace.transport.control.network.RelayState;

/**
 * Small in-process owner for live Link sessions and overlay read state.
 *
 * <p>The owner deliberately accepts membership only as an explicit signed snapshot. It never turns
 * an invitation, local allowlist, address, or authenticated-but-unknown peer into project
 * membership.
 *
 * <p>Thread-safe. Sessions and onboarding handles are tracked in concurrent collections, and
 * {@link #close()} is idempotent.
 */
public final class LinkRuntimeOwner implements ControlPlaneLinkOperations, Supplier<LinkState> {

    /**
     * Where an owner writes down which peers its sessions were made with, so that a restart can resume them (see
     * {@link #resumeDialing} and {@link #resumeHosting}). It is told only about peers the owner's returning rule
     * names, and must not throw.
     */
    public interface PeerMemory {

        /**
         * This computer dialed a host through an invitation.
         *
         * @param hostNodeId     the host's node ID
         * @param invitationLink the exact invitation link
         */
        void dialed(String hostNodeId, String invitationLink);

        /**
         * This computer admitted a node through the invitation it hosts on a UDP port.
         *
         * @param port   the port the invitation is hosted on
         * @param nodeId the node admitted
         */
        void hosted(int port, String nodeId);
    }

    /**
     * Makes a node a member of the project this computer leads, durably. Told only when the owner's person accepts a
     * join request ({@link #acceptJoin}).
     */
    @FunctionalInterface
    public interface MemberAdmission {

        /**
         * Adds the node to the member list and saves it, with the code remote the invitation it used carried.
         *
         * @param nodeId        the node to add to the member list
         * @param codeRemoteUrl the URL of the code remote the project link carried, or {@code null} for an invitation
         *                      that was not a project link
         * @throws java.io.IOException when the list cannot be saved; the request then stays pending. A message of
         *                             {@value #CODE_REMOTE_NOT_CONFIGURED} makes the refusal that code
         */
        void admit(String nodeId, String codeRemoteUrl) throws java.io.IOException;

        /** The refusal code of an accept whose invitation carried no usable code remote. */
        String CODE_REMOTE_NOT_CONFIGURED = "CODE_REMOTE_NOT_CONFIGURED";
    }

    private record Held(String nodeId, Instant requestedAt, Instant expiresAt, int port, String codeRemoteUrl) {
    }

    private static final PeerMemory FORGETS = new PeerMemory() {
        @Override
        public void dialed(String hostNodeId, String invitationLink) {
        }

        @Override
        public void hosted(int port, String nodeId) {
        }
    };

    /**
     * Number of recent frames the overlay forwarder remembers to drop duplicates.
     */
    private static final int DUPLICATE_GUARD_CAPACITY = 256;

    private static final Predicate<String> NO_RETURNING_PEERS = peer -> false;

    private static final Consumer<String> NO_JOURNAL = line -> {
    };

    /**
     * Delay before the first redial and its ceiling: a dropped link usually returns within seconds, a sleeping
     * laptop within minutes, and thirty seconds keeps a dead peer from being dialed more than twice a minute.
     */
    static final Duration REDIAL_FIRST = Duration.ofSeconds(1);
    static final Duration REDIAL_CAP = Duration.ofSeconds(30);

    /**
     * Link-owned onboarding facade that creates and imports invitations.
     */
    private final Onboarding onboarding;

    /**
     * Local authenticated node identity; signs topology advertisements.
     */
    private final NodeIdentity localIdentity;

    /**
     * Sink for safe Link events; failures in it never affect the Link lifecycle.
     */
    private final Consumer<OnboardingEvent> events;

    /**
     * Time source for membership, topology, and lifecycle checks.
     */
    private final Clock clock;

    /**
     * Canonical local project identity, or {@code null} when no project-context exchange is required.
     */
    private final ProjectIdentity projectIdentity;

    /**
     * Verified signed membership, or {@code null} when overlay membership is not configured.
     */
    private final OverlayMembershipView membership;

    /**
     * Topology learned from peer advertisements; only exposed when membership is configured.
     */
    private final OverlayTopologyView topology;

    /**
     * Outbound transports used to forward overlay frames, keyed by remote node id.
     */
    private final OverlayPeerRegistry<OverlayForwarder.PeerTransport> peerTransports;

    /**
     * Outbound transports used to propagate membership, keyed by remote node id.
     */
    private final OverlayPeerRegistry<OverlayMembershipPropagation.PeerTransport> membershipTransports;

    /**
     * Outbound transports used to propagate topology, keyed by remote node id.
     */
    private final OverlayPeerRegistry<OverlayTopologyPropagation.PeerTransport> topologyTransports;

    /**
     * Publishes this node's topology advertisements, or {@code null} when membership is not configured.
     */
    private final OverlayTopologyPropagation topologyPropagation;

    /**
     * Handler for inbound application streams on every session, or {@code null} when none applies.
     */
    private final PeerSession.ApplicationStreamHandler applicationHandler;

    /**
     * Told about each admitted session; a no-op unless the caller supplied one.
     */
    private final Consumer<PeerSession> sessionListener;

    /**
     * Whether a node may come back without an invitation, and be redialed; see the constructor that takes it.
     */
    private final Predicate<String> returningPeers;

    /**
     * Receives one line for each session start and end and each redial outcome.
     */
    private final Consumer<String> journal;

    /**
     * Told which peers sessions were made with, so a restart can resume them; forgets everything unless supplied.
     */
    private final PeerMemory memory;

    /**
     * Adds an accepted joiner to the member list, or {@code null} when this computer holds no joiners.
     */
    private final MemberAdmission admission;

    /**
     * Join requests by node id. A request outlives its session and ends with the invitation it used.
     */
    private final ConcurrentMap<String, Held> requests = new ConcurrentHashMap<>();

    /**
     * The code remote URL each project link carried, by the connection invitation it wraps, so that accepting a
     * joiner records the remote of the link that brought it and not whatever the project has configured by then.
     */
    private final ConcurrentMap<String, String> projectUrls = new ConcurrentHashMap<>();

    /**
     * Accepted sessions by remote node id; at most one per peer, a newer session replaces an older one.
     */
    private final ConcurrentMap<String, PeerSession> sessions = new ConcurrentHashMap<>();

    /**
     * Open onboarding handles, closed together by {@link #close()}.
     */
    private final Set<AutoCloseable> handles = ConcurrentHashMap.newKeySet();

    /**
     * Monotonic sequence stamped on each topology advertisement.
     */
    private final AtomicLong topologySequence = new AtomicLong();

    /**
     * Set once by {@link #close()}; afterwards no new handles or sessions are admitted.
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a runtime owner that requires an authenticated project-context exchange.
     *
     * <p>The project identity is deliberately kept above core Link. It is exchanged only after Link
     * authentication and before this owner retains a session for project-facing use.
     *
     * @param onboarding      Link-owned onboarding facade
     * @param localIdentity   local authenticated node identity
     * @param membership      explicitly verified signed membership, or {@code null} when overlay
     *                        membership is not configured
     * @param events          safe Link event sink
     * @param projectIdentity canonical local project identity
     */
    public LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity, OverlayMembershipSnapshot membership, Consumer<OnboardingEvent> events, ProjectIdentity projectIdentity) {
        this(onboarding, localIdentity, membership, events, Clock.systemUTC(), Objects.requireNonNull(projectIdentity, "project identity"), null, session -> {
        }, NO_RETURNING_PEERS, NO_JOURNAL, FORGETS, null);
    }

    /**
     * Creates a runtime owner whose project application traffic is answered by a delegate, and which tells a
     * listener about every session it admits (ADR-0098: the replication of a project's log runs on the sessions
     * the browser control plane's invite and join establish).
     *
     * <p>The delegate sees only frames that follow the project-context exchange, so it never serves a peer of
     * another project. It cannot be combined with overlay membership, which installs its own handler.
     *
     * @param onboarding      Link-owned onboarding facade
     * @param localIdentity   local authenticated node identity
     * @param events          safe Link event sink
     * @param projectIdentity canonical local project identity
     * @param projectHandler  answers every application stream that is not the project-context exchange; a frame it
     *                        does not understand must be refused by it
     * @param sessionListener called with each admitted session after the owner has registered it; a thrown
     *                        exception rejects the session, so a listener that merely ignores a peer returns
     */
    public LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity, Consumer<OnboardingEvent> events, ProjectIdentity projectIdentity, PeerSession.ApplicationStreamHandler projectHandler,
            Consumer<PeerSession> sessionListener) {
        this(onboarding, localIdentity, events, projectIdentity, projectHandler, sessionListener, NO_RETURNING_PEERS, NO_JOURNAL);
    }

    /**
     * Creates a runtime owner like the one above, whose sessions come back by themselves (ADR-0098: a session that
     * ended is dialed again, and a peer that was admitted once is admitted again, without a new invitation).
     *
     * <p>The computer that dialed an invitation redials its host, with a delay that doubles from one second to
     * thirty, for as long as {@code returningPeers} names the host and no session to it is held. The computer that
     * hosted the invitation admits a connection with no invitation from exactly the nodes {@code returningPeers}
     * names, after authenticating both identities. Nobody else is admitted that way, and the rule is read at every
     * connection and every attempt, so a node it stops naming is neither dialed nor admitted from then on. The
     * hosting side keeps listening on the same port, which is why a redial at the invitation's endpoints reaches it.
     * Neither side remembers this across a restart of the owner unless the constructor that takes a {@link PeerMemory}
     * is used; without one a restart needs a new invitation.
     *
     * @param onboarding      Link-owned onboarding facade
     * @param localIdentity   local authenticated node identity
     * @param events          safe Link event sink
     * @param projectIdentity canonical local project identity
     * @param projectHandler  answers every application stream that is not the project-context exchange
     * @param sessionListener called with each admitted session after the owner has registered it; a thrown
     *                        exception rejects the session
     * @param returningPeers whether a node is one this computer redials or admits again (a member's leader, a
     *                       leader's members); read afresh each time and must not block
     * @param journal        receives one line for each session start and end and each redial outcome, with the
     *                       reason a session ended; it should be cheap and may be called from a Link thread
     * @see #LinkRuntimeOwner(Onboarding, NodeIdentity, Consumer, ProjectIdentity, PeerSession.ApplicationStreamHandler, Consumer)
     */
    public LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity, Consumer<OnboardingEvent> events, ProjectIdentity projectIdentity, PeerSession.ApplicationStreamHandler projectHandler,
            Consumer<PeerSession> sessionListener, Predicate<String> returningPeers, Consumer<String> journal) {
        this(onboarding, localIdentity, events, projectIdentity, projectHandler, sessionListener, returningPeers, journal, FORGETS);
    }

    /**
     * Creates a runtime owner like the one above, which also writes down the peers its sessions were made with, so
     * that a restart of the computer's coordination server resumes them with no new invitation (ADR-0098): the
     * caller reads what it wrote and hands it to {@link #resumeDialing} and {@link #resumeHosting}.
     *
     * @param onboarding      Link-owned onboarding facade
     * @param localIdentity   local authenticated node identity
     * @param events          safe Link event sink
     * @param projectIdentity canonical local project identity
     * @param projectHandler  answers every application stream that is not the project-context exchange
     * @param sessionListener called with each admitted session after the owner has registered it
     * @param returningPeers  whether a node is one this computer redials or admits again
     * @param journal         receives one line for each session start and end and each redial outcome
     * @param memory          told about each dialed host and each admitted node that {@code returningPeers} names
     * @see #LinkRuntimeOwner(Onboarding, NodeIdentity, Consumer, ProjectIdentity, PeerSession.ApplicationStreamHandler, Consumer, Predicate, Consumer)
     */
    public LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity, Consumer<OnboardingEvent> events, ProjectIdentity projectIdentity, PeerSession.ApplicationStreamHandler projectHandler,
            Consumer<PeerSession> sessionListener, Predicate<String> returningPeers, Consumer<String> journal, PeerMemory memory) {
        this(onboarding, localIdentity, events, projectIdentity, projectHandler, sessionListener, returningPeers, journal, memory, null);
    }

    /**
     * Creates a runtime owner like the one above for the computer that leads a project and lets its person decide who
     * joins (ADR-0098, "Joining, revised"). A node that authenticates through an invitation this computer hosts and
     * that {@code returningPeers} does not name gets a session, which the project handler serves nothing, and is
     * listed as a {@link JoinRequest}. {@link #acceptJoin} adds it with {@code admission}, serves its live session
     * through {@code sessionListener} and records it for a restart; {@link #declineJoin} closes its session.
     *
     * @param onboarding      Link-owned onboarding facade
     * @param localIdentity   local authenticated node identity
     * @param events          safe Link event sink
     * @param projectIdentity canonical local project identity
     * @param projectHandler  answers every application stream that is not the project-context exchange
     * @param sessionListener called with each admitted session after the owner has registered it, and again with the
     *                        live session of an accepted request
     * @param returningPeers  whether a node is one this computer serves, redials or admits again
     * @param journal         receives one line for each session start and end, redial outcome and decision
     * @param memory          told about each dialed host and each admitted node that {@code returningPeers} names
     * @param admission       makes an accepted node a member, or {@code null} to hold no requests
     */
    public LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity, Consumer<OnboardingEvent> events, ProjectIdentity projectIdentity, PeerSession.ApplicationStreamHandler projectHandler,
            Consumer<PeerSession> sessionListener, Predicate<String> returningPeers, Consumer<String> journal, PeerMemory memory, MemberAdmission admission) {
        this(onboarding, localIdentity, null, events, Clock.systemUTC(), Objects.requireNonNull(projectIdentity, "project identity"), Objects.requireNonNull(projectHandler, "project handler"),
                Objects.requireNonNull(sessionListener, "session listener"), Objects.requireNonNull(returningPeers, "returning peers"), Objects.requireNonNull(journal, "journal"),
                Objects.requireNonNull(memory, "peer memory"), admission);
    }

    /**
     * Creates a runtime owner and wires the inbound application handler.
     *
     * @param onboarding      Link-owned onboarding facade
     * @param localIdentity   local authenticated node identity
     * @param membership      explicitly verified signed membership, or {@code null} when not configured
     * @param events          safe Link event sink
     * @param clock           time source
     * @param projectIdentity canonical local project identity, or {@code null} to skip the project-context exchange
     * @throws IllegalArgumentException when the local node is not in the membership, or the
     *                                  membership belongs to a different project
     */
    private LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity, OverlayMembershipSnapshot membership, Consumer<OnboardingEvent> events, Clock clock, ProjectIdentity projectIdentity,
            PeerSession.ApplicationStreamHandler projectHandler, Consumer<PeerSession> sessionListener, Predicate<String> returningPeers, Consumer<String> journal, PeerMemory memory, MemberAdmission admission) {
        this.memory = memory;
        this.admission = admission;
        this.sessionListener = sessionListener;
        this.returningPeers = returningPeers;
        this.journal = journal;
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.localIdentity = Objects.requireNonNull(localIdentity, "local identity");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.projectIdentity = projectIdentity;
        this.membership = membership == null ? null : new OverlayMembershipView(membership);
        if (this.membership != null && !this.membership.current().allows(localIdentity.nodeId())) {
            throw new IllegalArgumentException("local identity is not in the supplied membership");
        }
        if (projectIdentity != null && this.membership != null && !projectIdentity.projectId().equals(this.membership.current().projectId())) {
            throw new IllegalArgumentException("membership project does not match local project identity");
        }
        this.topology = new OverlayTopologyView();
        this.peerTransports = new OverlayPeerRegistry<>();
        this.membershipTransports = new OverlayPeerRegistry<>();
        this.topologyTransports = new OverlayPeerRegistry<>();
        PeerSession.ApplicationStreamHandler handler;
        if (this.membership == null) {
            this.topologyPropagation = null;
            handler = null;
        } else {
            OverlayMembershipPropagation membershipPropagation = new OverlayMembershipPropagation(localIdentity.nodeId(), this.membership, membershipTransports);
            this.topologyPropagation = new OverlayTopologyPropagation(localIdentity.nodeId(), this.membership, topology, topologyTransports);
            OverlayForwarder forwarder = new OverlayForwarder(localIdentity.nodeId(),
                    this.membership,
                    topology,
                    new OverlayDuplicateGuard(DUPLICATE_GUARD_CAPACITY),
                    peerTransports,
                    frame -> CompletableFuture.completedFuture(null));
            handler = OverlayPeerSessionBridge.inbound(forwarder, topologyPropagation, membershipPropagation, clock);
        }
        if (projectIdentity != null) {
            // The project responder wraps the overlay handler; with no overlay it must fail closed rather than ignore frames.
            PeerSession.ApplicationStreamHandler delegate = handler != null ? handler
                    : projectHandler != null ? projectHandler
                    : (remote, payload) -> CompletableFuture.failedFuture(new IllegalStateException("no project application handler is installed"));
            handler = ProjectContextExchange.withResponder(projectIdentity, delegate);
        }
        this.applicationHandler = handler;
    }

    /**
     * Captures the current session list and Link/overlay references for the control-plane projection.
     *
     * <p>Session, membership, and topology objects remain live views; this is not an atomic
     * snapshot across those independently changing objects.
     *
     * @return current authenticated Link and overlay state
     */
    @Override
    public LinkState get() {
        List<PeerSession> currentSessions = sessions.values().stream().sorted(Comparator.comparing(PeerSession::remoteNodeId)).toList();
        return new LinkState(localIdentity.nodeId(), currentSessions, Optional.ofNullable(membership), Optional.ofNullable(topologyPropagation == null ? null : topology), peerTransports.nodeIds(), RelayState.disabled());
    }

    /**
     * Creates and retains one live host-side onboarding handle.
     *
     * @param expectedPeer   optional expected responder node ID
     * @param openRouterPort whether the user chose to have the home router forward a port
     * @return live pending host operation
     * @throws OnboardingFailure if Link cannot create the invitation
     */
    @Override
    public PendingHost createInvitation(String expectedPeer, boolean openRouterPort) throws OnboardingFailure {
        ensureOpen();
        AtomicReference<Onboarding.PreparedHost> hosting = new AtomicReference<>();
        AtomicReference<Instant> invitationExpiry = new AtomicReference<>();
        Onboarding.PreparedHost delegate = onboarding.createRetainedInvitation(expectedPeer, openRouterPort, applicationHandler, session -> {
            acceptSession(session);
            // The host is set before the listener can accept anyone.
            if (admission != null && !returningPeers.test(session.remoteNodeId())) {
                hold(session, hosting.get().listeningPort(), invitationExpiry.get(), hosting.get().invitationLink());
            }
            remember(() -> memory.hosted(hosting.get().listeningPort(), session.remoteNodeId()), session);
        }, peer -> !closed.get() && returningPeers.test(peer));
        hosting.set(delegate);
        invitationExpiry.set(expiryOf(delegate.invitationLink()));
        LiveHost result = new LiveHost(delegate);
        handles.add(result);
        result.startWait();
        return result;
    }

    /**
     * Creates and retains one live join-side onboarding handle.
     *
     * @param link exact invitation URI
     * @return live pending join operation
     * @throws OnboardingFailure if Link rejects the invitation
     */
    @Override
    public PendingJoin importInvitation(String link) throws OnboardingFailure {
        ensureOpen();
        return retainJoin(link, onboarding::importRetainedInvitation);
    }

    private Instant expiryOf(String invitationLink) {
        try {
            return org.synesis.link.protocol.CompactBootstrapInvitation.fromShareLink(invitationLink).expiresAt();
        } catch (java.io.IOException | RuntimeException unreadable) {
            return clock.instant().plus(org.synesis.link.protocol.CompactBootstrapInvitation.MAX_LIFETIME);
        }
    }

    /**
     * Keeps a joiner that is not one of this computer's members as a request. The session stays open and is served
     * nothing: only the project-context exchange runs on it until a person decides.
     */
    private void hold(PeerSession session, int port, Instant expiresAt, String connectionLink) {
        Instant now = clock.instant();
        requests.put(session.remoteNodeId(), new Held(session.remoteNodeId(), now,
                expiresAt == null ? now.plus(org.synesis.link.protocol.CompactBootstrapInvitation.MAX_LIFETIME) : expiresAt, port, projectUrls.get(connectionLink)));
        record("JOIN_REQUESTED peer=" + session.remoteNodeId());
        publish(OnboardingEventType.SESSION_CREATED, "JOIN_REQUESTED");
    }

    /**
     * Lists the joiners waiting for a decision, oldest first. A request ends with the invitation it used, and the
     * session it held, if still open, is closed with it.
     *
     * @return the pending requests
     */
    @Override
    public List<JoinRequest> joinRequests() {
        Instant now = clock.instant();
        for (Held held : List.copyOf(requests.values())) {
            if (!now.isBefore(held.expiresAt) && requests.remove(held.nodeId, held)) {
                closeHeld(held.nodeId);
                record("JOIN_EXPIRED peer=" + held.nodeId);
            }
        }
        return requests.values().stream().sorted(Comparator.comparing(Held::requestedAt).thenComparing(Held::nodeId))
                .map(held -> new JoinRequest(held.nodeId, held.requestedAt, held.expiresAt)).toList();
    }

    /**
     * Accepts a joiner: the node becomes a member, its live session (if it still has one) is served from now on, and
     * it is recorded so that a restart admits it again. If its session ended meanwhile, it is admitted when it
     * dials again, because it is a member now. A failure to save the member list leaves the request pending.
     *
     * @param nodeId the requesting node
     * @throws JoinRequestException when there is no such request, it expired, or the member list was not saved
     */
    @Override
    public void acceptJoin(String nodeId) throws JoinRequestException {
        Held held = pendingRequest(nodeId);
        try {
            admission.admit(nodeId, held.codeRemoteUrl);
        } catch (java.io.IOException | RuntimeException unsaved) {
            throw new JoinRequestException(MemberAdmission.CODE_REMOTE_NOT_CONFIGURED.equals(unsaved.getMessage()) ? MemberAdmission.CODE_REMOTE_NOT_CONFIGURED : "MEMBER_NOT_SAVED");
        }
        requests.remove(nodeId, held);
        record("JOIN_ACCEPTED peer=" + nodeId);
        publish(OnboardingEventType.SESSION_CREATED, "JOIN_ACCEPTED");
        try {
            memory.hosted(held.port, nodeId);
        } catch (RuntimeException ignored) {
            // The memory serves the next restart; it does not decide this session.
        }
        PeerSession live = sessions.get(nodeId);
        if (live != null) {
            try {
                sessionListener.accept(live);
            } catch (RuntimeException ignored) {
                // A member that cannot be attached now is served when it dials again.
            }
        }
    }

    /**
     * Declines a joiner: its session is closed and it is not a member. Its invitation was used by its first
     * connection, so it cannot come back with it.
     *
     * @param nodeId the requesting node
     * @throws JoinRequestException when there is no such request or it expired
     */
    @Override
    public void declineJoin(String nodeId) throws JoinRequestException {
        Held held = pendingRequest(nodeId);
        requests.remove(nodeId, held);
        closeHeld(nodeId);
        record("JOIN_DECLINED peer=" + nodeId);
        publish(OnboardingEventType.SESSION_CREATED, "JOIN_DECLINED");
    }

    private Held pendingRequest(String nodeId) throws JoinRequestException {
        if (admission == null) {
            throw new JoinRequestException("JOIN_REQUEST_UNKNOWN");
        }
        joinRequests();
        Held held = requests.get(nodeId);
        if (held == null) {
            throw new JoinRequestException("JOIN_REQUEST_UNKNOWN");
        }
        return held;
    }

    private void closeHeld(String nodeId) {
        PeerSession live = sessions.get(nodeId);
        if (live != null) {
            closeSessionQuietly(live);
        }
    }

    /**
     * Signs a project link around an invitation this computer made.
     *
     * @param connectionLink the {@code SLB3} link {@link #createInvitation} returned
     * @param projectId      the project's identifier
     * @param createdAt      the project's creation time
     * @param codeRemoteUrl  URL of the code remote without credentials, or {@code null}
     * @return the {@code SLP1} link
     * @throws java.io.IOException              when {@code connectionLink} is not an invitation
     * @throws GeneralSecurityException         when signing fails
     */
    @Override
    public String projectInvitationLink(String connectionLink, java.util.UUID projectId, Instant createdAt, String codeRemoteUrl)
            throws java.io.IOException, GeneralSecurityException {
        String link = org.synesis.link.protocol.ProjectInvitation.create(localIdentity,
                org.synesis.link.protocol.CompactBootstrapInvitation.fromShareLink(connectionLink), projectId, createdAt, codeRemoteUrl).shareLink();
        if (codeRemoteUrl != null) {
            projectUrls.put(connectionLink, codeRemoteUrl);
        }
        return link;
    }

    @Override
    public String localNodeId() {
        return localIdentity.nodeId();
    }

    /**
     * Dials a host again after this computer's coordination server restarted, from the invitation its first session
     * was made with, with no new invitation (ADR-0098 step 2). The first dial is made after the redial delay, and
     * later ones back off like any redial. Nothing is dialed when the returning rule no longer names the host, and
     * a link that is not an authentic invitation, or any other failure, is journaled and ignored.
     *
     * @param invitationLink the link {@link PeerMemory#dialed} was given
     */
    public void resumeDialing(String invitationLink) {
        try {
            ensureOpen();
            LiveJoin join = retainJoin(invitationLink, onboarding::resumeRetainedJoin);
            if (join.resume()) {
                record("DIAL_RESUMED peer=" + join.peer);
            }
        } catch (OnboardingFailure | RuntimeException failure) {
            record("DIAL_RESUME_FAILED cause=" + failure);
        }
    }

    /**
     * Listens again on the UDP port an invitation was hosted on before this computer's coordination server restarted,
     * and admits exactly {@code peers} that {@code returningPeers} still names, with no invitation (ADR-0098 step 2).
     * If the port is taken nothing is hosted, a line says so, and only a new invitation reconnects those peers: the
     * ones that dial redial the old endpoints.
     *
     * @param port  the port {@link PeerMemory#hosted} was given
     * @param peers the nodes it was given for that port
     */
    public void resumeHosting(int port, Set<String> peers) {
        Set<String> allowed = Set.copyOf(peers.stream().filter(returningPeers).toList());
        if (allowed.isEmpty()) {
            record("HOST_RESUME_SKIPPED port=" + port + " reason=no recorded peer is named any more");
            return;
        }
        try {
            ensureOpen();
            Onboarding.ResumedHost resumed = onboarding.resumeRetainedHost(port, applicationHandler, this::acceptSession,
                    peer -> !closed.get() && allowed.contains(peer) && returningPeers.test(peer));
            handles.add(resumed);
            record("HOST_RESUMED port=" + resumed.listeningPort() + " peers=" + allowed.size());
        } catch (OnboardingFailure | RuntimeException failure) {
            record("HOST_RESUME_FAILED port=" + port + " cause=" + (failure.getCause() != null ? failure.getCause() : failure) + "; only a new invitation reconnects those peers");
        }
    }

    /**
     * Creates the join handle for a link and wires it to this owner.
     */
    private LiveJoin retainJoin(String link, JoinFactory factory) throws OnboardingFailure {
        AtomicReference<LiveJoin> dialer = new AtomicReference<>();
        Onboarding.PreparedJoin delegate = factory.prepare(link, applicationHandler, session -> {
            acceptSession(session);
            remember(() -> memory.dialed(session.remoteNodeId(), link), session);
            // The handle is set before connect() can run, and a redial runs only after connect() returned.
            dialer.get().watch(session);
        });
        LiveJoin result = new LiveJoin(delegate);
        dialer.set(result);
        handles.add(result);
        return result;
    }

    /**
     * Makes or restores a join handle.
     */
    @FunctionalInterface
    private interface JoinFactory {
        Onboarding.PreparedJoin prepare(String link, PeerSession.ApplicationStreamHandler handler, Consumer<PeerSession> sessionAction) throws OnboardingFailure;
    }

    /**
     * Writes a peer down for the next restart, but only a peer the returning rule names: anything else would never be
     * dialed or admitted again. A failing memory never affects a session.
     */
    private void remember(Runnable write, PeerSession session) {
        if (!returningPeers.test(session.remoteNodeId())) {
            return;
        }
        try {
            write.run();
        } catch (RuntimeException ignored) {
            // The memory serves the next restart; it does not decide this session.
        }
    }

    /**
     * Stops all owner-retained Link handles and sessions.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<PeerSession> liveSessions = new ArrayList<>(sessions.values());
        sessions.clear();
        liveSessions.forEach(LinkRuntimeOwner::closeSessionQuietly);
        peerTransports.nodeIds().forEach(peerTransports::unbind);
        membershipTransports.nodeIds().forEach(membershipTransports::unbind);
        topologyTransports.nodeIds().forEach(topologyTransports::unbind);
        List<AutoCloseable> openHandles = new ArrayList<>(handles);
        handles.clear();
        openHandles.forEach(LinkRuntimeOwner::closeQuietly);
    }

    /**
     * Closes a handle, deliberately discarding any failure.
     *
     * @param value handle to close
     */
    private static void closeQuietly(AutoCloseable value) {
        try {
            value.close();
        } catch (Exception ignored) {
            // Shutdown is best effort after the owner has closed admission.
        }
    }

    /**
     * Gracefully closes a session, deliberately discarding any failure.
     *
     * @param session session to close
     */
    private static void closeSessionQuietly(PeerSession session) {
        try {
            session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
        } catch (RuntimeException ignored) {
            // The session terminal lifecycle owns transport cleanup.
        }
    }

    /**
     * Rejects new work once the owner has been closed.
     *
     * @throws IllegalStateException when {@link #close()} has run
     */
    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Link runtime owner is closed");
        }
    }

    /**
     * Admits an authenticated session: verifies membership and project context, replaces any
     * previous session for the peer, binds overlay transports, and republishes topology.
     *
     * <p>Installed as the Link session callback, so a thrown exception rejects the session.
     *
     * @param session authenticated peer session
     * @throws IllegalStateException when the owner is closed, the peer is not a current project member,
     *                               or the project context does not match
     */
    private void acceptSession(PeerSession session) {
        Objects.requireNonNull(session, "session");
        ensureOpen();
        requireCurrentMember(session);
        requireProjectContext(session);
        PeerSession previous = sessions.put(session.remoteNodeId(), session);
        if (previous != null && previous != session) {
            unbind(previous);
            closeSessionQuietly(previous);
        }
        peerTransports.bind(session.remoteNodeId(), OverlayPeerSessionBridge.outbound(session));
        membershipTransports.bind(session.remoteNodeId(), frame -> session.requestApplication(frame.encoded()).thenApply(ignored -> null));
        topologyTransports.bind(session.remoteNodeId(), frame -> session.requestApplication(frame.encoded()).thenApply(ignored -> null));
        try {
            session.addLivenessListener(this::publishLiveness);
        } catch (RuntimeException ignored) {
            // A session that closes during callback registration is removed by
            // its terminal completion below.
        }
        record("CONNECTED peer=" + session.remoteNodeId() + " expiry=" + session.livenessConfiguration().expiryTimeout().toSeconds() + "s");
        session.terminalCompletion().whenComplete((ignored, failure) -> {
            boolean current = sessions.remove(session.remoteNodeId(), session);
            if (current) {
                unbind(session);
                publish(OnboardingEventType.SESSION_CLOSED, "");
            }
            // close() empties the map before closing its sessions; those ended, nothing replaced them.
            recordEnd(session, current || closed.get());
        });
        publishTopology();
        sessionListener.accept(session);
    }

    /**
     * Records why a session ended, since nothing else keeps it once the session object is gone: the category Link
     * closed it for (a peer that said goodbye, a transport that closed, silence past the expiry, a protocol error),
     * the liveness state it ended in, and how long it lasted.
     *
     * @param session the ended session
     * @param current false when a newer session to the same peer had already replaced it
     */
    private void recordEnd(PeerSession session, boolean current) {
        record("DISCONNECT peer=" + session.remoteNodeId() + " reason=" + session.closeReason() + " liveness=" + session.livenessState()
                + " lasted=" + Duration.between(session.establishedAt(), clock.instant()).toSeconds() + "s" + (current ? "" : " replaced=true"));
    }

    /**
     * Hands a line to the journal; a failing journal never affects a session.
     *
     * @param line the event and its details
     */
    private void record(String line) {
        try {
            journal.accept(line);
        } catch (RuntimeException ignored) {
            // The journal explains the sessions; it does not decide them.
        }
    }

    /**
     * Requires the peer to be a member of the current, unexpired membership; a no-op without overlay membership.
     *
     * @param session authenticated peer session
     * @throws IllegalStateException when the peer is not a current project member
     */
    private void requireCurrentMember(PeerSession session) {
        if (membership == null) {
            return;
        }
        OverlayMembershipSnapshot current = membership.current();
        if (!current.isUsableAt(clock.instant()) || !current.allows(session.remoteNodeId())) {
            throw new IllegalStateException("authenticated peer is not a current project member");
        }
    }

    /**
     * Requires the peer to belong to the same project; a no-op without a project identity.
     *
     * <p>A mismatching session is closed and reported before the failure is thrown.
     *
     * @param session authenticated peer session
     * @throws IllegalStateException when the project context does not match
     */
    private void requireProjectContext(PeerSession session) {
        if (projectIdentity == null) {
            return;
        }
        try {
            ProjectContextExchange.require(session, projectIdentity);
        } catch (ProjectContextExchange.ProjectContextException mismatch) {
            closeSessionQuietly(session);
            publish(OnboardingEventType.LIVENESS, mismatch.code());
            throw new IllegalStateException(mismatch.code(), mismatch);
        }
    }

    /**
     * Advertises this node's currently connected member peers to the overlay; a no-op without
     * overlay membership. Failure is reported as degraded liveness rather than thrown.
     */
    private void publishTopology() {
        if (topologyPropagation == null || membership == null) {
            return;
        }
        Instant now = clock.instant();
        // One snapshot for the whole advertisement so project and revision cannot come from different memberships.
        OverlayMembershipSnapshot current = membership.current();
        List<String> peers = sessions.values().stream().map(PeerSession::remoteNodeId).filter(current::allows).sorted().toList();
        try {
            OverlayTopologyAdvertisement advertisement = OverlayTopologyAdvertisement.create(current.projectId(),
                    current.revision(),
                    topologySequence.incrementAndGet(),
                    now.plus(OverlayTopologyView.defaultLifetime()),
                    peers,
                    localIdentity);
            topologyPropagation.publish(advertisement, now);
        } catch (GeneralSecurityException | RuntimeException ignored) {
            publish(OnboardingEventType.LIVENESS, "DEGRADED");
        }
    }

    /**
     * Publishes a session liveness change.
     *
     * @param transition liveness transition
     */
    private void publishLiveness(LivenessTransition transition) {
        publish(OnboardingEventType.LIVENESS, transition.to().name());
    }

    /**
     * Removes a session's overlay transports.
     *
     * @param session session whose transports are unbound
     */
    private void unbind(PeerSession session) {
        peerTransports.unbind(session.remoteNodeId());
        membershipTransports.unbind(session.remoteNodeId());
        topologyTransports.unbind(session.remoteNodeId());
    }

    /**
     * Publishes an event to the sink, swallowing sink failures.
     *
     * @param type  event type
     * @param value event value
     */
    private void publish(OnboardingEventType type, String value) {
        try {
            events.accept(new OnboardingEvent(type, value));
        } catch (RuntimeException ignored) {
            // Event observation cannot be allowed to break Link lifecycle.
        }
    }

    /**
     * Host-side handle that keeps the established session with this owner.
     *
     * <p>Compact invitations connect without an answer, so the owner waits for the peer in the background.
     */
    private final class LiveHost implements PendingHost {

        /**
         * Underlying Link host operation.
         */
        private final Onboarding.PreparedHost delegate;

        /**
         * Guards {@link #close()} so the delegate is closed once.
         */
        private final AtomicBoolean closedHandle = new AtomicBoolean();

        /**
         * Wraps a prepared host.
         *
         * @param delegate underlying Link host operation
         */
        private LiveHost(Onboarding.PreparedHost delegate) {
            this.delegate = delegate;
        }

        /**
         * Starts waiting for the peer.
         *
         * <p>Called after the handle is registered so that {@link #close()} can cancel it.
         */
        private void startWait() {
            CompletableFuture.runAsync(() -> {
                try {
                    delegate.awaitConnectionAndKeepAlive();
                } catch (OnboardingFailure ignored) {
                    // The session is delivered to acceptSession; a failed wait stays observable through completion().
                }
            });
        }

        /**
         * Returns the invitation link to share with the peer.
         *
         * @return invitation share link
         */
        @Override
        public String invitationLink() {
            return delegate.invitationLink();
        }

        /**
         * Starts sending toward the joiner the answer names; the session still arrives through the
         * wait started by {@link #startWait()}.
         *
         * @param link answer share link
         * @throws OnboardingFailure {@link OnboardingFailureCode#ANSWER_INVALID} if the answer is
         *                           malformed or answers another invitation
         */
        @Override
        public void importAnswer(String link) throws OnboardingFailure {
            delegate.importAnswer(link);
        }

        /**
         * Returns the stage that completes when the operation succeeds or fails.
         *
         * @return completion stage of the underlying operation
         */
        @Override
        public CompletionStage<Void> completion() {
            return delegate.completion();
        }

        /**
         * Reports that the session outlives the handle.
         *
         * @return always {@code true}
         */
        @Override
        public boolean retainsSession() {
            return true;
        }

        /**
         * Unregisters and closes the underlying operation; later calls are no-ops.
         */
        @Override
        public void close() {
            if (closedHandle.compareAndSet(false, true)) {
                handles.remove(this);
                delegate.close();
            }
        }
    }

    /**
     * Join-side handle that keeps the established session with this owner.
     */
    private final class LiveJoin implements PendingJoin {

        /**
         * Underlying Link join operation.
         */
        private final Onboarding.PreparedJoin delegate;

        /**
         * Guards {@link #close()} so the delegate is closed once.
         */
        private final AtomicBoolean closedHandle = new AtomicBoolean();

        /**
         * Dials the host again after a session ended.
         */
        private final Redialer redialer;

        /**
         * The host's node ID, known once the first session is established.
         */
        private volatile String peer;

        /**
         * Wraps a prepared join.
         *
         * @param delegate underlying Link join operation
         */
        private LiveJoin(Onboarding.PreparedJoin delegate) {
            this.delegate = delegate;
            this.redialer = new Redialer(this::redialWanted, delegate::redial, REDIAL_FIRST, REDIAL_CAP, LinkRuntimeOwner.this::record);
        }

        /**
         * Redials the host for as long as this handle is open, the owner is open, no session to the host is held,
         * and the rule still names the host.
         *
         * @return whether another dial is wanted
         */
        private boolean redialWanted() {
            String host = peer;
            return !closedHandle.get() && !closed.get() && host != null && !sessions.containsKey(host) && returningPeers.test(host);
        }

        /**
         * Starts redialing a host this computer was connected to before it restarted.
         *
         * @return false, after closing this handle, when the rule no longer names the host
         */
        private boolean resume() {
            peer = delegate.hostNodeId();
            if (!returningPeers.test(peer)) {
                record("DIAL_RESUME_SKIPPED peer=" + peer + " reason=not named any more");
                close();
                return false;
            }
            redialer.start();
            return true;
        }

        /**
         * Starts redialing when the session ends. Called for the first session and for every one a redial makes.
         *
         * @param session a session this handle established
         */
        private void watch(PeerSession session) {
            peer = session.remoteNodeId();
            session.terminalCompletion().whenComplete((ignored, failure) -> redialer.start());
        }

        /**
         * Returns the answer link to hand back to the host.
         *
         * @return answer share link
         */
        @Override
        public String answerLink() {
            return delegate.answerLink();
        }

        /**
         * Connects to the host and keeps the resulting session alive.
         *
         * @throws OnboardingFailure when the connection cannot be established
         */
        @Override
        public void connect() throws OnboardingFailure {
            delegate.connectAndKeepAlive();
        }

        /**
         * Reports that the session outlives the handle.
         *
         * @return always {@code true}
         */
        @Override
        public boolean retainsSession() {
            return true;
        }

        /**
         * Unregisters and closes the underlying operation; later calls are no-ops.
         */
        @Override
        public void close() {
            if (closedHandle.compareAndSet(false, true)) {
                handles.remove(this);
                redialer.close();
                delegate.close();
            }
        }
    }
}
