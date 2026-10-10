package org.synesis.link.transport.quic;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.AttributeKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.session.InvitationAdmission;
import org.synesis.link.protocol.HandshakeEnvelope;
import org.synesis.link.protocol.HandshakeFailure;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.protocol.HandshakeException;
import org.synesis.link.protocol.HandshakeFailureCode;
import org.synesis.link.protocol.HandshakeRole;
import org.synesis.link.protocol.HandshakeTranscript;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;
import org.synesis.link.transport.control.ApplicationStreamCodec;
import org.synesis.link.transport.control.ApplicationStreamTransport;
import org.synesis.link.transport.control.DemoWorkTransport;
import org.synesis.link.transport.control.LengthPrefixedFrames;
import org.synesis.link.transport.control.NettyControlStream;

/**
 * Internal Netty adapter for the bounded authenticated control exchange.
 */
public final class NettySessionHandshake {

  private static final AttributeKey<StreamState> SERVER_STATE = AttributeKey.valueOf(
      "synesis-link-server-state");
  private static final AttributeKey<StreamState> CLIENT_STATE = AttributeKey.valueOf(
      "synesis-link-client-state");

  private NettySessionHandshake() {
  }

  /**
   * Creates the default initiator handshake stream handler.
   *
   * @param localIdentity        local node identity
   * @param expectedRemoteNodeId expected remote node identifier
   * @param transcript           handshake transcript
   * @param localProof           local handshake proof
   * @param replayGuard          replay guard
   * @param result               completion receiving the authenticated session
   * @return configured client handshake handler
   */
  public static ChannelHandler clientStreamHandler(NodeIdentity localIdentity,
      String expectedRemoteNodeId,
      HandshakeTranscript transcript, HandshakeProof localProof, ReplayGuard replayGuard,
      CompletableFuture<PeerSession> result) {
    return clientStreamHandler(localIdentity, expectedRemoteNodeId, List.of(ProtocolVersion.V1),
        transcript, localProof, replayGuard, result, LivenessConfiguration.DEFAULT, null);
  }

  /**
   * Creates the default initiator handshake stream handler with liveness and application-stream
   * hooks.
   *
   * @param localIdentity         local node identity
   * @param expectedRemoteNodeId  expected remote node identifier
   * @param transcript            handshake transcript
   * @param localProof            local handshake proof
   * @param replayGuard           replay guard
   * @param result                completion receiving the authenticated session
   * @param livenessConfiguration liveness settings
   * @param applicationHandler    optional application-stream handler
   * @return configured client handshake handler
   */
  public static ChannelHandler clientStreamHandler(NodeIdentity localIdentity,
      String expectedRemoteNodeId,
      HandshakeTranscript transcript, HandshakeProof localProof, ReplayGuard replayGuard,
      CompletableFuture<PeerSession> result, LivenessConfiguration livenessConfiguration,
      PeerSession.ApplicationStreamHandler applicationHandler) {
    return clientStreamHandler(localIdentity, expectedRemoteNodeId, List.of(ProtocolVersion.V1),
        transcript, localProof, replayGuard, result, livenessConfiguration, applicationHandler);
  }

  /**
   * Creates an initiator handshake stream handler with explicit protocol support, liveness
   * settings, and an application-stream hook.
   *
   * @param localIdentity         local node identity
   * @param expectedRemoteNodeId  expected remote node identifier
   * @param supportedVersions     supported protocol versions
   * @param transcript            handshake transcript
   * @param localProof            local handshake proof
   * @param replayGuard           replay guard
   * @param result                completion receiving the authenticated session
   * @param livenessConfiguration liveness settings
   * @param applicationHandler    optional application-stream handler
   * @return configured client handshake handler
   */
  public static ChannelHandler clientStreamHandler(NodeIdentity localIdentity,
      String expectedRemoteNodeId,
      List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript,
      HandshakeProof localProof,
      ReplayGuard replayGuard, CompletableFuture<PeerSession> result,
      LivenessConfiguration livenessConfiguration,
      PeerSession.ApplicationStreamHandler applicationHandler) {
    return streamInitializer(
        new StreamState(localIdentity, expectedRemoteNodeId, supportedVersions, transcript,
            localProof, replayGuard, result, HandshakeRole.INITIATOR, livenessConfiguration, null,
            applicationHandler));
  }

