package org.synesis.coordination.domain.operator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.MissingReferenceException;

/**
 * Deterministic replay projection of operator requests.
 *
 * <p>Synesis keeps at most one open request per condition it raises itself. Several processes
 * observe the same condition at once, and without that rule each would raise its own request for
 * one stray file. The rule is judged on this computer's own raise only: every condition Synesis raises is about the
 * computer that raises it (its checkout, its integration pass), so a raise from another computer is another
 * request (ADR-0098 step 4, designer item 16).</p>
 *
 * <p>Answers are kept per writing node. A node's new answer replaces its own, and a request two nodes answered
 * differently stays open, so the result does not depend on which answer a computer saw first.</p>
 */
public final class OperatorRequestProjection {

    private final Map<String, OperatorRequest> requests = new LinkedHashMap<>();
    /** Each request's answers, by the node that wrote them; a request is closed while they agree. */
    private final Map<String, Map<String, String>> answers = new LinkedHashMap<>();
    private final ApplyMode mode;

    /**
     * Creates an empty replay projection.
     */
    public OperatorRequestProjection() {
        mode = ApplyMode.REPLAY;
    }

    private OperatorRequestProjection(OperatorRequestProjection source, ApplyMode mode) {
        requests.putAll(source.requests);
        source.answers.forEach((id, byNode) -> answers.put(id, new LinkedHashMap<>(byNode)));
        this.mode = mode;
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
            case OPERATOR_REQUEST_CLOSED -> close(OperatorRequest.decodeClosed(event.payload()), event.actorNodeId());
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
        validate(event, ApplyMode.CHECK);
    }

    /**
     * Applies an event to a copy of this projection, judged as {@code mode} says.
     *
     * @param event signed event
     * @param mode  how the event is judged
     * @throws IOException if the event violates the request invariants, or names a request not seen yet
     */
    public synchronized void validate(PredictionEvent event, ApplyMode mode) throws IOException {
        new OperatorRequestProjection(this, mode).apply(event);
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
        if (mode.checking() && request.raisedBySynesis() && open(request.condition()).isPresent()) {
            throw new CoordinationException("OPERATOR_REQUEST_ALREADY_OPEN");
        }
        requests.put(request.id(), request);
    }

    private void close(OperatorRequest.Closing closing, String answerer) throws IOException {
        OperatorRequest current = requests.get(closing.id());
        if (current == null) {
            // Raised in another computer's log that has not arrived.
            throw new MissingReferenceException("OPERATOR_REQUEST_NOT_OPEN");
        }
        if (mode.checking() && current.state() != OperatorRequest.State.OPEN) {
            throw new CoordinationException("OPERATOR_REQUEST_NOT_OPEN");
        }
        Map<String, String> given = answers.computeIfAbsent(current.id(), id -> new LinkedHashMap<>());
        given.put(answerer, closing.because());
        boolean agreed = given.values().stream().distinct().count() == 1;
        OperatorRequest open = current.state() == OperatorRequest.State.OPEN ? current : current.reopened();
        requests.put(current.id(), agreed ? open.closed(closing.because()) : open);
    }
}
