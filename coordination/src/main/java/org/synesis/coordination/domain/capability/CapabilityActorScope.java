package org.synesis.coordination.domain.capability;

import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Verified provider-binding identity used to transfer a capability request actor with its lane.
 *
 * @param nodeId authenticated node identity
 * @param supervisorId optional supervisor identity, or empty when absent
 * @param workerId optional worker identity, or empty when absent
 * @param sessionId exact provider session identity from the verified binding
 */
public record CapabilityActorScope(String nodeId, String supervisorId, String workerId, String sessionId) {

    /**
     * Normalizes absent supervisor and worker identities to empty and rejects a blank node or session.
     */
    public CapabilityActorScope {
        CoordinationNullException.requireNonNull(nodeId, "nodeId");
        CoordinationNullException.requireNonNull(sessionId, "sessionId");
        supervisorId = supervisorId == null ? "" : supervisorId;
        workerId = workerId == null ? "" : workerId;
        if (nodeId.isBlank() || sessionId.isBlank()) {
            throw new CoordinationArgumentException("capability actor node and session IDs must be nonblank");
        }
    }

    /**
     * Matches the durable capability actor fields bound at request creation.
     *
     * @param expectedNodeId       recorded node identity
     * @param expectedSupervisorId recorded supervisor identity, or {@code null} when absent
     * @param expectedWorkerId     recorded worker identity, or {@code null} when absent
     * @return true when node, supervisor, and worker all equal this scope exactly
     */
    boolean matches(String expectedNodeId, String expectedSupervisorId, String expectedWorkerId) {
        return nodeId.equals(expectedNodeId)
                && supervisorId.equals(expectedSupervisorId == null ? "" : expectedSupervisorId)
                && workerId.equals(expectedWorkerId == null ? "" : expectedWorkerId);
    }
}