  /**
   * Creates a responder handshake stream handler with explicit protocol support and default
   * liveness settings.
   *
   * @param localIdentity        local node identity
   * @param expectedRemoteNodeId expected remote node identifier
   * @param supportedVersions    supported protocol versions
   * @param replayGuard          replay guard
   * @param result               completion receiving the authenticated session
   * @return configured server handshake handler
   */
  public static ChannelHandler serverStreamHandler(NodeIdentity localIdentity,
      String expectedRemoteNodeId,
      List<ProtocolVersion> supportedVersions, ReplayGuard replayGuard,
      CompletableFuture<PeerSession> result) {
    return serverStreamHandler(localIdentity, expectedRemoteNodeId, supportedVersions,
        replayGuard, result, LivenessConfiguration.DEFAULT, null);
  }

  /**
   * Creates a responder handshake stream handler with liveness and application-stream hooks.
   *
   * @param localIdentity         local node identity
   * @param expectedRemoteNodeId  expected remote node identifier
   * @param supportedVersions     supported protocol versions
   * @param replayGuard           replay guard
   * @param result                completion receiving the authenticated session
   * @param livenessConfiguration liveness settings
   * @param applicationHandler    optional application-stream handler
   * @return configured server handshake handler
   */
  public static ChannelHandler serverStreamHandler(NodeIdentity localIdentity,
      String expectedRemoteNodeId,
      List<ProtocolVersion> supportedVersions, ReplayGuard replayGuard,
      CompletableFuture<PeerSession> result, LivenessConfiguration livenessConfiguration,
      PeerSession.ApplicationStreamHandler applicationHandler) {
    return streamInitializer(
        new StreamState(localIdentity, expectedRemoteNodeId, supportedVersions, null,
            null, replayGuard, result, HandshakeRole.RESPONDER, livenessConfiguration, null,
            applicationHandler));
  }

  /**
   * Creates the default client-side handler for application streams initiated by the peer.
   *
   * <p>The handler uses the authenticated state installed by the client control-stream handshake;
   * it does not establish or accept a session on its own.
   *
   * @return configured inbound application-stream handler
   */
  public static ChannelHandler clientApplicationStreamHandler() {
    return new ChannelInitializer<QuicStreamChannel>() {
      @Override
      protected void initChannel(QuicStreamChannel channel) {
        StreamState state = channel.parent().attr(CLIENT_STATE).get();
        if (state == null || state.session == null || state.applicationHandler == null) {
          channel.close();
          return;
        }
        LengthPrefixedFrames.install(channel.pipeline(), ApplicationStreamCodec.MAX_FRAME_BYTES);
        channel.pipeline().addLast(new ClientApplicationStreamHandler(state));
      }
    };
  }

  /**
   * Creates a responder handshake handler that allocates independent completion state for each
   * accepted QUIC connection.
   *
   * <p>This is the multi-peer server seam. Each connection still has one control stream and uses
   * the same identity, replay, liveness, and application-stream validation as the fixed-result
   * overloads; only the caller-owned completion future is allocated per connection.
   *
   * @param localIdentity         local responder identity
   * @param expectedRemoteNodeId expected remote node identifier, or {@code null}
   * @param supportedVersions    supported protocol versions
   * @param replayGuard           shared bounded replay guard
   * @param resultFactory         factory for one fresh connection completion
   * @param livenessConfiguration liveness settings
   * @param applicationHandler    optional application-stream handler
   * @return configured multi-peer server handshake handler
   */
  public static ChannelHandler serverStreamHandler(NodeIdentity localIdentity,
      String expectedRemoteNodeId, List<ProtocolVersion> supportedVersions,
      ReplayGuard replayGuard, Supplier<CompletableFuture<PeerSession>> resultFactory,
      LivenessConfiguration livenessConfiguration,
      PeerSession.ApplicationStreamHandler applicationHandler) {
    Objects.requireNonNull(resultFactory, "result factory");
    return streamInitializer(
        () -> new StreamState(localIdentity, expectedRemoteNodeId, supportedVersions, null,
            null, replayGuard, Objects.requireNonNull(resultFactory.get(), "result"),
            HandshakeRole.RESPONDER, livenessConfiguration, null, applicationHandler),
        HandshakeRole.RESPONDER);
  }

