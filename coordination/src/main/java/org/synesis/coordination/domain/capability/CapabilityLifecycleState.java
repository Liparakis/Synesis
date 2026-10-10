package org.synesis.coordination.domain.capability;


import java.util.Locale;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Public lifecycle states for Stage 2B capability requests.
 *
 * <p>State machine for capability negotiation:
 * {@code AWAITING_OWNER} &rarr; {@code REVISION_REQUESTED} / {@code ACCEPTED} / {@code REJECTED} / {@code CANCELLED} /
 * {@code EXPIRED} /
 * {@code SUPERSEDED}. After acceptance: {@code ACCEPTED} &rarr; {@code IMPLEMENTING} &rarr;
 * {@code IMPLEMENTATION_AVAILABLE} &rarr;
 * {@code VALIDATING} &rarr; {@code VALIDATED}. Validation revision loop: {@code VALIDATING} &rarr;
 * {@code IMPLEMENTING}.
 *
 * @since 1.0
 */
public enum CapabilityLifecycleState {

    /**
     * Request has been published and is awaiting owner review or response.
     */
    AWAITING_OWNER("awaiting_owner"),

    /**
     * Owner has requested a revision to the capability contract.
     */
    REVISION_REQUESTED("revision_requested"),

    /**
     * Owner has accepted the capability contract.
     */
    ACCEPTED("accepted"),

    /**
     * Owner has rejected the capability request.
     */
    REJECTED("rejected"),

    /**
     * Original requester has cancelled the capability request.
     */
    CANCELLED("cancelled"),

    /**
     * Capability request has expired without owner resolution.
     */
    EXPIRED("expired"),

    /**
     * Capability request has been superseded by a newer request revision.
     */
    SUPERSEDED("superseded"),

    /**
     * Owner is implementing the accepted capability.
     */
    IMPLEMENTING("implementing"),

    /**
     * Owner has published an immutable implementation snapshot; awaiting requester validation.
     */
    IMPLEMENTATION_AVAILABLE("implementation_available"),

    /**
     * Requester is actively validating the implementation snapshot.
     */
    VALIDATING("validating"),

    /**
     * Requester has accepted the implementation; capability is fully validated.
     */
    VALIDATED("validated");

    private final String value;

    /**
     * Validates and initializes this capability lifecycle state.
     */
    CapabilityLifecycleState(String value) {
        this.value = value;
    }

    /**
     * Parses a string into a {@link CapabilityLifecycleState}.
     *
     * @param input string representation
     * @return matching lifecycle state
     * @throws IllegalArgumentException if null or unknown
     */
    public static CapabilityLifecycleState fromValue(String input) {
        CoordinationNullException.requireNonNull(input, "input state value cannot be null");
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (CapabilityLifecycleState state : values()) {
            if (state.value.equals(normalized)) {
                return state;
            }
        }
        throw new CoordinationArgumentException("Unknown capability lifecycle state: " + input);
    }

    /**
     * Returns the stable lowercase string representation of this state.
     *
     * @return lowercase string value
     */
    public String value() {
        return value;
    }

    /**
     * Tells whether the owner and requester are still negotiating the contract, so it may be revised, accepted
     * or cancelled.
     *
     * @return true for {@link #AWAITING_OWNER} and {@link #REVISION_REQUESTED}
     */
    public boolean isUnderNegotiation() {
        return this == AWAITING_OWNER || this == REVISION_REQUESTED;
    }

    /**
     * Tells whether the owner may publish an implementation revision.
     *
     * @return true for {@link #ACCEPTED} and {@link #IMPLEMENTING}
     */
    public boolean acceptsImplementation() {
        return this == ACCEPTED || this == IMPLEMENTING;
    }

    /**
     * Tells whether a request still obliges one of its parties: negotiation, acceptance, implementation or
     * validation has not ended.
     *
     * @return true until the request is rejected, cancelled, expired, superseded or validated
     */
    public boolean hasOpenObligation() {
        return isUnderNegotiation() || this == ACCEPTED || this == IMPLEMENTING || this == IMPLEMENTATION_AVAILABLE
                || this == VALIDATING;
    }

    /**
     * Tells whether a request still binds the lanes it names, and so must move with a lane that changes hands.
     *
     * <p>A validated request owes no further negotiation, but both lanes still act on it: the owner's lane must
     * publish the snapshot integration waits for, and the requester's lane may only finish because its dependency
     * is validated. Leaving it with the predecessor would show the successor owner nothing to complete and the
     * successor requester a dependency it apparently never requested. Requests that ended without an agreement
     * bind nobody.</p>
     *
     * @return true when the request has an open obligation or is validated
     */
    public boolean bindsLanes() {
        return hasOpenObligation() || this == VALIDATED;
    }

    /**
     * Tells whether the requester has something to read or act on in a request.
     *
     * @return true for the open-obligation states and {@link #REJECTED}
     */
    public boolean isActionableForRequester() {
        return hasOpenObligation() || this == REJECTED;
    }
}
