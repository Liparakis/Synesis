package org.synesis.workspace.agent;

import java.util.Locale;

/**
 * Public agent-facing operational status classifications.
 *
 * <p>Serialized values are stable lowercase strings exposed to agents and harnesses.
 * Internal diagnostic status details are handled separately.
 *
 * @since 1.0
 */
public enum AgentStatus {

    /**
     * Session is initialized or workspace is active and ready.
     */
    READY("ready"),

    /**
     * Operational action completed successfully.
     */
    COMPLETED("completed"),

    /**
     * Action is blocked by policy or protected path.
     */
    BLOCKED("blocked"),

    /**
     * Action requires capability owner contract details or clarification.
     */
    NEEDS_CAPABILITY("needs_capability"),

    /**
     * Action is waiting for background owner response or processing.
     */
    WAITING("waiting"),

    /**
     * Workspace or session context is stale or unverified; setup refresh required.
     */
    RETRY_REQUIRED("retry_required"),

    /**
     * Unrecoverable safety or system failure occurred.
     */
    FAILED("failed");

    private final String value;

    AgentStatus(String value) {
        this.value = value;
    }

    /**
     * Returns the stable lowercase JSON representation.
     *
     * @return lowercase string representation
     */
    public String value() {
        return value;
    }

    /**
     * Parses a string into an {@link AgentStatus}.
     *
     * @param input string value
     * @return matching status
     * @throws IllegalArgumentException if unknown
     */
    public static AgentStatus fromValue(String input) {
        if (input == null) {
            throw new IllegalArgumentException("status value cannot be null");
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (AgentStatus status : values()) {
            if (status.value.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown agent status: " + input);
    }
}
