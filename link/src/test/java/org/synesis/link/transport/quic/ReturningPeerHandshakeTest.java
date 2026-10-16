package org.synesis.link.transport.quic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.NetUtil;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.link.SynesisLink;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.HandshakeException;
import org.synesis.link.protocol.HandshakeFailureCode;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.protocol.HandshakeTranscript;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.InvitationAdmission;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;

/**
 * Who may connect to a host whose invitation has been used, without an invitation (ADR-0098: a dropped session is
 * redialed). The host admits a node only when its rule names it, only after the invitation was consumed, only with
 * both identities proved, and never twice for the same handshake.
 */
@Timeout(60)
final class ReturningPeerHandshakeTest {

  private NodeIdentity host;
  private NodeIdentity known;
  private NodeIdentity stranger;
  private final UUID invitationId = UUID.randomUUID();
  private final byte[] capability = new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES];
  private InvitationAdmission admission;
  private final CompletableFuture<PeerSession> first = new CompletableFuture<>();
  private final CompletableFuture<PeerSession> returned = new CompletableFuture<>();
  private MultiThreadIoEventLoopGroup group;
  private TestTlsMaterial certificate;
  private Channel serverUdp;
  private Channel clientUdp;

  @BeforeEach
  void start() throws Exception {
    capability[0] = 7;
    admission = new InvitationAdmission(invitationId, capability);
    host = NodeIdentity.generate();
    known = NodeIdentity.generate();
    stranger = NodeIdentity.generate();
    group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    certificate = TestTlsMaterial.create();
    serverUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
        .handler(NettyQuicTransport.serverCodec(QuicSslContextBuilder.forServer(certificate.key, null,
                certificate.certificate).applicationProtocols(SynesisLink.ALPN).build(),
            InsecureQuicTokenHandler.INSTANCE, NettyQuicTransport.sharedConnectionHandler(),
            NettySessionHandshake.serverInvitationStreamHandler(host, null,
                List.of(ProtocolVersion.V1), new ReplayGuard(), first,
                LivenessConfiguration.RETAINED, admission, null)))
        .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0)).sync().channel();
    clientUdp = new Bootstrap().group(group).channel(NioDatagramChannel.class)
        .handler(NettyQuicTransport.clientCodec(QuicSslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE).applicationProtocols(SynesisLink.ALPN)
            .build()))
        .bind(new InetSocketAddress(NetUtil.LOCALHOST4, 0)).sync().channel();
  }

  @AfterEach
  void stop() throws Exception {
    admission.close();
    clientUdp.close().syncUninterruptibly();
    serverUdp.close().syncUninterruptibly();
    group.shutdownGracefully().syncUninterruptibly();
    certificate.close();
  }

  /** Dials the host as {@code signer}, claiming the identity in {@code transcript}, and returns the result. */
  private CompletableFuture<PeerSession> dial(NodeIdentity signer, HandshakeTranscript transcript)
      throws Exception {
    CompletableFuture<PeerSession> result = new CompletableFuture<>();
    QuicChannel client = QuicChannel.newBootstrap(clientUdp).handler(new ChannelInboundHandlerAdapter())
        .streamHandler(NettySessionHandshake.clientApplicationStreamHandler())
        .remoteAddress(serverUdp.localAddress()).connect().sync().getNow();
    HandshakeProof proof = SessionAuthenticator.createProof(signer, transcript, HandshakeRole.INITIATOR);
    client.createStream(QuicStreamType.BIDIRECTIONAL, NettySessionHandshake.clientStreamHandler(signer,
        host.nodeId(), List.of(ProtocolVersion.V1), transcript, proof, new ReplayGuard(), result,
        LivenessConfiguration.RETAINED, null)).sync();
    return result;
  }

  private HandshakeTranscript withInvitation(NodeIdentity initiator) {
    return HandshakeTranscript.create(ProtocolVersion.V1, SynesisLink.ALPN, invitationId, 1, 1,
        new byte[] {1}, new byte[] {2}, capability, initiator.nodeId(), initiator.publicKeyEncoded(),
        host.nodeId(), host.publicKeyEncoded());
  }

  /** A fresh handshake with no capability, as a redial sends it. */
  private HandshakeTranscript withoutInvitation(NodeIdentity initiator, byte seed) {
    return HandshakeTranscript.forIdentities(ProtocolVersion.V1, UUID.randomUUID(), 1, 1,
        new byte[] {seed}, new byte[] {seed, 1}, initiator, host);
  }

  private void useTheInvitation() throws Exception {
    dial(known, withInvitation(known)).get(10, TimeUnit.SECONDS);
    first.get(10, TimeUnit.SECONDS);
  }

  private static HandshakeFailureCode refusal(CompletableFuture<PeerSession> attempt) {
    ExecutionException failure = assertThrows(ExecutionException.class,
        () -> attempt.get(10, TimeUnit.SECONDS));
    return ((HandshakeException) failure.getCause()).code();
  }

  @Test
  void aKnownNodeThatUsedTheInvitationIsAdmittedAgainWithoutOne() throws Exception {
    admission.admitReturning(node -> node.equals(known.nodeId()), returned::complete);
    useTheInvitation();

    PeerSession again = dial(known, withoutInvitation(known, (byte) 10)).get(10, TimeUnit.SECONDS);

    assertEquals(known.nodeId(), again.localNodeId());
    assertEquals(host.nodeId(), again.remoteNodeId());
    PeerSession admitted = returned.get(10, TimeUnit.SECONDS);
    assertEquals(known.nodeId(), admitted.remoteNodeId());
    assertEquals(LivenessConfiguration.RETAINED, admitted.livenessConfiguration());
  }

  @Test
  void aNodeTheRuleDoesNotNameIsRefusedEvenWithBothIdentitiesProved() throws Exception {
    admission.admitReturning(node -> node.equals(known.nodeId()), returned::complete);
    useTheInvitation();

    assertEquals(HandshakeFailureCode.HANDSHAKE_REPLAY_REJECTED,
        refusal(dial(stranger, withoutInvitation(stranger, (byte) 20))));
    assertFalse(returned.isDone());
  }

  @Test
  void aNodeClaimingAKnownIdentityWithAnotherKeyIsRefused() throws Exception {
    admission.admitReturning(node -> node.equals(known.nodeId()), returned::complete);
    useTheInvitation();
    // The transcript names the known node and its key (one that names a node with another key cannot even be
    // built), but the stranger signs the proof: the claim of an identity without its key.
    CompletableFuture<PeerSession> attempt = dial(stranger, withoutInvitation(known, (byte) 30));

    // Which side notices first depends on timing: the host's proof check, or the dialer's own.
    assertTrue(List.of(HandshakeFailureCode.IDENTITY_PROOF_INVALID, HandshakeFailureCode.IDENTITY_MISMATCH,
        HandshakeFailureCode.MALFORMED_HANDSHAKE).contains(refusal(attempt)));
    Thread.sleep(500);
    assertFalse(returned.isDone());
  }

  @Test
  void aRecordedRedialIsNotAdmittedTwice() throws Exception {
    List<PeerSession> admitted = new CopyOnWriteArrayList<>();
    admission.admitReturning(node -> node.equals(known.nodeId()), admitted::add);
    useTheInvitation();
    HandshakeTranscript redial = withoutInvitation(known, (byte) 40);
    dial(known, redial).get(10, TimeUnit.SECONDS);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (admitted.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(1, admitted.size());

    // The host sends its proof before it checks for a replay, so the replayer may even see a session
    // for a moment; what matters is that the host never admits it.
    dial(known, redial).handle((session, failure) -> null).get(10, TimeUnit.SECONDS);
    Thread.sleep(500);

    assertEquals(1, admitted.size());
  }

  @Test
  void nobodyIsAdmittedWithoutTheInvitationWhileItIsUnused() throws Exception {
    admission.admitReturning(node -> true, returned::complete);

    assertEquals(HandshakeFailureCode.HANDSHAKE_REPLAY_REJECTED,
        refusal(dial(known, withoutInvitation(known, (byte) 50))));
    assertFalse(returned.isDone());
    assertFalse(first.isDone());
  }

  @Test
  void aHostWithNoRuleAdmitsNobodyWithoutTheInvitation() throws Exception {
    useTheInvitation();

    assertEquals(HandshakeFailureCode.HANDSHAKE_REPLAY_REJECTED,
        refusal(dial(known, withoutInvitation(known, (byte) 60))));
  }

  @Test
  void aNodeTheRuleStoppedNamingIsRefusedFromThenOn() throws Exception {
    boolean[] member = {true};
    admission.admitReturning(node -> member[0] && node.equals(known.nodeId()), returned::complete);
    useTheInvitation();
    dial(known, withoutInvitation(known, (byte) 70)).get(10, TimeUnit.SECONDS);

    member[0] = false;

    assertEquals(HandshakeFailureCode.HANDSHAKE_REPLAY_REJECTED,
        refusal(dial(known, withoutInvitation(known, (byte) 71))));
  }
}
