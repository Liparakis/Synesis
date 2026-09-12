package org.synesis.link.transport.quic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
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
import io.netty.util.NetUtil;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayDuplicateGuard;
import org.synesis.link.overlay.OverlayEnvelope;
import org.synesis.link.overlay.OverlayForwarder;
import org.synesis.link.overlay.OverlayForwardingFrame;
import org.synesis.link.overlay.OverlayKeyAgreement;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayPeerRegistry;
import org.synesis.link.overlay.OverlayPeerSessionBridge;
import org.synesis.link.overlay.OverlayReconciliationEvent;
import org.synesis.link.overlay.OverlayReconciliationPolicy;
import org.synesis.link.overlay.OverlayReconciler;
import org.synesis.link.overlay.OverlayRoute;
import org.synesis.link.overlay.OverlayRouteSelector;
import org.synesis.link.overlay.OverlayTopologyAdvertisement;
import org.synesis.link.overlay.OverlayTopologyPolicy;
import org.synesis.link.overlay.OverlayTopologyPropagation;
import org.synesis.link.overlay.OverlayTopologyPropagationFrame;
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;

/**
 * Verifies convergence, peer transit, failure recovery, and rejoin for the bounded six-member
 * overlay over native QUIC sessions.
 */
final class OverlayGraphQuicTest {

  private static final int MEMBER_COUNT = 6;
  private static final java.util.concurrent.Semaphore NATIVE_CONNECT_GATE =
      new java.util.concurrent.Semaphore(1);
  private static final LivenessConfiguration TEST_LIVENESS = new LivenessConfiguration(
      Duration.ofMillis(50), Duration.ofMillis(150), Duration.ofMillis(300), true);
  private static final OverlayReconciliationPolicy RECONCILIATION_POLICY =
      new OverlayReconciliationPolicy(Duration.ofMillis(50), Duration.ofMillis(250), 4);