  /**
   * Creates the invitation-accepting responder handshake stream handler.
   *
   * @param localIdentity         local node identity
   * @param expectedRemoteNodeId  expected remote node identifier
   * @param supportedVersions     supported protocol versions
   * @param replayGuard           replay guard
   * @param result                completion receiving the authenticated session
   * @param livenessConfiguration liveness settings
   * @param admission             invitation admission state
   * @param applicationHandler    optional application-stream handler
   * @return configured invitation handshake handler
   */
  public static ChannelHandler serverInvitationStreamHandler(NodeIdentity localIdentity,
      String expectedRemoteNodeId,
      List<ProtocolVersion> supportedVersions, ReplayGuard replayGuard,
      CompletableFuture<PeerSession> result,
      LivenessConfiguration livenessConfiguration, InvitationAdmission admission,
      PeerSession.ApplicationStreamHandler applicationHandler) {
    return streamInitializer(
        () -> new StreamState(localIdentity, expectedRemoteNodeId, supportedVersions, null,
            null, replayGuard, result, HandshakeRole.RESPONDER, livenessConfiguration, admission,
            applicationHandler), HandshakeRole.RESPONDER);
  }

  private static ChannelHandler streamInitializer(StreamState state) {
    return streamInitializer(() -> state, state.localRole);
  }

  private static ChannelHandler streamInitializer(Supplier<StreamState> stateFactory,
      HandshakeRole role) {
    return new ChannelInitializer<QuicStreamChannel>() {
      @Override
      protected void initChannel(QuicStreamChannel channel) {
        StreamState state;
        if (role == HandshakeRole.RESPONDER) {
          synchronized (channel.parent()) {
            var attribute = channel.parent().attr(SERVER_STATE);
            StreamState prior = attribute.get();
            if (prior == null) {
              state = stateFactory.get();
              attribute.set(state);
            } else {
              state = prior;
            }
          }
        } else {
          state = stateFactory.get();
          channel.parent().attr(CLIENT_STATE).setIfAbsent(state);
        }
        LengthPrefixedFrames.install(channel.pipeline(), HandshakeEnvelope.MAX_BYTES);
        channel.pipeline()
            .addLast(new HandshakeStreamHandler(state));
      }
    };
  }

  /** Processes peer-initiated application streams after client authentication. */
  private static final class ClientApplicationStreamHandler
      extends SimpleChannelInboundHandler<ByteBuf> {

    private final StreamState state;

    private ClientApplicationStreamHandler(StreamState state) {
      this.state = state;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
      byte[] bytes = new byte[message.readableBytes()];
      message.readBytes(bytes);
      routeInboundStream(context, this, state, bytes, false);
    }
  }

  /**
   * Hands an inbound post-handshake stream to its owner. Closes the stream when no session is
   * established, when the first frame is not an application frame and demo work is not accepted,
   * or when the owner rejects it. Only the responder accepts demo-work streams.
   */
  private static void routeInboundStream(ChannelHandlerContext context, ChannelHandler handler,
      StreamState state, byte[] bytes, boolean acceptDemoWork) {
    if (state.session == null) {
      context.close();
      return;
    }
    try {
      if (ApplicationStreamCodec.looksLike(bytes)) {
        ApplicationStreamTransport.accept(context, handler, state.session,
            state.applicationHandler, state.applicationStreams, bytes);
      } else if (acceptDemoWork) {
        DemoWorkTransport.accept(context, handler, state.session, state.demoRequests,
            state.demoStreams, bytes);
      } else {
        context.close();
      }
    } catch (RuntimeException exception) {
      context.close();
    }
  }

  /**
   * Tracks bounded handshake state for one Netty stream.
   */
  private static final class StreamState {

