package org.synesis.coordination.domain.task;


/**
 * Public task completion lifecycle states for Stage 2B Slice 3.
 *
 * <p>State machine for task completion &amp; integration:
 * {@code ACTIVE} &rarr; {@code COMPLETION_REQUESTED} &rarr; {@code COMPLETION_PREPARED} &rarr; {@code SNAPSHOT_READY}
 * &rarr; optional
 * review states ({@code REVIEW_PENDING} &rarr; {@code REVIEW_ACCEPTED} or {@code REVIEW_REJECTED}) &rarr;
 * {@code WAITING_FOR_DEPENDENCIES}
 * &rarr; {@code READY_FOR_INTEGRATION} &rarr; {@code INTEGRATING} &rarr; {@code INTEGRATED}. A rejected snapshot is
 * immutable history; a
 * later same-lineage correction publishes a distinct snapshot for the next lane revision. The same holds for a snapshot
 * the project's validation command refused at integration ({@code VALIDATION_REFUSED}). Failure/terminal states:
 * {@code INTEGRATION_FAILED}, {@code CANCELLED}, {@code ABANDONED}.
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

    /**
     * Completion preparation is durable and the lane is mutation-fenced.
     */
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

    /**
     * Published snapshot is eligible for integration.
     */
    INTEGRATION_PENDING("integration_pending"),

    /**
     * Immutable snapshot is published and awaits its exact review decision.
     */
    REVIEW_PENDING("review_pending"),

    /**
     * Immutable snapshot was rejected and is permanently ineligible for integration.
     */
    REVIEW_REJECTED("review_rejected"),

    /**
     * The project's validation command failed on an integration candidate that ended with this
     * snapshot. Like a rejected snapshot it is permanently ineligible for integration and its
     * lane publishes a correction, but no reviewer decided it and it makes nothing review-required.
     */
    VALIDATION_REFUSED("validation_refused"),

    /**
     * Immutable snapshot received the exact durable acceptance required for integration.
     */
    REVIEW_ACCEPTED("review_accepted"),

    /**
     * Immutable candidate is structurally invalid and awaits explicit recovery.
     */
    INTEGRATION_BLOCKED("integration_blocked"),

    /**
     * Valid immutable candidate has been materialized into a repair lane.
     */
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

    /**
     * Validates and initializes this task completion state.
     */
    TaskCompletionState(String value) {
        this.value = value;
    }

    /**
     * Tells whether a snapshot in this state has left its lane and only integration stands
     * between it and the control branch.
     *
     * @return true while the snapshot waits to be integrated
     */
    public boolean awaitsIntegration() {
        return this == SNAPSHOT_READY || this == WAITING_FOR_DEPENDENCIES || this == READY_FOR_INTEGRATION || this == INTEGRATING || this == INTEGRATION_PENDING || this == REVIEW_ACCEPTED;
    }

    /**
     * Tells whether this snapshot was refused for good and its lane owes a correction, whoever
     * refused it: a reviewer, or the project's validation command at integration.
     *
     * @return true for a snapshot that never integrates and is answered by a later revision
     */
    public boolean refused() {
        return this == REVIEW_REJECTED || this == VALIDATION_REFUSED;
    }

    /**
     * Returns the stable lowercase JSON representation.
     *
     * @return lowercase string representation
     */
    public String value() {
        return value;
    }
}