  @Test
  void sixMemberOverlayConvergesRoutesTransitRecoversAndRejoins() throws Exception {
    List<NodeIdentity> identities = new ArrayList<>();
    for (int index = 0; index < MEMBER_COUNT; index++) {
      identities.add(NodeIdentity.generate());
    }
    identities.sort(Comparator.comparing(NodeIdentity::nodeId));
    NodeIdentity authority = identities.getFirst();
    Instant issuedAt = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plusSeconds(600);
    UUID projectId = UUID.randomUUID();
    OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(projectId, 1,
        issuedAt, expiresAt,
        identities.stream().map(OverlayGraphQuicTest::member).toList(), authority);
    OverlayTopologyPolicy policy = new OverlayTopologyPolicy();
    for (int count = 1; count < MEMBER_COUNT; count++) {
      assertTrue(isFullMesh(policy.desiredNeighbors(membership.members().subList(0, count))),
          "all smaller populations remain effectively full mesh at N=" + count);
    }
    Map<String, List<String>> expectedNeighbors = policy.desiredNeighbors(membership.members());
    Set<Edge> expectedEdges = edges(expectedNeighbors);
    assertEquals(12, expectedEdges.size(), "six-member +/-1/+/-2 graph has twelve edges");
    assertTrue(expectedNeighbors.values().stream().allMatch(value -> value.size() == 4));
    assertFalse(expectedNeighbors.get(identities.getFirst().nodeId())
        .contains(identities.get(identities.size() / 2).nodeId()),
        "the opposite six-member pair is intentionally non-direct");

    Map<String, OverlayNode> nodes = new HashMap<>();
    try {
      for (NodeIdentity identity : identities) {
        OverlayNode node = new OverlayNode(identity, membership);
        node.startServer(0);
        nodes.put(identity.nodeId(), node);
      }
      Map<String, Integer> ports = new HashMap<>();
      nodes.values().forEach(node -> ports.put(node.nodeId(), node.port()));
      LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
      for (OverlayNode node : nodes.values()) {
        node.configure(nodes, ports, policy);
      }
      for (NodeIdentity identity : identities) {
        nodes.get(identity.nodeId()).startReconciliation();
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
      }
      awaitGraph("six-member graph convergence", nodes, expectedEdges);
      assertEquals(expectedEdges, observedEdges(nodes));
      assertEquals(Map.ofEntries(
          Map.entry(identities.get(0).nodeId(), 4), Map.entry(identities.get(1).nodeId(), 4),
          Map.entry(identities.get(2).nodeId(), 4), Map.entry(identities.get(3).nodeId(), 4),
          Map.entry(identities.get(4).nodeId(), 4), Map.entry(identities.get(5).nodeId(), 4)),
          degrees(nodes));
      assertTrue(degrees(nodes).values().stream().max(Integer::compareTo).orElseThrow() <= 4);
      await("topology convergence diagnostics", () -> nodes.values().stream()
          .allMatch(node -> node.hasEvent(OverlayReconciliationEvent.Type.TOPOLOGY_CONVERGED,
              null)));
      awaitTopology(nodes, expectedNeighbors);

      OverlayNode origin = nodes.get(identities.getFirst().nodeId());
      OverlayNode destination = nodes.get(identities.get(3).nodeId());
      Instant now = Instant.now();
      OverlayRoute initialRoute = route(origin, destination, membership, now);
      assertEquals(OverlayRoute.Kind.PEER_TRANSIT, initialRoute.kind());
      assertFalse(origin.liveNeighbors().contains(destination.nodeId()));
      assertEquals(2, initialRoute.path().size());
      OverlayNode killedTransit = nodes.get(initialRoute.path().getFirst());
      assertNotEquals(destination.nodeId(), killedTransit.nodeId());

      byte[] plaintext = "six-member transit payload".getBytes(StandardCharsets.UTF_8);
      OverlayKeyAgreement.E2eSession sender = establishE2e(origin, destination, membership,
          now);
      OverlayEnvelope envelope = sender.encrypt(UUID.randomUUID(), plaintext);
      OverlayForwardingFrame dataFrame = OverlayForwardingFrame.create(projectId,
          destination.nodeId(), envelope.messageId(), initialRoute.path().size(), envelope.encoded());
      origin.forward(dataFrame, now).toCompletableFuture().join();
      await("initial transit delivery", () -> destination.lastDeliveredPayload() != null);
      assertArrayEquals(plaintext, destination.lastDeliveredPayload());
      assertTrue(killedTransit.sawOpaqueEnvelope(envelope.messageId(), plaintext));
      assertFalse(killedTransit.hasE2eSessionFor(origin.nodeId(), destination.nodeId()));

      killedTransit.crashAbruptly();
      nodes.remove(killedTransit.nodeId());
      await("transit adjacency withdrawal", () -> nodes.values().stream()
          .noneMatch(node -> node.liveNeighbors().contains(killedTransit.nodeId())));
      await("loss and route withdrawal diagnostics", () -> nodes.values().stream()
          .anyMatch(node -> node.hasEvent(OverlayReconciliationEvent.Type.ADJACENCY_LOST,
              killedTransit.nodeId()) && node.hasEvent(
                  OverlayReconciliationEvent.Type.ROUTE_WITHDRAWN, killedTransit.nodeId())));
      assertTrue(membership.member(killedTransit.nodeId()).isPresent(),
          "physical loss must not revoke signed membership");
      Set<String> survivingIds = Set.copyOf(nodes.keySet());
      Set<Edge> expectedAfterFailure = expectedEdges.stream()
          .filter(edge -> survivingIds.contains(edge.left()) && survivingIds.contains(edge.right()))
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      Map<String, List<String>> expectedNeighborsAfterFailure = expectedNeighbors.entrySet().stream()
          .filter(entry -> survivingIds.contains(entry.getKey()))
          .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
              entry -> entry.getValue().stream().filter(survivingIds::contains).toList()));
      await("surviving graph remains bounded", () -> observedEdges(nodes).equals(expectedAfterFailure));
      awaitTopology(nodes, expectedNeighborsAfterFailure);
      await("surviving topology withdrawal", () -> nodes.values().stream()
          .allMatch(node -> node.hasExpectedTopology(expectedNeighborsAfterFailure)));
      OverlayRoute alternateRoute = route(origin, destination, membership, Instant.now());
      assertEquals(OverlayRoute.Kind.PEER_TRANSIT, alternateRoute.kind());
      assertFalse(alternateRoute.path().contains(killedTransit.nodeId()));
      origin.forward(OverlayForwardingFrame.create(projectId, destination.nodeId(),
              UUID.randomUUID(), alternateRoute.path().size(),
              sender.encrypt(UUID.randomUUID(), "alternate transit payload".getBytes(
                  StandardCharsets.UTF_8)).encoded()), Instant.now()).toCompletableFuture().join();
      await("alternate transit delivery", () -> destination.deliveryCount() >= 2);
      await("bounded reconnect retry", () -> nodes.values().stream()
          .mapToInt(node -> node.reconnectFailureCount(killedTransit.nodeId())).sum() >= 1);
      assertEquals(1, nodes.values().stream()
          .mapToInt(node -> node.reconnectFailureCount(killedTransit.nodeId())).sum(),
          "only the deterministic edge owner retries the missing edge");
      assertThrows(java.util.concurrent.CompletionException.class,
          () -> origin.forward(dataFrame, Instant.now()).toCompletableFuture().join(),
          "duplicate overlay frame must remain suppressed");

