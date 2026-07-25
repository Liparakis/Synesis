package org.synesis.coordination;

import java.util.Locale;
import java.util.Objects;

/**
 * Public lifecycle states for Stage 2B capability requests.
 *
 * <p>State machine for capability negotiation:
 * {@code AWAITING_OWNER} &rarr; {@code REVISION_REQUESTED} / {@code ACCEPTED} / {@code REJECTED} / {@code CANCELLED} / {@code EXPIRED} / {@code SUPERSEDED}.
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
    SUPERSEDED("superseded");

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
