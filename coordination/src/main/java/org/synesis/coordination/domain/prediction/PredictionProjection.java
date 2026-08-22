package org.synesis.coordination.domain.prediction;




import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic in-memory projection of prediction events.
 */
public final class PredictionProjection {

    private final Map<UUID, PredictionState> states = new LinkedHashMap<>();

    /**
     * Creates an empty projection.
     */
    public PredictionProjection() {
    }

    private static PredictionState transition(PredictionState current, PredictionEventType type) {
        if (current == null) {
            if (type == PredictionEventType.PREDICTION_CREATED) {
                return PredictionState.PROPOSED;
            }
            if (type == PredictionEventType.TASK_CREATED || type == PredictionEventType.TASK_CLAIMED
                    || type == PredictionEventType.TASK_RELEASED || type == PredictionEventType.OWNERSHIP_CLAIMED
                    || type == PredictionEventType.OWNERSHIP_RELEASED || type == PredictionEventType.CAPABILITY_REQUEST_CREATED
                    || type == PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED || type == PredictionEventType.CAPABILITY_REQUEST_ACCEPTED
                    || type == PredictionEventType.CAPABILITY_REQUEST_REJECTED || type == PredictionEventType.CAPABILITY_REQUEST_CANCELLED
                    || type == PredictionEventType.CAPABILITY_REQUEST_SUPERSEDED || type == PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED
                    || type == PredictionEventType.CAPABILITY_VALIDATION_STARTED || type == PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED
                    || type == PredictionEventType.CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED || type == PredictionEventType.TASK_COMPLETION_REQUESTED
                    || type == PredictionEventType.TASK_SNAPSHOT_CREATED || type == PredictionEventType.TASK_WAITING_FOR_DEPENDENCIES
                    || type == PredictionEventType.INTEGRATION_ATTEMPT_STARTED || type == PredictionEventType.INTEGRATION_ATTEMPT_FAILED
                    || type == PredictionEventType.INTEGRATION_CONFLICTED || type == PredictionEventType.INTEGRATION_COMMIT_CREATED
                    || type == PredictionEventType.CONTROL_BRANCH_ADVANCED || type == PredictionEventType.TASK_INTEGRATED
                    || type == PredictionEventType.SESSION_FINALIZED || type == PredictionEventType.SESSION_ABANDONED
                    || type == PredictionEventType.TASK_CANCELLATION_REQUESTED || type == PredictionEventType.TASK_CANCELLED
                    || type == PredictionEventType.WORK_INTENT_ANNOUNCED || type == PredictionEventType.WORK_INTENT_RELEASED
                    || type == PredictionEventType.COORDINATION_REQUESTED || type == PredictionEventType.COORDINATION_RESPONDED
                    || type == PredictionEventType.PARTICIPANT_HEARTBEAT || type == PredictionEventType.CLAIM_HANDOFF_ACCEPTED
                    || type == PredictionEventType.PARTICIPANT_ABANDONED
                    || type == PredictionEventType.CONTRACT_PUBLISHED || type == PredictionEventType.CONTRACT_DEPENDENCY_BOUND
                    || type == PredictionEventType.CONTRACT_SUPERSEDED
                    || type == PredictionEventType.WORK_GROUP_CREATED || type == PredictionEventType.LANE_GRANT_ISSUED
                    || type == PredictionEventType.LANE_GRANT_CONSUMED || type == PredictionEventType.LANE_REVOKED
                    || type == PredictionEventType.WORK_GROUP_STATUS_CHANGED
                    || type == PredictionEventType.PARTICIPANT_SUSPENDED
                    || type == PredictionEventType.RECOVERY_SNAPSHOT_HELD
                    || type == PredictionEventType.PARTICIPANT_REVOKED
                    || type == PredictionEventType.INBOX_ITEM_ACKNOWLEDGED
                    || type == PredictionEventType.PARTICIPANT_CANCELLED
                    || type == PredictionEventType.LANE_CONTINUATION_ACCEPTED
                    || type == PredictionEventType.PARTICIPANT_DETACHED
                    || type == PredictionEventType.COMPLETION_PREPARED
                    || type == PredictionEventType.INTEGRATION_BLOCKED
                    || type == PredictionEventType.REPAIR_REQUIRED
                    || type == PredictionEventType.REPAIR_LANE_CREATED
                    || type == PredictionEventType.COMPLETION_UNWOUND
                    || type == PredictionEventType.REVIEW_VALIDATION_RECORDED
                    || type == PredictionEventType.DEPENDENCY_INVALIDATED) {
                return null;
            }
            throw new IllegalStateException("prediction must be created first");
        }
        return switch (type) {
            case PREDICTION_CREATED -> invalid(current, type);
            case PREDICTION_ROUTED -> require(current, PredictionState.ROUTED, PredictionState.PROPOSED);
            case REQUEST_RECEIVED -> require(current, PredictionState.RECEIVED, PredictionState.ROUTED);
            case ACCEPTED_EXACT -> require(current, PredictionState.ACCEPTED_EXACT, PredictionState.RECEIVED);
            case ACCEPTED_EQUIVALENT -> require(current, PredictionState.ACCEPTED_EQUIVALENT, PredictionState.RECEIVED);
            case CONTRACT_REVISED -> require(current, PredictionState.REVISED, PredictionState.RECEIVED);
            case IMPLEMENTATION_STARTED -> require(current, PredictionState.IMPLEMENTING,
                    PredictionState.ACCEPTED_EXACT, PredictionState.ACCEPTED_EQUIVALENT, PredictionState.REVISED);
            case PATCH_READY -> require(current, PredictionState.PATCH_READY, PredictionState.IMPLEMENTING);
            case CAPABILITY_AVAILABLE -> require(current, PredictionState.AVAILABLE, PredictionState.PATCH_READY);
            case VALIDATION_STARTED -> require(current, PredictionState.VALIDATING, PredictionState.AVAILABLE);
            case SPECULATION_RETIRED -> require(current, PredictionState.RETIRED, PredictionState.VALIDATING);
            case PREDICTION_INVALIDATED -> requireNonTerminal(current, PredictionState.INVALIDATED);
            case REQUEST_REJECTED -> requireNonTerminal(current, PredictionState.REJECTED);
            case PREDICTION_EXPIRED -> requireNonTerminal(current, PredictionState.EXPIRED);
            case TASK_CREATED, TASK_CLAIMED, TASK_RELEASED, OWNERSHIP_CLAIMED, OWNERSHIP_RELEASED,
                 CAPABILITY_REQUEST_CREATED, CAPABILITY_REQUEST_CONTRACT_REVISED, CAPABILITY_REQUEST_ACCEPTED,
                 CAPABILITY_REQUEST_REJECTED, CAPABILITY_REQUEST_CANCELLED, CAPABILITY_REQUEST_SUPERSEDED,
                 CAPABILITY_IMPLEMENTATION_PUBLISHED, CAPABILITY_VALIDATION_STARTED,
                 CAPABILITY_IMPLEMENTATION_VALIDATED, CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
                 TASK_COMPLETION_REQUESTED, TASK_SNAPSHOT_CREATED, TASK_WAITING_FOR_DEPENDENCIES,
                 INTEGRATION_ATTEMPT_STARTED, INTEGRATION_ATTEMPT_FAILED, INTEGRATION_CONFLICTED,
                 INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED, SESSION_FINALIZED,
                 SESSION_ABANDONED, TASK_CANCELLATION_REQUESTED, TASK_CANCELLED,
                 WORK_INTENT_ANNOUNCED, WORK_INTENT_RELEASED, COORDINATION_REQUESTED,
                 COORDINATION_RESPONDED, PARTICIPANT_HEARTBEAT, CLAIM_HANDOFF_ACCEPTED,
                 PARTICIPANT_ABANDONED,
                 CONTRACT_PUBLISHED, CONTRACT_DEPENDENCY_BOUND, CONTRACT_SUPERSEDED,
                 DEPENDENCY_INVALIDATED, WORK_GROUP_CREATED, LANE_GRANT_ISSUED,
                 LANE_GRANT_CONSUMED, LANE_REVOKED, WORK_GROUP_STATUS_CHANGED,
                 PARTICIPANT_SUSPENDED, RECOVERY_SNAPSHOT_HELD, PARTICIPANT_REVOKED,
                 INBOX_ITEM_ACKNOWLEDGED, PARTICIPANT_CANCELLED, LANE_CONTINUATION_ACCEPTED,
                 PARTICIPANT_DETACHED, COMPLETION_PREPARED, INTEGRATION_BLOCKED,
                 REPAIR_REQUIRED, REPAIR_LANE_CREATED, COMPLETION_UNWOUND,
                 REVIEW_VALIDATION_RECORDED -> current;
        };
    }