      int killedPort = killedTransit.port();
      OverlayNode replacement = new OverlayNode(killedTransit.identity(), membership);
      replacement.startServer(killedPort);
      replacement.configure(nodesWith(nodes, replacement), portsWith(nodes, replacement), policy);
      replacement.startReconciliation();
      nodes.put(replacement.nodeId(), replacement);
      await("six-member rejoin convergence", () -> observedEdges(nodes).equals(expectedEdges));
      assertEquals(expectedEdges, observedEdges(nodes));
      assertTrue(degrees(nodes).values().stream().allMatch(value -> value <= 4));
      await("rejoin authentication diagnostics", () -> nodes.values().stream()
          .anyMatch(node -> node.hasEvent(OverlayReconciliationEvent.Type.RECONNECT_AUTHENTICATED,
              replacement.nodeId())));
      awaitTopology(nodes, expectedNeighbors);
      assertTrue(nodes.values().stream()
          .allMatch(node -> node.hasExpectedTopology(expectedNeighbors)),
          nodes.values().stream().map(OverlayNode::topologySummary).toList().toString());
      Set<Edge> stableGraph = observedEdges(nodes);
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(300));
      assertEquals(stableGraph, observedEdges(nodes));
      assertEquals(expectedNeighbors.get(replacement.nodeId()).size(),
          replacement.liveNeighbors().size(), "rejoined node has no duplicate/missing bindings");
    } finally {
      nodes.values().forEach(OverlayNode::closeQuietly);
    }
  }

  private static OverlayMembershipSnapshot.Member member(NodeIdentity identity) {
    return new OverlayMembershipSnapshot.Member(identity.nodeId(),
        OverlayMembershipSnapshot.STATUS_ACTIVE, identity.publicKeyEncoded());
  }

  private static Map<String, OverlayNode> nodesWith(Map<String, OverlayNode> nodes,
      OverlayNode replacement) {
    Map<String, OverlayNode> result = new HashMap<>(nodes);
    result.put(replacement.nodeId(), replacement);
    return result;
  }

  private static Map<String, Integer> portsWith(Map<String, OverlayNode> nodes,
      OverlayNode replacement) {
    Map<String, Integer> result = new HashMap<>();
    nodes.values().forEach(node -> result.put(node.nodeId(), node.port()));
    result.put(replacement.nodeId(), replacement.port());
    return result;
  }

  private static OverlayRoute route(OverlayNode origin, OverlayNode destination,
      OverlayMembershipSnapshot membership, Instant now) throws Exception {
    return OverlayRouteSelector.select(origin.nodeId(), destination.nodeId(), membership,
        origin.liveNeighbors(), origin.topology, now, null);
  }

  private static OverlayKeyAgreement.E2eSession establishE2e(OverlayNode origin,
      OverlayNode destination, OverlayMembershipSnapshot membership, Instant now) {
    OverlayKeyAgreement.Initiator initiator;
    try {
      initiator = OverlayKeyAgreement.start(membership.projectId(), destination.nodeId(),
          UUID.randomUUID(), membership.revision(), origin.identity);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
    origin.rememberInitiator(initiator);
    OverlayRoute route;
    try {
      route = route(origin, destination, membership, now);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
    OverlayForwardingFrame frame = OverlayForwardingFrame.create(membership.projectId(),
        destination.nodeId(), initiator.init().sessionId(), route.path().size(),
        initiator.init().encoded());
    origin.forward(frame, now).toCompletableFuture().join();
    return origin.outboundSession(initiator.init().sessionId()).join();
  }

  private static Set<Edge> edges(Map<String, List<String>> adjacency) {
    Set<Edge> result = new HashSet<>();
    adjacency.forEach((origin, neighbors) -> neighbors.forEach(neighbor ->
        result.add(new Edge(origin, neighbor))));
    return Set.copyOf(result);
  }

  private static boolean isFullMesh(Map<String, List<String>> adjacency) {
    return adjacency.entrySet().stream()
        .allMatch(entry -> entry.getValue().size() == adjacency.size() - 1);
  }

  private static Set<Edge> observedEdges(Map<String, OverlayNode> nodes) {
    Set<Edge> result = new HashSet<>();
    nodes.values().forEach(node -> node.liveNeighbors().forEach(neighbor -> {
      if (nodes.containsKey(neighbor)) {
        result.add(new Edge(node.nodeId(), neighbor));
      }
    }));
    return Set.copyOf(result);
  }

  private static Map<String, Integer> degrees(Map<String, OverlayNode> nodes) {
    Map<String, Integer> result = new HashMap<>();
    nodes.values().forEach(node -> result.put(node.nodeId(), node.liveNeighbors().size()));
    return Map.copyOf(result);
  }

  private static void await(String description, BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
    }
    assertTrue(condition.getAsBoolean(), description);
  }

  private static void awaitGraph(String description, Map<String, OverlayNode> nodes,
      Set<Edge> expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (!observedEdges(nodes).equals(expected) && System.nanoTime() < deadline) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
    }
    assertTrue(observedEdges(nodes).equals(expected), description + " observed="
        + observedEdges(nodes) + " degrees=" + degrees(nodes) + " states="
        + nodes.values().stream().map(OverlayNode::summary).toList());
  }

  private static void awaitTopology(Map<String, OverlayNode> nodes,
      Map<String, List<String>> expected) {
    List<OverlayNode> orderedNodes = new ArrayList<>(nodes.values());
    for (OverlayNode origin : orderedNodes) {
      try {
        origin.publishCurrentTopology().toCompletableFuture().join();
      } catch (java.util.concurrent.CompletionException ignored) {
        // The bounded propagation remains observable through the final assertion diagnostics.
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      while (!nodes.values().stream().allMatch(node -> node.hasExpectedAdvertisement(origin.nodeId(),
          expected.get(origin.nodeId()))) && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
      }
      assertTrue(nodes.values().stream().allMatch(node -> node.hasExpectedAdvertisement(
          origin.nodeId(), expected.get(origin.nodeId()))),
          "signed topology propagation origin=" + origin.nodeId() + " states=" + nodes.values()
              .stream().map(node -> node.advertisementSummary(origin.nodeId()) + "/"
                  + node.topologySummary()).toList());
    }
  }

  private record Edge(String left, String right) {
    private Edge {
      if (left.compareTo(right) > 0) {
        String swap = left;
        left = right;
        right = swap;
      }
    }
  }

  private static final class OverlayNode implements AutoCloseable {

    private final NodeIdentity identity;
    private final OverlayMembershipView membership;
    private final MultiThreadIoEventLoopGroup group;
    private final TestTlsMaterial certificate;
    private final OverlayPeerRegistry<PeerSession> sessions = new OverlayPeerRegistry<>();
    private final OverlayPeerRegistry<OverlayForwarder.PeerTransport> forwardingPeers =
        new OverlayPeerRegistry<>();
    private final OverlayPeerRegistry<OverlayTopologyPropagation.PeerTransport> topologyPeers =
        new OverlayPeerRegistry<>();
    private final OverlayTopologyView topology = new OverlayTopologyView();
    private final OverlayDuplicateGuard duplicateGuard = new OverlayDuplicateGuard(256);
    private final List<byte[]> observedInnerRecords = new CopyOnWriteArrayList<>();
    private final Map<UUID, OverlayKeyAgreement.Initiator> initiators = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<OverlayKeyAgreement.E2eSession>> outbound =
        new ConcurrentHashMap<>();
    private final Map<String, OverlayKeyAgreement.E2eSession> inbound = new ConcurrentHashMap<>();
    private final List<byte[]> deliveredPayloads = new CopyOnWriteArrayList<>();
    private final List<OverlayReconciliationEvent> reconciliationEvents =
        new CopyOnWriteArrayList<>();
    private final List<String> clientFailures = new CopyOnWriteArrayList<>();
    private final List<String> topologyFailures = new CopyOnWriteArrayList<>();
    private final AtomicInteger applicationCalls = new AtomicInteger();
    private final OverlayForwarder forwarder;
    private final OverlayTopologyPropagation topologyPropagation;
    private volatile OverlayReconciler reconciler;
    private volatile Channel serverUdp;
    private final Set<QuicChannel> acceptedChannels = ConcurrentHashMap.newKeySet();
    private volatile int port;
    private volatile long topologySequence;
    private volatile int topologyPublishAttempts;
    private volatile boolean closed;
    private ClientConnectionManager clients;
    private Map<String, OverlayNode> allNodes;
    private OverlayTopologyPolicy policy;

    private OverlayNode(NodeIdentity identity, OverlayMembershipSnapshot membership) throws Exception {
      this.identity = identity;
      this.membership = new OverlayMembershipView(membership);
      this.group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
      this.certificate = TestTlsMaterial.create();
      this.forwarder = new OverlayForwarder(identity.nodeId(), this.membership, topology,
          duplicateGuard, forwardingPeers, this::deliver);
      this.topologyPropagation = new OverlayTopologyPropagation(identity.nodeId(),
          this.membership, topology, topologyPeers);
    }

    private String nodeId() {
      return identity.nodeId();
    }

    private NodeIdentity identity() {
      return identity;
    }

    private int port() {
      return port;
    }

    private void startServer(int requestedPort) throws Exception {
      QuicSslContext serverSsl = QuicSslContextBuilder.forServer(certificate.key, null,
              certificate.certificate)
          .applicationProtocols(SynesisLink.ALPN)
          .build();
      serverUdp = new Bootstrap().group(group)
          .channel(NioDatagramChannel.class)
          .handler(NettyQuicTransport.serverCodec(serverSsl, InsecureQuicTokenHandler.INSTANCE,
              new ChannelInboundHandlerAdapter() {
                @Override
                public boolean isSharable() {
                  return true;
                }

                @Override
                public void channelActive(io.netty.channel.ChannelHandlerContext context) {
                  acceptedChannels.add((QuicChannel) context.channel());
                }
              }, NettySessionHandshake.serverStreamHandler(identity, null,
                  List.of(ProtocolVersion.V1), new ReplayGuard(), this::newInboundResult,
                  TEST_LIVENESS, applicationHandler())))
          .bind(new InetSocketAddress(NetUtil.LOCALHOST4, requestedPort))
          .sync().channel();
      port = ((InetSocketAddress) serverUdp.localAddress()).getPort();
    }

    private CompletableFuture<PeerSession> newInboundResult() {
      CompletableFuture<PeerSession> result = new CompletableFuture<>();
      result.whenComplete((session, failure) -> {
        if (failure == null && session != null) {
          acceptAuthenticated(session);
        }
      });
      return result;
    }

    private void configure(Map<String, OverlayNode> allNodes, Map<String, Integer> ports,
        OverlayTopologyPolicy policy) throws Exception {
      this.allNodes = allNodes;
      this.policy = policy;
      Map<String, CandidateDescriptor> descriptors = new HashMap<>();
      Instant issuedAt = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.SECONDS);
      Instant expiresAt = issuedAt.plusSeconds(600);
      for (OverlayNode peer : allNodes.values()) {
        if (!peer.nodeId().equals(nodeId())) {
          descriptors.put(peer.nodeId(), CandidateDescriptor.create(peer.identity, issuedAt,
              expiresAt, List.of(new Candidate(CandidateType.MANUAL, NetUtil.LOCALHOST4,
                  ports.get(peer.nodeId()), 0))));
        }
      }
      clients = new ClientConnectionManager(this);
      reconciler = new OverlayReconciler(nodeId(), membership, policy, sessions, descriptors,
          clients, RECONCILIATION_POLICY, Clock.systemUTC(), null,
          ignored -> syncPeerTransports(), reconciliationEvents::add);
    }

    private void startReconciliation() {
      reconciler.start();
    }

    private OverlayTopologyPolicy policy() {
      return policy;
    }

    private Set<String> liveNeighbors() {
      return reconciler.liveNeighbors();
    }

    private CompletionStage<Void> forward(OverlayForwardingFrame frame, Instant now) {
      return forwarder.forward(frame, now);
    }

    private void acceptAuthenticated(PeerSession session) {
      if (closed || !nodeId().equals(session.localNodeId())
          || !membership.current().allows(session.remoteNodeId())
          || !membership.current().member(session.remoteNodeId())
          .map(member -> session.hasRemotePublicKey(member.publicKeyEncoded())).orElse(false)) {
        session.closeGracefully(org.synesis.link.session.SessionCloseReason.LOCAL_REQUEST);
        return;
      }
      if (!sessions.bindIfAbsent(session.remoteNodeId(), session)) {
        session.closeGracefully(org.synesis.link.session.SessionCloseReason.LOCAL_REQUEST);
        return;
      }
      syncPeerTransports();
      session.terminalCompletion().whenComplete((ignored, failure) -> {
        if (sessions.unbindIfSame(session.remoteNodeId(), session)) {
          topology.removeOrigin(session.remoteNodeId());
          forwardingPeers.unbind(session.remoteNodeId());
          topologyPeers.unbind(session.remoteNodeId());
          syncPeerTransports();
        }
      });
      reconciler.reconcile();
    }

    private void syncPeerTransports() {
      for (var entry : sessions.snapshot().entrySet()) {
        forwardingPeers.bind(entry.getKey(), OverlayPeerSessionBridge.outbound(entry.getValue()));
        topologyPeers.bind(entry.getKey(), frame -> entry.getValue().requestApplication(
            frame.encoded()).thenApply(ignored -> null));
      }
      for (String peer : new HashSet<>(forwardingPeers.nodeIds())) {
        if (!sessions.contains(peer)) {
          forwardingPeers.unbind(peer);
          topologyPeers.unbind(peer);
        }
      }
    }

    private CompletionStage<Void> publishCurrentTopology() {
      topologyPublishAttempts++;
      if (closed || !membership.current().allows(nodeId())) {
        return CompletableFuture.completedFuture(null);
      }
      try {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OverlayTopologyAdvertisement advertisement = OverlayTopologyAdvertisement.create(
            membership.current().projectId(), membership.current().revision(),
            ++topologySequence, now.plusSeconds(600), new ArrayList<>(liveNeighbors()), identity);
        CompletionStage<Void> publication = topologyPropagation.publish(advertisement, now);
        publication.whenComplete((ignored, failure) -> {
          if (failure != null) {
            topologyFailures.add(failure.getClass().getSimpleName() + ":"
                + String.valueOf(failure.getMessage()));
          }
        });
        return publication;
      } catch (Exception failure) {
        topologyFailures.add(failure.getClass().getSimpleName() + ":"
            + String.valueOf(failure.getMessage()));
        // A topology publication is best effort; the next adjacency event retries it.
        return CompletableFuture.failedFuture(failure);
      }
    }

    private PeerSession.ApplicationStreamHandler applicationHandler() {
      return (remoteNodeId, payload) -> {
        try {
          applicationCalls.incrementAndGet();
          Instant now = Instant.now();
          if (OverlayTopologyPropagationFrame.hasMagic(payload)) {
            OverlayTopologyPropagationFrame frame = OverlayTopologyPropagationFrame.decode(payload);
            try {
              topologyPropagation.receive(remoteNodeId, frame, now).whenComplete((ignored, failure) -> {
                if (failure != null) {
                  topologyFailures.add(failure.getClass().getSimpleName() + ":"
                      + String.valueOf(failure.getMessage()));
                }
              });
            } catch (Exception failure) {
              topologyFailures.add(failure.getClass().getSimpleName() + ":"
                  + String.valueOf(failure.getMessage()));
            }
            return CompletableFuture.completedFuture(new byte[0]);
          }
          OverlayForwardingFrame frame = OverlayForwardingFrame.decode(payload);
          if (!nodeId().equals(frame.destinationNodeId())) {
            observedInnerRecords.add(frame.innerRecord());
          }
          return forwarder.forwardFrom(remoteNodeId, frame, now)
              .thenApply(ignored -> new byte[0]);
        } catch (Exception failure) {
          return CompletableFuture.failedFuture(failure);
        }
      };
    }

    private CompletionStage<Void> deliver(OverlayForwardingFrame frame) {
      try {
        byte[] inner = frame.innerRecord();
        if (magic(inner, 0x534C4B31)) {
          if (initiators.containsKey(frame.messageId())) {
            OverlayKeyAgreement.ResponseRecord response =
                OverlayKeyAgreement.ResponseRecord.decode(inner);
            OverlayKeyAgreement.Initiator initiator = initiators.get(frame.messageId());
            OverlayKeyAgreement.E2eSession session = OverlayKeyAgreement.finish(initiator,
                response, membership.current());
            outbound.get(frame.messageId()).complete(session);
            return CompletableFuture.completedFuture(null);
          }
          OverlayKeyAgreement.InitRecord init = OverlayKeyAgreement.InitRecord.decode(inner);
          OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(init, identity,
              membership.current());
          inbound.put(init.originNodeId(), response.session());
          OverlayRoute reverse = OverlayRouteSelector.select(nodeId(), init.originNodeId(),
              membership.current(), liveNeighbors(), topology, Instant.now(), null);
          OverlayForwardingFrame responseFrame = OverlayForwardingFrame.create(
              membership.current().projectId(), init.originNodeId(), response.response().sessionId(),
              reverse.path().size(), response.response().encoded());
          return forwarder.forward(responseFrame, Instant.now());
        }
        if (magic(inner, 0x534C4531)) {
          OverlayEnvelope envelope = OverlayEnvelope.decode(inner);
          OverlayKeyAgreement.E2eSession session = inbound.get(envelope.originNodeId());
          if (session == null) {
            throw new IllegalStateException("destination has no E2E session");
          }
          deliveredPayloads.add(session.decrypt(envelope));
        }
        return CompletableFuture.completedFuture(null);
      } catch (Exception failure) {
        return CompletableFuture.failedFuture(failure);
      }
    }

    private void rememberInitiator(OverlayKeyAgreement.Initiator initiator) {
      initiators.put(initiator.init().sessionId(), initiator);
      outbound.put(initiator.init().sessionId(), new CompletableFuture<>());
    }

    private CompletableFuture<OverlayKeyAgreement.E2eSession> outboundSession(UUID sessionId) {
      return outbound.get(sessionId);
    }

    private byte[] lastDeliveredPayload() {
      return deliveredPayloads.isEmpty() ? null : deliveredPayloads.getLast();
    }

    private int deliveryCount() {
      return deliveredPayloads.size();
    }

    private boolean sawOpaqueEnvelope(UUID messageId, byte[] plaintext) {
      return observedInnerRecords.stream().map(value -> {
        try {
          return OverlayEnvelope.decode(value);
        } catch (java.io.IOException ignored) {
          return null;
        }
      }).filter(java.util.Objects::nonNull)
          .anyMatch(envelope -> envelope.messageId().equals(messageId)
              && !contains(envelope.ciphertext(), plaintext));
    }

    private boolean hasE2eSessionFor(String origin, String destination) {
      return nodeId().equals(destination) && inbound.containsKey(origin)
          || nodeId().equals(origin) && outbound.values().stream().anyMatch(
              value -> value.isDone() && !value.isCompletedExceptionally());
    }

    private boolean hasExpectedTopology(Map<String, List<String>> expected) {
      return hasExpectedTopology(expected, null);
    }

    private boolean hasExpectedTopology(Map<String, List<String>> expected, String excluded) {
      return topology.adjacencyGraph(membership.current(), Instant.now()).entrySet().stream()
          .filter(entry -> expected.containsKey(entry.getKey())
              && !entry.getKey().equals(excluded))
          .allMatch(entry -> entry.getValue().equals(expected.get(entry.getKey()).stream()
                  .filter(peer -> !peer.equals(excluded)).toList()))
          && expected.keySet().stream().allMatch(key -> topology.adjacencyGraph(
              membership.current(), Instant.now()).containsKey(key));
    }

    private String topologySummary() {
      return nodeId() + "=" + topology.adjacencyGraph(membership.current(), Instant.now())
          + "/peers=" + topologyPeers.nodeIds() + "/publishes=" + topologyPublishAttempts
          + "/appCalls=" + applicationCalls
          + "/failures=" + topologyFailures;
    }

    private boolean hasExpectedAdvertisement(String originNodeId, List<String> neighbors) {
      return topology.advertisement(originNodeId)
          .map(advertisement -> advertisement.adjacentNodeIds().equals(neighbors))
          .orElse(false);
    }

    private String advertisementSummary(String originNodeId) {
      return nodeId() + "=" + topology.advertisement(originNodeId)
          .map(advertisement -> advertisement.adjacentNodeIds().toString()).orElse("missing");
    }

    private int reconnectFailureCount(String peerNodeId) {
      return (int) reconciliationEvents.stream()
          .filter(event -> event.type() == OverlayReconciliationEvent.Type.RECONNECT_FAILED)
          .filter(event -> peerNodeId.equals(event.peerNodeId()))
          .count();
    }

    private boolean hasEvent(OverlayReconciliationEvent.Type type, String peerNodeId) {
      return reconciliationEvents.stream().anyMatch(event -> event.type() == type
          && (peerNodeId == null || peerNodeId.equals(event.peerNodeId())));
    }

    private String summary() {
      return nodeId() + "@" + port + "/accepted=" + acceptedChannels.size() + "="
          + liveNeighbors() + "/" + reconciliationEvents.stream()
          .map(event -> event.type().name() + ":" + event.peerNodeId() + ":" + event.detail())
          .toList() + "/appCalls=" + applicationCalls + "/failures=" + clientFailures;
    }

    @Override
    public void close() throws java.io.IOException {
      if (closed) {
        return;
      }
      closed = true;
      if (reconciler != null) {
        reconciler.close();
      }
      if (clients != null) {
        clients.close();
      }
      acceptedChannels.forEach(channel -> channel.close(true, 0,
          io.netty.buffer.Unpooled.EMPTY_BUFFER).syncUninterruptibly());
      acceptedChannels.clear();
      if (serverUdp != null) {
        serverUdp.close().syncUninterruptibly();
        serverUdp = null;
      }
      certificate.close();
      group.shutdownGracefully().syncUninterruptibly();
    }

    private void closeQuietly() {
      try {
        close();
      } catch (Exception ignored) {
      }
    }

    private void crashAbruptly() {
      if (closed) {
        return;
      }
      closed = true;
      if (clients != null) {
        clients.close();
      }
      acceptedChannels.forEach(channel -> channel.close(true, 0,
          io.netty.buffer.Unpooled.EMPTY_BUFFER).syncUninterruptibly());
      acceptedChannels.clear();
      if (serverUdp != null) {
        serverUdp.close().syncUninterruptibly();
        serverUdp = null;
      }
      group.shutdownGracefully().syncUninterruptibly();
      try {
        certificate.close();
      } catch (java.io.IOException ignored) {
        // The transport is already dead; test cleanup must not revive it.
      }
    }

    private static boolean magic(byte[] bytes, int expected) {
      return bytes.length >= Integer.BYTES
          && java.nio.ByteBuffer.wrap(bytes).getInt() == expected;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
      outer: for (int index = 0; index <= haystack.length - needle.length; index++) {
        for (int offset = 0; offset < needle.length; offset++) {
          if (haystack[index + offset] != needle[offset]) {
            continue outer;
          }
        }
        return true;
      }
      return false;
    }
  }

  private static final class ClientConnectionManager
      implements OverlayReconciler.PeerConnector, AutoCloseable {

    private final OverlayNode owner;
    private final QuicSslContext clientSsl;
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private ClientConnectionManager(OverlayNode owner) {
      this.owner = owner;
      try {
        clientSsl = QuicSslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols(SynesisLink.ALPN)
            .build();
      } catch (Exception failure) {
        throw new IllegalStateException("unable to build QUIC client context", failure);
      }
    }

    @Override
    public CompletionStage<PeerSession> connect(CandidateDescriptor descriptor) {
      CompletableFuture<PeerSession> result = new CompletableFuture<>();
      CompletableFuture.runAsync(() -> connectBlocking(descriptor, result));
      return result;
    }

    private void connectBlocking(CandidateDescriptor descriptor,
        CompletableFuture<PeerSession> result) {
      ClientConnection connection = null;
      Channel clientUdp = null;
      boolean gateAcquired = false;
      try {
        NATIVE_CONNECT_GATE.acquire();
        gateAcquired = true;
        if (closed) {
          throw new IllegalStateException("connector is closed");
        }
        clientUdp = new Bootstrap().group(owner.group)
            .channel(NioDatagramChannel.class)
            .handler(NettyQuicTransport.clientCodec(clientSsl))
            .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0))
            .sync().channel();
        Candidate candidate = descriptor.candidates().stream()
            .filter(value -> value.type() != CandidateType.RELAY)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no direct candidate"));
        var connectFuture = QuicChannel.newBootstrap(clientUdp)
            .handler(new ChannelInboundHandlerAdapter())
            .streamHandler(NettySessionHandshake.clientApplicationStreamHandler())
            .remoteAddress(new InetSocketAddress(candidate.address(), candidate.port()))
            .connect();
        if (!connectFuture.await(5, TimeUnit.SECONDS) || !connectFuture.isSuccess()) {
          throw new java.util.concurrent.TimeoutException("QUIC connection did not complete: "
              + connectFuture.cause());
        }
        QuicChannel client = connectFuture.getNow();
        connection = new ClientConnection(clientUdp, client);
        clientUdp = null;
        HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1,
            SynesisLink.ALPN, UUID.randomUUID(), 1, 1,
            UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
            UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8), owner.nodeId(),
            owner.identity.publicKeyEncoded(), descriptor.nodeId(), descriptor.publicKeyEncoded());
        HandshakeProof proof = org.synesis.link.session.SessionAuthenticator.createProof(
            owner.identity, transcript, HandshakeRole.INITIATOR);
        CompletableFuture<PeerSession> established = new CompletableFuture<>();
        var streamFuture = client.createStream(QuicStreamType.BIDIRECTIONAL,
                NettySessionHandshake.clientStreamHandler(owner.identity, descriptor.nodeId(),
                    transcript, proof, new ReplayGuard(), established, TEST_LIVENESS,
                    owner.applicationHandler()));
        if (!streamFuture.await(5, TimeUnit.SECONDS) || !streamFuture.isSuccess()) {
          throw new java.util.concurrent.TimeoutException("QUIC stream did not open: "
              + streamFuture.cause());
        }
        PeerSession session = established.get(10, TimeUnit.SECONDS);
        if (closed) {
          throw new IllegalStateException("connector closed during connection");
        }
        ClientConnection retained = connection;
        connections.add(retained);
        session.terminalCompletion().whenComplete((ignored, failure) -> {
          connections.remove(retained);
          retained.close();
        });
        result.complete(session);
      } catch (Exception failure) {
        owner.clientFailures.add(descriptor.nodeId() + ":"
            + failure.getClass().getSimpleName() + ":" + String.valueOf(failure.getMessage()));
        if (connection != null) {
          connection.close();
        } else if (clientUdp != null) {
          clientUdp.close().syncUninterruptibly();
        }
        result.completeExceptionally(failure);
      } finally {
        if (gateAcquired) {
          NATIVE_CONNECT_GATE.release();
        }
      }
    }

    @Override
    public void close() {
      closed = true;
      connections.forEach(ClientConnection::close);
      connections.clear();
    }
  }

  private record ClientConnection(Channel clientUdp, QuicChannel client) {

    private void close() {
      if (client.isOpen()) {
        client.close(true, 0, io.netty.buffer.Unpooled.EMPTY_BUFFER).syncUninterruptibly();
      }
      if (clientUdp.isOpen()) {
        clientUdp.close().syncUninterruptibly();
      }
    }
  }
}
