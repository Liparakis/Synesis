package org.synesis.coordination.domain.capability;




import java.util.Locale;
import java.util.Objects;

/**
 * Public lifecycle states for Stage 2B capability requests.
 *
 * <p>State machine for capability negotiation:
 * {@code AWAITING_OWNER} &rarr; {@code REVISION_REQUESTED} / {@code ACCEPTED} / {@code REJECTED} / {@code CANCELLED} / {@code EXPIRED} / {@code SUPERSEDED}.
 * After acceptance:
 * {@code ACCEPTED} &rarr; {@code IMPLEMENTING} &rarr; {@code IMPLEMENTATION_AVAILABLE} &rarr; {@code VALIDATING} &rarr; {@code VALIDATED}.
 * Validation revision loop:
 * {@code VALIDATING} &rarr; {@code IMPLEMENTING}.
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

    CapabilityLifecycleState(String value) {
        this.value = value;
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
     * Parses a string into a {@link CapabilityLifecycleState}.
     *
     * @param input string representation
     * @return matching lifecycle state
     * @throws IllegalArgumentException if null or unknown
     */
    public static CapabilityLifecycleState fromValue(String input) {
        Objects.requireNonNull(input, "input state value cannot be null");
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (CapabilityLifecycleState state : values()) {
            if (state.value.equals(normalized)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown capability lifecycle state: " + input);
    }
}
