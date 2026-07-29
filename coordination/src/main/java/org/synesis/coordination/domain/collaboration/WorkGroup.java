package org.synesis.coordination.domain.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Durable logical parent for related single-participant mutation lanes. */
public record WorkGroup(UUID workGroupId, UUID projectId, String goal, String acceptance,
                        long version, Status status) {
    /** Logical work-group lifecycle. */
    public enum Status { ACTIVE, COMPLETED, CANCELLED }

    /** Validates the bounded group identity and lifecycle. */
    public WorkGroup {
        Objects.requireNonNull(workGroupId, "workGroupId");
        Objects.requireNonNull(projectId, "projectId");
        require(goal, "goal");
        require(acceptance, "acceptance");
        if (version < 1) throw new IllegalArgumentException("group version must be positive");
        Objects.requireNonNull(status, "status");
    }

    private static void require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new IllegalArgumentException(name + " is empty or exceeds bound");
        }
    }
}
