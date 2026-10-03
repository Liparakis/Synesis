package org.synesis.coordination.domain.collaboration;

import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.RequestId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Signed request used by participants to negotiate an overlapping intent.
 *
 * @param requestId           request identity
 * @param projectId           project identity
 * @param requester           opaque requester
 * @param target              opaque target
 * @param conflictingIntentId conflicting intent
 * @param kind                request kind
 * @param proposal            bounded proposal
 * @param status              lifecycle status
 * @param snapshotId          exact review candidate, when this is a snapshot-bound review request
 */
public record CoordinationRequest(UUID requestId, UUID projectId, String requester, String target,
                                  UUID conflictingIntentId, Kind kind, String proposal, Status status,
                                  String snapshotId) {

    /**
     * Creates a request that is not bound to a particular review snapshot.
     *
     * @param requestId           request identity
     * @param projectId           project identity
     * @param requester           opaque requester
     * @param target              opaque target
     * @param conflictingIntentId conflicting intent
     * @param kind                request kind
     * @param proposal            bounded proposal
     * @param status              lifecycle status
     */
    public CoordinationRequest(UUID requestId,
            UUID projectId,
            String requester,
            String target,
            UUID conflictingIntentId,
            Kind kind,
            String proposal,
            Status status) {
        this(requestId, projectId, requester, target, conflictingIntentId, kind, proposal, status, null);
    }

    /**
     * Validates bounded request fields.
     */
    public CoordinationRequest {
        CoordinationNullException.requireNonNull(requestId, "request ID");
        CoordinationNullException.requireNonNull(projectId, "project ID");
        CoordinationNullException.requireNonNull(requester, "requester");
        CoordinationNullException.requireNonNull(target, "target");
        CoordinationNullException.requireNonNull(conflictingIntentId, "conflicting intent ID");
        CoordinationNullException.requireNonNull(kind, "kind");
        CoordinationNullException.requireNonNull(proposal, "proposal");
        CoordinationNullException.requireNonNull(status, "status");
        if (requester.isBlank() || target.isBlank() || proposal.isBlank() || proposal.length() > 8192) {
            throw new CoordinationArgumentException("invalid coordination request");
        }
        if (snapshotId != null && (snapshotId.isBlank() || snapshotId.length() > 256)) {
            throw new CoordinationArgumentException("invalid review snapshot ID");
        }
        if (requester.equals(target)) {
            throw new CoordinationArgumentException("request cannot target requester");
        }
    }

    /**
     * Returns the typed request identity.
     *
     * @return request identity
     */
    public RequestId identity() {
        return new RequestId(requestId);
    }

    /**
     * Returns the requester as a participant-incarnation identity.
     *
     * @return requester participant identity
     */
    public ParticipantId requesterIdentity() {
        return new ParticipantId(requester);
    }

    /**
     * Returns the target as a participant-incarnation identity.
     *
     * @return target participant identity
     */
    public ParticipantId targetIdentity() {
        return new ParticipantId(target);
    }

    /**
     * Returns the typed conflicting work-intent identity.
     *
     * @return conflicting intent identity
     */
    public WorkIntentId conflictingIntent() {
        return new WorkIntentId(conflictingIntentId);
    }

    /**
     * Returns the snapshot ID with its domain type when this request is review-bound.
     *
     * @return snapshot identity when review-bound
     */
    public Optional<SnapshotId> snapshot() {
        return Optional.ofNullable(snapshotId).map(SnapshotId::new);
    }

    /**
     * Request categories supported by the first negotiation slice.
     */
    public enum Kind {
        /**
         * Contract negotiation.
         */
        CONTRACT,
        /**
         * Ownership handoff negotiation.
         */
        HANDOFF,
        /**
         * Scope revision negotiation.
         */
        SCOPE_REVISION,
        /**
         * Read-only review admission negotiation.
         */
        REVIEW
    }

    /**
     * Durable request lifecycle.
     */
    public enum Status {
        /**
         * Pending.
         */
        PENDING,
        /**
         * Accepted.
         */
        ACCEPTED,
        /**
         * Revised.
         */
        REVISED,
        /**
         * Rejected.
         */
        REJECTED,
        /**
         * Cancelled.
         */
        CANCELLED,
        /**
         * Completed.
         */
        COMPLETED
    }
}
