package org.synesis.link.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Objects;

import org.synesis.link.session.HandshakeException;
import org.synesis.link.session.HandshakeFailureCode;
import org.synesis.link.session.LivenessConfiguration;
import org.synesis.link.session.LivenessListener;
import org.synesis.link.session.LivenessMetrics;
import org.synesis.link.session.LivenessState;
import org.synesis.link.session.PeerSession;
import org.synesis.link.session.SessionCloseReason;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.demo.DemoWorkResult;

/** Internal bounded control-stream owner, heartbeat loop, and close state machine. */
final class NettyControlStream extends SimpleChannelInboundHandler<ByteBuf>
        implements PeerSession.ControlBinding, PeerSession.DemoWorkBinding,
        PeerSession.ApplicationStreamBinding {

    static final int MAX_CLOSE_MILLIS = 2_000;
    private static final int READY_BYTES = 20;
    private static final int GOODBYE_BYTES = 17;
    private static final int ACK_BYTES = 16;
    private final PeerSession session;
    private final CompletableFuture<PeerSession> established;
    private final Runnable claim;
    private final UUID sessionId;
    private final LivenessConfiguration livenessConfiguration;
    private final PeerSession.ApplicationStreamHandler applicationHandler;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean goodbyeSent = new AtomicBoolean();
    private final AtomicBoolean terminalOnce = new AtomicBoolean();
    private final AtomicReference<SessionCloseReason> reason = new AtomicReference<>();
    private final AtomicInteger activeDemoStreams = new AtomicInteger();
    private final AtomicInteger activeApplicationStreams = new AtomicInteger();
    private final CompletableFuture<Void> terminal = new CompletableFuture<>();
    private final EventDispatcher events = new EventDispatcher();
    private ChannelHandlerContext context;
    private LivenessTracker liveness;
    private long nextHeartbeatSequence;
    private long highestSentSequence = -1;
    private long highestAcknowledgedSequence = -1;
    private long highestReceivedSequence = -1;
    private long lastHeartbeatMarker;

    private NettyControlStream(PeerSession session, CompletableFuture<PeerSession> established,
            Runnable claim, LivenessConfiguration livenessConfiguration,
            PeerSession.ApplicationStreamHandler applicationHandler) {
        this.session = session;
        this.established = established;
        this.claim = claim;
        this.sessionId = session.sessionId();
        this.livenessConfiguration = livenessConfiguration;
        this.applicationHandler = applicationHandler;
    }

    static NettyControlStream create(PeerSession session, CompletableFuture<PeerSession> established,
            Runnable claim) {
        return create(session, established, claim, LivenessConfiguration.DEFAULT);
    }

    static NettyControlStream create(PeerSession session, CompletableFuture<PeerSession> established,
            Runnable claim, LivenessConfiguration livenessConfiguration) {
        return create(session, established, claim, livenessConfiguration, null);
    }

    static NettyControlStream create(PeerSession session, CompletableFuture<PeerSession> established,
            Runnable claim, LivenessConfiguration livenessConfiguration,
            PeerSession.ApplicationStreamHandler applicationHandler) {
        return new NettyControlStream(session, established, claim, livenessConfiguration, applicationHandler);
    }

    @Override
    public void channelActive(ChannelHandlerContext context) {
        this.context = context;
        write(ControlFrame.of(ControlMessageType.CONTROL_READY, sessionPayload()));
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf message) {
        try {
            ControlFrame frame = ControlFrame.decode(readBytes(message));
            switch (frame.type) {
                case CONTROL_READY -> receiveReady(frame.payload);
                case GOODBYE -> receiveGoodbye(frame.payload);
                case GOODBYE_ACK -> receiveGoodbyeAck(frame.payload);
                case PROTOCOL_ERROR -> receiveProtocolError(frame.payload);
                case HEARTBEAT -> receiveHeartbeat(frame.payload);
                case HEARTBEAT_ACK -> receiveHeartbeatAck(frame.payload);
                default -> protocolFailure(ControlErrorCode.UNSUPPORTED_MESSAGE);
            }
        } catch (Exception exception) {
            protocolFailure(ControlErrorCode.MALFORMED_FRAME);
        }
    }

    private void receiveReady(byte[] payload) throws HandshakeException {
        validateSessionPayload(payload, READY_BYTES);
        if (ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt(16) != ControlFrame.MAX_PAYLOAD) {
            protocolFailure(ControlErrorCode.ILLEGAL_STATE);
            return;
        }
        if (!ready.compareAndSet(false, true)) {
            protocolFailure(ControlErrorCode.ILLEGAL_STATE);
            return;
        }
        claim.run();
        session.attachControl(this);
        startLiveness();
        established.complete(session);
    }

    private void startLiveness() {
        LivenessScheduler scheduler = (action, delay) -> {
            java.util.concurrent.ScheduledFuture<?> future = context.executor().schedule(action,
                    delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        };
        liveness = new LivenessTracker(livenessConfiguration, System::nanoTime, scheduler,
                new LivenessTracker.Sink() {
                    @Override public void heartbeatDue() { sendHeartbeat(); }
                    @Override public void expired() { expireFromLiveness(); }
                }, null, events);
        liveness.start();
    }

    private void receiveHeartbeat(byte[] payload) {
        try {
            HeartbeatMessage heartbeat = HeartbeatMessage.decode(payload, sessionId, false);
            if (heartbeat.relatedSequence() > highestSentSequence) {
                protocolFailure(ControlErrorCode.INVALID_HEARTBEAT);
                return;
            }
            if (heartbeat.sequence() > highestReceivedSequence + 1
                    && highestReceivedSequence != Long.MAX_VALUE) {
                protocolFailure(ControlErrorCode.INVALID_HEARTBEAT);
                return;
            }
            boolean newest = heartbeat.sequence() > highestReceivedSequence;
            if (newest) highestReceivedSequence = heartbeat.sequence();
            if (liveness != null) {
                liveness.recordHeartbeatReceived(newest);
                if (newest) liveness.validPeerActivity();
            }
            write(ControlFrame.of(ControlMessageType.HEARTBEAT_ACK,
                    HeartbeatMessage.acknowledgement(sessionId, heartbeat.sequence(), highestReceivedSequence,
                            heartbeat.marker()).encoded()));
        } catch (IllegalArgumentException exception) {
            protocolFailure(ControlErrorCode.INVALID_HEARTBEAT);
        }
    }

    private void receiveHeartbeatAck(byte[] payload) {
        try {
            HeartbeatMessage acknowledgement = HeartbeatMessage.decode(payload, sessionId, true);
            if (acknowledgement.sequence() > highestSentSequence) {
                protocolFailure(ControlErrorCode.INVALID_HEARTBEAT);
                return;
            }
            boolean newest = acknowledgement.sequence() > highestAcknowledgedSequence;
            if (newest) highestAcknowledgedSequence = acknowledgement.sequence();
            if (liveness != null) {
                liveness.recordAcknowledged(newest);
                if (newest) {
                    liveness.validPeerActivity();
                    if (acknowledgement.sequence() == highestSentSequence
                            && acknowledgement.marker() == lastHeartbeatMarker) {
                        liveness.recordRtt(Math.max(0, System.nanoTime() - acknowledgement.marker()));
                    }
                }
            }
        } catch (IllegalArgumentException exception) {
            protocolFailure(ControlErrorCode.INVALID_HEARTBEAT);
        }
    }

    private void receiveGoodbye(byte[] payload) throws HandshakeException {
        validateSessionPayload(payload, GOODBYE_BYTES);
        SessionCloseReason remoteReason = decodeReason(payload[16]);
        if (remoteReason == null) {
            protocolFailure(ControlErrorCode.MALFORMED_FRAME);
            return;
        }
        if (terminal.isDone()) return;
        if (!ready.get()) {
            protocolFailure(ControlErrorCode.ILLEGAL_STATE);
            return;
        }
        setReason(SessionCloseReason.REMOTE_REQUEST);
        stopLiveness(LivenessState.CLOSED_BY_PEER);
        write(ControlFrame.of(ControlMessageType.GOODBYE_ACK, sessionOnlyPayload()));
        closeTransport();
    }

    private void receiveGoodbyeAck(byte[] payload) throws HandshakeException {
        validateSessionPayload(payload, ACK_BYTES);
        if (!goodbyeSent.get()) {
            protocolFailure(ControlErrorCode.ILLEGAL_STATE);
            return;
        }
        closeTransport();
    }

    private void receiveProtocolError(byte[] payload) throws HandshakeException {
        validateSessionPayload(payload, GOODBYE_BYTES);
        if ((payload[16] & 255) == 0 || (payload[16] & 255) > ControlErrorCode.values().length) {
            throw new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE);
        }
        setReason(SessionCloseReason.PROTOCOL_ERROR);
        stopLiveness(LivenessState.CLOSED_BY_PROTOCOL);
        closeTransport();
    }

    private void validateSessionPayload(byte[] payload, int expectedLength) throws HandshakeException {
        if (payload == null || payload.length != expectedLength) {
            throw new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE);
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        if (!new UUID(buffer.getLong(), buffer.getLong()).equals(sessionId)) {
            throw new HandshakeException(HandshakeFailureCode.IDENTITY_MISMATCH);
        }
    }

    private SessionCloseReason decodeReason(byte value) {
        SessionCloseReason[] values = SessionCloseReason.values();
        return (value & 255) < values.length ? values[value & 255] : null;
    }

    private byte[] sessionPayload() {
        ByteBuffer buffer = ByteBuffer.allocate(READY_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(sessionId.getMostSignificantBits()).putLong(sessionId.getLeastSignificantBits())
                .putInt(ControlFrame.MAX_PAYLOAD);
        return buffer.array();
    }

    private byte[] sessionOnlyPayload() {
        ByteBuffer buffer = ByteBuffer.allocate(ACK_BYTES).order(ByteOrder.BIG_ENDIAN);
        return buffer.putLong(sessionId.getMostSignificantBits()).putLong(sessionId.getLeastSignificantBits()).array();
    }

    private void sendHeartbeat() {
        if (liveness == null || !liveness.isRunning() || terminal.isDone() || !ready.get()) return;
        if (nextHeartbeatSequence < 0) {
            protocolFailure(ControlErrorCode.HEARTBEAT_SEQUENCE_EXHAUSTED);
            return;
        }
        long sequence = nextHeartbeatSequence;
        nextHeartbeatSequence = sequence == Long.MAX_VALUE ? -1 : sequence + 1;
        long marker = Math.max(0, System.nanoTime());
        lastHeartbeatMarker = marker;
        highestSentSequence = sequence;
        ChannelFuture write = write(ControlFrame.of(ControlMessageType.HEARTBEAT,
                HeartbeatMessage.heartbeat(sessionId, sequence, highestReceivedSequence, marker).encoded()));
        if (write == null) liveness.recordSendFailure();
        else {
            liveness.recordHeartbeatSent();
            write.addListener(future -> { if (!future.isSuccess() && liveness != null) liveness.recordSendFailure(); });
        }
    }

    @Override
    public boolean isReady() { return ready.get(); }

    @Override
    public PeerSession.DemoWorkBinding demoWorkBinding() { return this; }

    @Override
    public PeerSession.ApplicationStreamBinding applicationStreamBinding() { return this; }

    @Override
    public CompletionStage<DemoWorkResult> request(DemoWorkRequest request) {
        if (!isReady() || terminal.isDone()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "demo work requires a live control stream"));
        }
        return DemoWorkTransport.open(context, request, activeDemoStreams);
    }

    @Override
    public CompletionStage<byte[]> exchange(byte[] payload) {
        if (!isReady() || terminal.isDone()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "application stream requires a live control stream"));
        }
        return ApplicationStreamTransport.open(context, payload, activeApplicationStreams);
    }

    @Override
    public CompletionStage<Void> closeGracefully(SessionCloseReason closeReason) {
        if (!goodbyeSent.compareAndSet(false, true)) return terminal;
        setReason(Objects.requireNonNull(closeReason, "close reason"));
        stopLiveness(LivenessState.CLOSED_GRACEFULLY);
        Runnable close = () -> {
            if (!terminal.isDone() && context != null && context.channel().isOpen()) {
                ByteBuffer payload = ByteBuffer.allocate(GOODBYE_BYTES).order(ByteOrder.BIG_ENDIAN)
                        .putLong(sessionId.getMostSignificantBits()).putLong(sessionId.getLeastSignificantBits())
                        .put((byte) closeReason.ordinal());
                write(ControlFrame.of(ControlMessageType.GOODBYE, payload.array()));
                context.executor().schedule(this::closeTransport, MAX_CLOSE_MILLIS, TimeUnit.MILLISECONDS);
            } else closeTransport();
        };
        if (context != null && context.executor().inEventLoop()) close.run();
        else if (context != null) context.executor().execute(close);
        else closeTransport();
        return terminal;
    }

    @Override
    public CompletionStage<Void> terminalCompletion() { return terminal; }

    @Override
    public SessionCloseReason closeReason() { return reason.get(); }

    @Override
    public LivenessState livenessState() {
        return liveness == null ? LivenessState.CONNECTING : liveness.state();
    }

    @Override
    public LivenessMetrics livenessMetrics() {
        return liveness == null ? new LivenessMetrics(0, 0, 0, 0, 0,
                Duration.ZERO, Duration.ZERO, 0, 0, 0, 0, 0) : liveness.metrics();
    }

    @Override
    public void addLivenessListener(LivenessListener listener) {
        if (liveness != null) liveness.addListener(listener);
    }

    @Override
    public void removeLivenessListener(LivenessListener listener) {
        if (liveness != null) liveness.removeListener(listener);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        if (reason.get() == null) setReason(SessionCloseReason.TRANSPORT_CLOSED);
        if (liveness != null && !livenessState().equals(LivenessState.EXPIRED)) stopLiveness(LivenessState.FAILED);
        if (!established.isDone()) {
            established.completeExceptionally(new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE));
        }
        finishTerminal();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        setReason(SessionCloseReason.PROTOCOL_ERROR);
        stopLiveness(LivenessState.CLOSED_BY_PROTOCOL);
        finishTerminal();
        context.close();
    }

    private void protocolFailure(ControlErrorCode code) {
        setReason(SessionCloseReason.PROTOCOL_ERROR);
        stopLiveness(LivenessState.CLOSED_BY_PROTOCOL);
        if (!established.isDone()) {
            established.completeExceptionally(new HandshakeException(HandshakeFailureCode.MALFORMED_HANDSHAKE));
        }
        if (context != null && context.channel().isOpen()) {
            byte[] payload = ByteBuffer.allocate(GOODBYE_BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putLong(sessionId.getMostSignificantBits()).putLong(sessionId.getLeastSignificantBits())
                    .put((byte) code.code).array();
            write(ControlFrame.of(ControlMessageType.PROTOCOL_ERROR, payload));
            context.close();
        } else finishTerminal();
    }

    private void expireFromLiveness() {
        if (terminal.isDone() || reason.get() != null) return;
        setReason(SessionCloseReason.LIVENESS_EXPIRED);
        closeTransport();
    }

    private void stopLiveness(LivenessState state) {
        if (liveness != null) liveness.stop(state);
    }

    private ChannelFuture write(ControlFrame frame) {
        if (context != null && context.channel().isOpen()) {
            return context.writeAndFlush(Unpooled.wrappedBuffer(frame.encoded()));
        }
        return null;
    }

    private void closeTransport() {
        if (context != null && context.channel().isOpen()) context.close();
        else finishTerminal();
    }

    private void finishTerminal() {
        if (terminalOnce.compareAndSet(false, true)) {
            events.close();
            terminal.complete(null);
        }
    }

    private void setReason(SessionCloseReason value) {
        reason.compareAndSet(null, value);
    }

    private static byte[] readBytes(ByteBuf message) {
        if (message.readableBytes() > ControlFrame.MAX_FRAME) throw new IllegalArgumentException("frame too large");
        byte[] value = new byte[message.readableBytes()];
        message.readBytes(value);
        return value;
    }

    /** Per-session bounded callback executor; it never becomes protocol backpressure. */
    private static final class EventDispatcher implements LivenessEventDispatcher {
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), runnable -> {
                    Thread thread = new Thread(runnable, "synesis-link-liveness");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());

        @Override
        public boolean dispatch(Runnable action) {
            try {
                executor.execute(action);
                return true;
            } catch (RejectedExecutionException exception) {
                return false;
            }
        }

        private void close() { executor.shutdownNow(); }
    }
}
