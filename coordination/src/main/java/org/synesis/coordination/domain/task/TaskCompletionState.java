package org.synesis.coordination.domain.task;




import java.util.Locale;
import java.util.Objects;

/**
 * Public task completion lifecycle states for Stage 2B Slice 3.
 *
 * <p>State machine for task completion &amp; integration:
 * {@code ACTIVE} &rarr; {@code COMPLETION_REQUESTED} &rarr; {@code SNAPSHOT_READY} &rarr;
 * {@code WAITING_FOR_DEPENDENCIES} &rarr; {@code READY_FOR_INTEGRATION} &rarr; {@code INTEGRATING} &rarr; {@code INTEGRATED}.
 * Failure/terminal states: {@code INTEGRATION_FAILED}, {@code CANCELLED}, {@code ABANDONED}.
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
