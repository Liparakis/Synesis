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
    PARTICIPANT_ABANDONED;

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
