package org.synesis.link.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayPeerRegistry;
import org.synesis.link.overlay.OverlayReconciliationEvent;
import org.synesis.link.overlay.OverlayReconciliationPolicy;
import org.synesis.link.overlay.OverlayReconciler;
import org.synesis.link.overlay.OverlayRoute;
import org.synesis.link.overlay.OverlayRouteSelector;
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.overlay.OverlayTopologyPolicy;
import org.synesis.link.protocol.ProtocolVersion;

/**
 * Deterministic tests for local overlay adjacency reconciliation.
 */
final class OverlayReconcilerTest {

  private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

  private static OverlayMembershipSnapshot membership(NodeIdentity authority,
      NodeIdentity... peers) throws Exception {
    List<OverlayMembershipSnapshot.Member> members = new ArrayList<>();
    members.add(member(authority));
    Arrays.stream(peers).map(OverlayReconcilerTest::member).forEach(members::add);
    return OverlayMembershipSnapshot.create(UUID.randomUUID(), 1, NOW.minusSeconds(1),
        NOW.plusSeconds(300), members, authority);
  }

  private static OverlayMembershipSnapshot.Member member(NodeIdentity identity) {
    return new OverlayMembershipSnapshot.Member(identity.nodeId(),
        OverlayMembershipSnapshot.STATUS_ACTIVE, identity.publicKeyEncoded());
  }

  private static CandidateDescriptor descriptor(NodeIdentity identity) throws Exception {
    return CandidateDescriptor.create(identity, NOW.minusSeconds(1), NOW.plusSeconds(300), List.of());
  }

  private static PeerSession session(String localNodeId, NodeIdentity remote) {
    return session(localNodeId, remote, new StubBinding());
  }

  private static PeerSession session(String localNodeId, NodeIdentity remote, StubBinding binding) {
    PeerSession session = new PeerSession(localNodeId, remote.nodeId(), remote.publicKeyEncoded(),
        UUID.randomUUID(), 1, 1, ProtocolVersion.V1, NOW);
    session.attachControl(binding);
    return session;
  }

