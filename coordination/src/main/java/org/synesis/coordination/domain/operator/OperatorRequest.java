package org.synesis.coordination.domain.operator;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;

import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * A block that only someone with authority over the project can clear (ADR-0091).
 *
 * <p>The request says what is blocked and carries the evidence unchanged, so the operator does
 * not have to reconstruct it from an agent's account. It never says how the block was cleared:
 * a request raised by Synesis closes when Synesis observes that its condition no longer holds.</p>
 *
 * @param id            stable request identifier
 * @param condition     the condition that holds while the request is open
 * @param raisedBy      authority lineage of the lane that asked, or empty when Synesis raised the request itself
 * @param summary       what is blocked and what would clear it
 * @param evidence      the raw output of whatever refused, or empty
 * @param failurePoint  identifier of the local diagnostics entry, or empty
 * @param state         whether the request is still open
 * @param closedBecause what was observed when the request closed, or empty while open
 */
public record OperatorRequest(String id, String condition, String raisedBy, String summary, String evidence, String failurePoint, State state, String closedBecause) {

    /**
     * A control checkout holding changes Synesis did not make. Integration refuses such a
     * checkout: the changes may be the user's unsaved work, and the tree that was verified must
     * be the tree that is checked out.
     */
    public static final String CONTROL_CHECKOUT_FOREIGN_CHANGES = "control_checkout_foreign_changes";

    /**
     * A lane bound to another lane's contract owes an action, its lane is open, and its agent
     * has not returned. The owner's work is not integrated without it (ADR-0092).
     */
    public static final String BOUND_CONSUMER_ABSENT = "bound_consumer_absent";

    /**
     * A lane put something to the operator (ADR-0093). Nothing is blocked by it: it changes how a
     * run that stalls is reported, and it closes when the operator says it is dealt with, since
     * Synesis cannot observe that a question was answered.
     */
    public static final String LANE_ASKS_OPERATOR = "lane_asks_operator";

    /**
     * The project's validation command fails on the work waiting to be integrated and no lane is
     * left to answer for it: the command timed out or could not be started, or the pass that ran
     * it died twice, which says nothing about anybody's work, or a lane with a snapshot in the
     * attempt has used its repairs. Nothing integrates while it is open, and the command is not
     * run again on every poll.
     */
    public static final String INTEGRATION_VALIDATION_FAILING = "integration_validation_failing";

    private static final int MAGIC_RAISED = 0x534f5231;
    private static final int MAGIC_CLOSED = 0x534f5232;
    private static final int MAX_TEXT_BYTES = 8 * 1024;
    private static final WireFormat TEXT = WireFormat.bounded(MAX_TEXT_BYTES, true, "operator request text bound", "truncated operator request").checkingWrites(MAX_TEXT_BYTES, "operator request text bound");

    /**
     * Validates the request.
     */
    public OperatorRequest {
        requireText(id, "id");
        requireText(condition, "condition");
        requireText(summary, "summary");
        CoordinationNullException.requireNonNull(raisedBy, "raisedBy");
        CoordinationNullException.requireNonNull(evidence, "evidence");
        CoordinationNullException.requireNonNull(failurePoint, "failurePoint");
        CoordinationNullException.requireNonNull(state, "state");
        CoordinationNullException.requireNonNull(closedBecause, "closedBecause");
    }

    /**
     * Creates an open request raised by Synesis.
     *
     * @param id        request identifier
     * @param condition condition that holds
     * @param summary   what is blocked and what would clear it
     * @param evidence  raw output of whatever refused
     * @return the open request
     */
    public static OperatorRequest raised(String id, String condition, String summary, String evidence) {
        return new OperatorRequest(id, condition, "", summary, bounded(evidence), "", State.OPEN, "");
    }

    /**
     * Creates an open request a lane put to the operator.
     *
     * @param id      request identifier
     * @param lineage authority lineage of the lane that asks
     * @param text    what the lane says
     * @param context who holds the lane and what it claims, for the operator to place it
     * @return the open request
     */
    public static OperatorRequest askedByLane(String id, String lineage, String text, String context) {
        requireText(lineage, "lane");
        return new OperatorRequest(id, LANE_ASKS_OPERATOR, lineage, text, bounded(context), "", State.OPEN, "");
    }

