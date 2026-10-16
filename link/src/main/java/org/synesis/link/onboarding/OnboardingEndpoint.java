package org.synesis.link.onboarding;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateProvider;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.candidate.StunCandidateProvider;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.portmap.RouterPortMapping;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.InvitationAdmission;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.transport.quic.EphemeralTlsMaterial;
import org.synesis.link.transport.quic.NettyQuicTransport;
import org.synesis.link.transport.quic.NettySessionHandshake;
import org.synesis.link.transport.quic.NettyStunBindingTransport;

/**
 * Sole owner of the network resources behind one onboarding host or join handle: the event loop
 * group, the bound UDP channel, the optional STUN transport, and, on the host side, the invitation
 * admission and the ephemeral TLS material.
 *
 * <p>A factory either returns an endpoint that owns everything it opened or releases the partial set
 * and throws, so no caller closes a resource individually. {@link #close()} is the only release
 * path and applies one order: router port forward, STUN binding, UDP channel, event loop group,
 * invitation admission, then TLS material. Components layered on the UDP channel, such as a traversal exchange, must be
 * closed before the endpoint.
 */
final class OnboardingEndpoint implements AutoCloseable {

  private final InetSocketAddress stunServer;
  private MultiThreadIoEventLoopGroup group;
  private Channel udp;
  private EphemeralTlsMaterial tls;
  private NettyStunBindingTransport stun;
  private InvitationAdmission admission;
  private CompletableFuture<PeerSession> established;

  /**
   * Time without any packet after which the QUIC connection of a retained session closes: twice
   * {@link LivenessConfiguration#RETAINED}'s expiry, so liveness always decides first and this only
   * reclaims a connection whose control stream already ended. Heartbeats every five seconds keep
   * a working path from idling, and the home router's UDP mapping (RFC 9308 allows 30 s) is kept
   * open by them as well.
   */
  static final Duration RETAINED_QUIC_IDLE = Duration.ofSeconds(60);
  /**
   * Accepted handshakes a retained host remembers to refuse replays. Each redial adds one, and a
   * full guard refuses every later handshake (it fails closed), so it is sized for days of a link
   * that drops every half minute.
   */
  static final int RETAINED_REPLAY_ENTRIES = 8_192;
  /** How long the host keeps sending toward a joiner after its answer arrives. */
  static final Duration PUNCH_DURATION = Duration.ofSeconds(30);
  private static final long PUNCH_INTERVAL_MILLIS = 250;
  /** One byte no QUIC or STUN parser accepts, so the joiner drops it; it only has to leave. */
  private static final byte[] PUNCH_PAYLOAD = {0};
  private ScheduledFuture<?> punching;
  private RouterPortMapping portMapping;

  private OnboardingEndpoint(InetSocketAddress stunServer) {
    this.stunServer = resolveIpv4(stunServer);
  }

  /**
   * Resolves a configured STUN host to an IPv4 address. Behind a NAT it is the IPv4 mapping a
   * peer needs; IPv6 addresses are offered as they are. A server that does not resolve, as when
   * the machine is offline, leaves the endpoint without a reflexive candidate.
   */
  private static InetSocketAddress resolveIpv4(InetSocketAddress server) {
    if (server == null || !server.isUnresolved()) {
      return server;
    }
    try {
      for (InetAddress address : InetAddress.getAllByName(server.getHostString())) {
        if (address instanceof Inet4Address) {
          return new InetSocketAddress(address, server.getPort());
        }
      }
    } catch (UnknownHostException unresolvable) {
      // No reflexive candidate; local and IPv6 candidates still work.
    }
    return null;
  }

  /**
   * Opens the listening side: admission for the invitation, an ephemeral QUIC server, and its UDP
   * channel.
   *
   * <p>With {@code returning} the session is meant to outlive one exchange: it runs under
   * {@link LivenessConfiguration#RETAINED}, its QUIC connection closes after
   * {@link #RETAINED_QUIC_IDLE} without a packet, and, once the invitation is used, the peers that
   * rule names may connect again without one; each such session goes to {@code returned}. The
   * socket stays bound for as long as this endpoint is open, so the port a peer redials is the one
   * in the invitation.
   *
   * @param applicationHandler optional application-stream handler for the established session
   * @param returning          who may reconnect without the invitation, or {@code null} for a
   *                           one-shot endpoint
   * @param returned           receives each session admitted by {@code returning}
   * @param retainedReplay     the guard of a retained endpoint, which the caller may have restored from disk
   */
  static OnboardingEndpoint openServer(NodeIdentity identity, String expectedPeer, UUID sessionId,
      byte[] capability, PeerSession.ApplicationStreamHandler applicationHandler,
      InetSocketAddress stunServer, Predicate<String> returning, Consumer<PeerSession> returned,
      ReplayGuard retainedReplay) throws Exception {
    return openServer(identity, expectedPeer, sessionId, capability, applicationHandler, stunServer,
        returning, returned, 0, false, retainedReplay);
  }

