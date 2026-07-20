package org.synesis.link.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateCancellation;
import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.candidate.CandidateRacer;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.candidate.ConnectionAttempt;
import org.synesis.link.candidate.ConnectionPolicy;
import org.synesis.link.candidate.DirectConnectionResult;

/** Tests authenticated winner selection and loser cancellation. */
final class CandidateRacerTest {

    @Test
    void laterAuthenticatedAttemptWinsAfterEarlierFailure() throws Exception {
        PeerSession winner = usableSession("remote");
        CandidatePair first = pair("192.0.2.1", 1);
        CandidatePair second = pair("192.0.2.2", 2);
        AtomicInteger cancelled = new AtomicInteger();
        CompletableFuture<PeerSession> pending = new CompletableFuture<>();

        try (CandidateRacer racer = new CandidateRacer(new ConnectionPolicy(
                4, 4, 2, java.time.Duration.ZERO, java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(1), 16))) {
            CandidateRacer.Operation operation = racer.race(List.of(first, second), "remote", pair -> {
                if (pair.equals(first)) return new StubAttempt(pending, cancelled);
                return new StubAttempt(CompletableFuture.completedFuture(winner), cancelled);
            });
            DirectConnectionResult result = operation.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(winner, result.session());
            assertEquals(1, cancelled.get());
        }
    }

    private static CandidatePair pair(String address, int priority) throws Exception {
        Candidate local = new Candidate(CandidateType.MANUAL, InetAddress.getByName(address), 4433, priority);
        Candidate remote = new Candidate(CandidateType.MANUAL, InetAddress.getByName("198.51.100.2"), 4433, priority);
        return new CandidatePair(local, remote);
    }

    private static PeerSession usableSession(String remote) {
        PeerSession session = new PeerSession("local", remote, new byte[] {1}, UUID.randomUUID(), 1, 1,
                org.synesis.link.protocol.ProtocolVersion.V1, Instant.now());
        session.attachControl(new PeerSession.ControlBinding() {
            private final CompletableFuture<Void> terminal = new CompletableFuture<>();
            @Override public boolean isReady() { return true; }
            @Override public java.util.concurrent.CompletionStage<Void> closeGracefully(SessionCloseReason reason) {
                terminal.complete(null); return terminal;
            }
            @Override public java.util.concurrent.CompletionStage<Void> terminalCompletion() { return terminal; }
            @Override public SessionCloseReason closeReason() { return null; }
            @Override public LivenessState livenessState() { return LivenessState.LIVE; }
            @Override public LivenessMetrics livenessMetrics() {
                return new LivenessMetrics(0, 0, 0, 0, 0, java.time.Duration.ZERO,
                        java.time.Duration.ZERO, 0, 0, 0, 0, 0);
            }
            @Override public void addLivenessListener(LivenessListener listener) { }
            @Override public void removeLivenessListener(LivenessListener listener) { }
        });
        return session;
    }

    private record StubAttempt(CompletableFuture<PeerSession> result, AtomicInteger cancelled)
            implements ConnectionAttempt {
        @Override
        public CompletableFuture<PeerSession> connect(CandidateCancellation cancellation) { return result; }
        @Override
        public void cancel() { cancelled.incrementAndGet(); result.cancel(false); }
    }
}
