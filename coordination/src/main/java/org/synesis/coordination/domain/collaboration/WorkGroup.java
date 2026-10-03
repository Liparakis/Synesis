package org.synesis.coordination.domain.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkGroupVersion;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Durable logical parent for related single-participant mutation lanes.
 *
 * @param workGroupId  group ID
 * @param projectId    project ID
 * @param goal         shared goal
 * @param acceptance   shared acceptance criteria
 * @param version      group version
 * @param status       lifecycle status
 * @param reviewPolicy structured review requirement
 */
public record WorkGroup(UUID workGroupId, UUID projectId, String goal, String acceptance, long version, Status status,
                        ReviewPolicy reviewPolicy) {

    /**
     * Creates a group with the legacy no-review policy.
     *
     * @param workGroupId group ID
     * @param projectId   project ID
     * @param goal        shared goal
     * @param acceptance  shared acceptance criteria
     * @param version     group version
     * @param status      lifecycle status
     */
    public WorkGroup(UUID workGroupId, UUID projectId, String goal, String acceptance, long version, Status status) {
        this(workGroupId, projectId, goal, acceptance, version, status, ReviewPolicy.none());
    }

    /**
     * Validates the bounded group identity and lifecycle.
     */
    public WorkGroup {
        CoordinationNullException.requireNonNull(workGroupId, "workGroupId");
        CoordinationNullException.requireNonNull(projectId, "projectId");
        require(goal, "goal");
        require(acceptance, "acceptance");
        if (version < 1) {
            throw new CoordinationArgumentException("group version must be positive");
        }
        CoordinationNullException.requireNonNull(status, "status");
        CoordinationNullException.requireNonNull(reviewPolicy, "reviewPolicy");
    }

    /**
     * Checks that the type value satisfies this type's bounds.
     */
    private static void require(String value, String name) {
        CoordinationNullException.requireNonNull(value, name);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new CoordinationArgumentException(name + " is empty or exceeds bound");
        }
    }

    /**
     * Returns the typed logical group identity.
     *
     * @return the typed logical group identity.
     */
    public WorkGroupId identity() {
        return new WorkGroupId(workGroupId);
    }

    /**
     * Returns the typed project identity.
     *
     * @return the typed project identity.
     */
    public ProjectId project() {
        return new ProjectId(projectId);
    }

    /**
     * Returns the optimistic-concurrency version with its domain type.
     *
     * @return the optimistic-concurrency version with its domain type.
     */
    public WorkGroupVersion groupVersion() {
        return new WorkGroupVersion(version);
    }

    /**
     * Logical work-group lifecycle.
     */
    public enum Status {
        /**
         * Group accepts new lane activity.
         */
        ACTIVE,
        /**
         * Group completed.
         */
        COMPLETED,
        /**
         * Group cancelled.
         */
        CANCELLED
    }
}
