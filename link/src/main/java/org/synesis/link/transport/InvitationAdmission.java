package org.synesis.link.transport;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.synesis.link.protocol.SessionInvitation;
import org.synesis.link.session.HandshakeTranscript;

/** Single-use host admission state shared by per-connection handshake state. */
final class InvitationAdmission implements AutoCloseable {
    static final Duration RESERVATION_TIMEOUT = Duration.ofSeconds(15);
    private enum State { AVAILABLE, RESERVED, CONSUMED }
    private final UUID sessionId;
    private final byte[] capability;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "synesis-link-invite-reservation");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<State> state = new AtomicReference<>(State.AVAILABLE);
    private volatile ScheduledFuture<?> expiry;

    InvitationAdmission(SessionInvitation invitation) {
        this(invitation.sessionId(), invitation.capability());
    }

    InvitationAdmission(UUID sessionId, byte[] capability) {
        this.sessionId = sessionId;
        this.capability = capability.clone();
        if (capability.length != SessionInvitation.CAPABILITY_BYTES) {
            throw new IllegalArgumentException("invalid invitation capability");
        }
    }

    boolean reserve(HandshakeTranscript transcript) {
        if (!sessionId.equals(transcript.sessionId()) || !Arrays.equals(capability, transcript.invitationCapability())) {
            return false;
        }
        if (!state.compareAndSet(State.AVAILABLE, State.RESERVED)) return false;
        expiry = scheduler.schedule(() -> state.compareAndSet(State.RESERVED, State.AVAILABLE),
                RESERVATION_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        return true;
    }

    void authenticated() {
        if (!state.compareAndSet(State.RESERVED, State.CONSUMED)) {
            throw new IllegalStateException("invitation admission is no longer reserved");
        }
        cancelExpiry();
    }

    void releaseBeforeAuthentication() {
        if (state.compareAndSet(State.RESERVED, State.AVAILABLE)) cancelExpiry();
    }

    @Override
    public void close() {
        cancelExpiry();
        scheduler.shutdownNow();
    }

    private void cancelExpiry() {
        ScheduledFuture<?> value = expiry;
        if (value != null) value.cancel(false);
    }
}
