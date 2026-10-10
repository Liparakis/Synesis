package org.synesis.link.lab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
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
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidateGatherer;
import org.synesis.link.candidate.CandidateGatheringPolicy;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.candidate.CandidatePairs;
import org.synesis.link.session.CandidateRacer;
import org.synesis.link.session.ConnectionAttempt;
import org.synesis.link.candidate.ConnectionPolicy;
import org.synesis.link.session.DirectConnectionResult;
import org.synesis.link.candidate.LocalInterfaceCandidateProvider;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.onboarding.OnboardingEvent;
import org.synesis.link.onboarding.OnboardingEventType;
import org.synesis.link.onboarding.OnboardingFailure;
import org.synesis.link.overlay.OverlayDuplicateGuard;
import org.synesis.link.overlay.OverlayEnvelope;
import org.synesis.link.overlay.OverlayForwarder;
import org.synesis.link.overlay.OverlayForwardingFrame;
import org.synesis.link.overlay.OverlayKeyAgreement;
import org.synesis.link.overlay.OverlayMembershipPropagation;
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
import org.synesis.link.overlay.OverlayTopologyView;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.protocol.HandshakeTranscript;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;
import org.synesis.link.session.SessionCloseReason;
import org.synesis.link.transport.quic.EphemeralTlsMaterial;
import org.synesis.link.transport.quic.NettyQuicTransport;
import org.synesis.link.transport.quic.NettySessionHandshake;

/**
 * Test-only process adapter for the Docker-based SL-D-040 Checkpoint 3A lab.
 *
 * <p>This class is deliberately not part of the shipped CLI. It exposes only a bounded local
 * HTTP control surface around the existing Link identity, SLB2 onboarding, authenticated QUIC
 * sessions, signed overlay membership, topology propagation, and {@link OverlayReconciler}.
 * The Docker harness is responsible for lifecycle, membership distribution, descriptor refresh,
 * failure injection, and evidence collection.
 */
public final class ProcessLabNode {

  private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(30);
  private static final LivenessConfiguration LAB_LIVENESS = new LivenessConfiguration(
      Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofSeconds(3), true);
  private static final OverlayReconciliationPolicy LAB_RECONCILIATION =
      new OverlayReconciliationPolicy(Duration.ofMillis(250), Duration.ofSeconds(4), 1);
  private static final Semaphore NATIVE_CONNECT_GATE = new Semaphore(1);
  private static final int MAX_EVENT_RECORDS = 512;
  private static final int MEMBERSHIP_MAGIC = 0x534C4D31;
  private static final int KEY_INIT_MAGIC = 0x534C4B31;
  private static final int ENVELOPE_MAGIC = 0x534C4531;

  private final Options options;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(2,
      NioIoHandler.newFactory());
  private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(
      runnable -> {
        Thread thread = new Thread(runnable, "synesis-lab-maintenance");
        thread.setDaemon(true);
        return thread;
      });
  private final ExecutorService controlExecutor = Executors.newCachedThreadPool(runnable -> {
    Thread thread = new Thread(runnable, "synesis-lab-http");
    thread.setDaemon(true);
    return thread;
  });
  private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
  private final OverlayPeerRegistry<PeerSession> sessions = new OverlayPeerRegistry<>();
  private final OverlayPeerRegistry<OverlayForwarder.PeerTransport> forwardingPeers =
      new OverlayPeerRegistry<>();
  private final OverlayPeerRegistry<OverlayMembershipPropagation.PeerTransport> membershipPeers =
      new OverlayPeerRegistry<>();
  private final OverlayPeerRegistry<OverlayTopologyPropagation.PeerTransport> topologyPeers =
      new OverlayPeerRegistry<>();
  private final Map<String, CandidateDescriptor> descriptors = new ConcurrentHashMap<>();
  private final Map<UUID, OverlayKeyAgreement.Initiator> initiators = new ConcurrentHashMap<>();
  private final Map<UUID, CompletableFuture<OverlayKeyAgreement.E2eSession>> outbound =
      new ConcurrentHashMap<>();
  private final Map<String, OverlayKeyAgreement.E2eSession> inbound = new ConcurrentHashMap<>();
  private final CopyOnWriteArrayList<byte[]> deliveredPayloads = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<byte[]> observedInnerRecords = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<String> reconciliationEvents = new CopyOnWriteArrayList<>();
  private final Map<String, Onboarding.PreparedHost> hosts = new ConcurrentHashMap<>();
  private final Map<String, Onboarding.PreparedJoin> joins = new ConcurrentHashMap<>();
  private final Set<ClientConnection> clientConnections = ConcurrentHashMap.newKeySet();
  private final Set<QuicChannel> acceptedChannels = ConcurrentHashMap.newKeySet();
  private final Object membershipLock = new Object();