    private static PredictionState require(PredictionState current, PredictionState result,
            PredictionState... allowed) {
        if (java.util.Arrays.asList(allowed)
                .contains(current)) {
            return result;
        }
        throw new IllegalStateException("invalid transition from " + current);
    }

    private static PredictionState requireNonTerminal(PredictionState current, PredictionState result) {
        if (current == PredictionState.RETIRED || current == PredictionState.INVALIDATED
                || current == PredictionState.REJECTED || current == PredictionState.EXPIRED) {
            throw new IllegalStateException("terminal prediction cannot transition");
        }
        return result;
    }

    private static PredictionState invalid(PredictionState current, PredictionEventType type) {
        throw new IllegalStateException("duplicate creation from " + current + " via " + type);
    }

    private static boolean isPredictionEvent(PredictionEventType type) {
        return switch (type) {
            case TASK_CREATED, TASK_CLAIMED, TASK_RELEASED, OWNERSHIP_CLAIMED, OWNERSHIP_RELEASED,
                 CAPABILITY_REQUEST_CREATED, CAPABILITY_REQUEST_CONTRACT_REVISED, CAPABILITY_REQUEST_ACCEPTED,
                 CAPABILITY_REQUEST_REJECTED, CAPABILITY_REQUEST_CANCELLED, CAPABILITY_REQUEST_SUPERSEDED,
                 CAPABILITY_IMPLEMENTATION_PUBLISHED, CAPABILITY_VALIDATION_STARTED,
                 CAPABILITY_IMPLEMENTATION_VALIDATED, CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED,
                 TASK_COMPLETION_REQUESTED, TASK_SNAPSHOT_CREATED, TASK_WAITING_FOR_DEPENDENCIES,
                 INTEGRATION_ATTEMPT_STARTED, INTEGRATION_ATTEMPT_FAILED, INTEGRATION_CONFLICTED,
                 INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED, SESSION_FINALIZED,
                 COMPLETION_PREPARED, INTEGRATION_BLOCKED, REPAIR_REQUIRED, REPAIR_LANE_CREATED,
                 COMPLETION_UNWOUND, REVIEW_VALIDATION_RECORDED -> false;
            default -> true;
        };
    }

    /**
     * Applies one event and rejects illegal lifecycle transitions.
     *
     * @param event event to apply
     */
    public synchronized void apply(PredictionEvent event) {
        Objects.requireNonNull(event, "event");
        if (!isPredictionEvent(event.type())) {
            return;
        }
        PredictionState next = validate(event);
        states.put(event.predictionId(), next);
    }

    /**
     * Validates an event against the current state without mutating the projection.
     *
     * @param event event to validate
     * @return resulting state if the event were applied
     */
    public synchronized PredictionState validate(PredictionEvent event) {
        Objects.requireNonNull(event, "event");
        if (!isPredictionEvent(event.type())) {
            return null;
        }
        return transition(states.get(event.predictionId()), event.type());
    }

    /**
     * Returns the current state, or empty when no creation event has arrived.
     *
     * @param predictionId prediction identifier
     * @return current state when known
     */
    public synchronized java.util.Optional<PredictionState> state(UUID predictionId) {
        return java.util.Optional.ofNullable(states.get(predictionId));
    }

    /**
     * Returns a stable snapshot of all known prediction states.
     *
     * @return immutable state snapshot
     */
    public synchronized Map<UUID, PredictionState> snapshot() {
        return Map.copyOf(states);
    }
}
