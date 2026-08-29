package org.synesis.coordination.domain.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable logical parent for related single-participant mutation lanes.
 *
 * @param workGroupId group ID
 * @param projectId   project ID
 * @param goal        shared goal
 * @param acceptance  shared acceptance criteria
 * @param version     group version
 * @param status      lifecycle status
 */
public record WorkGroup(UUID workGroupId, UUID projectId, String goal, String acceptance,
                        long version, Status status) {

    /**
     * Validates the bounded group identity and lifecycle.
     */
    public WorkGroup {
        Objects.requireNonNull(workGroupId, "workGroupId");
        Objects.requireNonNull(projectId, "projectId");
        require(goal, "goal");
        require(acceptance, "acceptance");
        if (version < 1) {
            throw new IllegalArgumentException("group version must be positive");
        }
        Objects.requireNonNull(status, "status");
    }

    private static void require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new IllegalArgumentException(name + " is empty or exceeds bound");
        }
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
