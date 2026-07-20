package org.synesis.link.candidate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Caller-configured direct endpoint provider for tests, VPNs, and known peers. */
public final class ManualCandidateProvider implements CandidateProvider {
    private final String id;
    private final List<Candidate> candidates;

    /**
     * Creates an immutable manual provider.
     *
     * @param id stable provider identifier
     * @param candidates explicitly configured candidates
     */
    public ManualCandidateProvider(String id, List<Candidate> candidates) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("provider ID is blank");
        this.id = id;
        this.candidates = List.copyOf(candidates);
    }

    @Override public String id() { return id; }
    @Override public Set<CandidateType> supportedTypes() { return Set.of(CandidateType.MANUAL, CandidateType.LAN, CandidateType.IPV6); }
    @Override public CompletableFuture<List<Candidate>> gather(CandidateCancellation cancellation) {
        if (cancellation.isCancelled()) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        return CompletableFuture.completedFuture(candidates);
    }
}
