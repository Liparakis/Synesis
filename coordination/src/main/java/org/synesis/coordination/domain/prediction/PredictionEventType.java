package org.synesis.coordination.domain.prediction;




/**
 * Append-only events that advance a speculative prediction.
 */
public enum PredictionEventType {
    /**
     * Creates a prediction and its immutable contract.
     */
    PREDICTION_CREATED,
    /**
     * Routes a prediction to its owner supervisor.
     */
    PREDICTION_ROUTED,
    /**
     * Confirms owner receipt.
     */
    REQUEST_RECEIVED,
    /**
     * Accepts the requested contract exactly.
     */
    ACCEPTED_EXACT,
    /**
     * Accepts an equivalent contract.
     */
    ACCEPTED_EQUIVALENT,
    /**
     * Revises the requested contract.
     */
    CONTRACT_REVISED,
    /**
     * Starts owner implementation.
     */
    IMPLEMENTATION_STARTED,
    /**
     * Publishes an implementation reference.
     */
    PATCH_READY,
    /**
     * Announces capability availability.
     */
    CAPABILITY_AVAILABLE,
    /**
     * Starts requester validation.
     */
    VALIDATION_STARTED,
    /**
     * Retires resolved speculation.
     */
    SPECULATION_RETIRED,
    /**
     * Invalidates speculation and dependent work.
     */
    PREDICTION_INVALIDATED,
    /**
     * Rejects the capability request.
     */
    REQUEST_REJECTED,
    /**
     * Expires an unresolved prediction.
     */
    PREDICTION_EXPIRED,
    /**
     * Creates a claimable task.
     */
    TASK_CREATED,
    /**
     * Claims a task for one supervisor.
     */
    TASK_CLAIMED,
    /**
     * Releases a task claim.
     */
    TASK_RELEASED,
    /**
     * Assigns semantic ownership to a task capability.
     */
    OWNERSHIP_CLAIMED,
    /**
     * Releases semantic ownership from a task capability.
     */
    OWNERSHIP_RELEASED,
    /**
     * Creates a durable capability request handle and contract.
     */
    CAPABILITY_REQUEST_CREATED,
    /**
     * Revises an existing capability request contract.
     */
    CAPABILITY_REQUEST_CONTRACT_REVISED,
    /**
     * Accepts a capability request contract.
     */
    CAPABILITY_REQUEST_ACCEPTED,
    /**
     * Rejects a capability request.
     */
    CAPABILITY_REQUEST_REJECTED,
    /**
     * Cancels a pending capability request.
     */
    CAPABILITY_REQUEST_CANCELLED,
    /**
     * Supersedes a capability request with a replacement.
     */
    CAPABILITY_REQUEST_SUPERSEDED,
    /**
     * Owner publishes an immutable implementation snapshot for a capability request.
     */
    CAPABILITY_IMPLEMENTATION_PUBLISHED,
    /**
     * Requester begins active validation of the current implementation revision.
     */
    CAPABILITY_VALIDATION_STARTED,
    /**
     * Requester accepts the implementation snapshot; capability transitions to VALIDATED.
     */
    CAPABILITY_IMPLEMENTATION_VALIDATED,
    /**
     * Requester rejects the implementation snapshot and requests a revision.
     */
    CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
    /**
     * Agent requests task completion.
     */
    TASK_COMPLETION_REQUESTED,
    /**
     * Immutable task snapshot created from worker worktree commit.
     */
    TASK_SNAPSHOT_CREATED,
    /**
     * Task snapshot created but waiting for dependent tasks to complete.
     */
    TASK_WAITING_FOR_DEPENDENCIES,
    /**
     * Dedicated integration attempt started in external integration worktree.
     */
    INTEGRATION_ATTEMPT_STARTED,
    /**
     * Integration attempt failed build or test gate.
     */
    INTEGRATION_ATTEMPT_FAILED,
    /**
     * Integration attempt encountered git merge conflict.
     */
    INTEGRATION_CONFLICTED,
    /**
     * Verified integration commit created in integration worktree.
     */
    INTEGRATION_COMMIT_CREATED,
    /**
     * Control branch fast-forwarded to integration commit.
     */
    CONTROL_BRANCH_ADVANCED,
    /**
     * Task successfully integrated into control branch.
     */
    TASK_INTEGRATED,
    /**
     * Worker session finalized following successful task integration.
     */
    SESSION_FINALIZED,
    /**
     * Provider session marked abandoned due to verified process death beyond grace period.
     */
    SESSION_ABANDONED,
    /**
     * Ambient worker requests task cancellation.
     */
    TASK_CANCELLATION_REQUESTED,
    /**
     * Task transitioned to terminal cancelled state.
     */
    TASK_CANCELLED,
    /**
     * Capability dependency invalidated due to cancellation or abandonment of supplier task.
     */
    DEPENDENCY_INVALIDATED,
    /** Announces an authenticated worker intent and atomically requested claims. */
    WORK_INTENT_ANNOUNCED,
    /** Releases all claims associated with an authenticated worker intent. */
    WORK_INTENT_RELEASED,
    /** Creates a signed coordination request between conflicting participants. */
    COORDINATION_REQUESTED,
    /** Records the target participant's coordination response. */
    COORDINATION_RESPONDED,
    /** Records verified activity for an authenticated participant. */
    PARTICIPANT_HEARTBEAT,
    /** Atomically transfers an intent after an accepted handoff request. */
    CLAIM_HANDOFF_ACCEPTED,
    /** Marks a participant abandoned after verified process absence beyond grace. */
    PARTICIPANT_ABANDONED,
    /** Publishes a stable contract revision. */
    CONTRACT_PUBLISHED,
    /** Binds an intent to an exact contract revision. */
    CONTRACT_DEPENDENCY_BOUND,
    /** Supersedes the current contract revision. */
    CONTRACT_SUPERSEDED,
    /** Creates a durable logical work group. */
    WORK_GROUP_CREATED,
    /** Issues a targeted lane join or continuation grant. */
    LANE_GRANT_ISSUED,
    /** Consumes a single-use lane grant. */
    LANE_GRANT_CONSUMED,
    /** Revokes a lane grant or lane authority epoch. */
    LANE_REVOKED,
    /** Changes logical work-group lifecycle without closing sibling lanes. */
    WORK_GROUP_STATUS_CHANGED,
    /** Fences a participant after verified process loss without releasing its claims. */
    PARTICIPANT_SUSPENDED,
    /** Records that an immutable recovery snapshot exists for a suspended participant. */
    RECOVERY_SNAPSHOT_HELD,
    /** Explicitly revokes a participant lane and releases its claims. */
    PARTICIPANT_REVOKED,
    /** Idempotently acknowledges a durable inbox item. */
    INBOX_ITEM_ACKNOWLEDGED,
    /** Explicitly cancels a participant lane and fences its epoch. */
    PARTICIPANT_CANCELLED,
    /** Atomically transfers a held recovery lane into a new participant lane. */
    LANE_CONTINUATION_ACCEPTED,
    /** Records a clean connection shutdown without treating the lane as completed. */
    PARTICIPANT_DETACHED,
    /** Pins a verified prepared lane tree before mutation authority is fenced. */
    COMPLETION_PREPARED,
    /** Records a structurally invalid immutable integration candidate. */
    INTEGRATION_BLOCKED,
    /** Records a valid immutable candidate materialized into a repair lane. */
    REPAIR_REQUIRED,
    /** Atomically transfers reserved selectors into a new repair lane. */
    REPAIR_LANE_CREATED,
    /** Unfences a prepared but unpublished completion at a new claim epoch. */
    COMPLETION_UNWOUND,
    /** Records an authenticated review decision for an immutable task snapshot. */
    REVIEW_VALIDATION_RECORDED;

