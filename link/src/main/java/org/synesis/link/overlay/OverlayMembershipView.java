package org.synesis.link.overlay;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Monotonic local read model for the currently accepted signed membership snapshot.
 *
 * <p>The view accepts only a newer snapshot from the same project authority.
 * It does not perform admission, authority succession, revocation workflow, or transport
 * reconnection.
 */
public final class OverlayMembershipView {

  private volatile OverlayMembershipSnapshot current;

  /**
   * Creates a view from one verified membership snapshot.
   *
   * @param initial initial signed snapshot
   * @throws IllegalArgumentException if the snapshot signature is invalid
   */
  public OverlayMembershipView(OverlayMembershipSnapshot initial) {
    Objects.requireNonNull(initial, "initial membership");
    if (!initial.verify()) {
      throw new IllegalArgumentException("initial membership signature is invalid");
    }
    current = initial;
  }

  /**
   * Attempts to install a newer signed snapshot.
   *
   * @param candidate candidate signed snapshot
   * @param now       current time
   * @return acceptance result
   * @throws GeneralSecurityException if authority, project, validity, or same-revision contents are
   *                                  invalid
   */
  public synchronized Acceptance accept(OverlayMembershipSnapshot candidate, Instant now)
      throws GeneralSecurityException {
    Objects.requireNonNull(candidate, "candidate membership");
    Objects.requireNonNull(now, "now");
    OverlayMembershipSnapshot existing = current;
    if (!existing.projectId().equals(candidate.projectId())
        || !existing.authorityNodeId().equals(candidate.authorityNodeId())) {
      throw new GeneralSecurityException("membership authority or project changed");
    }
    if (!candidate.verify()) {
      throw new GeneralSecurityException("membership signature is invalid");
    }
    if (candidate.revision() < existing.revision()) {
      return Acceptance.STALE;
    }
    if (candidate.revision() == existing.revision()) {
      if (Arrays.equals(candidate.encoded(), existing.encoded())) {
        return Acceptance.DUPLICATE;
      }
      throw new GeneralSecurityException("conflicting membership revision");
    }
    if (!candidate.isUsableAt(now)) {
      throw new GeneralSecurityException("membership candidate is not currently usable");
    }
    current = candidate;
    return Acceptance.ACCEPTED;
  }

  /**
   * Returns the newest accepted signed snapshot.
   *
   * @return current membership snapshot
   */
  public OverlayMembershipSnapshot current() {
    return current;
  }

  /**
   * Result of attempting to advance the local membership snapshot.
   */
  public enum Acceptance {
    /**
     * A newer verified snapshot replaced the current view.
     */
    ACCEPTED,
    /**
     * The exact current snapshot was supplied again.
     */
    DUPLICATE,
    /**
     * The candidate revision is older than the current view.
     */
    STALE
  }
}
