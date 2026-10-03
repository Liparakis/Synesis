package org.synesis.coordination.domain.ownership;


import java.util.LinkedHashMap;
import java.util.Map;
import org.synesis.coordination.domain.identity.CapabilityId;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Durable semantic capability ownership view used by provider guardrails.
 */
public final class OwnershipRegistry {

    private final Map<CapabilityId, OwnershipOwner> owners = new LinkedHashMap<>();

    /**
     * Creates an empty ownership view.
     */
    public OwnershipRegistry() {
    }

    /**
     * Records or replaces an explicit semantic ownership claim.
     *
     * @param capability capability name
     * @param owner      owner descriptor
     */
    public synchronized void claim(String capability, OwnershipOwner owner) {
        if (capability == null || capability.isBlank()) {
            throw new CoordinationArgumentException("capability required");
        }
        claim(new CapabilityId(capability), owner);
    }

    /**
     * Records or replaces an ownership claim using its typed capability key.
     *
     * @param capability capability identity
     * @param owner      owner descriptor
     */
    public synchronized void claim(CapabilityId capability, OwnershipOwner owner) {
        CapabilityId identity = CoordinationNullException.requireNonNull(capability, "capability ID");
        if (identity.value().isBlank()) {
            throw new CoordinationArgumentException("capability required");
        }
        owners.put(identity, CoordinationNullException.requireNonNull(owner, "owner"));
    }

    /**
     * Evaluates a capability for a requester without mutating ownership.
     *
     * @param capability      capability name
     * @param requesterNodeId requester node identifier
     * @return ownership decision
     */
    public synchronized OwnershipDecision evaluate(String capability, String requesterNodeId) {
        if (capability == null) {
            return new OwnershipDecision(Result.UNKNOWN, null, "No semantic owner is registered");
        }
        CapabilityId capabilityIdentity = new CapabilityId(capability);
        OwnershipOwner owner = owners.get(capabilityIdentity);
        if (owner == null) {
            return new OwnershipDecision(Result.UNKNOWN, null, "No semantic owner is registered");
        }
        if (requesterNodeId == null) {
            return new OwnershipDecision(Result.REQUEST_OWNER, owner, "Capability is owned by " + owner.nodeId());
        }
        return evaluate(capabilityIdentity, new NodeId(requesterNodeId));
    }

    /**
     * Evaluates a typed capability for a typed requester without mutating ownership.
     *
     * @param capability      capability identity, or {@code null} to preserve the unknown result
     * @param requesterNodeId requesting node identity, or {@code null} when unavailable
     * @return ownership decision
     */
    public synchronized OwnershipDecision evaluate(CapabilityId capability, NodeId requesterNodeId) {
        CapabilityId identity = CoordinationNullException.requireNonNull(capability, "capability ID");
        NodeId requester = CoordinationNullException.requireNonNull(requesterNodeId, "requester node ID");
        OwnershipOwner owner = owners.get(identity);
        if (owner == null) {
            return new OwnershipDecision(Result.UNKNOWN, null, "No semantic owner is registered");
        }
        if (owner.node().equals(requester)) {
            return new OwnershipDecision(Result.ALLOW, owner, "Owned by requester");
        }
        return new OwnershipDecision(Result.REQUEST_OWNER, owner, "Capability is owned by " + owner.nodeId());
    }

    /**
     * Possible ownership evaluation outcomes.
     */
    public enum Result {
        /**
         * no owner
         */
        UNKNOWN,
        /**
         * requester owns it
         */
        ALLOW,
        /**
         * another node owns it
         */
        REQUEST_OWNER
    }

}