  @Test
  void removesTerminalAdjacencyAndAutomaticallyRetriesMissingDesiredEdge() throws Exception {
    NodeIdentity authority = NodeIdentity.generate();
    NodeIdentity second = NodeIdentity.generate();
    NodeIdentity third = NodeIdentity.generate();
    List<NodeIdentity> identities = new ArrayList<>(List.of(authority, second, third));
    identities.sort(java.util.Comparator.comparing(NodeIdentity::nodeId));
    NodeIdentity local = identities.getFirst();
    NodeIdentity middle = identities.get(1);
    NodeIdentity survivor = identities.get(2);
    OverlayMembershipSnapshot snapshot = membership(local, middle, survivor);
    OverlayPeerRegistry<PeerSession> peers = new OverlayPeerRegistry<>();
    StubBinding middleBinding = new StubBinding();
    PeerSession middleSession = session(local.nodeId(), middle, middleBinding);
    peers.bind(middle.nodeId(), middleSession);
    StubScheduler scheduler = new StubScheduler();
    CompletableFuture<PeerSession> survivorConnection = new CompletableFuture<>();
    AtomicInteger attempts = new AtomicInteger();
    List<OverlayReconciliationEvent> events = new CopyOnWriteArrayList<>();
    OverlayReconciler reconciler = new OverlayReconciler(local.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), peers,
        Map.of(middle.nodeId(), descriptor(middle), survivor.nodeId(), descriptor(survivor)),
        descriptor -> {
          attempts.incrementAndGet();
          return descriptor.nodeId().equals(survivor.nodeId())
              ? survivorConnection
              : CompletableFuture.failedFuture(new IllegalStateException("middle is offline"));
        }, new OverlayReconciliationPolicy(Duration.ofMillis(10), Duration.ofMillis(40), 1),
        Clock.fixed(NOW, ZoneOffset.UTC), scheduler, ignored -> {
        }, events::add);
    try (reconciler) {
      reconciler.start();
      assertEquals(1, attempts.get(), "the missing surviving edge should be attempted once");
      survivorConnection.complete(session(local.nodeId(), survivor));
      assertTrue(reconciler.liveNeighbors().contains(survivor.nodeId()));

      middleBinding.terminate();
      assertFalse(reconciler.liveNeighbors().contains(middle.nodeId()));
      assertTrue(events.stream().anyMatch(event -> event.type()
          == OverlayReconciliationEvent.Type.ROUTE_WITHDRAWN));
      assertEquals(OverlayRoute.Kind.UNREACHABLE,
          OverlayRouteSelector.select(local.nodeId(), middle.nodeId(), snapshot,
              peers.nodeIds(), new OverlayTopologyView(), NOW, null).kind());
      scheduler.runImmediate();
      assertTrue(reconciler.liveNeighbors().contains(survivor.nodeId()));
      assertEquals(OverlayRoute.Kind.DIRECT,
          OverlayRouteSelector.select(local.nodeId(), survivor.nodeId(), snapshot,
              peers.nodeIds(), new OverlayTopologyView(), NOW, null).kind());
      assertTrue(events.stream().anyMatch(event -> event.type()
          == OverlayReconciliationEvent.Type.RECONNECT_SCHEDULED)
          || reconciler.isConverged());
    }
  }

  @Test
  void coalescesConcurrentAttemptsAndUsesBoundedBackoff() throws Exception {
    NodeIdentity local = NodeIdentity.generate();
    NodeIdentity peer = NodeIdentity.generate();
    if (local.nodeId().compareTo(peer.nodeId()) > 0) {
      NodeIdentity swap = local;
      local = peer;
      peer = swap;
    }
    OverlayMembershipSnapshot snapshot = membership(local, peer);
    OverlayPeerRegistry<PeerSession> peers = new OverlayPeerRegistry<>();
    StubScheduler scheduler = new StubScheduler();
    AtomicInteger attempts = new AtomicInteger();
    OverlayReconciler reconciler = new OverlayReconciler(local.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), peers,
        Map.of(peer.nodeId(), descriptor(peer)), ignored -> {
          attempts.incrementAndGet();
          return CompletableFuture.failedFuture(new IllegalStateException("unreachable"));
        }, new OverlayReconciliationPolicy(Duration.ofMillis(10), Duration.ofMillis(25), 1),
        Clock.fixed(NOW, ZoneOffset.UTC), scheduler, null, null);
    try (reconciler) {
      reconciler.reconcile();
      reconciler.reconcile();
      assertEquals(1, attempts.get());
      assertEquals(Duration.ofMillis(10), scheduler.lastDelay());
      scheduler.runNext();
      assertEquals(2, attempts.get());
      assertEquals(Duration.ofMillis(20), scheduler.lastDelay());
      scheduler.runNext();
      assertEquals(Duration.ofMillis(25), scheduler.lastDelay());
    }
  }

  @Test
  void stableCorrectTopologyDoesNotConnectOrSchedule() throws Exception {
    NodeIdentity local = NodeIdentity.generate();
    NodeIdentity peer = NodeIdentity.generate();
    if (local.nodeId().compareTo(peer.nodeId()) > 0) {
      NodeIdentity swap = local;
      local = peer;
      peer = swap;
    }
    NodeIdentity stableLocal = local;
    NodeIdentity stablePeer = peer;
    OverlayMembershipSnapshot snapshot = membership(stableLocal, stablePeer);
    OverlayPeerRegistry<PeerSession> peers = new OverlayPeerRegistry<>();
    peers.bind(stablePeer.nodeId(), session(stableLocal.nodeId(), stablePeer));
    StubScheduler scheduler = new StubScheduler();
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger publications = new AtomicInteger();
    OverlayReconciler reconciler = new OverlayReconciler(stableLocal.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), peers,
        Map.of(stablePeer.nodeId(), descriptor(stablePeer)), ignored -> {
          attempts.incrementAndGet();
          return CompletableFuture.completedFuture(session(stableLocal.nodeId(), stablePeer));
        }, OverlayReconciliationPolicy.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC), scheduler,
        ignored -> publications.incrementAndGet(), null);
    try (reconciler) {
      reconciler.start();
      reconciler.reconcile();
      reconciler.reconcile();
      assertTrue(reconciler.isConverged());
      assertEquals(0, attempts.get());
      assertEquals(0, scheduler.pending());
      assertEquals(1, publications.get(), "stable convergence must not republish on every tick");
    }
  }

  @Test
  void limitsConcurrentMissingEdgesAndAdmitsEachOwnedEdgeOnce() throws Exception {
    List<NodeIdentity> identities = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      identities.add(NodeIdentity.generate());
    }
    identities.sort(java.util.Comparator.comparing(NodeIdentity::nodeId));
    NodeIdentity local = identities.getFirst();
    NodeIdentity[] remoteIdentities = identities.subList(1, identities.size())
        .toArray(NodeIdentity[]::new);
    OverlayMembershipSnapshot snapshot = membership(local, remoteIdentities);
    OverlayPeerRegistry<PeerSession> peers = new OverlayPeerRegistry<>();
    Map<String, CandidateDescriptor> descriptors = new HashMap<>();
    Map<String, CompletableFuture<PeerSession>> pending = new HashMap<>();
    for (NodeIdentity remote : remoteIdentities) {
      descriptors.put(remote.nodeId(), descriptor(remote));
    }
    AtomicInteger attempts = new AtomicInteger();
    StubScheduler scheduler = new StubScheduler();
    OverlayReconciler reconciler = new OverlayReconciler(local.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), peers, descriptors,
        candidate -> {
          attempts.incrementAndGet();
          CompletableFuture<PeerSession> result = new CompletableFuture<>();
          pending.put(candidate.nodeId(), result);
          return result;
        }, new OverlayReconciliationPolicy(Duration.ofMillis(10), Duration.ofMillis(40), 2),
        Clock.fixed(NOW, ZoneOffset.UTC), scheduler, null, null);
    try (reconciler) {
      reconciler.start();
      assertEquals(2, attempts.get(), "connector concurrency must be bounded");
      assertEquals(2, pending.size());

      String firstPeer = pending.keySet().iterator().next();
      pending.get(firstPeer).complete(session(local.nodeId(), identity(firstPeer, remoteIdentities)));
      assertEquals(3, attempts.get(), "one completion should release one owned slot");

      for (CompletableFuture<PeerSession> result : List.copyOf(pending.values())) {
        if (!result.isDone()) {
          String peerNodeId = pending.entrySet().stream()
              .filter(entry -> entry.getValue() == result)
              .map(Map.Entry::getKey)
              .findFirst()
              .orElseThrow();
          result.complete(session(local.nodeId(), identity(peerNodeId, remoteIdentities)));
        }
      }
      assertTrue(reconciler.isConverged());
      assertEquals(3, attempts.get(), "already-correct edges must not be retried");
    }
  }

  @Test
  void reciprocalReconcilersHaveOneDeterministicConnectionOwner() throws Exception {
    NodeIdentity first = NodeIdentity.generate();
    NodeIdentity second = NodeIdentity.generate();
    if (first.nodeId().compareTo(second.nodeId()) > 0) {
      NodeIdentity swap = first;
      first = second;
      second = swap;
    }
    OverlayMembershipSnapshot snapshot = membership(first, second);
    OverlayPeerRegistry<PeerSession> firstPeers = new OverlayPeerRegistry<>();
    OverlayPeerRegistry<PeerSession> secondPeers = new OverlayPeerRegistry<>();
    StubScheduler firstScheduler = new StubScheduler();
    StubScheduler secondScheduler = new StubScheduler();
    AtomicInteger firstAttempts = new AtomicInteger();
    AtomicInteger secondAttempts = new AtomicInteger();
    OverlayReconciler firstReconciler = new OverlayReconciler(first.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), firstPeers,
        Map.of(second.nodeId(), descriptor(second)), ignored -> {
          firstAttempts.incrementAndGet();
          return new CompletableFuture<>();
        }, OverlayReconciliationPolicy.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC), firstScheduler,
        null, null);
    OverlayReconciler secondReconciler = new OverlayReconciler(second.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), secondPeers,
        Map.of(first.nodeId(), descriptor(first)), ignored -> {
          secondAttempts.incrementAndGet();
          return new CompletableFuture<>();
        }, OverlayReconciliationPolicy.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC), secondScheduler,
        null, null);
    try (firstReconciler; secondReconciler) {
      firstReconciler.start();
      secondReconciler.start();
      assertEquals(1, firstAttempts.get());
      assertEquals(0, secondAttempts.get(), "larger member must not initiate the reciprocal edge");
    }
  }

  @Test
  void livePeerReturningDuringBackoffSuppressesAnotherAttempt() throws Exception {
    NodeIdentity local = NodeIdentity.generate();
    NodeIdentity peer = NodeIdentity.generate();
    if (local.nodeId().compareTo(peer.nodeId()) > 0) {
      NodeIdentity swap = local;
      local = peer;
      peer = swap;
    }
    OverlayMembershipSnapshot snapshot = membership(local, peer);
    OverlayPeerRegistry<PeerSession> peers = new OverlayPeerRegistry<>();
    StubScheduler scheduler = new StubScheduler();
    AtomicInteger attempts = new AtomicInteger();
    OverlayReconciler reconciler = new OverlayReconciler(local.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), peers,
        Map.of(peer.nodeId(), descriptor(peer)), ignored -> {
          attempts.incrementAndGet();
          return CompletableFuture.failedFuture(new IllegalStateException("temporarily offline"));
        }, new OverlayReconciliationPolicy(Duration.ofMillis(10), Duration.ofMillis(25), 1),
        Clock.fixed(NOW, ZoneOffset.UTC), scheduler, null, null);
    try (reconciler) {
      reconciler.start();
      assertEquals(1, attempts.get());
      peers.bind(peer.nodeId(), session(local.nodeId(), peer));
      scheduler.runNext();
      assertTrue(reconciler.isConverged());
      assertEquals(1, attempts.get(), "backoff must not reconnect an already-live peer");
    }
  }

  @Test
  void duplicateAuthenticatedCompletionCannotReplaceExistingBinding() throws Exception {
    NodeIdentity local = NodeIdentity.generate();
    NodeIdentity peer = NodeIdentity.generate();
    if (local.nodeId().compareTo(peer.nodeId()) > 0) {
      NodeIdentity swap = local;
      local = peer;
      peer = swap;
    }
    OverlayMembershipSnapshot snapshot = membership(local, peer);
    OverlayPeerRegistry<PeerSession> peers = new OverlayPeerRegistry<>();
    StubScheduler scheduler = new StubScheduler();
    StubBinding incumbentBinding = new StubBinding();
    StubBinding duplicateBinding = new StubBinding();
    PeerSession incumbent = session(local.nodeId(), peer, incumbentBinding);
    PeerSession duplicate = session(local.nodeId(), peer, duplicateBinding);
    CompletableFuture<PeerSession> pending = new CompletableFuture<>();
    AtomicInteger attempts = new AtomicInteger();
    OverlayReconciler reconciler = new OverlayReconciler(local.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), peers,
        Map.of(peer.nodeId(), descriptor(peer)), ignored -> {
          attempts.incrementAndGet();
          return pending;
        }, new OverlayReconciliationPolicy(Duration.ofMillis(10), Duration.ofMillis(25), 1),
        Clock.fixed(NOW, ZoneOffset.UTC), scheduler, null, null);
    try (reconciler) {
      reconciler.start();
      peers.bind(peer.nodeId(), incumbent);
      pending.complete(duplicate);
      assertTrue(duplicateBinding.isTerminated(), "losing duplicate must be closed");
      assertEquals(incumbent, peers.binding(peer.nodeId()));
      scheduler.runNext();
      assertTrue(reconciler.isConverged());
      assertEquals(1, attempts.get());
    }
  }

  @Test
  void staleTerminalCallbackCannotRemoveAReplacementBinding() throws Exception {
    NodeIdentity local = NodeIdentity.generate();
    NodeIdentity peer = NodeIdentity.generate();
    if (local.nodeId().compareTo(peer.nodeId()) > 0) {
      NodeIdentity swap = local;
      local = peer;
      peer = swap;
    }
    OverlayMembershipSnapshot snapshot = membership(local, peer);
    OverlayPeerRegistry<PeerSession> peers = new OverlayPeerRegistry<>();
    StubBinding firstBinding = new StubBinding();
    PeerSession first = session(local.nodeId(), peer, firstBinding);
    peers.bind(peer.nodeId(), first);
    OverlayReconciler reconciler = new OverlayReconciler(local.nodeId(),
        new OverlayMembershipView(snapshot), new OverlayTopologyPolicy(), peers,
        Map.of(peer.nodeId(), descriptor(peer)), ignored -> new CompletableFuture<>(),
        OverlayReconciliationPolicy.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC),
        new StubScheduler(), null, null);
    StubBinding replacementBinding = new StubBinding();
    PeerSession replacement = session(local.nodeId(), peer, replacementBinding);
    try (reconciler) {
      reconciler.start();
      assertTrue(peers.unbindIfSame(peer.nodeId(), first));
      assertTrue(peers.bindIfAbsent(peer.nodeId(), replacement));
      firstBinding.terminate();
      assertEquals(replacement, peers.binding(peer.nodeId()));
    }
  }

  private static NodeIdentity identity(String nodeId, NodeIdentity[] identities) {
    return Arrays.stream(identities).filter(identity -> identity.nodeId().equals(nodeId)).findFirst()
        .orElseThrow();
  }

  private static final class StubBinding implements PeerSession.ControlBinding {

    private final CompletableFuture<Void> terminal = new CompletableFuture<>();
    private final List<LivenessListener> listeners = new CopyOnWriteArrayList<>();
    private volatile LivenessState state = LivenessState.LIVE;

    @Override
    public boolean isReady() {
      return !terminal.isDone();
    }

    @Override
    public CompletionStage<Void> closeGracefully(SessionCloseReason reason) {
      terminate();
      return terminal;
    }

    @Override
    public CompletionStage<Void> terminalCompletion() {
      return terminal;
    }

    @Override
    public SessionCloseReason closeReason() {
      return terminal.isDone() ? SessionCloseReason.TRANSPORT_CLOSED : null;
    }

    @Override
    public LivenessState livenessState() {
      return state;
    }

    @Override
    public LivenessMetrics livenessMetrics() {
      return new LivenessMetrics(0, 0, 0, 0, 0, Duration.ZERO, Duration.ZERO, 0, 0, 0, 0, 0);
    }

    @Override
    public void addLivenessListener(LivenessListener listener) {
      listeners.add(listener);
    }

    @Override
    public void removeLivenessListener(LivenessListener listener) {
      listeners.remove(listener);
    }

    private void terminate() {
      if (terminal.complete(null)) {
        state = LivenessState.FAILED;
        LivenessTransition transition = new LivenessTransition(LivenessState.LIVE,
            LivenessState.FAILED, Duration.ZERO);
        listeners.forEach(listener -> listener.onTransition(transition));
      }
    }

    private boolean isTerminated() {
      return terminal.isDone();
    }
  }

  private static final class StubScheduler implements OverlayReconciler.ReconciliationScheduler {

    private final List<Task> tasks = new ArrayList<>();
    private Duration lastDelay;

    @Override
    public synchronized Cancellable schedule(Runnable action, Duration delay) {
      lastDelay = delay;
      Task task = new Task(action, delay);
      tasks.add(task);
      return () -> {
        synchronized (StubScheduler.this) {
          task.cancelled = true;
        }
      };
    }

    private synchronized Duration lastDelay() {
      return lastDelay;
    }

    private void runImmediate() {
      Task task;
      synchronized (this) {
        task = tasks.stream().filter(value -> !value.cancelled && value.delay.isZero()).findFirst()
            .orElseThrow();
        task.cancelled = true;
      }
      task.action.run();
    }

    private void runNext() {
      Task task;
      synchronized (this) {
        task = tasks.stream().filter(value -> !value.cancelled).findFirst().orElseThrow();
        task.cancelled = true;
      }
      task.action.run();
    }

    private synchronized int pending() {
      return (int) tasks.stream().filter(value -> !value.cancelled).count();
    }

    private static final class Task {
      private final Runnable action;
      private final Duration delay;
      private boolean cancelled;

      private Task(Runnable action, Duration delay) {
        this.action = action;
        this.delay = delay;
      }
    }
  }
}
