package org.synesis.coordination.domain.ownership;

import org.synesis.coordination.domain.identity.IntentVersion;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SupervisorId;
import org.synesis.coordination.exception.CoordinationArgumentException;

/**
 * Semantic owner identity and intent version.
 *
 * @param node       owning node identity
 * @param supervisor owning supervisor identity
 * @param version    version establishing current ownership
 */
public record OwnershipOwner(NodeId node, SupervisorId supervisor, IntentVersion version) {

    /**
     * Creates an owner from current provider-boundary values.
     *
     * @param supervisorId  owning supervisor identity value
     * @param intentVersion version establishing current ownership
     * @param nodeId        owning node identity value
     */
    public OwnershipOwner(String nodeId, String supervisorId, long intentVersion) {
        this(new NodeId(nodeId), new SupervisorId(supervisorId), new IntentVersion(intentVersion));
    }

    /**
     * Validates owner identity and version.
     */
    public OwnershipOwner {
        if (node == null || node.value().isBlank() || supervisor == null || supervisor.value().isBlank()
                || version == null || version.value() < 0) {
            throw new CoordinationArgumentException("invalid owner");
        }
    }

    /**
     * Compatibility accessor for current adapters.
     *
     * @return Compatibility accessor for current adapters.
     */
    public String nodeId() {
        return node.value();
    }

    /**
     * Compatibility accessor for current adapters.
     *
     * @return Compatibility accessor for current adapters.
     */
    public String supervisorId() {
        return supervisor.value();
    }

    /**
     * Compatibility accessor for current adapters.
     *
     * @return Compatibility accessor for current adapters.
     */
    public long intentVersion() {
        return version.value();
    }
}