    private final NodeIdentity localIdentity;
    private final String expectedRemoteNodeId;
    private final List<ProtocolVersion> supportedVersions;
    private final HandshakeProof localProof;
    private final ReplayGuard replayGuard;
    private final CompletableFuture<PeerSession> result;
    private final HandshakeRole localRole;
    private final LivenessConfiguration livenessConfiguration;
    private final PeerSession.ApplicationStreamHandler applicationHandler;
    private final InvitationAdmission admission;
    // Lives for the whole connection: shared by every stream until the connection closes.
    private final AtomicBoolean controlClaimed = new AtomicBoolean();
    private final Set<UUID> demoRequests = ConcurrentHashMap.newKeySet();
    private final AtomicInteger demoStreams = new AtomicInteger();
    private final AtomicInteger applicationStreams = new AtomicInteger();
    private volatile PeerSession session;
    // Lives for one handshake attempt: set by the control stream, settled when it completes or
    // fails.
    private HandshakeTranscript transcript;
    private InvitationAdmission.Reservation reservation;

    private StreamState(NodeIdentity localIdentity, String expectedRemoteNodeId,
        List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript,
        HandshakeProof localProof, ReplayGuard replayGuard,
        CompletableFuture<PeerSession> result, HandshakeRole localRole,
        LivenessConfiguration livenessConfiguration, InvitationAdmission admission,
        PeerSession.ApplicationStreamHandler applicationHandler) {
      this.localIdentity = Objects.requireNonNull(localIdentity, "local identity");
      this.expectedRemoteNodeId = expectedRemoteNodeId;
      this.supportedVersions = List.copyOf(supportedVersions);
      this.transcript = transcript;
      this.localProof = localProof;
      this.replayGuard = Objects.requireNonNull(replayGuard, "replay guard");
      this.result = Objects.requireNonNull(result, "result");
      this.localRole = Objects.requireNonNull(localRole, "local role");
      this.livenessConfiguration = Objects.requireNonNull(livenessConfiguration,
          "liveness configuration");
      this.admission = admission;
      this.applicationHandler = applicationHandler;
    }
  }

  /**
   * Processes authenticated handshake frames on a Netty stream.
   */
  private static final class HandshakeStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private final StreamState state;
    private boolean sent;

    private HandshakeStreamHandler(StreamState state) {
      this.state = state;
    }

    private static byte[] readBytes(ByteBuf message) {
      return LengthPrefixedFrames.read(message, HandshakeEnvelope.MAX_BYTES,
          "handshake frame exceeds supported bound");
    }

    @Override
    public void channelActive(ChannelHandlerContext context) throws Exception {
      if (state.localRole == HandshakeRole.INITIATOR) {
        send(context, state.localProof);
      }
      super.channelActive(context);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
      try {
        byte[] bytes = readBytes(message);
        if (isPostHandshakeStream(bytes)) {
          routeInboundStream(context, this, state, bytes, true);
          return;
        }
        claimControlStream();
        HandshakeEnvelope envelope = decodePeerEnvelope(bytes);
        state.transcript = negotiateTranscript(envelope);
        reserveAdmission();
        checkIdentities();
        if (state.localRole == HandshakeRole.RESPONDER) {
          HandshakeProof proof = SessionAuthenticator.createProof(state.localIdentity,
              state.transcript, state.localRole);
          send(context, proof);
          complete(context, proof, envelope.proof());
        } else {
          complete(context, state.localProof, envelope.proof());
        }
      } catch (Exception exception) {
        fail(context, exception);
      }
    }

    /** A responder stream that is not a handshake frame once the control stream is claimed. */
    private boolean isPostHandshakeStream(byte[] bytes) {
      return state.localRole == HandshakeRole.RESPONDER && state.controlClaimed.get()
          && !HandshakeEnvelope.looksLike(bytes);
    }

    private void claimControlStream() throws HandshakeException {
      if (state.localRole == HandshakeRole.RESPONDER
          && !state.controlClaimed.compareAndSet(false, true)) {
        throw new HandshakeException(HandshakeFailureCode.DUPLICATE_CONTROL_STREAM);
      }
    }