  private volatile NodeIdentity identity;
  private volatile Onboarding onboarding;
  private volatile EphemeralTlsMaterial tls;
  private volatile QuicSslContext clientSsl;
  private volatile Channel serverUdp;
  private volatile HttpServer controlServer;
  private volatile int directPort;
  private volatile CandidateDescriptor localDescriptor;
  private volatile OverlayMembershipSnapshot membershipSnapshot;
  private volatile OverlayMembershipView membership;
  private volatile OverlayTopologyView topology;
  private volatile OverlayTopologyPropagation topologyPropagation;
  private volatile OverlayMembershipPropagation membershipPropagation;
  private volatile OverlayForwarder forwarder;
  private volatile PeerSession.ApplicationStreamHandler overlayHandler;
  private volatile OverlayReconciler reconciler;
  private volatile boolean reconciliationStarted;
  private volatile long topologySequence;

  private ProcessLabNode(Options options) {
    this.options = options;
  }

  /**
   * Starts one lab node and serves its control surface until shutdown.
   *
   * @param arguments command-line options
   * @throws Exception if identity, transport, or control setup fails
   */
  public static void main(String[] arguments) throws Exception {
    Options options = Options.parse(arguments);
    if (options.help()) {
      System.out.println(Options.usage());
      return;
    }
    ProcessLabNode node = new ProcessLabNode(options);
    Runtime.getRuntime().addShutdownHook(new Thread(node::close, "synesis-lab-shutdown"));
    node.start();
    node.stopped.await();
  }

