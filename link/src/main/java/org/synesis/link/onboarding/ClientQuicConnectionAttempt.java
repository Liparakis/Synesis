package org.synesis.link.onboarding;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import java.net.InetSocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.session.ConnectionAttempt;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.protocol.HandshakeTranscript;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;
import org.synesis.link.transport.quic.NettySessionHandshake;

/**
 * Builds the initiator-side {@link ConnectionAttempt} that the candidate race runs per pair.
 *
 * <p>This is separate from {@link Onboarding} because both the legacy and compact join paths hand
 * the same QUIC connect, handshake-proof and cancellation behaviour to the candidate racer, and it
 * needs none of the facade's state.
 */
final class ClientQuicConnectionAttempt {

  private ClientQuicConnectionAttempt() {
  }

  static ConnectionAttempt attempt(NodeIdentity identity, CandidateDescriptor host,
      HandshakeTranscript transcript, Channel udp, CandidatePair pair,
      PeerSession.ApplicationStreamHandler applicationHandler, LivenessConfiguration liveness) {
    return attempt(identity, host.nodeId(), host.publicKeyEncoded(), transcript, udp, pair,
        applicationHandler, liveness);
  }

  static ConnectionAttempt attempt(NodeIdentity identity, String hostNodeId,
      byte[] hostPublicKey, HandshakeTranscript transcript, Channel udp, CandidatePair pair,
      PeerSession.ApplicationStreamHandler applicationHandler, LivenessConfiguration liveness) {
    return new ConnectionAttempt() {
      /** Known from registration on, before the first packet leaves, so a connection still dialing can be closed. */
      private volatile Channel connection;
      private volatile boolean cancelled;

      @Override
      public CompletionStage<PeerSession> connect(
          org.synesis.link.candidate.CandidateCancellation cancellation) {
        CompletableFuture<PeerSession> result = new CompletableFuture<>();
        QuicChannel.newBootstrap(udp)
            .handler(new ChannelInboundHandlerAdapter() {
              @Override
              public void handlerAdded(ChannelHandlerContext context) {
                connection = context.channel();
                if (cancelled) {
                  context.close();
                }
              }
            })
            .streamHandler(NettySessionHandshake.clientApplicationStreamHandler())
            .remoteAddress(new InetSocketAddress(pair.remote()
                .address(),
                pair.remote()
                    .port()))
            .connect()
            .addListener(future -> {
              if (!future.isSuccess()) {
                result.completeExceptionally(future.cause());
                return;
              }
              QuicChannel connected = (QuicChannel) future.getNow();
              // A race that gave up on this attempt dials again with the same transcript. Were this connection to get
              // through later, as when the host's firewall opens for the answer, its handshake would use up the
              // single-use invitation on a session nobody waits for, and the live attempt would be refused.
              if (cancelled) {
                connected.close();
                result.completeExceptionally(new CancellationException("connection attempt cancelled"));
                return;
              }
              CompletableFuture<PeerSession> established = new CompletableFuture<>();
              try {
                HandshakeProof proof = SessionAuthenticator.createProof(identity, transcript,
                    HandshakeRole.INITIATOR);
                connected.createStream(QuicStreamType.BIDIRECTIONAL,
                    NettySessionHandshake.clientStreamHandler(identity, hostNodeId, transcript,
                        proof, new ReplayGuard(), established,
                        liveness,
                        applicationHandler));
                established.whenComplete((session, failure) -> {
                  if (failure == null) {
                    result.complete(session);
                  } else {
                    result.completeExceptionally(failure);
                  }
                });
              } catch (Exception exception) {
                result.completeExceptionally(exception);
              }
            });
        return result;
      }

      @Override
      public void cancel() {
        cancelled = true;
        Channel dialing = connection;
        if (dialing != null) {
          dialing.close()
              .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }
      }
    };
  }
}
