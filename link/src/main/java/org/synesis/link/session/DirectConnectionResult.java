package org.synesis.link.session;

import java.util.List;

import org.synesis.link.candidate.CandidatePair;
import org.synesis.link.candidate.ConnectionAttemptDiagnostic;
import org.synesis.link.candidate.ConnectionFailureCategory;
import org.synesis.link.session.PeerSession;

/**
 * Immutable one-winner race result with bounded loser diagnostics.
 *
 * @param session         authenticated control-ready winner, or null on failure
 * @param winner          pair the winning session was established over, or null on failure
 * @param failureCategory final failure category, or null on success
 * @param diagnostics     bounded per-attempt diagnostics
 */
public record DirectConnectionResult(PeerSession session, CandidatePair winner,
                                     ConnectionFailureCategory failureCategory,
                                     List<ConnectionAttemptDiagnostic> diagnostics) {

  /**
   * Copies diagnostics; {@code session} is null only when no winner exists.
   */
  public DirectConnectionResult {
    diagnostics = List.copyOf(diagnostics);
  }
}
