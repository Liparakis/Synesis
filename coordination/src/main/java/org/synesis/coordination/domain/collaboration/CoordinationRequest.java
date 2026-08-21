package org.synesis.coordination.domain.collaboration;

import java.util.Objects;
import java.util.UUID;

/** Signed request used by participants to negotiate an overlapping intent.
 * @param requestId request identity
 * @param projectId project identity
 * @param requester opaque requester
 * @param target opaque target
 * @param conflictingIntentId conflicting intent
 * @param kind request kind
 * @param proposal bounded proposal
 * @param status lifecycle status
 */
public record CoordinationRequest(UUID requestId, UUID projectId, String requester,
        String target, UUID conflictingIntentId, Kind kind, String proposal, Status status) {
    /** Request categories supported by the first negotiation slice. */
    public enum Kind { /** Contract negotiation. */ CONTRACT, /** Ownership handoff negotiation. */ HANDOFF, /** Scope revision negotiation. */ SCOPE_REVISION, /** Read-only review admission negotiation. */ REVIEW }
    /** Durable request lifecycle. */
    public enum Status { /** Pending. */ PENDING, /** Accepted. */ ACCEPTED, /** Revised. */ REVISED, /** Rejected. */ REJECTED, /** Cancelled. */ CANCELLED, /** Completed. */ COMPLETED }

    /** Validates bounded request fields. */
    public CoordinationRequest {
        Objects.requireNonNull(requestId, "request ID");
        Objects.requireNonNull(projectId, "project ID");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(conflictingIntentId, "conflicting intent ID");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(status, "status");
        if (requester.isBlank() || target.isBlank() || proposal.isBlank() || proposal.length() > 8192) {
            throw new IllegalArgumentException("invalid coordination request");
        }
        if (requester.equals(target)) {
            throw new IllegalArgumentException("request cannot target requester");
        }
    }
}
