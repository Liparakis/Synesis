package org.synesis.link.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.quic.QuicStreamChannel;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.protocol.HandshakeEnvelope;
import org.synesis.link.protocol.HandshakeFailure;
import org.synesis.link.protocol.HandshakeProof;
import org.synesis.link.protocol.ProtocolVersion;
import org.synesis.link.session.HandshakeException;
import org.synesis.link.session.HandshakeFailureCode;
import org.synesis.link.session.HandshakeRole;
import org.synesis.link.session.HandshakeTranscript;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.ReplayGuard;
import org.synesis.link.session.SessionAuthenticator;

/** Internal Netty adapter for the bounded authenticated control exchange. */
final class NettySessionHandshake {

    private NettySessionHandshake() {
    }

    static ChannelHandler clientStreamHandler(NodeIdentity localIdentity, String expectedRemoteNodeId,
            HandshakeTranscript transcript, HandshakeProof localProof, ReplayGuard replayGuard,
            CompletableFuture<PeerSession> result) {
        return clientStreamHandler(localIdentity, expectedRemoteNodeId, List.of(ProtocolVersion.V1), transcript,
                localProof, replayGuard, result);
    }

    static ChannelHandler clientStreamHandler(NodeIdentity localIdentity, String expectedRemoteNodeId,
            List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript, HandshakeProof localProof,
            ReplayGuard replayGuard, CompletableFuture<PeerSession> result) {
        return clientStreamHandler(localIdentity, expectedRemoteNodeId, supportedVersions, transcript, localProof,
                replayGuard, result, LivenessConfiguration.DEFAULT);
    }

    static ChannelHandler clientStreamHandler(NodeIdentity localIdentity, String expectedRemoteNodeId,
            List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript, HandshakeProof localProof,
            ReplayGuard replayGuard, CompletableFuture<PeerSession> result, LivenessConfiguration livenessConfiguration) {
        return streamInitializer(new StreamState(localIdentity, expectedRemoteNodeId, supportedVersions, transcript,
                localProof, replayGuard, result, HandshakeRole.INITIATOR, livenessConfiguration));
    }

    static ChannelHandler serverStreamHandler(NodeIdentity localIdentity, String expectedRemoteNodeId,
            HandshakeTranscript transcript, ReplayGuard replayGuard, CompletableFuture<PeerSession> result) {
        return serverStreamHandler(localIdentity, expectedRemoteNodeId, List.of(ProtocolVersion.V1), transcript,
                replayGuard, result);
    }

    static ChannelHandler serverStreamHandler(NodeIdentity localIdentity, String expectedRemoteNodeId,
            List<ProtocolVersion> supportedVersions, ReplayGuard replayGuard,
            CompletableFuture<PeerSession> result) {
        return serverStreamHandler(localIdentity, expectedRemoteNodeId, supportedVersions, null, replayGuard, result);
    }

    static ChannelHandler serverStreamHandler(NodeIdentity localIdentity, String expectedRemoteNodeId,
            List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript,
            ReplayGuard replayGuard, CompletableFuture<PeerSession> result) {
        return serverStreamHandler(localIdentity, expectedRemoteNodeId, supportedVersions, transcript, replayGuard,
                result, LivenessConfiguration.DEFAULT);
    }

    static ChannelHandler serverStreamHandler(NodeIdentity localIdentity, String expectedRemoteNodeId,
            List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript,
            ReplayGuard replayGuard, CompletableFuture<PeerSession> result, LivenessConfiguration livenessConfiguration) {
        return streamInitializer(new StreamState(localIdentity, expectedRemoteNodeId, supportedVersions, transcript,
                null, replayGuard, result, HandshakeRole.RESPONDER, livenessConfiguration));
    }

