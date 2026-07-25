package org.synesis.workspace.agent;

import java.util.Locale;

/**
 * Public agent-facing recommended next action indicators.
 *
 * <p>Instructs the agent on the appropriate recovery step when an operation does
 * not complete immediately.
 *
 * @since 1.0
 */
public enum AgentNextAction {

    /**
     * Re-run session bootstrap or session refresh before proceeding.
     */
    ENSURE_SESSION("ensure_session"),

    /**
     * Provide input/output/behavior contract specifications for requested capability.
     */
    DESCRIBE_REQUIRED_CAPABILITY("describe_required_capability"),

    /**
     * Wait for background owner response or pending event.
     */
    WAIT("wait"),

    /**
     * Retry the operation after refreshing session context.
     */
    RETRY("retry"),

    /**
     * Stop operation and request human intervention.
     */
    REQUEST_HUMAN_HELP("request_human_help"),

    /**
     * Respond to pending capability request from another worker as the capability owner.
     */
    RESPOND_TO_OWNER_REQUEST("respond_to_owner_request"),

    /**
     * Revise capability request contract in response to owner feedback.
     */
    REVISE_CAPABILITY_REQUEST("revise_capability_request");

    private final String value;

    AgentNextAction(String value) {
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
     * Parses a string into an {@link AgentNextAction}.
     *
     * @param input string value
     * @return matching next action
     * @throws IllegalArgumentException if unknown
     */
    public static AgentNextAction fromValue(String input) {
        if (input == null) {
            throw new IllegalArgumentException("nextAction value cannot be null");
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (AgentNextAction action : values()) {
            if (action.value.equals(normalized)) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown agent next action: " + input);
    }
}
