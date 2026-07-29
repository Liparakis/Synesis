package org.synesis.coordination.domain.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable authenticated declaration of one worker's intended change.
 * @param intentId intent identifier
 * @param projectId project identifier
 * @param participant opaque participant handle
 * @param provider provider identifier
 * @param taskId task identifier
 * @param goal work goal
 * @param acceptance acceptance criteria
 * @param baseCommit base commit
 * @param selectors requested resource selectors
 * @param version intent version
 * @param workGroupId logical work-group parent
 * @param status lifecycle status
 */
public record WorkIntent(UUID intentId, UUID projectId, String participant,
                         String provider, UUID taskId, String goal,
                         String acceptance, String baseCommit,
                         List<ResourceSelector> selectors, long version,
                         UUID workGroupId,
                         Status status) {

    /** Backward-compatible singleton-group intent constructor.
     * @param intentId intent ID
     * @param projectId project ID
     * @param participant participant
     * @param provider provider
     * @param taskId task ID
     * @param goal goal
     * @param acceptance acceptance
     * @param baseCommit base commit
     * @param selectors selectors
     * @param version version
     * @param status status
     */
    public WorkIntent(UUID intentId, UUID projectId, String participant,
                      String provider, UUID taskId, String goal,
                      String acceptance, String baseCommit,
                      List<ResourceSelector> selectors, long version, Status status) {
        this(intentId, projectId, participant, provider, taskId, goal, acceptance,
                baseCommit, selectors, version, intentId, status);
    }

    /** Intent lifecycle states. */
    public enum Status {
        /** Intent owns its selectors. */
        ANNOUNCED,
        /** Intent has released its selectors. */
        RELEASED
    }

    /** Validates bounds and immutable collections. */
    public WorkIntent {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(workGroupId, "workGroupId");
        require(participant, "participant");
        require(provider, "provider");
        Objects.requireNonNull(taskId, "taskId");
        require(goal, "goal");
        require(acceptance, "acceptance");
        require(baseCommit, "baseCommit");
        Objects.requireNonNull(selectors, "selectors");
        if (selectors.isEmpty() || selectors.size() > 128) {
            throw new IllegalArgumentException("intent must contain 1..128 selectors");
        }
        selectors = List.copyOf(selectors);
        if (version < 1) {
            throw new IllegalArgumentException("intent version must be positive");
        }
        Objects.requireNonNull(status, "status");
    }

    private static void require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new IllegalArgumentException(name + " is empty or exceeds bound");
        }
    }
}
