package org.synesis.coordination;

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
    CAPABILITY_REQUEST_SUPERSEDED
}
