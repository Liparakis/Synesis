package org.synesis.link.candidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Tests bounded provider isolation, partial success, and cancellation. */
final class CandidateGathererTest {

    @Test
    void oneProviderFailureDoesNotSuppressAnotherProvider() throws Exception {
        Candidate candidate = new Candidate(CandidateType.MANUAL,
                InetAddress.getByName("192.0.2.9"), 4433, 1);
        CandidateProvider good = new ManualCandidateProvider("good", List.of(candidate));
        CandidateProvider bad = new StubProvider("bad", CompletableFuture.failedFuture(
                new IllegalStateException("not safe to log")));

        try (CandidateGatherer gatherer = new CandidateGatherer(CandidateGatheringPolicy.defaults())) {
            CandidateGatheringResult result = gatherer.gather(List.of(good, bad)).completion()
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(List.of(candidate), result.candidates());
            assertEquals(1, result.diagnostics().stream().filter(diagnostic ->
                    diagnostic.providerId().equals("bad")).count());
        }
    }

    @Test
    void cancellationCompletesOnceWithoutWaitingForProvider() throws Exception {
        CompletableFuture<List<Candidate>> pending = new CompletableFuture<>();
        try (CandidateGatherer gatherer = new CandidateGatherer(CandidateGatheringPolicy.defaults())) {
            CandidateGatheringOperation operation = gatherer.gather(List.of(
                    new StubProvider("pending", pending)));
            assertTrue(operation.cancel());
            assertTrue(!operation.cancel());
            CandidateGatheringResult result = operation.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(CandidateProviderFailureCategory.CANCELLED,
                    result.diagnostics().get(0).category());
        }
    }

    private record StubProvider(String id, CompletableFuture<List<Candidate>> result)
            implements CandidateProvider {
        @Override
        public Set<CandidateType> supportedTypes() { return Set.of(CandidateType.MANUAL); }

        @Override
        public CompletableFuture<List<Candidate>> gather(CandidateCancellation cancellation) {
            return result;
        }
    }
}