    /**
     * Tells whether Synesis raised this request itself, as opposed to a participant asking.
     *
     * @return true when no participant is named as having raised it
     */
    public boolean raisedBySynesis() {
        return raisedBy.isEmpty();
    }

    /**
     * Encodes the raising of this request.
     *
     * @return canonical event payload
     */
    public byte[] encodeRaised() {
        return WireFormat.encode(MAGIC_RAISED, out -> {
            TEXT.writeText(out, id);
            TEXT.writeText(out, condition);
            TEXT.writeText(out, raisedBy);
            TEXT.writeText(out, summary);
            TEXT.writeText(out, evidence);
            TEXT.writeText(out, failurePoint);
        });
    }

    /**
     * Decodes the raising of a request.
     *
     * @param encoded event payload
     * @return the open request
     * @throws IOException when the payload is malformed
     */
    public static OperatorRequest decodeRaised(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_RAISED) {
                throw new CoordinationException("operator request format");
            }
            OperatorRequest request = new OperatorRequest(TEXT.readText(in), TEXT.readText(in), TEXT.readText(in), TEXT.readText(in), TEXT.readText(in), TEXT.readText(in), State.OPEN, "");
            if (in.available() != 0) {
                throw new CoordinationException("trailing operator request bytes");
            }
            return request;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed operator request", failure);
        }
    }

    /**
     * Encodes the closing of one request.
     *
     * @param id      request identifier
     * @param because what was observed
     * @return canonical event payload
     */
    public static byte[] encodeClosed(String id, String because) {
        return WireFormat.encode(MAGIC_CLOSED, out -> {
            TEXT.writeText(out, id);
            TEXT.writeText(out, because);
        });
    }

    /**
     * Decodes the closing of one request.
     *
     * @param encoded event payload
     * @return the request identifier and what was observed
     * @throws IOException when the payload is malformed
     */
    static Closing decodeClosed(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC_CLOSED) {
                throw new CoordinationException("operator request format");
            }
            Closing closing = new Closing(TEXT.readText(in), TEXT.readText(in));
            if (in.available() != 0) {
                throw new CoordinationException("trailing operator request bytes");
            }
            return closing;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed operator request", failure);
        }
    }

    /**
     * Reads which request a closing event closes.
     *
     * @param encoded payload of the closing event
     * @return the identifier of the request it closes
     * @throws IOException when the payload is malformed
     */
    public static String closedRequestId(byte[] encoded) throws IOException {
        return decodeClosed(encoded).id();
    }

    /**
     * Returns this request showing other evidence. The condition is the same; what proves it has
     * changed since the request was raised.
     *
     * @param current the refusing tool's output now
     * @return the request with that evidence
     */
    public OperatorRequest withEvidence(String current) {
        return new OperatorRequest(id, condition, raisedBy, summary, bounded(current), failurePoint, state, closedBecause);
    }

    /**
     * Returns this request naming where the failure it reports was recorded.
     *
     * @param point identifier of the local diagnostics entry, or empty when none was written
     * @return the request with that failure point
     */
    public OperatorRequest withFailurePoint(String point) {
        return new OperatorRequest(id, condition, raisedBy, summary, evidence, point, state, closedBecause);
    }

    OperatorRequest closed(String because) {
        return new OperatorRequest(id, condition, raisedBy, summary, evidence, failurePoint, State.CLOSED, because);
    }

    /** The request open again: two computers answered it differently (ADR-0100 row 6). */
    OperatorRequest reopened() {
        return new OperatorRequest(id, condition, raisedBy, summary, evidence, failurePoint, State.OPEN, "");
    }

    /** Keeps evidence within the wire bound by its bytes, since tool output is not ours to size. */
    private static String bounded(String text) {
        String value = text == null ? "" : text;
        while (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            value = value.substring(0, value.length() / 2);
        }
        return value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new CoordinationArgumentException("operator request " + name + " is required");
        }
    }

    /** Whether a request still stands. */
    public enum State {
        /** The condition holds, or nobody has yet observed otherwise. */
        OPEN,
        /** The condition was observed not to hold. */
        CLOSED
    }

    /**
     * The closing of one request.
     *
     * @param id      request identifier
     * @param because what was observed
     */
    record Closing(String id, String because) {
    }
}
