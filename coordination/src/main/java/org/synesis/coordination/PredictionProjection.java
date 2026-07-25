package org.synesis.coordination;

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
                 INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED, SESSION_FINALIZED -> current;
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
                 INTEGRATION_COMMIT_CREATED, CONTROL_BRANCH_ADVANCED, TASK_INTEGRATED, SESSION_FINALIZED -> false;
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
