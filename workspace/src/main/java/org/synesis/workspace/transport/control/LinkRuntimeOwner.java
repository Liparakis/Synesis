package org.synesis.workspace.transport.control;

import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingEvent;
import org.synesis.link.onboarding.OnboardingEventType;
import org.synesis.link.onboarding.OnboardingFailure;
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

/**
 * Small in-process owner for live Link sessions and overlay read state.
 *
 * <p>The owner deliberately accepts membership only as an explicit signed
 * snapshot. It never turns an invitation, local allowlist, address, or authenticated-but-unknown
 * peer into project membership.</p>
 */
public final class LinkRuntimeOwner implements ControlPlaneLinkOperations,
    Supplier<LinkNetworkProjection.LinkState> {

  private final Onboarding onboarding;
  private final NodeIdentity localIdentity;
  private final Consumer<OnboardingEvent> events;
  private final Clock clock;
  private final OverlayMembershipView membership;
  private final OverlayTopologyView topology;
  private final OverlayPeerRegistry<OverlayForwarder.PeerTransport> peerTransports;
  private final OverlayPeerRegistry<OverlayMembershipPropagation.PeerTransport> membershipTransports;
  private final OverlayPeerRegistry<OverlayTopologyPropagation.PeerTransport> topologyTransports;
  private final OverlayTopologyPropagation topologyPropagation;
  private final PeerSession.ApplicationStreamHandler applicationHandler;
  private final ConcurrentMap<String, PeerSession> sessions = new ConcurrentHashMap<>();
  private final Set<AutoCloseable> handles = ConcurrentHashMap.newKeySet();
  private final AtomicLong topologySequence = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Creates a runtime owner using the system UTC clock.
   *
   * @param onboarding    Link-owned onboarding facade
   * @param localIdentity local authenticated node identity
   * @param membership    explicitly verified signed membership, or empty when overlay membership is
   *                      not configured
   * @param events        safe Link event sink
   */
  public LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity,
      Optional<OverlayMembershipSnapshot> membership, Consumer<OnboardingEvent> events) {
    this(onboarding, localIdentity, membership, events, Clock.systemUTC());
  }

  /**
   * Creates a runtime owner with an injectable clock.
   *
   * @param onboarding    Link-owned onboarding facade
   * @param localIdentity local authenticated node identity
   * @param membership    explicitly verified signed membership, or empty when overlay membership is
   *                      not configured
   * @param events        safe Link event sink
   * @param clock         time source for membership, topology, and lifecycle views
   */
  public LinkRuntimeOwner(Onboarding onboarding, NodeIdentity localIdentity,
      Optional<OverlayMembershipSnapshot> membership, Consumer<OnboardingEvent> events,
      Clock clock) {
    this.onboarding = Objects.requireNonNull(onboarding, "onboarding");
    this.localIdentity = Objects.requireNonNull(localIdentity, "local identity");
    this.events = Objects.requireNonNull(events, "events");
    this.clock = Objects.requireNonNull(clock, "clock");
    Optional<OverlayMembershipSnapshot> initial = Objects.requireNonNull(membership, "membership");
    this.membership = initial.map(OverlayMembershipView::new).orElse(null);
    if (this.membership != null && !this.membership.current().allows(localIdentity.nodeId())) {
      throw new IllegalArgumentException("local identity is not in the supplied membership");
    }
    this.topology = new OverlayTopologyView();
    this.peerTransports = new OverlayPeerRegistry<>();
    this.membershipTransports = new OverlayPeerRegistry<>();
    this.topologyTransports = new OverlayPeerRegistry<>();
    if (this.membership == null) {
      this.topologyPropagation = null;
      this.applicationHandler = null;
    } else {
      OverlayMembershipPropagation membershipPropagation = new OverlayMembershipPropagation(
          localIdentity.nodeId(), this.membership,
          membershipTransports);
      this.topologyPropagation = new OverlayTopologyPropagation(localIdentity.nodeId(),
          this.membership,
          topology, topologyTransports);
      OverlayForwarder forwarder = new OverlayForwarder(localIdentity.nodeId(), this.membership,
          topology,
          new OverlayDuplicateGuard(256), peerTransports,
          frame -> CompletableFuture.completedFuture(null));
      this.applicationHandler = OverlayPeerSessionBridge.inbound(forwarder, topologyPropagation,
          membershipPropagation, clock);
    }
  }

  private static void closeQuietly(AutoCloseable value) {
    try {
      value.close();
    } catch (Exception ignored) {
      // Shutdown is best effort after the owner has closed admission.
    }
  }

  private static void closeSessionQuietly(PeerSession session) {
    try {
      session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
    } catch (RuntimeException ignored) {
      // The session terminal lifecycle owns transport cleanup.
    }
  }

  /**
   * Returns one consistent snapshot source for the control-plane projection.
   *
   * @return current authenticated Link and overlay state
   */
  @Override
  public LinkNetworkProjection.LinkState get() {
    List<PeerSession> currentSessions = sessions.values().stream()
        .sorted(Comparator.comparing(PeerSession::remoteNodeId))
        .toList();
    return new LinkNetworkProjection.LinkState(localIdentity.nodeId(), currentSessions,
        Optional.ofNullable(membership),
        Optional.ofNullable(topologyPropagation == null ? null : topology),
        peerTransports.nodeIds(), LinkNetworkProjection.RelayState.disabled());
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
    Onboarding.PreparedHost delegate = onboarding.createInvitation(expectedPeer, applicationHandler,
        this::acceptSession);
    LiveHost result = new LiveHost(delegate);
    handles.add(result);
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
    Onboarding.PreparedJoin delegate = onboarding.importInvitation(link, applicationHandler,
        this::acceptSession);
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
    liveSessions.forEach(session -> {
      try {
        session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
      } catch (RuntimeException ignored) {
        // The endpoint close below remains the authoritative cleanup.
      }
    });
    peerTransports.nodeIds().forEach(peerTransports::unbind);
    membershipTransports.nodeIds().forEach(membershipTransports::unbind);
    topologyTransports.nodeIds().forEach(topologyTransports::unbind);
    List<AutoCloseable> openHandles = new ArrayList<>(handles);
    handles.clear();
    openHandles.forEach(LinkRuntimeOwner::closeQuietly);
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new IllegalStateException("Link runtime owner is closed");
    }
  }

  private void acceptSession(PeerSession session) {
    Objects.requireNonNull(session, "session");
    ensureOpen();
    if (membership != null) {
      OverlayMembershipSnapshot current = membership.current();
      Instant now = clock.instant();
      if (!current.isUsableAt(now) || !current.allows(session.remoteNodeId())) {
        throw new IllegalStateException("authenticated peer is not a current project member");
      }
    }
    PeerSession previous = sessions.put(session.remoteNodeId(), session);
    if (previous != null && previous != session) {
      unbind(previous);
      closeSessionQuietly(previous);
    }
    peerTransports.bind(session.remoteNodeId(), OverlayPeerSessionBridge.outbound(session));
    membershipTransports.bind(session.remoteNodeId(), frame ->
        session.requestApplication(frame.encoded()).thenApply(ignored -> null));
    topologyTransports.bind(session.remoteNodeId(), frame ->
        session.requestApplication(frame.encoded()).thenApply(ignored -> null));
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

  private void publishTopology() {
    if (topologyPropagation == null || membership == null) {
      return;
    }
    Instant now = clock.instant();
    List<String> peers = sessions.values().stream()
        .map(PeerSession::remoteNodeId)
        .filter(membership.current()::allows)
        .sorted()
        .toList();
    try {
      OverlayTopologyAdvertisement advertisement = OverlayTopologyAdvertisement.create(
          membership.current().projectId(), membership.current().revision(),
          topologySequence.incrementAndGet(), now.plus(OverlayTopologyView.defaultLifetime()),
          peers,
          localIdentity);
      topologyPropagation.publish(advertisement, now);
    } catch (GeneralSecurityException | RuntimeException ignored) {
      publish(OnboardingEventType.LIVENESS, "DEGRADED");
    }
  }

  private void publishLiveness(LivenessTransition transition) {
    publish(OnboardingEventType.LIVENESS, transition.to().name());
  }

  private void unbind(PeerSession session) {
    peerTransports.unbind(session.remoteNodeId());
    membershipTransports.unbind(session.remoteNodeId());
    topologyTransports.unbind(session.remoteNodeId());
  }

  private void publish(OnboardingEventType type, String value) {
    try {
      events.accept(new OnboardingEvent(type, value));
    } catch (RuntimeException ignored) {
      // Event observation cannot be allowed to break Link lifecycle.
    }
  }

  private final class LiveHost implements PendingHost {

    private final Onboarding.PreparedHost delegate;
    private final AtomicBoolean closedHandle = new AtomicBoolean();

    private LiveHost(Onboarding.PreparedHost delegate) {
      this.delegate = delegate;
    }

    @Override
    public String invitationLink() {
      return delegate.invitationLink();
    }

    @Override
    public void importAnswer(String link) throws OnboardingFailure {
      delegate.importAnswerAndKeepAlive(link);
    }

    @Override
    public boolean retainsSession() {
      return true;
    }

    @Override
    public void close() {
      if (closedHandle.compareAndSet(false, true)) {
        handles.remove(this);
        delegate.close();
      }
    }
  }

  private final class LiveJoin implements PendingJoin {

    private final Onboarding.PreparedJoin delegate;
    private final AtomicBoolean closedHandle = new AtomicBoolean();

    private LiveJoin(Onboarding.PreparedJoin delegate) {
      this.delegate = delegate;
    }

    @Override
    public String answerLink() {
      return delegate.answerLink();
    }

    @Override
    public void connect() throws OnboardingFailure {
      delegate.connectAndKeepAlive();
    }

    @Override
    public boolean retainsSession() {
      return true;
    }

    @Override
    public void close() {
      if (closedHandle.compareAndSet(false, true)) {
        handles.remove(this);
        delegate.close();
      }
    }
  }
}
