package org.synesis.link.candidate;

import java.util.List;

/**
 * Immutable partial-or-complete result of bounded candidate gathering.
 *
 * @param candidates normalized usable candidates
 * @param diagnostics bounded provider outcomes
 * @param timedOut whether the global deadline ended gathering
 */
public record CandidateGatheringResult(List<Candidate> candidates,
        List<CandidateProviderDiagnostic> diagnostics, boolean timedOut) {
    /** Copies collections so late provider callbacks cannot mutate the result. */
    public CandidateGatheringResult {
        candidates = List.copyOf(candidates);
        diagnostics = List.copyOf(diagnostics);
    }
}
