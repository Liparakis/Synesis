package org.synesis.link.onboarding;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.synesis.link.candidate.CandidateDescriptor;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.session.ConnectionAttempt;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.protocol.HandshakeTranscript;
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
      PeerSession.ApplicationStreamHandler applicationHandler) {
    return attempt(identity, host.nodeId(), host.publicKeyEncoded(), transcript, udp, pair,
        applicationHandler);
  }

  static ConnectionAttempt attempt(NodeIdentity identity, String hostNodeId,
      byte[] hostPublicKey, HandshakeTranscript transcript, Channel udp, CandidatePair pair,
      PeerSession.ApplicationStreamHandler applicationHandler) {
    return new ConnectionAttempt() {
      private volatile QuicChannel connection;

      @Override
      public CompletionStage<PeerSession> connect(
          org.synesis.link.candidate.CandidateCancellation cancellation) {
        CompletableFuture<PeerSession> result = new CompletableFuture<>();
        QuicChannel.newBootstrap(udp)
            .handler(new ChannelInboundHandlerAdapter())
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
              connection = (QuicChannel) future.getNow();
              CompletableFuture<PeerSession> established = new CompletableFuture<>();
              try {
                HandshakeProof proof = SessionAuthenticator.createProof(identity, transcript,
                    HandshakeRole.INITIATOR);
                connection.createStream(QuicStreamType.BIDIRECTIONAL,
                    NettySessionHandshake.clientStreamHandler(identity, hostNodeId, transcript,
                        proof, new ReplayGuard(), established,
                        org.synesis.link.session.LivenessConfiguration.DEFAULT,
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
        if (connection != null) {
          connection.close()
              .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }
      }
    };
  }
}
