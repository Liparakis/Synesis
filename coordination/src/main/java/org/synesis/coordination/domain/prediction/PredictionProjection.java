package org.synesis.coordination.domain.prediction;


import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;

/**
 * Deterministic in-memory projection of prediction events.
 */
public final class PredictionProjection {

    private final Map<PredictionId, PredictionState> states = new LinkedHashMap<>();

    /**
     * Creates an empty projection.
     */
    public PredictionProjection() {
    }

    /**
     * Selects the transition table for the prediction's current lifecycle state.
     */
    private static PredictionState transition(PredictionState current, PredictionEventType type) {
        return current == null ? transitionBeforeCreation(type) : transitionAfterCreation(current, type);
    }

    /**
     * Handles lifecycle events arriving before this projection has a prediction creation event.
     */
    private static PredictionState transitionBeforeCreation(PredictionEventType type) {
        if (type == PredictionEventType.PREDICTION_CREATED) {
            return PredictionState.PROPOSED;
        }
        throw new CoordinationStateException("prediction must be created first");
    }

    /**
     * Applies an event to an already-created prediction lifecycle.
     */
    private static PredictionState transitionAfterCreation(PredictionState current, PredictionEventType type) {
        return switch (type) {
            case PREDICTION_CREATED -> invalid(current, type);
            case PREDICTION_ROUTED -> require(current, PredictionState.ROUTED, PredictionState.PROPOSED);
            case REQUEST_RECEIVED -> require(current, PredictionState.RECEIVED, PredictionState.ROUTED);
            case ACCEPTED_EXACT -> require(current, PredictionState.ACCEPTED_EXACT, PredictionState.RECEIVED);
            case ACCEPTED_EQUIVALENT -> require(current, PredictionState.ACCEPTED_EQUIVALENT, PredictionState.RECEIVED);
            case CONTRACT_REVISED -> require(current, PredictionState.REVISED, PredictionState.RECEIVED);
            case IMPLEMENTATION_STARTED -> require(current,
                    PredictionState.IMPLEMENTING,
                    PredictionState.ACCEPTED_EXACT,
                    PredictionState.ACCEPTED_EQUIVALENT,
                    PredictionState.REVISED);
            case PATCH_READY -> require(current, PredictionState.PATCH_READY, PredictionState.IMPLEMENTING);
            case CAPABILITY_AVAILABLE -> require(current, PredictionState.AVAILABLE, PredictionState.PATCH_READY);
            case VALIDATION_STARTED -> require(current, PredictionState.VALIDATING, PredictionState.AVAILABLE);
            case SPECULATION_RETIRED -> require(current, PredictionState.RETIRED, PredictionState.VALIDATING);
            case PREDICTION_INVALIDATED -> requireNonTerminal(current, PredictionState.INVALIDATED);
            case REQUEST_REJECTED -> requireNonTerminal(current, PredictionState.REJECTED);
            case PREDICTION_EXPIRED -> requireNonTerminal(current, PredictionState.EXPIRED);
            default -> current;
        };
    }

    /**
     * Checks that the type value satisfies this type's bounds.
     *
     * @param current fresh authoritative project projection
     */
    private static PredictionState require(PredictionState current,
            PredictionState result,
            PredictionState... allowed) {
        if (Arrays.asList(allowed).contains(current)) {
            return result;
        }
        throw new CoordinationStateException("invalid transition from " + current);
    }

    /**
     * Checks that the non terminal value satisfies this type's bounds.
     *
     * @param current fresh authoritative project projection
     */
    private static PredictionState requireNonTerminal(PredictionState current, PredictionState result) {
        if (current == PredictionState.RETIRED || current == PredictionState.INVALIDATED
                || current == PredictionState.REJECTED || current == PredictionState.EXPIRED) {
            throw new CoordinationStateException("terminal prediction cannot transition");
        }
        return result;
    }

    /**
     * Rejects a second creation event for a prediction that already has state.
     *
     * @param current fresh authoritative project projection
     */
    private static PredictionState invalid(PredictionState current, PredictionEventType type) {
        throw new CoordinationStateException("duplicate creation from " + current + " via " + type);
    }

    /**
     * Applies one event and rejects illegal lifecycle transitions.
     *
     * @param event event to apply
     */
    public synchronized void apply(PredictionEvent event) {
        CoordinationNullException.requireNonNull(event, "event");
        PredictionState next = validate(event);
        if (next != null) {
            states.put(event.predictionIdentity(), next);
        }
    }

    /**
     * Validates an event against the current state without mutating the projection.
     *
     * @param event event to validate
     * @return resulting state if the event were applied, or {@code null} for an event outside the prediction
     *         lifecycle
     */
    public synchronized PredictionState validate(PredictionEvent event) {
        CoordinationNullException.requireNonNull(event, "event");
        if (!event.type().isPredictionLifecycle()) {
            return null;
        }
        return transition(states.get(event.predictionIdentity()), event.type());
    }

    /**
     * Returns the current state, or empty when no creation event has arrived.
     *
     * @param predictionId prediction identifier
     * @return current state when known
     */
    public synchronized Optional<PredictionState> state(UUID predictionId) {
        return predictionId == null ? Optional.empty() : state(new PredictionId(predictionId));
    }

    /**
     * Returns the current state for a typed prediction identity.
     *
     * @param predictionId prediction identity
     * @return current state when known
     */
    public synchronized Optional<PredictionState> state(PredictionId predictionId) {
        return Optional.ofNullable(states.get(CoordinationNullException.requireNonNull(predictionId, "prediction ID")));
    }

    /**
     * Returns a stable snapshot of all known prediction states.
     *
     * @return immutable state snapshot
     */
    public synchronized Map<UUID, PredictionState> snapshot() {
        Map<UUID, PredictionState> snapshot = new LinkedHashMap<>();
        states.forEach((id, state) -> snapshot.put(id.value(), state));
        return Map.copyOf(snapshot);
    }

}
