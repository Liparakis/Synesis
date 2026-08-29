package org.synesis.coordination.domain.capability;


import java.util.Objects;
import java.util.UUID;

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
 * @param authorityLineageId    durable authority lineage required for implementation publication
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
        UUID authorityLineageId,
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
     * @param authorityLineageId    durable authority lineage required for implementation publication
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
        Objects.requireNonNull(authorityLineageId, "authorityLineageId");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(state, "state");
    }

    /**
     * Constructs a worker-aware request without an explicit authority lineage.
     *
     * @param handle                request handle
     * @param capability            capability identifier
     * @param requesterNodeId       requester node
     * @param requesterSupervisorId requester supervisor
     * @param requesterWorkerId     requester worker
     * @param ownerNodeId           owner node
     * @param ownerSupervisorId     owner supervisor
     * @param ownerWorkerId         owner worker
     * @param contract              capability contract
     * @param state                 lifecycle state
     * @param reason                optional reason
     * @param createdAtEpochMillis  creation timestamp
     * @param updatedAtEpochMillis  update timestamp
     */
    public CapabilityRequestRecord(
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
        this(handle, capability, requesterNodeId, requesterSupervisorId, requesterWorkerId,
                ownerNodeId, ownerSupervisorId, ownerWorkerId, unresolvedLineage(handle),
                contract, state, reason, createdAtEpochMillis, updatedAtEpochMillis);
    }

    /**
     * Constructs a request without optional worker or supervisor identities.
     *
     * @param handle               request handle
     * @param capability           capability identifier
     * @param requesterNodeId      requester node
     * @param ownerNodeId          owner node
     * @param contract             capability contract
     * @param state                lifecycle state
     * @param reason               optional reason
     * @param createdAtEpochMillis creation timestamp
     * @param updatedAtEpochMillis update timestamp
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
        this(handle, capability, requesterNodeId, "", "", ownerNodeId, "", "",
                unresolvedLineage(handle), contract, state, reason, createdAtEpochMillis, updatedAtEpochMillis);
    }

    /**
     * Constructs a request with an explicit authority lineage and no optional actors.
     *
     * @param handle               request handle
     * @param capability           capability identifier
     * @param requesterNodeId      requester node
     * @param ownerNodeId          owner node
     * @param authorityLineageId   durable authority lineage
     * @param contract             capability contract
     * @param state                lifecycle state
     * @param reason               optional reason
     * @param createdAtEpochMillis creation timestamp
     * @param updatedAtEpochMillis update timestamp
     */
    public CapabilityRequestRecord(
            CapabilityRequestHandle handle,
            String capability,
            String requesterNodeId,
            String ownerNodeId,
            UUID authorityLineageId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
        this(handle, capability, requesterNodeId, "", "", ownerNodeId, "", "",
                authorityLineageId, contract, state, reason, createdAtEpochMillis, updatedAtEpochMillis);
    }

    private static UUID unresolvedLineage(CapabilityRequestHandle handle) {
        return UUID.nameUUIDFromBytes(("synesis-unresolved-capability-lineage:" + handle.value())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
    public CapabilityRequestRecord withUpdate(CapabilityLifecycleState newState,
            CapabilityContract newContract,
            String newReason,
            long updatedEpochMillis) {
        return new CapabilityRequestRecord(
                handle,
                capability,
                requesterNodeId,
                requesterSupervisorId,
                requesterWorkerId,
                ownerNodeId,
                ownerSupervisorId,
                ownerWorkerId,
                authorityLineageId,
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
        if (scopedIdentityMismatch(requesterWorkerId, callerWorkerId)) {
            return false;
        }
        return !scopedIdentityMismatch(requesterSupervisorId, callerSupervisorId);
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
        if (scopedIdentityMismatch(ownerWorkerId, callerWorkerId)) {
            return false;
        }
        return !scopedIdentityMismatch(ownerSupervisorId, callerSupervisorId);
    }

    private static boolean scopedIdentityMismatch(String expected, String caller) {
        return !expected.isBlank() && caller != null && !caller.isBlank() && !expected.equals(caller);
    }
}