    private static ChannelHandler streamInitializer(StreamState state) {
        return new ChannelInitializer<QuicStreamChannel>() {
            @Override
            protected void initChannel(QuicStreamChannel channel) {
                channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                        HandshakeEnvelope.MAX_BYTES + Integer.BYTES, 0, Integer.BYTES, 0, Integer.BYTES));
                channel.pipeline().addLast(new LengthFieldPrepender(Integer.BYTES));
                channel.pipeline().addLast(new HandshakeStreamHandler(state));
            }
        };
    }

    private static final class StreamState {
        private final NodeIdentity localIdentity;
        private final String expectedRemoteNodeId;
        private final List<ProtocolVersion> supportedVersions;
        private HandshakeTranscript transcript;
        private final HandshakeProof localProof;
        private final ReplayGuard replayGuard;
        private final CompletableFuture<PeerSession> result;
        private final HandshakeRole localRole;
        private final LivenessConfiguration livenessConfiguration;
        private final AtomicBoolean controlClaimed = new AtomicBoolean();
        private volatile PeerSession session;
        private final Set<UUID> demoRequests = ConcurrentHashMap.newKeySet();
        private final AtomicInteger demoStreams = new AtomicInteger();

        private StreamState(NodeIdentity localIdentity, String expectedRemoteNodeId,
                List<ProtocolVersion> supportedVersions, HandshakeTranscript transcript,
                HandshakeProof localProof, ReplayGuard replayGuard,
                CompletableFuture<PeerSession> result, HandshakeRole localRole,
                LivenessConfiguration livenessConfiguration) {
            this.localIdentity = Objects.requireNonNull(localIdentity, "local identity");
            this.expectedRemoteNodeId = Objects.requireNonNull(expectedRemoteNodeId, "expected remote node ID");
            this.supportedVersions = List.copyOf(supportedVersions);
            this.transcript = transcript;
            this.localProof = localProof;
            this.replayGuard = Objects.requireNonNull(replayGuard, "replay guard");
            this.result = Objects.requireNonNull(result, "result");
            this.localRole = Objects.requireNonNull(localRole, "local role");
            this.livenessConfiguration = Objects.requireNonNull(livenessConfiguration, "liveness configuration");
        }
    }

    private static final class HandshakeStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final StreamState state;
        private boolean sent;

        private HandshakeStreamHandler(StreamState state) {
            this.state = state;
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
                if (state.localRole == HandshakeRole.RESPONDER && state.controlClaimed.get()
                        && !HandshakeEnvelope.looksLike(bytes)) {
                    if (state.session == null) {
                        context.close();
                        return;
                    }
                    try {
                        DemoWorkTransport.accept(context, this, state.session, state.demoRequests,
                                state.demoStreams, bytes);
                    } catch (RuntimeException exception) { context.close(); }
                    return;
                }
                if (state.localRole == HandshakeRole.RESPONDER
                        && !state.controlClaimed.compareAndSet(false, true)) {
                    throw new HandshakeException(HandshakeFailureCode.DUPLICATE_CONTROL_STREAM);
                }
                if (HandshakeFailure.looksLike(bytes)) {
                    throw HandshakeFailure.decode(bytes).exception();
                }
                HandshakeEnvelope envelope = HandshakeEnvelope.decode(bytes);
                if (envelope.role() != state.localRole.opposite()) {
                    throw new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE);
                }
                ProtocolVersion selected;
                try {
                    selected = ProtocolVersion.negotiate(state.supportedVersions, envelope.supportedVersions());
                } catch (IllegalArgumentException exception) {
                    throw new HandshakeException(HandshakeFailureCode.UNSUPPORTED_PROTOCOL_VERSION);
                }
                if (!envelope.transcript().version().equals(selected)
                        || (state.transcript != null
                        && !Arrays.equals(envelope.transcript().encoded(), state.transcript.encoded()))) {
                    throw new HandshakeException(HandshakeFailureCode.UNSUPPORTED_PROTOCOL_VERSION);
                }
                state.transcript = envelope.transcript();
                HandshakeRole remoteRole = state.localRole.opposite();
                if (!state.expectedRemoteNodeId.equals(state.transcript.nodeId(remoteRole))) {
                    throw new HandshakeException(HandshakeFailureCode.IDENTITY_MISMATCH);
                }
                if (!state.localIdentity.nodeId().equals(state.transcript.nodeId(state.localRole))
                        || !Arrays.equals(state.localIdentity.publicKeyEncoded(),
                        state.transcript.publicKeyEncoded(state.localRole))) {
                    throw new HandshakeException(HandshakeFailureCode.IDENTITY_MISMATCH);
                }
                if (state.localRole == HandshakeRole.RESPONDER) {
                    HandshakeProof proof = SessionAuthenticator.createProof(state.localIdentity,
                            state.transcript, state.localRole);
                    send(context, proof);
                    complete(context, state.localProof == null ? proof : state.localProof, envelope.proof());
                } else {
                    complete(context, state.localProof, envelope.proof());
                }
            } catch (Exception exception) {
                fail(context, exception);
            }
        }

        private void send(ChannelHandlerContext context, HandshakeProof proof) {
            if (sent) {
                throw new IllegalStateException("duplicate handshake send");
            }
            sent = true;
            HandshakeEnvelope envelope = HandshakeEnvelope.create(state.localRole, state.supportedVersions,
                    state.transcript, proof);
            context.writeAndFlush(Unpooled.wrappedBuffer(envelope.encoded())).addListener(future -> {
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
                PeerSession session = SessionAuthenticator.establish(state.localIdentity, state.expectedRemoteNodeId,
                        state.transcript, localProof, remoteProof, state.replayGuard, Instant.now());
                state.session = session;
                NettyControlStream control = NettyControlStream.create(session, state.result,
                        () -> state.controlClaimed.set(true), state.livenessConfiguration);
                context.pipeline().replace(this, "synesis-control", control);
                control.channelActive(context);
            } catch (IllegalStateException exception) {
                throw new HandshakeException(HandshakeFailureCode.HANDSHAKE_REPLAY_REJECTED);
            } catch (IllegalArgumentException exception) {
                throw new HandshakeException(HandshakeFailureCode.IDENTITY_PROOF_INVALID);
            }
        }

        private static byte[] readBytes(ByteBuf message) {
            if (message.readableBytes() > HandshakeEnvelope.MAX_BYTES) {
                throw new IllegalArgumentException("handshake frame exceeds supported bound");
            }
            byte[] bytes = new byte[message.readableBytes()];
            message.readBytes(bytes);
            return bytes;
        }

        private void fail(ChannelHandlerContext context, Throwable failure) {
            HandshakeException exception = failure instanceof HandshakeException value
                    ? value : new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE);
            state.result.completeExceptionally(exception);
            if (context.channel().isOpen()) {
                context.writeAndFlush(Unpooled.wrappedBuffer(HandshakeFailure.create(exception.code()).encoded()))
                        .addListener(future -> context.close());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            fail(context, cause);
        }
    }
}
