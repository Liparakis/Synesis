package org.synesis.coordination.domain.capability;




import java.util.Objects;

/**
 * Projected durable state for a Stage 2B capability request.
 *
 * @param handle                public request handle locator
 * @param capability            target capability identifier
 * @param requesterNodeId       authenticated requester node ID
 * @param requesterSupervisorId authenticated requester supervisor ID
 * @param requesterWorkerId     authenticated requester worker ID
 * @param ownerNodeId           assigned semantic owner node ID
 * @param ownerSupervisorId     assigned semantic owner supervisor ID
 * @param ownerWorkerId         assigned semantic owner worker ID
 * @param contract              current contract specification
 * @param state                 current request lifecycle state
 * @param reason                optional rejection or revision reason
 * @param createdAtEpochMillis  creation timestamp
 * @param updatedAtEpochMillis  last modification timestamp
 * @since 1.0
 */
public record CapabilityRequestRecord(
        CapabilityRequestHandle handle,
        String capability,
        String requesterNodeId,
        String requesterSupervisorId,
        String requesterWorkerId,
        String ownerNodeId,
        String ownerSupervisorId,
        String ownerWorkerId,
        CapabilityContract contract,
        CapabilityLifecycleState state,
        String reason,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    /**
     * Compact constructor enforcing nullability checks.
     *
     * @param handle                public request handle locator
     * @param capability            target capability identifier
     * @param requesterNodeId       authenticated requester node ID
     * @param requesterSupervisorId authenticated requester supervisor ID
     * @param requesterWorkerId     authenticated requester worker ID
     * @param ownerNodeId           assigned semantic owner node ID
     * @param ownerSupervisorId     assigned semantic owner supervisor ID
     * @param ownerWorkerId         assigned semantic owner worker ID
     * @param contract              current contract specification
     * @param state                 current request lifecycle state
     * @param reason                optional rejection or revision reason
     * @param createdAtEpochMillis  creation timestamp
     * @param updatedAtEpochMillis  last modification timestamp
     */
    public CapabilityRequestRecord {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(requesterNodeId, "requesterNodeId");
        requesterSupervisorId = requesterSupervisorId == null ? "" : requesterSupervisorId;
        requesterWorkerId = requesterWorkerId == null ? "" : requesterWorkerId;
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        ownerSupervisorId = ownerSupervisorId == null ? "" : ownerSupervisorId;
        ownerWorkerId = ownerWorkerId == null ? "" : ownerWorkerId;
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(state, "state");
    }

    /**
     * Constructs a request without optional worker or supervisor identities.
     *
     * @param handle               public request handle locator
     * @param capability           target capability identifier
     * @param requesterNodeId      authenticated requester node ID
     * @param ownerNodeId          assigned semantic owner node ID
     * @param contract             current contract specification
     * @param state                current request lifecycle state
     * @param reason               optional rejection or revision reason
     * @param createdAtEpochMillis creation timestamp
     * @param updatedAtEpochMillis last modification timestamp
     */
    public CapabilityRequestRecord(
            CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String ownerNodeId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
        this(handle, capability, requesterNodeId, "", "", ownerNodeId, "", "", contract, state, reason, createdAtEpochMillis, updatedAtEpochMillis);
    }

    /**
     * Creates an updated record with a new state, contract, and modification timestamp.
     *
     * @param newState           updated lifecycle state
     * @param newContract        updated contract specification
     * @param newReason          updated reason message
     * @param updatedEpochMillis current modification timestamp
     * @return updated record instance
     */
    public CapabilityRequestRecord withUpdate(CapabilityLifecycleState newState, CapabilityContract newContract, String newReason, long updatedEpochMillis) {
        return new CapabilityRequestRecord(
                handle,
                capability,
                requesterNodeId,
                requesterSupervisorId,
                requesterWorkerId,
                ownerNodeId,
                ownerSupervisorId,
                ownerWorkerId,
                newContract != null ? newContract : contract,
                newState,
                newReason,
                createdAtEpochMillis,
                updatedEpochMillis
        );
    }

    /**
     * Verifies if a given caller worker matches the requester actor boundary.
     *
     * @param callerNodeId       caller node ID
     * @param callerSupervisorId caller supervisor ID
     * @param callerWorkerId     caller worker ID
     * @return true if authorized as requester
     */
    public boolean matchesRequester(String callerNodeId, String callerSupervisorId, String callerWorkerId) {
        if (!requesterNodeId.equals(callerNodeId)) {
            return false;
        }
        if (!requesterWorkerId.isBlank() && callerWorkerId != null && !callerWorkerId.isBlank() && !requesterWorkerId.equals(callerWorkerId)) {
            return false;
        }
        if (!requesterSupervisorId.isBlank() && callerSupervisorId != null && !callerSupervisorId.isBlank() && !requesterSupervisorId.equals(callerSupervisorId)) {
            return false;
        }
        return true;
    }

    /**
     * Verifies if a given caller worker matches the owner actor boundary.
     *
     * @param callerNodeId       caller node ID
     * @param callerSupervisorId caller supervisor ID
     * @param callerWorkerId     caller worker ID
     * @return true if authorized as owner
     */
    public boolean matchesOwner(String callerNodeId, String callerSupervisorId, String callerWorkerId) {
        if (!ownerNodeId.equals(callerNodeId)) {
            return false;
        }
        if (!ownerWorkerId.isBlank() && callerWorkerId != null && !callerWorkerId.isBlank() && !ownerWorkerId.equals(callerWorkerId)) {
            return false;
        }
        if (!ownerSupervisorId.isBlank() && callerSupervisorId != null && !callerSupervisorId.isBlank() && !ownerSupervisorId.equals(callerSupervisorId)) {
            return false;
        }
        return true;
    }
}
