package org.synesis.link.candidate;

import java.util.List;

import org.synesis.link.session.PeerSession;

/**
 * Immutable one-winner race result with bounded loser diagnostics.
 *
 * @param session authenticated control-ready winner, or null on failure
 * @param failureCategory final failure category, or null on success
 * @param diagnostics bounded per-attempt diagnostics
 */
public record DirectConnectionResult(PeerSession session, ConnectionFailureCategory failureCategory,
        List<ConnectionAttemptDiagnostic> diagnostics) {
    /** Copies diagnostics; {@code session} is null only when no winner exists. */
    public DirectConnectionResult {
        diagnostics = List.copyOf(diagnostics);
    }
}
