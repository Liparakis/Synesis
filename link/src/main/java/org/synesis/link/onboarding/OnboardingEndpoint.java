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
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateProvider;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.candidate.StunCandidateProvider;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.portmap.RouterPortMapping;
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
   * @param applicationHandler optional application-stream handler for the established session
   */
  static OnboardingEndpoint openServer(NodeIdentity identity, String expectedPeer, UUID sessionId,
      byte[] capability, PeerSession.ApplicationStreamHandler applicationHandler,
      InetSocketAddress stunServer) throws Exception {
    OnboardingEndpoint endpoint = new OnboardingEndpoint(stunServer);
    try {
      endpoint.admission = new InvitationAdmission(sessionId, capability);
      endpoint.tls = EphemeralTlsMaterial.create();
      endpoint.group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
      endpoint.established = new CompletableFuture<>();
      QuicSslContext ssl = QuicSslContextBuilder
          .forServer(endpoint.tls.key, null, endpoint.tls.certificate)
          .applicationProtocols(SynesisLink.ALPN)
          .build();
      endpoint.bind(NettyQuicTransport.serverCodec(ssl, InsecureQuicTokenHandler.INSTANCE,
          new ChannelInboundHandlerAdapter(),
          NettySessionHandshake.serverInvitationStreamHandler(
              identity, expectedPeer, List.of(ProtocolVersion.V1), new ReplayGuard(),
              endpoint.established, LivenessConfiguration.DEFAULT, endpoint.admission,
              applicationHandler)));
      return endpoint;
    } catch (Exception failure) {
      endpoint.close();
      throw failure;
    }
  }

  /** Opens the dialing side: an ephemeral QUIC client and its UDP channel. */
  static OnboardingEndpoint openClient(InetSocketAddress stunServer) throws Exception {
    OnboardingEndpoint endpoint = new OnboardingEndpoint(stunServer);
    try {
      endpoint.group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
      QuicSslContext ssl = QuicSslContextBuilder.forClient()
          .trustManager(InsecureTrustManagerFactory.INSTANCE)
          .applicationProtocols(SynesisLink.ALPN)
          .build();
      endpoint.bind(NettyQuicTransport.clientCodec(ssl));
      return endpoint;
    } catch (Exception failure) {
      endpoint.close();
      throw failure;
    }
  }

  private void bind(ChannelHandler codec) throws Exception {
    udp = new Bootstrap().group(group)
        .channel(NioDatagramChannel.class)
        .handler(codec)
        .bind(new InetSocketAddress(0))
        .sync()
        .channel();
    if (stunServer != null) {
      stun = new NettyStunBindingTransport(udp, Duration.ofSeconds(2));
    }
  }

  Channel udp() {
    return udp;
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
