package org.synesis.link.transport.quic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.overlay.OverlayMembershipSnapshot;
import org.synesis.link.overlay.OverlayMembershipView;
import org.synesis.link.overlay.OverlayPeerRegistry;
import org.synesis.link.overlay.OverlayReconciliationEvent;
import org.synesis.link.overlay.OverlayReconciliationPolicy;
import org.synesis.link.overlay.OverlayReconciler;
import org.synesis.link.overlay.OverlayRoute;
import org.synesis.link.overlay.OverlayRouteSelector;
import org.synesis.link.overlay.OverlayTopologyPolicy;
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.protocol.HandshakeTranscript;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;

/**
 * Exercises automatic overlay healing over actual authenticated native QUIC sessions.
 */
final class OverlayReconcilerQuicTest {

  private static final LivenessConfiguration TEST_LIVENESS = new LivenessConfiguration(
      Duration.ofMillis(50), Duration.ofMillis(150), Duration.ofMillis(300), true);

  @Test
  void removesPhysicalLossAndRejoinsWithFreshAuthenticatedSession() throws Exception {
    MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(4,
        NioIoHandler.newFactory());
    NodeIdentity local = NodeIdentity.generate();
    NodeIdentity second = NodeIdentity.generate();
    NodeIdentity third = NodeIdentity.generate();
    while (local.nodeId().compareTo(second.nodeId()) > 0
        || local.nodeId().compareTo(third.nodeId()) > 0) {
      local = NodeIdentity.generate();
      second = NodeIdentity.generate();
      third = NodeIdentity.generate();
    }
    final NodeIdentity peerB = second;
    final NodeIdentity peerC = third;
    Instant issuedAt = Instant.now().minusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plusSeconds(600);
    OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.create(UUID.randomUUID(), 1,
        issuedAt, expiresAt, List.of(member(local), member(peerB), member(peerC)), local);
    OverlayMembershipView membershipView = new OverlayMembershipView(membership);
    OverlayPeerRegistry<PeerSession> peers = new OverlayPeerRegistry<>();
    List<OverlayReconciliationEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    List<Set<String>> publishedAdjacencies = new java.util.concurrent.CopyOnWriteArrayList<>();
    Endpoint secondEndpoint = new Endpoint(peerB, local, group);
    Endpoint thirdEndpoint = new Endpoint(peerC, local, group);
    ClientConnector connector = new ClientConnector(local, group);
    OverlayReconciler reconciler = null;
    try {
      secondEndpoint.start(0);
      thirdEndpoint.start(0);
      Map<String, CandidateDescriptor> descriptors = Map.of(
          peerB.nodeId(), descriptor(peerB, secondEndpoint.port(), issuedAt, expiresAt),
          peerC.nodeId(), descriptor(peerC, thirdEndpoint.port(), issuedAt, expiresAt));
      reconciler = new OverlayReconciler(local.nodeId(), membershipView,
          new OverlayTopologyPolicy(), peers, descriptors, connector,
          new OverlayReconciliationPolicy(Duration.ofMillis(50), Duration.ofMillis(250), 2),
          java.time.Clock.systemUTC(), null, publishedAdjacencies::add, events::add);
      reconciler.start();
      OverlayReconciler activeReconciler = reconciler;
      await("initial full-mesh adjacency", () -> activeReconciler.liveNeighbors()
          .containsAll(Set.of(peerB.nodeId(), peerC.nodeId())));
      PeerSession firstSecondSession = secondEndpoint.session().get(5, TimeUnit.SECONDS);
      PeerSession firstThirdSession = thirdEndpoint.session().get(5, TimeUnit.SECONDS);
      assertEquals(Set.of(peerB.nodeId(), peerC.nodeId()), activeReconciler.liveNeighbors());

      secondEndpoint.stopAbruptly();
      await("lost adjacency withdrawal", () -> !activeReconciler.liveNeighbors()
          .contains(peerB.nodeId()));
      assertTrue(firstSecondSession.terminalCompletion().toCompletableFuture().isDone());
      assertTrue(activeReconciler.liveNeighbors().contains(peerC.nodeId()));
      assertEquals(OverlayRoute.Kind.UNREACHABLE,
          OverlayRouteSelector.select(local.nodeId(), peerB.nodeId(), membership,
              peers.nodeIds(), new OverlayTopologyView(), Instant.now(), null).kind());
      assertTrue(events.stream().anyMatch(event -> event.type()
          == OverlayReconciliationEvent.Type.ROUTE_WITHDRAWN));
      assertTrue(publishedAdjacencies.stream().anyMatch(value -> !value.contains(peerB.nodeId())));

      secondEndpoint.start(secondEndpoint.port());
      await("automatic authenticated rejoin", () -> activeReconciler.liveNeighbors()
          .contains(peerB.nodeId()));
      PeerSession secondSession = secondEndpoint.session().get(5, TimeUnit.SECONDS);
      assertNotEquals(firstSecondSession.sessionId(), secondSession.sessionId());
      assertEquals(local.nodeId(), secondSession.remoteNodeId());
      assertTrue(secondSession.isUsable());
      assertTrue(activeReconciler.liveNeighbors().contains(peerC.nodeId()));
      assertArrayEquals(membership.encoded(), membershipView.current().encoded());
      assertTrue(events.stream().anyMatch(event -> event.type()
          == OverlayReconciliationEvent.Type.RECONNECT_AUTHENTICATED));
      assertTrue(firstThirdSession.isUsable());
    } finally {
      if (reconciler != null) {
        reconciler.close();
      }
      connector.close();
      secondEndpoint.close();
      thirdEndpoint.close();
      group.shutdownGracefully().syncUninterruptibly();
    }
  }

