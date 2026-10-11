package org.synesis.coordination.domain.operator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Deterministic replay projection of operator requests.
 *
 * <p>Synesis keeps at most one open request per condition it raises itself. Several processes
 * observe the same condition at once, and without that rule each would raise its own request for
 * one stray file.</p>
 */
public final class OperatorRequestProjection {

    private final Map<String, OperatorRequest> requests = new LinkedHashMap<>();

    /**
     * Creates an empty replay projection.
     */
    public OperatorRequestProjection() {
    }

    private OperatorRequestProjection(OperatorRequestProjection source) {
        requests.putAll(source.requests);
    }

    /**
     * Applies an operator request event.
     *
     * @param event signed event
     * @throws IOException if the event violates the request invariants
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        CoordinationNullException.requireNonNull(event, "event");
        switch (event.type()) {
            case OPERATOR_REQUEST_RAISED -> raise(OperatorRequest.decodeRaised(event.payload()));
            case OPERATOR_REQUEST_CLOSED -> close(OperatorRequest.decodeClosed(event.payload()));
            default -> {
            }
        }
    }

    /**
     * Validates an operator request event without mutating this projection.
     *
     * @param event signed event
     * @throws IOException if the event violates the request invariants
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        new OperatorRequestProjection(this).apply(event);
    }

    /**
     * Returns every request still open, oldest first.
     *
     * @return immutable open requests
     */
    public synchronized List<OperatorRequest> open() {
        return requests.values().stream().filter(request -> request.state() == OperatorRequest.State.OPEN).toList();
    }

    /**
     * Returns the open request Synesis raised for one condition.
     *
     * @param condition condition name
     * @return the open request, or empty when the condition has none
     */
    public synchronized Optional<OperatorRequest> open(String condition) {
        return requests.values().stream()
                .filter(request -> request.state() == OperatorRequest.State.OPEN && request.raisedBySynesis() && request.condition().equals(condition))
                .findFirst();
    }

    /**
     * Returns every request, open and closed, oldest first.
     *
     * @return immutable request history
     */
    public synchronized List<OperatorRequest> all() {
        return List.copyOf(requests.values());
    }

    private void raise(OperatorRequest request) throws IOException {
        if (requests.containsKey(request.id())) {
            throw new CoordinationException("OPERATOR_REQUEST_EXISTS");
        }
        if (request.raisedBySynesis() && open(request.condition()).isPresent()) {
            throw new CoordinationException("OPERATOR_REQUEST_ALREADY_OPEN");
        }
        requests.put(request.id(), request);
    }

    private void close(OperatorRequest.Closing closing) throws IOException {
        OperatorRequest current = requests.get(closing.id());
        if (current == null || current.state() != OperatorRequest.State.OPEN) {
            throw new CoordinationException("OPERATOR_REQUEST_NOT_OPEN");
        }
        requests.put(current.id(), current.closed(closing.because()));
    }
}
