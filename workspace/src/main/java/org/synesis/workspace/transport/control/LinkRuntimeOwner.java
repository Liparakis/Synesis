package org.synesis.workspace.transport.control;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
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
import org.synesis.link.protocol.CompactBootstrapInvitation;
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
     * Number of recent frames the overlay forwarder remembers to drop duplicates.
     */
    private static final int DUPLICATE_GUARD_CAPACITY = 256;

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
     * @param membership      explicitly verified signed membership, or empty when overlay membership
     *                        is not configured
     * @param events          safe Link event sink
     * @param projectIdentity canonical local project identity
     */
    public LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity, Optional<OverlayMembershipSnapshot> membership, Consumer<OnboardingEvent> events, ProjectIdentity projectIdentity) {
        this(onboarding, localIdentity, membership, events, Clock.systemUTC(), Objects.requireNonNull(projectIdentity, "project identity"));
    }

    /**
     * Creates a runtime owner and wires the inbound application handler.
     *
     * @param onboarding      Link-owned onboarding facade
     * @param localIdentity   local authenticated node identity
     * @param membership      explicitly verified signed membership, or empty when not configured
     * @param events          safe Link event sink
     * @param clock           time source
     * @param projectIdentity canonical local project identity, or {@code null} to skip the project-context exchange
     * @throws IllegalArgumentException when the local node is not in the membership, or the
     *                                  membership belongs to a different project
     */
    private LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity, Optional<OverlayMembershipSnapshot> membership, Consumer<OnboardingEvent> events, Clock clock, ProjectIdentity projectIdentity) {
        this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
        this.localIdentity = Objects.requireNonNull(localIdentity, "local identity");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.projectIdentity = projectIdentity;
        Optional<OverlayMembershipSnapshot> initial = Objects.requireNonNull(membership, "membership");
        this.membership = initial.map(OverlayMembershipView::new).orElse(null);
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
            PeerSession.ApplicationStreamHandler delegate = handler == null ? (remote, payload) -> CompletableFuture.failedFuture(new IllegalStateException("no project application handler is installed")) : handler;
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
     * @param expectedPeer optional expected responder node ID
     * @return live pending host operation
     * @throws OnboardingFailure if Link cannot create the invitation
     */
    @Override
    public PendingHost createInvitation(String expectedPeer) throws OnboardingFailure {
        ensureOpen();
        Onboarding.PreparedHost delegate = onboarding.createCompactInvitation(expectedPeer, applicationHandler, this::acceptSession);
        LiveHost result = new LiveHost(delegate);
        handles.add(result);
        result.startDirectWaitIfNeeded();
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
        Onboarding.PreparedJoin delegate = onboarding.importInvitation(link, applicationHandler, this::acceptSession);
        LiveJoin result = new LiveJoin(delegate);
        handles.add(result);
        return result;
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
        session.terminalCompletion().whenComplete((ignored, failure) -> {
            if (sessions.remove(session.remoteNodeId(), session)) {
                unbind(session);
                publish(OnboardingEventType.SESSION_CLOSED, "");
            }
        });
        publishTopology();
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
         * Whether the invitation is compact and therefore rejects answers.
         */
        private final boolean compact;

        /**
         * Wraps a prepared host.
         *
         * @param delegate underlying Link host operation
         */
        private LiveHost(Onboarding.PreparedHost delegate) {
            this.delegate = delegate;
            compact = CompactBootstrapInvitation.isShareLink(delegate.invitationLink());
        }

        /**
         * Starts waiting for the peer of a compact invitation; a no-op for classic invitations.
         *
         * <p>Called after the handle is registered so that {@link #close()} can cancel it.
         */
        private void startDirectWaitIfNeeded() {
            if (!compact) {
                return;
            }
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
         * Imports the peer's answer and keeps the resulting session alive.
         *
         * @param link answer share link
         * @throws OnboardingFailure {@link OnboardingFailureCode#ANSWER_INVALID} for compact
         *                           invitations, otherwise whatever the Link layer reports
         */
        @Override
        public void importAnswer(String link) throws OnboardingFailure {
            if (compact) {
                throw new OnboardingFailure(OnboardingFailureCode.ANSWER_INVALID, new IllegalArgumentException("compact invitation does not accept an answer"));
            }
            delegate.importAnswerAndKeepAlive(link);
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
         * Wraps a prepared join.
         *
         * @param delegate underlying Link join operation
         */
        private LiveJoin(Onboarding.PreparedJoin delegate) {
            this.delegate = delegate;
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
                delegate.close();
            }
        }
    }
}