  private static OverlayMembershipSnapshot.Member member(NodeIdentity identity) {
    return new OverlayMembershipSnapshot.Member(identity.nodeId(),
        OverlayMembershipSnapshot.STATUS_ACTIVE, identity.publicKeyEncoded());
  }

  private static CandidateDescriptor descriptor(NodeIdentity identity, int port, Instant issuedAt,
      Instant expiresAt) throws Exception {
    return CandidateDescriptor.create(identity, issuedAt, expiresAt,
        List.of(new Candidate(CandidateType.MANUAL, NetUtil.LOCALHOST4, port, 0)));
  }

  private static void await(String description, BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
    }
    assertTrue(condition.getAsBoolean(), description);
  }

  private static final class Endpoint implements AutoCloseable {

    private final NodeIdentity identity;
    private final String expectedRemoteNodeId;
    private final MultiThreadIoEventLoopGroup group;
    private final TestTlsMaterial certificate;
    private Channel serverUdp;
    private volatile QuicChannel acceptedChannel;
    private volatile CompletableFuture<PeerSession> acceptedSession;
    private volatile int port;

    private Endpoint(NodeIdentity identity, NodeIdentity expectedRemote, MultiThreadIoEventLoopGroup group)
        throws Exception {
      this.identity = identity;
      this.expectedRemoteNodeId = expectedRemote.nodeId();
      this.group = group;
      this.certificate = TestTlsMaterial.create();
    }

    private synchronized void start(int requestedPort) throws Exception {
      if (serverUdp != null) {
        throw new IllegalStateException("endpoint is already started");
      }
      acceptedSession = new CompletableFuture<>();
      QuicSslContext serverSsl = QuicSslContextBuilder.forServer(certificate.key, null,
              certificate.certificate)
          .applicationProtocols(SynesisLink.ALPN)
          .build();
      serverUdp = new Bootstrap().group(group)
          .channel(NioDatagramChannel.class)
          .handler(NettyQuicTransport.serverCodec(serverSsl,
              InsecureQuicTokenHandler.INSTANCE, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(io.netty.channel.ChannelHandlerContext context) {
                  acceptedChannel = (QuicChannel) context.channel();
                }
              }, NettySessionHandshake.serverStreamHandler(identity, expectedRemoteNodeId,
                  List.of(ProtocolVersion.V1), new ReplayGuard(), acceptedSession, TEST_LIVENESS,
                  (remoteNodeId, payload) -> CompletableFuture.completedFuture(payload))))
          .bind(new InetSocketAddress(NetUtil.LOCALHOST4, requestedPort))
          .sync().channel();
      port = ((InetSocketAddress) serverUdp.localAddress()).getPort();
    }

    private CompletableFuture<PeerSession> session() {
      return acceptedSession;
    }

    private int port() {
      return port;
    }

    private synchronized void stopAbruptly() {
      QuicChannel channel = acceptedChannel;
      acceptedChannel = null;
      if (channel != null) {
        channel.close(true, 0, io.netty.buffer.Unpooled.EMPTY_BUFFER).syncUninterruptibly();
      }
      if (serverUdp != null) {
        serverUdp.close().syncUninterruptibly();
        serverUdp = null;
      }
    }

    @Override
    public void close() throws java.io.IOException {
      stopAbruptly();
      certificate.close();
    }
  }

  private static final class ClientConnector implements OverlayReconciler.PeerConnector,
      AutoCloseable {

    private final NodeIdentity localIdentity;
    private final MultiThreadIoEventLoopGroup group;
    private final QuicSslContext clientSsl;
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();

    private ClientConnector(NodeIdentity localIdentity, MultiThreadIoEventLoopGroup group) {
      this.localIdentity = localIdentity;
      this.group = group;
      try {
        clientSsl = QuicSslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols(SynesisLink.ALPN)
            .build();
      } catch (Exception failure) {
        throw new IllegalStateException("unable to build test QUIC client context", failure);
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
      try {
        Candidate candidate = descriptor.candidates().stream()
            .filter(value -> value.type() != CandidateType.RELAY)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("descriptor has no direct candidate"));
        Channel clientUdp = new Bootstrap().group(group)
            .channel(NioDatagramChannel.class)
            .handler(NettyQuicTransport.clientCodec(clientSsl))
            .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0))
            .sync().channel();
        QuicChannel client = QuicChannel.newBootstrap(clientUdp)
            .handler(new ChannelInboundHandlerAdapter())
            .streamHandler(new ChannelInboundHandlerAdapter())
            .remoteAddress(new InetSocketAddress(candidate.address(), candidate.port()))
            .connect().sync().getNow();
        connection = new ClientConnection(clientUdp, client);
        HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1,
            SynesisLink.ALPN, UUID.randomUUID(), 1, 1, UUID.randomUUID().toString().getBytes(),
            UUID.randomUUID().toString().getBytes(), localIdentity.nodeId(),
            localIdentity.publicKeyEncoded(), descriptor.nodeId(), descriptor.publicKeyEncoded());
        HandshakeProof proof = org.synesis.link.session.SessionAuthenticator.createProof(
            localIdentity, transcript, HandshakeRole.INITIATOR);
        CompletableFuture<PeerSession> established = new CompletableFuture<>();
        client.createStream(QuicStreamType.BIDIRECTIONAL,
                NettySessionHandshake.clientStreamHandler(localIdentity, descriptor.nodeId(),
                    transcript, proof, new ReplayGuard(), established, TEST_LIVENESS,
                    (remoteNodeId, payload) -> CompletableFuture.completedFuture(payload)))
            .sync();
        PeerSession session = established.get(10, TimeUnit.SECONDS);
        ClientConnection retained = connection;
        connections.add(retained);
        session.terminalCompletion().whenComplete((ignored, failure) -> {
          connections.remove(retained);
          retained.close();
        });
        result.complete(session);
      } catch (Exception failure) {
        if (connection != null) {
          connection.close();
        }
        result.completeExceptionally(failure);
      }
    }

    @Override
    public void close() {
      connections.forEach(ClientConnection::close);
      connections.clear();
    }
  }

  private static final class ClientConnection {

    private final Channel clientUdp;
    private final QuicChannel client;

    private ClientConnection(Channel clientUdp, QuicChannel client) {
      this.clientUdp = clientUdp;
      this.client = client;
    }

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
