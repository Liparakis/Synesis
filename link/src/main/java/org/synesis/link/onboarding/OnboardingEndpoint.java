package org.synesis.link.onboarding;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.synesis.link.SynesisLink;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateProvider;
import org.synesis.link.candidate.StunCandidateProvider;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.ProtocolVersion;
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
 * path and applies one order: STUN binding, UDP channel, event loop group, invitation admission,
 * then TLS material. Components layered on the UDP channel, such as a traversal exchange, must be
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

  private OnboardingEndpoint(InetSocketAddress stunServer) {
    this.stunServer = stunServer;
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

  /** Gathers local candidates on the bound port, plus the STUN-reflexive one when configured. */
  List<Candidate> gatherCandidates(String id) throws Exception {
    List<CandidateProvider> providers = stun == null ? List.of()
        : List.of(new StunCandidateProvider(stunServer, 100, stun));
    return LocalCandidateGathering.gather(id, ((InetSocketAddress) udp.localAddress()).getPort(),
        providers);
  }

  @Override
  public void close() {
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