    private HandshakeEnvelope decodePeerEnvelope(byte[] bytes) throws Exception {
      if (HandshakeFailure.looksLike(bytes)) {
        throw HandshakeFailure.decode(bytes)
            .exception();
      }
      HandshakeEnvelope envelope = HandshakeEnvelope.decode(bytes);
      if (envelope.role() != state.localRole.opposite()) {
        throw new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE);
      }
      return envelope;
    }

    private HandshakeTranscript negotiateTranscript(HandshakeEnvelope envelope)
        throws HandshakeException {
      ProtocolVersion selected;
      try {
        selected = ProtocolVersion.negotiate(state.supportedVersions,
            envelope.supportedVersions());
      } catch (IllegalArgumentException exception) {
        throw new HandshakeException(HandshakeFailureCode.UNSUPPORTED_PROTOCOL_VERSION);
      }
      if (!envelope.transcript()
          .version()
          .equals(selected)
          || (state.transcript != null
          && !Arrays.equals(envelope.transcript()
          .encoded(), state.transcript.encoded()))) {
        throw new HandshakeException(HandshakeFailureCode.UNSUPPORTED_PROTOCOL_VERSION);
      }
      return envelope.transcript();
    }

    private void reserveAdmission() throws HandshakeException {
      if (state.admission != null) {
        state.reservation = state.admission.reserve(state.transcript)
            .orElseThrow(
                () -> new HandshakeException(HandshakeFailureCode.HANDSHAKE_REPLAY_REJECTED));
      }
    }

    private void checkIdentities() throws HandshakeException {
      HandshakeRole remoteRole = state.localRole.opposite();
      if (state.expectedRemoteNodeId != null
          && !state.expectedRemoteNodeId.equals(state.transcript.nodeId(remoteRole))) {
        throw new HandshakeException(HandshakeFailureCode.IDENTITY_MISMATCH);
      }
      if (!state.localIdentity.nodeId()
          .equals(state.transcript.nodeId(state.localRole))
          || !Arrays.equals(state.localIdentity.publicKeyEncoded(),
          state.transcript.publicKeyEncoded(state.localRole))) {
        throw new HandshakeException(HandshakeFailureCode.IDENTITY_MISMATCH);
      }
    }

    private void send(ChannelHandlerContext context, HandshakeProof proof) {
      if (sent) {
        throw new IllegalStateException("duplicate handshake send");
      }
      sent = true;
      HandshakeEnvelope envelope = HandshakeEnvelope.create(state.localRole,
          state.supportedVersions,
          state.transcript, proof);
      context.writeAndFlush(Unpooled.wrappedBuffer(envelope.encoded()))
          .addListener(future -> {
            if (!future.isSuccess()) {
              fail(context, future.cause());
            }
          });
    }

    private void complete(ChannelHandlerContext context, HandshakeProof localProof,
        HandshakeProof remoteProof) throws Exception {
      if (localProof == null) {
        throw new IllegalStateException("local proof is missing");
      }
      try {
        PeerSession session = SessionAuthenticator.establish(state.localIdentity,
            state.expectedRemoteNodeId,
            state.transcript, localProof, remoteProof, state.replayGuard, Instant.now());
        if (state.admission != null) {
          state.admission.authenticated(state.reservation);
        }
        state.session = session;
        NettyControlStream control = NettyControlStream.create(session, state.result,
            () -> state.controlClaimed.set(true), state.livenessConfiguration);
        context.pipeline()
            .replace(this, "synesis-control", control);
        control.channelActive(context);
      } catch (IllegalStateException exception) {
        throw new HandshakeException(HandshakeFailureCode.HANDSHAKE_REPLAY_REJECTED);
      } catch (IllegalArgumentException exception) {
        throw new HandshakeException(HandshakeFailureCode.IDENTITY_PROOF_INVALID);
      }
    }

    private void fail(ChannelHandlerContext context, Throwable failure) {
      // At most once, and only the reservation this connection holds: the admission is shared, so a
      // repeated failure here must not free a later connection's reservation.
      InvitationAdmission.Reservation held = state.reservation;
      if (held != null) {
        state.reservation = null;
        state.admission.releaseBeforeAuthentication(held);
      }
      HandshakeException exception = failure instanceof HandshakeException value
          ? value : new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE);
      // Invitation admission is per-attempt: a malformed or wrong-capability
      // connection must not terminate the host's shared wait for the real joiner.
      if (state.admission == null) {
        state.result.completeExceptionally(exception);
      }
      if (context.channel()
          .isOpen()) {
        context.writeAndFlush(Unpooled.wrappedBuffer(HandshakeFailure.create(exception.code())
                .encoded()))
            .addListener(_ -> context.close());
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      fail(context, cause);
    }
  }
}
