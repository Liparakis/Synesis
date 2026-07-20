package org.synesis.link.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.synesis.link.protocol.ProtocolVersion;

final class ApplicationStreamBindingTest {
    @Test
    void applicationExchangeRequiresControlReadiness() {
        PeerSession session = session(false, false);
        assertThrows(IllegalStateException.class, () -> session.requestApplication(new byte[] {1}));
    }

    @Test
    void readySessionDelegatesOpaquePayloadAndExposesIdentity() {
        byte[] expected = new byte[] {3, 2, 1};
        PeerSession session = session(true, false);
        assertEquals("remote", session.remoteNodeId());
        assertArrayEquals(expected, session.requestApplication(new byte[] {1, 2, 3}).toCompletableFuture().join());
    }

    @Test
    void terminalBindingRejectsNewExchange() {
        PeerSession session = session(true, true);
        assertThrows(CompletionException.class,
                () -> session.requestApplication(new byte[] {1}).toCompletableFuture().join());
    }

    private static PeerSession session(boolean ready, boolean terminal) {
        PeerSession session = new PeerSession("local", "remote", new byte[] {1}, UUID.randomUUID(), 1, 1,
                ProtocolVersion.V1, java.time.Instant.EPOCH);
        session.attachControl(new StubControl(ready, terminal));
        return session;
    }

    private static final class StubControl implements PeerSession.ControlBinding {
        private final boolean ready;
        private final boolean terminal;

        private StubControl(boolean ready, boolean terminal) {
            this.ready = ready;
            this.terminal = terminal;
        }

        @Override public boolean isReady() { return ready; }
        @Override public CompletionStage<Void> closeGracefully(SessionCloseReason reason) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> terminalCompletion() {
            return terminal ? CompletableFuture.completedFuture(null) : new CompletableFuture<>();
        }
        @Override public SessionCloseReason closeReason() { return terminal ? SessionCloseReason.LOCAL_REQUEST : null; }
        @Override public LivenessState livenessState() { return terminal ? LivenessState.CLOSED_GRACEFULLY : LivenessState.LIVE; }
        @Override public LivenessMetrics livenessMetrics() {
            return new LivenessMetrics(0, 0, 0, 0, 0, Duration.ZERO, Duration.ZERO, 0, 0, 0, 0, 0);
        }
        @Override public void addLivenessListener(LivenessListener listener) { }
        @Override public void removeLivenessListener(LivenessListener listener) { }
        @Override public PeerSession.ApplicationStreamBinding applicationStreamBinding() {
            return payload -> {
                if (terminal) return CompletableFuture.failedFuture(new IllegalStateException("terminal"));
                byte[] reversed = payload.clone();
                for (int index = 0; index < reversed.length / 2; index++) {
                    byte value = reversed[index];
                    reversed[index] = reversed[reversed.length - index - 1];
                    reversed[reversed.length - index - 1] = value;
                }
                return CompletableFuture.completedFuture(reversed);
            };
        }
    }
}