  /**
   * Opens the listening side of a host that restarted: the same UDP {@code port}, and an admission
   * whose invitation counts as already used, so the peers {@code returning} names (and nobody else)
   * connect without one. The capability is random and told to no one, so no invitation can open it.
   *
   * @throws java.net.BindException when the port is taken
   */
  static OnboardingEndpoint openResumedServer(NodeIdentity identity, int port,
      PeerSession.ApplicationStreamHandler applicationHandler, InetSocketAddress stunServer,
      Predicate<String> returning, Consumer<PeerSession> returned, ReplayGuard retainedReplay)
      throws Exception {
    byte[] capability = new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES];
    new SecureRandom().nextBytes(capability);
    return openServer(identity, null, UUID.randomUUID(), capability, applicationHandler, stunServer,
        returning, returned, port, true, retainedReplay);
  }

  private static OnboardingEndpoint openServer(NodeIdentity identity, String expectedPeer,
      UUID sessionId, byte[] capability, PeerSession.ApplicationStreamHandler applicationHandler,
      InetSocketAddress stunServer, Predicate<String> returning, Consumer<PeerSession> returned,
      int port, boolean invitationUsed, ReplayGuard retainedReplay) throws Exception {
    OnboardingEndpoint endpoint = new OnboardingEndpoint(stunServer);
    try {
      boolean retained = returning != null;
      endpoint.admission = new InvitationAdmission(sessionId, capability);
      if (invitationUsed) {
        endpoint.admission.markConsumed();
      }
      if (retained) {
        endpoint.admission.admitReturning(returning, returned);
      }
      endpoint.tls = EphemeralTlsMaterial.create();
      endpoint.group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
      endpoint.established = new CompletableFuture<>();
      QuicSslContext ssl = QuicSslContextBuilder
          .forServer(endpoint.tls.key, null, endpoint.tls.certificate)
          .applicationProtocols(SynesisLink.ALPN)
          .build();
      endpoint.bind(NettyQuicTransport.serverCodec(ssl, InsecureQuicTokenHandler.INSTANCE,
          NettyQuicTransport.sharedConnectionHandler(),
          NettySessionHandshake.serverInvitationStreamHandler(
              identity, expectedPeer, List.of(ProtocolVersion.V1),
              retained ? retainedReplay : new ReplayGuard(),
              endpoint.established,
              retained ? LivenessConfiguration.RETAINED : LivenessConfiguration.DEFAULT,
              endpoint.admission, applicationHandler),
          retained ? RETAINED_QUIC_IDLE : null), port);
      return endpoint;
    } catch (Exception failure) {
      endpoint.close();
      throw failure;
    }
  }

  /**
   * Opens the dialing side: an ephemeral QUIC client and its UDP channel.
   *
   * @param retained whether its sessions outlive one exchange, which sets the QUIC idle timeout to
   *                 {@link #RETAINED_QUIC_IDLE}
   */
  static OnboardingEndpoint openClient(InetSocketAddress stunServer, boolean retained)
      throws Exception {
    OnboardingEndpoint endpoint = new OnboardingEndpoint(stunServer);
    try {
      endpoint.group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
      QuicSslContext ssl = QuicSslContextBuilder.forClient()
          .trustManager(InsecureTrustManagerFactory.INSTANCE)
          .applicationProtocols(SynesisLink.ALPN)
          .build();
      endpoint.bind(NettyQuicTransport.clientCodec(ssl, retained ? RETAINED_QUIC_IDLE : null), 0);
      return endpoint;
    } catch (Exception failure) {
      endpoint.close();
      throw failure;
    }
  }

  private void bind(ChannelHandler codec, int port) throws Exception {
    udp = new Bootstrap().group(group)
        .channel(NioDatagramChannel.class)
        .handler(codec)
        .bind(new InetSocketAddress(port))
        .sync()
        .channel();
    if (stunServer != null) {
      stun = new NettyStunBindingTransport(udp, Duration.ofSeconds(2));
    }
  }

  Channel udp() {
    return udp;
  }

  /** The UDP port this endpoint listens or sends from. */
  int port() {
    return ((InetSocketAddress) udp.localAddress()).getPort();
  }

  /** Completes when the server side authenticates one peer; absent on a client endpoint. */
  CompletableFuture<PeerSession> established() {
    return established;
  }

  /**
   * Sends a small datagram from the listening socket to each target, every quarter second for
   * {@link #PUNCH_DURATION}. Each one opens this side's router to that target, so a peer's
   * connection attempt that the router would otherwise drop gets through. A later call replaces an
   * earlier one.
   */
  synchronized void punch(List<Candidate> targets) {
    if (punching != null) {
      punching.cancel(false);
    }
    ScheduledFuture<?> sending = udp.eventLoop().scheduleAtFixedRate(() -> {
      for (Candidate target : targets) {
        udp.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(PUNCH_PAYLOAD),
            new InetSocketAddress(target.address(), target.port())));
      }
    }, 0, PUNCH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    udp.eventLoop().schedule(() -> sending.cancel(false), PUNCH_DURATION.toMillis(),
        TimeUnit.MILLISECONDS);
    punching = sending;
  }

  /**
   * Tells the listener, once, that a computer with another compatibility number tried to join.
   *
   * @param listener receives both numbers
   */
  void onIncompatiblePeer(java.util.function.Consumer<org.synesis.link.protocol.LinkCompatibility.Mismatch> listener) {
    admission.onIncompatiblePeer(listener);
  }

  /**
   * Asks the home router to forward the listening port, and returns the public endpoint as a
   * candidate. The forward is removed when this endpoint closes.
   *
   * @throws java.io.IOException with a short reason when no router agrees
   */
  Candidate openRouterPort() throws java.io.IOException {
    portMapping = RouterPortMapping.open(((InetSocketAddress) udp.localAddress()).getPort());
    return new Candidate(CandidateType.MAPPED_IPV4, portMapping.external().getAddress(),
        portMapping.external().getPort(), 0);
  }

  /** Gathers local candidates on the bound port, plus the STUN-reflexive one when configured. */
  List<Candidate> gatherCandidates(String id) throws Exception {
    List<CandidateProvider> providers = stun == null ? List.of()
        : List.of(new StunCandidateProvider(stunServer, 100, stun));
    return LocalCandidateGathering.gather(id, ((InetSocketAddress) udp.localAddress()).getPort(),
        providers);
  }

  @Override
  public void close() {
    if (portMapping != null) {
      portMapping.close();
    }
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
}
