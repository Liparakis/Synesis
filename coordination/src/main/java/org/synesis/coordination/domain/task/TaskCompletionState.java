package org.synesis.coordination.domain.task;




import java.util.Locale;
import java.util.Objects;

/**
 * Public task completion lifecycle states for Stage 2B Slice 3.
 *
 * <p>State machine for task completion &amp; integration:
 * {@code ACTIVE} &rarr; {@code COMPLETION_REQUESTED} &rarr; {@code COMPLETION_PREPARED} &rarr;
 * {@code SNAPSHOT_READY} &rarr; optional review states
 * ({@code REVIEW_PENDING} &rarr; {@code REVIEW_ACCEPTED} or
 * {@code REVIEW_REJECTED}) &rarr; {@code WAITING_FOR_DEPENDENCIES} &rarr;
 * {@code READY_FOR_INTEGRATION} &rarr; {@code INTEGRATING} &rarr;
 * {@code INTEGRATED}. A rejected snapshot is immutable history; a later
 * same-lineage correction publishes a distinct snapshot for the next lane
 * revision. Failure/terminal states: {@code INTEGRATION_FAILED},
 * {@code CANCELLED}, {@code ABANDONED}.
 *
 * @since 1.0
 */
public enum TaskCompletionState {

    /**
     * Task is active and under implementation by an assigned worker.
     */
    ACTIVE("active"),

    /**
     * Worker has requested task completion.
     */
    COMPLETION_REQUESTED("completion_requested"),

    /** Completion preparation is durable and the lane is mutation-fenced. */
    COMPLETION_PREPARED("completion_prepared"),

    /**
     * Immutable task snapshot has been created from worker worktree commit.
     */
    SNAPSHOT_READY("snapshot_ready"),

    /**
     * Task snapshot is ready but waiting for required dependent tasks to complete.
     */
    WAITING_FOR_DEPENDENCIES("waiting_for_dependencies"),

    /**
     * All required dependencies are complete; task is ready for integration.
     */
    READY_FOR_INTEGRATION("ready_for_integration"),

    /**
     * Dedicated integration attempt is actively running in external worktree.
     */
    INTEGRATING("integrating"),

    /** Published snapshot is eligible for integration. */
    INTEGRATION_PENDING("integration_pending"),

    /** Immutable snapshot is published and awaits its exact review decision. */
    REVIEW_PENDING("review_pending"),

    /** Immutable snapshot was rejected and is permanently ineligible for integration. */
    REVIEW_REJECTED("review_rejected"),

    /** Immutable snapshot received the exact durable acceptance required for integration. */
    REVIEW_ACCEPTED("review_accepted"),

    /** Immutable candidate is structurally invalid and awaits explicit recovery. */
    INTEGRATION_BLOCKED("integration_blocked"),

    /** Valid immutable candidate has been materialized into a repair lane. */
    REPAIR_REQUIRED("repair_required"),

    /**
     * Task is fully integrated into the control branch and verified.
     */
    INTEGRATED("integrated"),

    /**
     * Integration attempt encountered build/test failure or unresolvable merge conflict.
     */
    INTEGRATION_FAILED("integration_failed"),

    /**
     * Task completion was cancelled.
     */
    CANCELLED("cancelled"),

    /**
     * Task was abandoned without integration.
     */
    ABANDONED("abandoned");

    private final String value;

    TaskCompletionState(String value) {
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
     * Parses a string into a {@link TaskCompletionState}.
     *
     * @param input string representation
     * @return matching completion state
     * @throws IllegalArgumentException if null or unknown
     */
    public static TaskCompletionState fromValue(String input) {
        Objects.requireNonNull(input, "input state value cannot be null");
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (TaskCompletionState state : values()) {
            if (state.value.equals(normalized)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown task completion state: " + input);
    }
}