  private void start() throws Exception {
    Files.createDirectories(options.state());
    IdentityBootstrap.Result result = new IdentityBootstrap(options.state().resolve("profile")
        .resolve("link")).loadOrCreate();
    identity = result.identity();
    onboarding = new Onboarding(options.state().resolve("profile").resolve("link"),
        this::recordOnboardingEvent);
    tls = EphemeralTlsMaterial.create();
    clientSsl = QuicSslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE)
        .applicationProtocols(SynesisLink.ALPN)
        .build();
    startDirectListener();
    startControlServer();
    maintenance.scheduleAtFixedRate(this::maintenanceTick, 500, 500, TimeUnit.MILLISECONDS);
    System.out.println("NODE_ID=" + identity.nodeId());
    System.out.println("CONTROL_PORT=" + options.controlPort());
    System.out.println("DIRECT_PORT=" + directPort);
    System.out.println("DESCRIPTOR_B64=" + descriptorBase64());
    System.out.flush();
  }

  private void startDirectListener() throws Exception {
    QuicSslContext serverSsl = QuicSslContextBuilder.forServer(tls.key, null, tls.certificate)
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

              @Override
              public void channelInactive(io.netty.channel.ChannelHandlerContext context) {
                acceptedChannels.remove(context.channel());
              }
            },
            NettySessionHandshake.serverStreamHandler(identity, null, List.of(ProtocolVersion.V1),
                new ReplayGuard(), this::newInboundResult, LAB_LIVENESS,
                this::handleApplication)))
        .bind(new InetSocketAddress(localIpv4Address(), options.directPort()))
        .sync()
        .channel();
    directPort = ((InetSocketAddress) serverUdp.localAddress()).getPort();
    localDescriptor = createLocalDescriptor();
  }

  private void startControlServer() throws IOException {
    controlServer = HttpServer.create(new InetSocketAddress("0.0.0.0", options.controlPort()), 16);
    controlServer.createContext("/", new ControlHandler());
    controlServer.setExecutor(controlExecutor);
    controlServer.start();
  }

  private CandidateDescriptor createLocalDescriptor() throws Exception {
    Instant issued = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.SECONDS);
    return CandidateDescriptor.create(identity, issued, issued.plusSeconds(3_600),
        gatherCandidates("lab-direct-" + identity.nodeId(), directPort));
  }

  private static List<Candidate> gatherCandidates(String providerId, int port) throws Exception {
    try (CandidateGatherer gatherer = new CandidateGatherer(CandidateGatheringPolicy.defaults())) {
      return gatherer.gather(List.of(new LocalInterfaceCandidateProvider(providerId, port, 0)))
          .completion()
          .toCompletableFuture()
          .get(10, TimeUnit.SECONDS)
          .candidates();
    }
  }

  private static Inet4Address localIpv4Address() throws IOException {
    var interfaces = NetworkInterface.getNetworkInterfaces();
    while (interfaces.hasMoreElements()) {
      var network = interfaces.nextElement();
      if (!network.isUp()) {
        continue;
      }
      for (var address : java.util.Collections.list(network.getInetAddresses())) {
        if (address instanceof Inet4Address ipv4 && !ipv4.isLoopbackAddress()
            && !ipv4.isMulticastAddress()) {
          return ipv4;
        }
      }
    }
    throw new IOException("no non-loopback IPv4 interface is available");
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

  private void installMembership(byte[] encoded) throws Exception {
    Objects.requireNonNull(encoded, "membership bytes");
    OverlayMembershipSnapshot snapshot = OverlayMembershipSnapshot.decode(encoded);
    if (!snapshot.verify() || !snapshot.isUsableAt(Instant.now())) {
      throw new IllegalArgumentException("membership is not currently signed and usable");
    }
    if (!snapshot.allows(identity.nodeId())) {
      throw new IllegalArgumentException("local identity is not an active member");
    }
    synchronized (membershipLock) {
      if (membershipSnapshot != null
          && !java.util.Arrays.equals(membershipSnapshot.encoded(), snapshot.encoded())) {
        throw new IllegalStateException("membership replacement is not supported by this lab node");
      }
      if (membershipSnapshot != null) {
        return;
      }
      membershipSnapshot = snapshot;
      membership = new OverlayMembershipView(snapshot);
      topology = new OverlayTopologyView();
      OverlayDuplicateGuard duplicateGuard = new OverlayDuplicateGuard(512);
      forwardingPeers.nodeIds().forEach(forwardingPeers::unbind);
      membershipPeers.nodeIds().forEach(membershipPeers::unbind);
      topologyPeers.nodeIds().forEach(topologyPeers::unbind);
      membershipPropagation = new OverlayMembershipPropagation(identity.nodeId(), membership,
          membershipPeers);
      topologyPropagation = new OverlayTopologyPropagation(identity.nodeId(), membership, topology,
          topologyPeers);
      forwarder = new OverlayForwarder(identity.nodeId(), membership, topology, duplicateGuard,
          forwardingPeers, this::deliver);
      overlayHandler = OverlayPeerSessionBridge.inbound(forwarder, topologyPropagation,
          membershipPropagation, Clock.systemUTC());
      record("MEMBERSHIP_ACCEPTED revision=" + snapshot.revision()
          + " digest=" + digest(snapshot.encoded()));
      ensureReconciler();
    }
  }

  private void installDescriptors(String body) throws Exception {
    requireMembership();
    for (String line : body.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      String[] fields = line.trim().split("\\|", 2);
      if (fields.length != 2) {
        throw new IllegalArgumentException("descriptor line must be nodeId|base64");
      }
      CandidateDescriptor descriptor = CandidateDescriptor.decode(
          Base64.getDecoder().decode(fields[1]));
      if (!fields[0].equals(descriptor.nodeId()) || !descriptor.verify()
          || !descriptor.isValidAt(Instant.now(), CandidateDescriptor.DEFAULT_CLOCK_SKEW)) {
        throw new IllegalArgumentException("descriptor signature or identity is invalid");
      }
      OverlayMembershipSnapshot.Member member = membershipSnapshot.member(descriptor.nodeId())
          .orElseThrow(() -> new IllegalArgumentException("descriptor is outside membership"));
      if (!java.util.Arrays.equals(member.publicKeyEncoded(), descriptor.publicKeyEncoded())) {
        throw new IllegalArgumentException("descriptor key does not match membership");
      }
      descriptors.put(descriptor.nodeId(), descriptor);
      OverlayReconciler current = reconciler;
      if (current != null) {
        current.updateDescriptor(descriptor);
      }
    }
    ensureReconciler();
    record("DESCRIPTORS_ACCEPTED count=" + descriptors.size());
  }

  private void ensureReconciler() {
    if (reconciler != null || membership == null) {
      return;
    }
    reconciler = new OverlayReconciler(identity.nodeId(), membership, new OverlayTopologyPolicy(),
        sessions, descriptors, new DirectConnector(), LAB_RECONCILIATION, Clock.systemUTC(), null,
        ignored -> {
          syncPeerTransports();
          publishTopology();
        }, event -> {
          String value = event.peerNodeId() == null ? "" : " peer=" + event.peerNodeId();
          reconciliationEvents.add(event.type().name() + value + " " + event.detail());
          trim(reconciliationEvents);
        });
  }

  private void startReconciliation() {
    requireMembership();
    ensureReconciler();
    reconciliationStarted = true;
    reconciler.start();
    record("RECONCILIATION_STARTED");
  }

  private void maintenanceTick() {
    if (closed.get()) {
      return;
    }
    OverlayReconciler current = reconciler;
    if (current != null && reconciliationStarted) {
      try {
        current.reconcile();
      } catch (RuntimeException failure) {
        record("RECONCILE_ERROR " + failure.getClass().getSimpleName());
      }
    }
  }

  private void acceptAuthenticated(PeerSession session) {
    try {
      OverlayMembershipSnapshot snapshot = membershipSnapshot;
      if (closed.get() || snapshot == null || !session.isUsable()
          || !identity.nodeId().equals(session.localNodeId())
          || !snapshot.allows(session.remoteNodeId())
          || !snapshot.member(session.remoteNodeId())
              .map(member -> session.hasRemotePublicKey(member.publicKeyEncoded()))
              .orElse(false)) {
        closeSession(session);
        return;
      }
      if (!sessions.bindIfAbsent(session.remoteNodeId(), session)) {
        closeSession(session);
        return;
      }
      syncPeerTransports();
      try {
        session.addLivenessListener(ignored -> {
          OverlayReconciler current = reconciler;
          if (current != null) {
            current.reconcile();
          }
        });
      } catch (RuntimeException ignored) {
        // The reconciler still observes terminal completion when active.
      }
      session.terminalCompletion().whenComplete((ignored, failure) -> {
        if (sessions.unbindIfSame(session.remoteNodeId(), session)) {
          topology.removeOrigin(session.remoteNodeId());
          syncPeerTransports();
          publishTopology();
          OverlayReconciler current = reconciler;
          if (current != null) {
            current.reconcile();
          }
        }
      });
      publishTopology();
      OverlayReconciler current = reconciler;
      if (current != null) {
        current.reconcile();
      }
      record("SESSION_ACCEPTED peer=" + session.remoteNodeId());
    } catch (RuntimeException failure) {
      closeSession(session);
      record("SESSION_REJECTED " + failureSummary(failure));
    }
  }

  private void syncPeerTransports() {
    OverlayMembershipSnapshot snapshot = membershipSnapshot;
    if (snapshot == null) {
      return;
    }
    for (Map.Entry<String, PeerSession> entry : sessions.snapshot().entrySet()) {
      forwardingPeers.bind(entry.getKey(), OverlayPeerSessionBridge.outbound(entry.getValue()));
      membershipPeers.bind(entry.getKey(), frame -> entry.getValue().requestApplication(
          frame.encoded()).thenApply(ignored -> null));
      topologyPeers.bind(entry.getKey(), frame -> entry.getValue().requestApplication(
          frame.encoded()).thenApply(ignored -> null));
    }
    Set<String> live = sessions.nodeIds();
    new HashSet<>(forwardingPeers.nodeIds()).stream().filter(peer -> !live.contains(peer))
        .forEach(peer -> forwardingPeers.unbind(peer));
    new HashSet<>(membershipPeers.nodeIds()).stream().filter(peer -> !live.contains(peer))
        .forEach(peer -> membershipPeers.unbind(peer));
    new HashSet<>(topologyPeers.nodeIds()).stream().filter(peer -> !live.contains(peer))
        .forEach(peer -> topologyPeers.unbind(peer));
  }

  private void publishTopology() {
    OverlayMembershipSnapshot snapshot = membershipSnapshot;
    OverlayTopologyPropagation propagation = topologyPropagation;
    if (snapshot == null || propagation == null || !snapshot.allows(identity.nodeId())) {
      return;
    }
    try {
      Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      List<String> peers = new ArrayList<>(sessions.nodeIds());
      peers.removeIf(peer -> !snapshot.allows(peer));
      peers.sort(Comparator.naturalOrder());
      OverlayTopologyAdvertisement advertisement = OverlayTopologyAdvertisement.create(
          snapshot.projectId(), snapshot.revision(), ++topologySequence,
          now.plus(OverlayTopologyView.defaultLifetime()), peers, identity);
      propagation.publish(advertisement, now);
    } catch (Exception failure) {
      record("TOPOLOGY_PUBLISH_ERROR " + failure.getClass().getSimpleName());
    }
  }

  private PeerSession.ApplicationStreamHandler currentOverlayHandler() {
    PeerSession.ApplicationStreamHandler handler = overlayHandler;
    return handler == null
        ? (remote, payload) -> CompletableFuture.failedFuture(
            new IllegalStateException("membership is not loaded"))
        : handler;
  }

  private CompletionStage<byte[]> handleApplication(String remoteNodeId, byte[] payload) {
    return currentOverlayHandler().handle(remoteNodeId, payload);
  }

  private CompletionStage<Void> deliver(OverlayForwardingFrame frame) {
    try {
      byte[] inner = frame.innerRecord();
      if (magic(inner, KEY_INIT_MAGIC)) {
        if (initiators.containsKey(frame.messageId())) {
          OverlayKeyAgreement.ResponseRecord response =
              OverlayKeyAgreement.ResponseRecord.decode(inner);
          OverlayKeyAgreement.Initiator initiator = initiators.get(frame.messageId());
          OverlayKeyAgreement.E2eSession session = OverlayKeyAgreement.finish(initiator, response,
              membershipSnapshot);
          outbound.get(frame.messageId()).complete(session);
          return CompletableFuture.completedFuture(null);
        }
        OverlayKeyAgreement.InitRecord init = OverlayKeyAgreement.InitRecord.decode(inner);
        OverlayKeyAgreement.Responder response = OverlayKeyAgreement.respond(init, identity,
            membershipSnapshot);
        inbound.put(init.originNodeId(), response.session());
        OverlayRoute reverse = selectRoute(init.originNodeId());
        OverlayForwardingFrame responseFrame = OverlayForwardingFrame.create(
            membershipSnapshot.projectId(), init.originNodeId(), response.response().sessionId(),
            Math.max(1, reverse.path().size()), response.response().encoded());
        return forwarder.forward(responseFrame, Instant.now());
      }
      if (magic(inner, ENVELOPE_MAGIC)) {
        OverlayEnvelope envelope = OverlayEnvelope.decode(inner);
        OverlayKeyAgreement.E2eSession session = inbound.get(envelope.originNodeId());
        if (session == null) {
          throw new IllegalStateException("destination has no E2E session");
        }
        deliveredPayloads.add(session.decrypt(envelope));
        trim(deliveredPayloads);
      } else {
        observedInnerRecords.add(inner);
        trim(observedInnerRecords);
      }
      return CompletableFuture.completedFuture(null);
    } catch (Exception failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  private OverlayRoute selectRoute(String destination) throws GeneralSecurityException {
    Set<String> live = liveNeighbors();
    return OverlayRouteSelector.select(identity.nodeId(), destination, membershipSnapshot, live,
        topology, Instant.now(), null);
  }

  private Set<String> liveNeighbors() {
    OverlayReconciler current = reconciler;
    return current == null ? sessions.nodeIds() : current.liveNeighbors();
  }

  private void sendPayload(String destination, byte[] payload) throws Exception {
    requireMembership();
    OverlayKeyAgreement.Initiator initiator = OverlayKeyAgreement.start(
        membershipSnapshot.projectId(), destination, UUID.randomUUID(), membershipSnapshot.revision(),
        identity);
    UUID sessionId = initiator.init().sessionId();
    initiators.put(sessionId, initiator);
    CompletableFuture<OverlayKeyAgreement.E2eSession> sessionFuture = new CompletableFuture<>();
    outbound.put(sessionId, sessionFuture);
    OverlayRoute initRoute = selectRoute(destination);
    forwarder.forward(OverlayForwardingFrame.create(membershipSnapshot.projectId(), destination,
        sessionId, Math.max(1, initRoute.path().size()), initiator.init().encoded()), Instant.now())
        .toCompletableFuture().get(CONTROL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    OverlayKeyAgreement.E2eSession session = sessionFuture.get(CONTROL_TIMEOUT.toSeconds(),
        TimeUnit.SECONDS);
    OverlayEnvelope envelope = session.encrypt(UUID.randomUUID(), payload);
    OverlayRoute dataRoute = selectRoute(destination);
    forwarder.forward(OverlayForwardingFrame.create(membershipSnapshot.projectId(), destination,
        envelope.messageId(), Math.max(1, dataRoute.path().size()), envelope.encoded()), Instant.now())
        .toCompletableFuture().get(CONTROL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
  }

  private void createMembership(HttpExchange exchange) throws Exception {
    Map<String, String> query = query(exchange.getRequestURI());
    UUID projectId = UUID.fromString(required(query, "projectId"));
    List<OverlayMembershipSnapshot.Member> members = new ArrayList<>();
    String body = requestBody(exchange);
    for (String line : body.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      String[] fields = line.trim().split("\\|", 2);
      if (fields.length != 2) {
        throw new IllegalArgumentException("member line must be nodeId|base64-public-key");
      }
      members.add(new OverlayMembershipSnapshot.Member(fields[0],
          OverlayMembershipSnapshot.STATUS_ACTIVE, Base64.getDecoder().decode(fields[1])));
    }
    Instant issued = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MILLIS);
    OverlayMembershipSnapshot snapshot = OverlayMembershipSnapshot.create(projectId, 1, issued,
        issued.plus(Duration.ofHours(24)), members, identity);
    sendText(exchange, 200, Base64.getEncoder().encodeToString(snapshot.encoded()));
  }

  private void invite(HttpExchange exchange) throws Exception {
    requireMembership();
    String expectedPeer = required(query(exchange.getRequestURI()), "peer");
    Onboarding.PreparedHost host;
    synchronized (onboarding) {
      host = onboarding.createCompactInvitation(expectedPeer, this::handleApplication,
          this::acceptAuthenticated);
    }
    String link = host.invitationLink();
    hosts.put(link, host);
    CompletableFuture.runAsync(() -> {
      try {
        host.awaitConnectionAndKeepAlive();
        record("SLB2_HOST_CONNECTED peer=" + expectedPeer);
      } catch (OnboardingFailure failure) {
        record("SLB2_HOST_FAILED peer=" + expectedPeer + " code=" + failure.code());
      }
    }, controlExecutor);
    sendText(exchange, 200, link);
  }

  private void join(HttpExchange exchange) throws Exception {
    requireMembership();
    String link = requestBody(exchange).trim();
    if (link.isBlank()) {
      throw new IllegalArgumentException("invitation link is empty");
    }
    Onboarding.PreparedJoin join;
    synchronized (onboarding) {
      join = onboarding.importInvitation(link, this::handleApplication, this::acceptAuthenticated);
    }
    joins.put(UUID.randomUUID().toString(), join);
    CompletableFuture.runAsync(() -> {
      try {
        join.connectAndKeepAlive();
        record("SLB2_JOIN_CONNECTED");
      } catch (OnboardingFailure failure) {
        record("SLB2_JOIN_FAILED code=" + failure.code());
      }
    }, controlExecutor);
    sendText(exchange, 202, "accepted");
  }

  private String statusJson() {
    OverlayMembershipSnapshot snapshot = membershipSnapshot;
    List<String> desired = snapshot == null ? List.of() : new OverlayTopologyPolicy()
        .desiredNeighbors(identity.nodeId(), snapshot.members());
    List<String> live = new ArrayList<>(liveNeighbors());
    live.sort(Comparator.naturalOrder());
    Map<String, List<String>> graph = snapshot == null ? Map.of()
        : topology.adjacencyGraph(snapshot, Instant.now());
    StringBuilder json = new StringBuilder();
    json.append("{\"nodeId\":\"").append(json(identity.nodeId())
        ).append("\",\"directPort\":").append(directPort)
        .append(",\"membershipLoaded\":").append(snapshot != null)
        .append(",\"reconciliationStarted\":").append(reconciliationStarted)
        .append(",\"membershipDigest\":\"").append(snapshot == null ? ""
            : digest(snapshot.encoded())).append("\",\"desiredNeighbors\":")
        .append(jsonArray(desired)).append(",\"liveNeighbors\":").append(jsonArray(live))
        .append(",\"topology\":").append(jsonGraph(graph))
        .append(",\"deliveredCount\":").append(deliveredPayloads.size())
        .append(",\"lastDeliveredB64\":\"")
        .append(deliveredPayloads.isEmpty() ? "" : Base64.getEncoder().encodeToString(
            deliveredPayloads.getLast()))
        .append("\",\"descriptorDigest\":\"").append(descriptorDigest())
        .append("\",\"events\":").append(jsonArray(events))
        .append(",\"reconciliationEvents\":").append(jsonArray(reconciliationEvents))
        .append("}");
    return json.toString();
  }

  private String descriptorDigest() {
    return digest(descriptors.values().stream().sorted(Comparator.comparing(
        CandidateDescriptor::nodeId)).map(CandidateDescriptor::encoded)
        .reduce(new byte[0], ProcessLabNode::concat));
  }

  private String descriptorBase64() throws Exception {
    localDescriptor = createLocalDescriptor();
    return Base64.getEncoder().encodeToString(localDescriptor.encoded());
  }

  private void recordOnboardingEvent(OnboardingEvent event) {
    if (event.type() == OnboardingEventType.SHARE_LINK) {
      record("SHARE_LINK");
    } else {
      record(event.type().name() + (event.value().isBlank() ? "" : "=" + event.value()));
    }
  }

  private void record(String event) {
    events.add(Instant.now() + " " + event.replace('\n', ' '));
    trim(events);
  }

  private static String failureSummary(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return root.getClass().getSimpleName() + (message == null || message.isBlank()
        ? "" : ":" + message.replace('\n', ' '));
  }

  private static <T> void trim(Collection<T> values) {
    while (values.size() > MAX_EVENT_RECORDS) {
      if (values instanceof CopyOnWriteArrayList<?> copy) {
        copy.remove(0);
      } else {
        return;
      }
    }
  }

  private void requireMembership() {
    if (membershipSnapshot == null || overlayHandler == null) {
      throw new IllegalStateException("signed membership has not been installed");
    }
  }

  private static void closeSession(PeerSession session) {
    try {
      session.closeGracefully(SessionCloseReason.LOCAL_REQUEST);
    } catch (RuntimeException ignored) {
      // Cleanup is best effort after a rejected or terminal session.
    }
  }

  private static boolean magic(byte[] value, int expected) {
    return value.length >= Integer.BYTES
        && java.nio.ByteBuffer.wrap(value).getInt() == expected;
  }

  private static byte[] concat(byte[] left, byte[] right) {
    byte[] result = java.util.Arrays.copyOf(left, left.length + right.length);
    System.arraycopy(right, 0, result, left.length, right.length);
    return result;
  }

  private static String digest(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    try {
      OverlayReconciler current = reconciler;
      if (current != null) {
        current.close();
      }
      clientConnections.forEach(ClientConnection::close);
      clientConnections.clear();
      acceptedChannels.forEach(channel -> {
        try {
          channel.close(true, 0, Unpooled.EMPTY_BUFFER).syncUninterruptibly();
        } catch (RuntimeException ignored) {
          // Shutdown is best effort.
        }
      });
      acceptedChannels.clear();
      hosts.values().forEach(value -> {
        try {
          value.close();
        } catch (RuntimeException ignored) {
          // Shutdown is best effort.
        }
      });
      joins.values().forEach(value -> {
        try {
          value.close();
        } catch (RuntimeException ignored) {
          // Shutdown is best effort.
        }
      });
      sessions.snapshot().values().forEach(ProcessLabNode::closeSession);
      if (controlServer != null) {
        controlServer.stop(0);
      }
      maintenance.shutdownNow();
      controlExecutor.shutdownNow();
      if (serverUdp != null) {
        serverUdp.close().syncUninterruptibly();
      }
      group.shutdownGracefully().syncUninterruptibly();
      if (tls != null) {
        tls.close();
      }
    } catch (Exception failure) {
      record("SHUTDOWN_ERROR " + failure.getClass().getSimpleName());
    } finally {
      stopped.countDown();
    }
  }

  private final class DirectConnector implements OverlayReconciler.PeerConnector {

    @Override
    public CompletionStage<PeerSession> connect(CandidateDescriptor descriptor) {
      CompletableFuture<PeerSession> result = new CompletableFuture<>();
      CompletableFuture.runAsync(() -> connectBlocking(descriptor, result), controlExecutor);
      return result;
    }

    private void connectBlocking(CandidateDescriptor descriptor,
        CompletableFuture<PeerSession> result) {
      boolean gate = false;
      Channel clientUdp = null;
      ClientConnection connection = null;
      try {
        NATIVE_CONNECT_GATE.acquire();
        gate = true;
        if (closed.get()) {
          throw new IllegalStateException("node is closed");
        }
        clientUdp = new Bootstrap().group(group)
            .channel(NioDatagramChannel.class)
            .handler(NettyQuicTransport.clientCodec(clientSsl))
            .bind(new InetSocketAddress(localIpv4Address(), 0))
            .sync()
            .channel();
        Candidate candidate = descriptor.candidates().stream()
            .filter(value -> value.type() != org.synesis.link.candidate.CandidateType.RELAY)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no direct candidate"));
        var connectFuture = QuicChannel.newBootstrap(clientUdp)
            .handler(new ChannelInboundHandlerAdapter())
            .streamHandler(NettySessionHandshake.clientApplicationStreamHandler())
            .remoteAddress(new InetSocketAddress(candidate.address(), candidate.port()))
            .connect();
        if (!connectFuture.await(10, TimeUnit.SECONDS) || !connectFuture.isSuccess()) {
          throw new java.util.concurrent.TimeoutException("QUIC connection did not complete: "
              + connectFuture.cause());
        }
        QuicChannel client = connectFuture.getNow();
        connection = new ClientConnection(clientUdp, client);
        clientUdp = null;
        HandshakeTranscript transcript = HandshakeTranscript.create(ProtocolVersion.V1,
            SynesisLink.ALPN, UUID.randomUUID(), 1, 1, randomBytes(), randomBytes(),
            identity.nodeId(), identity.publicKeyEncoded(), descriptor.nodeId(),
            descriptor.publicKeyEncoded());
        HandshakeProof proof = SessionAuthenticator.createProof(identity, transcript,
            HandshakeRole.INITIATOR);
        CompletableFuture<PeerSession> established = new CompletableFuture<>();
        var streamFuture = client.createStream(QuicStreamType.BIDIRECTIONAL,
            NettySessionHandshake.clientStreamHandler(identity, descriptor.nodeId(),
                List.of(ProtocolVersion.V1), transcript, proof, new ReplayGuard(), established,
                LAB_LIVENESS, ProcessLabNode.this::handleApplication));
        if (!streamFuture.await(10, TimeUnit.SECONDS) || !streamFuture.isSuccess()) {
          throw new java.util.concurrent.TimeoutException("QUIC stream did not open: "
              + streamFuture.cause());
        }
        PeerSession session = established.get(20, TimeUnit.SECONDS);
        ClientConnection retained = connection;
        clientConnections.add(retained);
        session.terminalCompletion().whenComplete((ignored, failure) -> {
          clientConnections.remove(retained);
          retained.close();
        });
        result.complete(session);
      } catch (Exception failure) {
        if (connection != null) {
          connection.close();
        } else if (clientUdp != null) {
          clientUdp.close().syncUninterruptibly();
        }
        record("DIRECT_CONNECT_FAILED peer=" + descriptor.nodeId() + " reason="
            + failureSummary(failure));
        result.completeExceptionally(failure);
      } finally {
        if (gate) {
          NATIVE_CONNECT_GATE.release();
        }
      }
    }
  }

  private record ClientConnection(Channel udp, QuicChannel quic) {

    private void close() {
      try {
        if (quic.isOpen()) {
          quic.close(true, 0, Unpooled.EMPTY_BUFFER).syncUninterruptibly();
        }
        if (udp.isOpen()) {
          udp.close().syncUninterruptibly();
        }
      } catch (RuntimeException failure) {
        // Best-effort close for a transport that may already be terminal.
      }
    }
  }

  private final class ControlHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        dispatch(exchange);
      } catch (Exception failure) {
        sendText(exchange, 400, failure.getClass().getSimpleName() + ": "
            + String.valueOf(failure.getMessage()));
      } finally {
        exchange.close();
      }
    }
  }

  private void dispatch(HttpExchange exchange) throws Exception {
    String path = exchange.getRequestURI().getPath();
    if ("GET".equals(exchange.getRequestMethod()) && "/health".equals(path)) {
      sendText(exchange, 200, "OK");
      return;
    }
    if ("GET".equals(exchange.getRequestMethod()) && "/identity".equals(path)) {
      sendText(exchange, 200, identity.nodeId() + "|"
          + Base64.getEncoder().encodeToString(identity.publicKeyEncoded()));
      return;
    }
    if ("GET".equals(exchange.getRequestMethod()) && "/descriptor".equals(path)) {
      sendText(exchange, 200, descriptorBase64());
      return;
    }
    if ("GET".equals(exchange.getRequestMethod()) && "/status".equals(path)) {
      sendJson(exchange, 200, statusJson());
      return;
    }
    if ("GET".equals(exchange.getRequestMethod()) && "/route".equals(path)) {
      requireMembership();
      String destination = required(query(exchange.getRequestURI()), "destination");
      OverlayRoute route = selectRoute(destination);
      sendJson(exchange, 200, "{\"kind\":\"" + route.kind().name()
          + "\",\"destinationNodeId\":\"" + json(route.destinationNodeId())
          + "\",\"path\":" + jsonArray(route.path()) + "}");
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && "/membership/create".equals(path)) {
      createMembership(exchange);
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && "/membership".equals(path)) {
      installMembership(Base64.getDecoder().decode(requestBody(exchange).trim()));
      sendText(exchange, 200, "accepted");
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && "/descriptors".equals(path)) {
      installDescriptors(requestBody(exchange));
      sendText(exchange, 200, "accepted");
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && "/reconcile".equals(path)) {
      startReconciliation();
      sendText(exchange, 200, "started");
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && "/invite".equals(path)) {
      invite(exchange);
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && "/join".equals(path)) {
      join(exchange);
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && "/send".equals(path)) {
      String destination = required(query(exchange.getRequestURI()), "destination");
      sendPayload(destination, requestBody(exchange).getBytes(StandardCharsets.UTF_8));
      sendText(exchange, 200, "delivered");
      return;
    }
    if ("POST".equals(exchange.getRequestMethod()) && "/shutdown".equals(path)) {
      sendText(exchange, 200, "shutting-down");
      CompletableFuture.runAsync(this::close, controlExecutor);
      return;
    }
    sendText(exchange, 404, "not found");
  }

  private static String requestBody(HttpExchange exchange) throws IOException {
    byte[] bytes = exchange.getRequestBody().readAllBytes();
    if (bytes.length > 1_048_576) {
      throw new IOException("request body exceeds lab bound");
    }
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void sendText(HttpExchange exchange, int status, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static void sendJson(HttpExchange exchange, int status, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static Map<String, String> query(URI uri) {
    Map<String, String> values = new HashMap<>();
    String raw = uri.getRawQuery();
    if (raw == null || raw.isBlank()) {
      return values;
    }
    for (String pair : raw.split("&")) {
      String[] fields = pair.split("=", 2);
      if (fields.length == 2) {
        values.put(URLDecoder.decode(fields[0], StandardCharsets.UTF_8),
            URLDecoder.decode(fields[1], StandardCharsets.UTF_8));
      }
    }
    return values;
  }

  private static String required(Map<String, String> values, String key) {
    String value = values.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing " + key);
    }
    return value;
  }

  private static String json(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "\\r").replace("\n", "\\n");
  }

  private static String jsonArray(Collection<String> values) {
    return values.stream().map(value -> "\"" + json(value) + "\"")
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  private static String jsonGraph(Map<String, List<String>> graph) {
    return graph.entrySet().stream().sorted(Map.Entry.comparingByKey())
        .map(entry -> "\"" + json(entry.getKey()) + "\":" + jsonArray(entry.getValue()))
        .collect(java.util.stream.Collectors.joining(",", "{", "}"));
  }

  private static byte[] randomBytes() {
    byte[] value = new byte[16];
    new java.security.SecureRandom().nextBytes(value);
    return value;
  }

  private record Options(Path state, int controlPort, int directPort, boolean help) {

    private static Options parse(String[] arguments) {
      Path state = Path.of("/var/lib/synesis-lab");
      int control = 8080;
      int direct = 4555;
      boolean help = false;
      for (int index = 0; index < arguments.length; index++) {
        String argument = arguments[index];
        switch (argument) {
          case "--help" -> help = true;
          case "--state" -> state = Path.of(next(arguments, ++index, argument));
          case "--control-port" -> control = Integer.parseInt(next(arguments, ++index, argument));
          case "--direct-port" -> direct = Integer.parseInt(next(arguments, ++index, argument));
          default -> throw new IllegalArgumentException("unknown option: " + argument);
        }
      }
      if (control < 1 || control > 65_535 || direct < 1 || direct > 65_535) {
        throw new IllegalArgumentException("ports must be between 1 and 65535");
      }
      return new Options(state, control, direct, help);
    }

    private static String next(String[] arguments, int index, String option) {
      if (index >= arguments.length) {
        throw new IllegalArgumentException(option + " requires a value");
      }
      return arguments[index];
    }

    private static String usage() {
      return "ProcessLabNode --state DIR --control-port PORT --direct-port PORT";
    }
  }
}
