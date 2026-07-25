package org.synesis.coordination;

import java.util.Objects;

/**
 * Projected durable state for a Stage 2B capability request.
 *
 * @param handle               public request handle locator
 * @param capability           target capability identifier
 * @param requesterNodeId     authenticated requester node ID
 * @param ownerNodeId          assigned semantic owner node ID
 * @param contract             current contract specification
 * @param state                current request lifecycle state
 * @param reason               optional rejection or revision reason
 * @param createdAtEpochMillis creation timestamp
 * @param updatedAtEpochMillis last modification timestamp
 * @since 1.0
 */
public record CapabilityRequestRecord(
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

    /**
     * Compact constructor enforcing nullability checks.
     *
     * @param handle               public request handle locator
     * @param capability           target capability identifier
     * @param requesterNodeId     authenticated requester node ID
     * @param ownerNodeId          assigned semantic owner node ID
     * @param contract             current contract specification
     * @param state                current request lifecycle state
     * @param reason               optional rejection or revision reason
     * @param createdAtEpochMillis creation timestamp
     * @param updatedAtEpochMillis last modification timestamp
     */
    public CapabilityRequestRecord {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(requesterNodeId, "requesterNodeId");
        Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(state, "state");
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
                ownerNodeId,
                newContract != null ? newContract : contract,
                newState,
                newReason,
                createdAtEpochMillis,
                updatedEpochMillis
        );
    }
}
