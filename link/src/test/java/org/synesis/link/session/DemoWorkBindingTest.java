package org.synesis.link.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.junit.jupiter.api.Test;
import org.synesis.link.demo.DemoWorkRequest;
import org.synesis.link.demo.DemoWorkResult;
import org.synesis.link.demo.DemoWorkStatus;
import org.synesis.link.protocol.ProtocolVersion;

final class DemoWorkBindingTest {
    @Test
    void applicationWorkRequiresControlReadiness() {
        PeerSession session = session(new StubWork(false));
        assertThrows(IllegalStateException.class,
                () -> session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(), "describe-session")));
    }

    @Test
    void readySessionDelegatesOneBoundedRequest() {
        StubWork binding = new StubWork(true);
        PeerSession session = session(binding);
        DemoWorkResult result = session.requestDemoWork(new DemoWorkRequest(UUID.randomUUID(), "describe-session"))
                .toCompletableFuture().join();
        assertEquals(DemoWorkStatus.OK, result.status());
    }

    private static PeerSession session(StubWork binding) {
        PeerSession session = new PeerSession("local", "remote", new byte[] {1}, UUID.randomUUID(), 1, 1,
                ProtocolVersion.V1, java.time.Instant.EPOCH);
        session.attachControl(binding);
        return session;
    }

    private static final class StubWork implements PeerSession.ControlBinding {
        private final boolean ready;

        private StubWork(boolean ready) { this.ready = ready; }

        @Override public boolean isReady() { return ready; }
        @Override public CompletionStage<Void> closeGracefully(SessionCloseReason reason) { return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> terminalCompletion() { return CompletableFuture.completedFuture(null); }
        @Override public SessionCloseReason closeReason() { return null; }
        @Override public LivenessState livenessState() { return LivenessState.LIVE; }
        @Override public LivenessMetrics livenessMetrics() { return new LivenessMetrics(0, 0, 0, 0, 0, Duration.ZERO, Duration.ZERO, 0, 0, 0, 0, 0); }
        @Override public void addLivenessListener(LivenessListener listener) { }
        @Override public void removeLivenessListener(LivenessListener listener) { }
        @Override public PeerSession.DemoWorkBinding demoWorkBinding() {
            return request -> CompletableFuture.completedFuture(
                    new DemoWorkResult(request.requestId(), DemoWorkStatus.OK, "control-ready"));
        }
    }
}
