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

    /** The agent is operating from the control checkout instead of its assigned worktree. */
    WORKSPACE_MISMATCH("workspace_mismatch"),

    /** The complete Git tree fails the declared cross-platform portability policy. */
    REPOSITORY_NOT_PORTABLE("repository_not_portable"),

    /**
     * A modifying patch omitted the optimistic content precondition returned by a read.
     */
    PATCH_PRECONDITION_REQUIRED("patch_precondition_required"),

    /** Target file content differs from the revision supplied by the agent. */
    FILE_REVISION_STALE("file_revision_stale"),

    /** The requested edit context no longer applies to the target file. */
    PATCH_CONTEXT_MISMATCH("patch_context_mismatch"),

    /** The assigned worker generation changed and must be re-established. */
    WORKSPACE_GENERATION_CHANGED("workspace_generation_changed"),

    /** Mutation of a mixed-line-ending file requires explicit review. */
    MIXED_LINE_ENDINGS_REQUIRES_REVIEW("mixed_line_endings_require_review"),

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
    IMPLEMENTATION_UNAVAILABLE("implementation_unavailable"),

    /**
     * Requester validation of the implementation snapshot failed.
     */
    VALIDATION_FAILED("validation_failed"),

    /**
     * Task completion blocked by unresolved capability or task dependencies.
     */
    UNRESOLVED_DEPENDENCY("unresolved_dependency"),

    /** Capability publication or resolution does not match its durable authority lineage. */
    CAPABILITY_LINEAGE_MISMATCH("capability_lineage_mismatch"),

    /** The publisher is no longer the active authority for the capability lineage. */
    CAPABILITY_PUBLISHER_STALE("capability_publisher_stale"),

    /**
     * Task is not ready for completion due to active validation or unverified state.
     */
    TASK_NOT_READY("task_not_ready"),

    /**
     * Immutable task snapshot created and waiting for dependent tasks to complete integration.
     */
    INTEGRATION_PENDING("integration_pending"),

    /**
     * Integration attempt encountered git merge conflict.
     */
    INTEGRATION_CONFLICT("integration_conflict"),

    /**
     * Integration attempt failed build or test gate.
     */
    INTEGRATION_FAILED("integration_failed"),

    /**
     * Control branch moved during integration attempt; retry required.
     */
    INTEGRATION_STALE("integration_stale"),

    /** Mutation requires an announced intent and owned resource claim. */
    COORDINATION_INTENT_REQUIRED("coordination_intent_required"),

    /** Mutation overlaps another participant's active resource claim. */
    OVERLAPPING_CLAIM("overlapping_claim");

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
