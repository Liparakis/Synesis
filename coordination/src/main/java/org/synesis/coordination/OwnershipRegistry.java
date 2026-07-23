package org.synesis.coordination;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Durable semantic capability ownership view used by provider guardrails. */
public final class OwnershipRegistry {
    private final Map<String, Owner> owners = new LinkedHashMap<>();

    /** Creates an empty ownership view. */
    public OwnershipRegistry() { }

    /** Records or replaces an explicit semantic ownership claim.
     * @param capability capability name
     * @param owner owner descriptor
     */
    public synchronized void claim(String capability, Owner owner) {
        if (capability == null || capability.isBlank()) throw new IllegalArgumentException("capability required");
        owners.put(capability, Objects.requireNonNull(owner, "owner"));
    }

    /** Evaluates a capability for a requester without mutating ownership.
     * @param capability capability name
     * @param requesterNodeId requester node identifier
     * @return ownership decision
     */
    public synchronized Decision evaluate(String capability, String requesterNodeId) {
        Owner owner = owners.get(capability);
        if (owner == null) return new Decision(Result.UNKNOWN, null, "No semantic owner is registered");
        if (owner.nodeId().equals(requesterNodeId)) return new Decision(Result.ALLOW, owner, "Owned by requester");
        return new Decision(Result.REQUEST_OWNER, owner, "Capability is owned by " + owner.nodeId());
    }

    /** Semantic owner identity and intent version.
     * @param nodeId owner node identifier
     * @param supervisorId owner supervisor identifier
     * @param intentVersion owner intent version
     */
    public record Owner(String nodeId, String supervisorId, long intentVersion) {
        /** Validates owner identity and version. */
        public Owner {
            if (nodeId == null || nodeId.isBlank() || supervisorId == null || supervisorId.isBlank()
                    || intentVersion < 0) throw new IllegalArgumentException("invalid owner");
        }
    }

    /** Possible ownership evaluation outcomes. */
    public enum Result { /** no owner */ UNKNOWN, /** requester owns it */ ALLOW, /** another node owns it */ REQUEST_OWNER }

    /** Structured ownership decision returned to a provider adapter.
     * @param result decision result
     * @param owner semantic owner, when known
     * @param message bounded explanation
     */
    public record Decision(Result result, Owner owner, String message) {
        /** Validates decision fields. */
        public Decision { Objects.requireNonNull(result, "result"); Objects.requireNonNull(message, "message"); }
    }
}
