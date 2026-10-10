package org.synesis.link.overlay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.session.Cancellable;
import org.synesis.link.session.LivenessState;
import org.synesis.link.session.LivenessTransition;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;

/**
 * Compares the signed-membership-derived desired topology with live authenticated
 * {@link PeerSession} adjacencies and converges the local physical graph.
 *
 * <p>The reconciler owns only local physical bindings and retry state. It does
 * not edit signed membership, accept an unsigned peer, route application
 * plaintext, or create a second authentication path. The supplied connector is
 * required to perform the normal Link candidate, identity, replay, and control-
 * readiness handshake. Only the lexicographically smaller member ID initiates
 * a desired edge, which gives both endpoints a deterministic concurrent-attempt
 * owner.
 *
 * <p>The default scheduler uses one daemon executor. A custom scheduler is
 * available to deterministic tests and controlled runtimes. This type is
 * thread-safe; connector completions and liveness callbacks may arrive on any
 * thread.
 *
 * @since 1.0
 */
public final class OverlayReconciler implements AutoCloseable {

  private static final OverlayReconciliationListener NOOP_LISTENER = event -> {
  };
  private static final java.util.function.Consumer<Set<String>> NOOP_PUBLISHER = ignored -> {
  };

  private final String localNodeId;
  private final OverlayMembershipView membership;
  private final OverlayTopologyPolicy topologyPolicy;
  private final OverlayPeerRegistry<PeerSession> peers;
  private final Map<String, CandidateDescriptor> descriptors;
  private final PeerConnector connector;
  private final OverlayReconciliationPolicy policy;
  private final Clock clock;
  private final ReconciliationScheduler scheduler;
  private final boolean ownsScheduler;
  private final java.util.function.Consumer<Set<String>> topologyPublisher;
  private final OverlayReconciliationListener listener;
  private final Map<String, Integer> failures = new HashMap<>();
  private final Map<String, Cancellable> retries = new HashMap<>();
  private final Set<String> connecting = new HashSet<>();
  private final Set<PeerSession> observedSessions = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap<>());
  private Set<String> lastDesiredNeighbors = Set.of();
  private boolean previouslyConverged;
  private boolean closed;

  /**
   * Creates a reconciler with the default retry scheduler and system clock.
   *
   * @param localNodeId local member node ID
   * @param membership  current verified signed membership view
   * @param topologyPolicy deterministic desired-topology policy
   * @param peers       mutable authenticated physical peer registry
   * @param descriptors signed candidate descriptors retained from lawful onboarding
   * @param connector   normal authenticated Link connector
   */
  public OverlayReconciler(String localNodeId, OverlayMembershipView membership,
      OverlayTopologyPolicy topologyPolicy, OverlayPeerRegistry<PeerSession> peers,
      Map<String, CandidateDescriptor> descriptors, PeerConnector connector) {
    this(localNodeId, membership, topologyPolicy, peers, descriptors, connector,
        OverlayReconciliationPolicy.DEFAULT, Clock.systemUTC(), null, null, null);
  }

  /**
   * Creates a fully configured reconciler.
   *
   * @param localNodeId       local member node ID
   * @param membership        current verified signed membership view
   * @param topologyPolicy    deterministic desired-topology policy
   * @param peers             mutable authenticated physical peer registry
   * @param descriptors       signed candidate descriptors retained from onboarding
   * @param connector         normal authenticated Link connector
   * @param policy            retry and concurrency limits
   * @param clock             wall clock used only for signed record validity checks
   * @param customScheduler   deterministic scheduler, or {@code null} for the default
   * @param topologyPublisher callback for publishing a newly observed local adjacency set
   * @param listener          safe diagnostic listener, or {@code null}
   */
  public OverlayReconciler(String localNodeId, OverlayMembershipView membership,
      OverlayTopologyPolicy topologyPolicy, OverlayPeerRegistry<PeerSession> peers,
      Map<String, CandidateDescriptor> descriptors, PeerConnector connector,
      OverlayReconciliationPolicy policy, Clock clock, ReconciliationScheduler customScheduler,
      java.util.function.Consumer<Set<String>> topologyPublisher,
      OverlayReconciliationListener listener) {
    OverlayCodecSupport.requireNodeId(localNodeId);
    this.localNodeId = localNodeId;
    this.membership = Objects.requireNonNull(membership, "membership");
    this.topologyPolicy = Objects.requireNonNull(topologyPolicy, "topology policy");
    this.peers = Objects.requireNonNull(peers, "peer registry");
    this.descriptors = new HashMap<>(Objects.requireNonNull(descriptors, "descriptors"));
    this.connector = Objects.requireNonNull(connector, "connector");
    this.policy = Objects.requireNonNull(policy, "reconciliation policy");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.topologyPublisher = topologyPublisher == null ? NOOP_PUBLISHER : topologyPublisher;
    this.listener = listener == null ? NOOP_LISTENER : listener;
    if (customScheduler == null) {
      this.scheduler = new ExecutorScheduler();
      this.ownsScheduler = true;
    } else {
      this.scheduler = customScheduler;
      this.ownsScheduler = false;
    }
    if (!membership.current().allows(localNodeId)) {
      throw new IllegalArgumentException("local node is not an active member");
    }
  }

  /**
   * Adds or replaces a signed descriptor used for future healing attempts.
   *
   * <p>The descriptor is checked again against the current membership and
   * validity interval when an attempt starts. Replacing it does not mutate
   * membership or an existing session.
   *
   * @param descriptor signed descriptor for an active member
   */
  public synchronized void updateDescriptor(CandidateDescriptor descriptor) {
    Objects.requireNonNull(descriptor, "descriptor");
    descriptors.put(descriptor.nodeId(), descriptor);
  }

  /**
   * Starts reconciliation and returns immediately.
   */
  public void start() {
    reconcile();
  }

  /**
   * Reconciles live physical adjacency toward the current signed membership policy.
   *
   * <p>The call is non-blocking. At most the configured number of connector
   * attempts are launched; failed attempts are retried with bounded backoff.
   */
  public synchronized void reconcile() {
    if (closed) {
      return;
    }
    OverlayMembershipSnapshot current = membership.current();
    Instant now = clock.instant();
    Set<String> desired = desiredNeighbors(current);
    if (!desired.equals(lastDesiredNeighbors)) {
      lastDesiredNeighbors = desired;
      previouslyConverged = false;
    }
    observeExistingBindings();
    retireInvalidBindings(current, desired);
    cancelIrrelevantRetries(current, desired);
    if (!current.isUsableAt(now)) {
      return;
    }
    int available = Math.max(0, policy.maxConcurrentConnects() - connecting.size());
    for (String peerNodeId : desired) {
      if (available == 0) {
        break;
      }
      PeerSession existing = peers.binding(peerNodeId);
      if (isUsable(existing)) {
        continue;
      }
      if (!ownsEdge(peerNodeId) || connecting.contains(peerNodeId)
          || retries.containsKey(peerNodeId)) {
        continue;
      }
      OverlayReconciliationEvent rejection = validateDescriptor(peerNodeId, current, now);
      if (rejection != null) {
        emit(rejection);
        continue;
      }
      emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.DESIRED_EDGE_MISSING,
          peerNodeId, "desired edge is not physically bound"));
      startConnection(peerNodeId);
      available--;
    }
    boolean converged = isConvergedLocked(desired);
    if (converged && !previouslyConverged) {
      emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.TOPOLOGY_CONVERGED,
          null, "desired local adjacencies are live"));
      publishAdjacency();
    }
    previouslyConverged = converged;
  }

  /**
   * Returns currently usable authenticated physical neighbors.
   *
   * @return immutable live neighbor IDs
   */
  public synchronized Set<String> liveNeighbors() {
    Set<String> result = new java.util.TreeSet<>();
    for (var entry : peers.snapshot().entrySet()) {
      if (isUsable(entry.getValue())) {
        result.add(entry.getKey());
      }
    }
    return Set.copyOf(result);
  }

  /**
   * Returns whether every locally owned desired edge is currently live.
   *
   * @return true when the local physical graph satisfies the desired policy
   */
  public synchronized boolean isConverged() {
    return isConvergedLocked(desiredNeighbors(membership.current()));
  }

  /**
   * Stops retries and closes the reconciler scheduler. Existing sessions are
   * left to their owning runtime and are not silently converted into membership changes.
   */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    retries.values().forEach(Cancellable::cancel);
    retries.clear();
    connecting.clear();
    if (ownsScheduler) {
      scheduler.close();
    }
  }

  private Set<String> desiredNeighbors(OverlayMembershipSnapshot snapshot) {
    return Set.copyOf(new java.util.TreeSet<>(topologyPolicy.desiredNeighbors(localNodeId,
        snapshot.members())));
  }

  private void retireInvalidBindings(OverlayMembershipSnapshot current, Set<String> desired) {
    for (var entry : peers.snapshot().entrySet()) {
      String peerNodeId = entry.getKey();
      PeerSession session = entry.getValue();
      if (current.allows(peerNodeId) && desired.contains(peerNodeId) && isUsable(session)) {
        continue;
      }
      if (peers.unbindIfSame(peerNodeId, session)) {
        emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.EXTRA_EDGE_RETIRED,
            peerNodeId, current.allows(peerNodeId) ? "edge is outside desired topology"
                : "peer is no longer an active member"));
        closeQuietly(session);
        publishAdjacency();
      }
    }
  }

  private void cancelIrrelevantRetries(OverlayMembershipSnapshot current, Set<String> desired) {
    for (String peerNodeId : Set.copyOf(retries.keySet())) {
      if (!current.allows(peerNodeId) || !desired.contains(peerNodeId)) {
        cancelRetry(peerNodeId);
        failures.remove(peerNodeId);
      }
    }
  }

  private void observeExistingBindings() {
    for (PeerSession session : peers.snapshot().values()) {
      if (isUsable(session)) {
        attachLifecycle(session.remoteNodeId(), session);
      }
    }
  }

  private OverlayReconciliationEvent validateDescriptor(String peerNodeId,
      OverlayMembershipSnapshot current, Instant now) {
    CandidateDescriptor descriptor = descriptors.get(peerNodeId);
    if (descriptor == null) {
      return rejected(peerNodeId, "no retained signed candidate descriptor");
    }
    OverlayMembershipSnapshot.Member member = current.member(peerNodeId).orElse(null);
    try {
      if (!descriptor.isValidAt(now, CandidateDescriptor.DEFAULT_CLOCK_SKEW)
          || member == null || !java.util.Arrays.equals(descriptor.publicKeyEncoded(),
          member.publicKeyEncoded())) {
        return rejected(peerNodeId, "candidate descriptor is not current for membership");
      }
    } catch (java.security.GeneralSecurityException failure) {
      return rejected(peerNodeId, "candidate descriptor signature is invalid");
    }
    return null;
  }

  private OverlayReconciliationEvent rejected(String peerNodeId, String detail) {
    return new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.RECONNECT_REJECTED,
        peerNodeId, detail);
  }

  private void startConnection(String peerNodeId) {
    CandidateDescriptor descriptor = descriptors.get(peerNodeId);
    connecting.add(peerNodeId);
    emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.RECONNECT_ATTEMPT,
        peerNodeId, "authenticated Link connection attempt started"));
    CompletionStage<PeerSession> result;
    try {
      result = connector.connect(descriptor);
      if (result == null) {
        throw new IllegalStateException("connector returned no completion");
      }
    } catch (RuntimeException failure) {
      connectionFailed(peerNodeId, failure);
      return;
    }
    result.whenComplete((session, failure) -> {
      if (failure != null) {
        connectionFailed(peerNodeId, failure);
      } else {
        connectionSucceeded(peerNodeId, session);
      }
    });
  }

  private void connectionSucceeded(String peerNodeId, PeerSession session) {
    synchronized (this) {
      if (closed) {
        closeQuietly(session);
        return;
      }
      connecting.remove(peerNodeId);
      if (!validReturnedSession(peerNodeId, session)) {
        emit(rejected(peerNodeId, "connector returned an unauthenticated or mismatched session"));
        closeQuietly(session);
        scheduleRetry(peerNodeId);
        return;
      }
      if (!peers.bindIfAbsent(peerNodeId, session)) {
        emit(rejected(peerNodeId, "duplicate authenticated session lost deterministic admission"));
        closeQuietly(session);
        scheduleRetry(peerNodeId);
        return;
      }
      failures.remove(peerNodeId);
      cancelRetry(peerNodeId);
      attachLifecycle(peerNodeId, session);
      emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.RECONNECT_AUTHENTICATED,
          peerNodeId, "fresh authenticated Link session installed"));
      publishAdjacency();
      reconcile();
    }
  }

  private void connectionFailed(String peerNodeId, Throwable failure) {
    synchronized (this) {
      if (closed) {
        return;
      }
      OverlayMembershipSnapshot current = membership.current();
      if (!current.allows(peerNodeId) || !desiredNeighbors(current).contains(peerNodeId)) {
        connecting.remove(peerNodeId);
        failures.remove(peerNodeId);
        return;
      }
      connecting.remove(peerNodeId);
      int count = failures.merge(peerNodeId, 1, Integer::sum);
      emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.RECONNECT_FAILED,
          peerNodeId, "authenticated Link attempt failed; retry " + count));
      scheduleRetry(peerNodeId);
    }
  }

  private boolean validReturnedSession(String peerNodeId, PeerSession session) {
    if (session == null || !session.isUsable() || !isUsable(session)
        || !localNodeId.equals(session.localNodeId()) || !peerNodeId.equals(session.remoteNodeId())) {
      return false;
    }
    OverlayMembershipSnapshot.Member member = membership.current().member(peerNodeId).orElse(null);
    return member != null && membership.current().allows(peerNodeId)
        && session.hasRemotePublicKey(member.publicKeyEncoded());
  }

  private void attachLifecycle(String peerNodeId, PeerSession session) {
    if (!observedSessions.add(session)) {
      return;
    }
    try {
      session.addLivenessListener(transition -> onTransition(peerNodeId, session, transition));
    } catch (RuntimeException failure) {
      observedSessions.remove(session);
      retireSession(peerNodeId, session, "liveness listener unavailable");
      return;
    }
    session.terminalCompletion().whenComplete((ignored, failure) -> retireSession(peerNodeId, session,
        "session terminal completion"));
  }

  private void onTransition(String peerNodeId, PeerSession session, LivenessTransition transition) {
    if (isTerminal(transition.to())) {
      retireSession(peerNodeId, session, "liveness became " + transition.to());
    }
  }

  private void retireSession(String peerNodeId, PeerSession session, String detail) {
    synchronized (this) {
      if (closed || !peers.unbindIfSame(peerNodeId, session)) {
        return;
      }
      emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.ADJACENCY_LOST,
          peerNodeId, detail));
      emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.ROUTE_WITHDRAWN,
          peerNodeId, "physical adjacency removed from route inputs"));
      publishAdjacency();
      scheduleImmediateReconcile();
    }
  }

  private void scheduleRetry(String peerNodeId) {
    if (retries.containsKey(peerNodeId) || closed) {
      return;
    }
    int count = failures.getOrDefault(peerNodeId, 1);
    Duration delay = policy.delayFor(count);
    Cancellable handle = scheduler.schedule(() -> {
      synchronized (OverlayReconciler.this) {
        retries.remove(peerNodeId);
        reconcile();
      }
    }, delay);
    retries.put(peerNodeId, handle);
    emit(new OverlayReconciliationEvent(OverlayReconciliationEvent.Type.RECONNECT_SCHEDULED,
        peerNodeId, "retry scheduled after " + delay));
  }

  private void scheduleImmediateReconcile() {
    scheduler.schedule(this::reconcile, Duration.ZERO);
  }

  private void cancelRetry(String peerNodeId) {
    Cancellable handle = retries.remove(peerNodeId);
    if (handle != null) {
      handle.cancel();
    }
  }

  private boolean ownsEdge(String peerNodeId) {
    return localNodeId.compareTo(peerNodeId) < 0;
  }

  private boolean isConvergedLocked(Set<String> desired) {
    for (String peerNodeId : desired) {
      if (!isUsable(peers.binding(peerNodeId))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isUsable(PeerSession session) {
    if (session == null || !session.isUsable()) {
      return false;
    }
    LivenessState state = session.livenessState();
    return state == LivenessState.LIVE || state == LivenessState.SUSPECT;
  }

  private static boolean isTerminal(LivenessState state) {
    return state == LivenessState.EXPIRED || state == LivenessState.CLOSED_GRACEFULLY
        || state == LivenessState.CLOSED_BY_PEER || state == LivenessState.CLOSED_BY_PROTOCOL
        || state == LivenessState.FAILED;
  }

  private void publishAdjacency() {
    try {
      topologyPublisher.accept(liveNeighbors());
    } catch (RuntimeException ignored) {
      // Diagnostics must never corrupt reconciliation state.
    }
  }

  private void emit(OverlayReconciliationEvent event) {
    try {
      listener.onEvent(event);
    } catch (RuntimeException ignored) {
      // Diagnostics must never corrupt reconciliation state.
    }
  }

  private static void closeQuietly(PeerSession session) {
    if (session == null) {
      return;
    }
    try {
      session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
    } catch (RuntimeException ignored) {
      // The session may already be terminal.
    }
  }

  /**
   * Supplies a fresh normal Link connection for one retained signed descriptor.
   *
   * <p>Implementations must use candidate validation, expected-peer identity
   * checks, replay protection, and control readiness before completing. A
   * descriptor is routing information, never authentication.
   *
   * @since 1.0
   */
  @FunctionalInterface
  public interface PeerConnector {

    /**
     * Starts one bounded fresh authenticated connection.
     *
     * @param descriptor signed candidate descriptor for the expected peer
     * @return completion of a usable authenticated session
     */
    CompletionStage<PeerSession> connect(CandidateDescriptor descriptor);
  }

  /**
   * Minimal injectable scheduling seam for deterministic reconciliation tests.
   */
  @FunctionalInterface
  public interface ReconciliationScheduler extends AutoCloseable {

    /**
     * Schedules one bounded callback.
     *
     * @param action callback
     * @param delay  non-negative delay
     * @return cancellation handle
     */
    Cancellable schedule(Runnable action, Duration delay);

    /**
     * Releases scheduler resources when owned by the reconciler.
     */
    @Override
    default void close() {
    }
  }

  private static final class ExecutorScheduler implements ReconciliationScheduler {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "synesis-link-overlay-reconcile");
          thread.setDaemon(true);
          return thread;
        });

    @Override
    public Cancellable schedule(Runnable action, Duration delay) {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(delay, "delay");
      if (delay.isNegative()) {
        throw new IllegalArgumentException("schedule delay must not be negative");
      }
      ScheduledFuture<?> future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
      return () -> future.cancel(false);
    }

    @Override
    public void close() {
      executor.shutdownNow();
    }
  }
}
