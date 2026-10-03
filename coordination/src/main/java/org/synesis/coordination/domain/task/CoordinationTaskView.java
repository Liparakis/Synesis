package org.synesis.coordination.domain.task;

import java.util.Optional;
import org.synesis.coordination.domain.identity.NodeId;

/**
 * Projected task and optional claim.
 *
 * @param task  projected task state
 * @param claim current claim, or {@code null} while unclaimed
 */
public record CoordinationTaskView(CoordinationTask task, TaskClaim claim) {

    /**
     * Returns the current owner node, or {@code null} when unclaimed.
     *
     * @return owner node ID
     */
    public String ownerNodeId() {
        return claim == null ? null : claim.ownerNodeId();
    }

    /**
     * Returns the current owner as a typed node identity when the task is claimed.
     *
     * @return owner node identity when claimed
     */
    public Optional<NodeId> ownerNode() {
        return claim == null ? Optional.empty() : Optional.of(claim.ownerNode());
    }
}