    /** Returns the stable persisted wire code for this event kind.
     * @return wire code
     */
    public int wireCode() {
        return switch (this) {
            case WORK_INTENT_ANNOUNCED -> 43;
            case WORK_INTENT_RELEASED -> 44;
            case COORDINATION_REQUESTED -> 45;
            case COORDINATION_RESPONDED -> 46;
            case PARTICIPANT_HEARTBEAT -> 47;
            case CLAIM_HANDOFF_ACCEPTED -> 48;
            case PARTICIPANT_ABANDONED -> 49;
            case CONTRACT_PUBLISHED -> 50;
            case CONTRACT_DEPENDENCY_BOUND -> 51;
            case CONTRACT_SUPERSEDED -> 52;
            case WORK_GROUP_CREATED -> 53;
            case LANE_GRANT_ISSUED -> 54;
            case LANE_GRANT_CONSUMED -> 55;
            case LANE_REVOKED -> 56;
            case WORK_GROUP_STATUS_CHANGED -> 57;
            case PARTICIPANT_SUSPENDED -> 58;
            case RECOVERY_SNAPSHOT_HELD -> 59;
            case PARTICIPANT_REVOKED -> 60;
            case INBOX_ITEM_ACKNOWLEDGED -> 61;
            case PARTICIPANT_CANCELLED -> 62;
            case LANE_CONTINUATION_ACCEPTED -> 63;
            case PARTICIPANT_DETACHED -> 64;
            case COMPLETION_PREPARED -> 65;
            case INTEGRATION_BLOCKED -> 66;
            case REPAIR_REQUIRED -> 67;
            case REPAIR_LANE_CREATED -> 68;
            case COMPLETION_UNWOUND -> 69;
            case REVIEW_VALIDATION_RECORDED -> 70;
            default -> ordinal();
        };
    }

    /** Resolves a stable persisted wire code.
     * @param wireCode wire code
     * @return event type
     */
    public static PredictionEventType fromWireCode(int wireCode) {
        for (PredictionEventType type : values()) {
            if (type.wireCode() == wireCode) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown event wire code: " + wireCode);
    }
}
