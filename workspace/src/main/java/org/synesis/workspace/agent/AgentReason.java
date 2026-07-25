package org.synesis.workspace.agent;

import java.util.Locale;

/**
 * Public agent-facing reason codes.
 *
 * <p>Exposes safe, stable reason codes for non-successful agent outcomes without
 * leaking internal exception messages, stack traces, or internal protocol enums.
 *
 * @since 1.0
 */
public enum AgentReason {

    /**
     * Target path is a protected configuration or system file.
     */
    PROTECTED_CONFIGURATION("protected_configuration"),

    /**
     * Operation is denied by active workspace policy rules.
     */
    POLICY_DENIED("policy_denied"),

    /**
     * Capability is owned by another worker and requires contract specification.
     */
    OWNER_REQUIRED("owner_required"),

    /**
     * Workspace trust or worktree state is unverified.
     */
    WORKSPACE_NOT_READY("workspace_not_ready"),

    /**
     * Provider session is unbound or missing.
     */
    SESSION_NOT_READY("session_not_ready"),

    /**
     * Workspace context is stale or out of sync.
     */
    WORKSPACE_STALE("workspace_stale"),

    /**
     * Requested path or target specification is invalid.
     */
    INVALID_PATH("invalid_path"),

    /**
     * Hook interception is required but missing or synthetic.
     */
    INTERCEPTION_REQUIRED("interception_required"),

    /**
     * Response from capability owner is pending.
     */
    OWNER_RESPONSE_PENDING("owner_response_pending"),

    /**
     * An internal system or safety failure occurred.
     */
    INTERNAL_FAILURE("internal_failure"),

    /**
     * Project command failed with a non-zero exit code.
     */
    COMMAND_FAILED("command_failed"),

    /**
     * Project command execution exceeded the maximum allowed time limit.
     */
    COMMAND_TIMEOUT("command_timeout"),

    /**
     * Requested tool or command adapter is not available or supported for this project.
     */
    TOOL_UNAVAILABLE("tool_unavailable"),

    /**
     * Dependency or speculative assumption has been invalidated.
     */
    DEPENDENCY_INVALIDATED("dependency_invalidated"),

    /**
     * Request from another worker requiring owner response.
     */
    OWNER_REQUEST_PENDING("owner_request_pending"),

    /**
     * Implementation is available and validation is required.
     */
    VALIDATION_REQUIRED("validation_required"),

    /**
     * Target capability request was not found.
     */
    REQUEST_NOT_FOUND("request_not_found"),

    /**
     * Capability request or revision state is stale.
     */
    STALE_REQUEST("stale_request"),

    /**
     * Capability request was rejected by the owner.
     */
    CAPABILITY_REJECTED("capability_rejected"),

    /**
     * Contract revision is required by the owner.
     */
    REVISION_REQUIRED("revision_required"),

    /**
     * Implementation snapshot is not yet published or available.
     */
    IMPLEMENTATION_UNAVAILABLE("implementation_unavailable");

    private final String value;

    AgentReason(String value) {
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
     * Parses a string into an {@link AgentReason}.
     *
     * @param input string value
     * @return matching reason
     * @throws IllegalArgumentException if unknown
     */
    public static AgentReason fromValue(String input) {
        if (input == null) {
            throw new IllegalArgumentException("reason value cannot be null");
        }
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (AgentReason reason : values()) {
            if (reason.value.equals(normalized)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unknown agent reason: " + input);
    }
}
