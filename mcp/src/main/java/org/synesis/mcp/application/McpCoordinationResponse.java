package org.synesis.mcp.application;

import java.util.List;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;

/**
 * One validated {@code respond_coordination} operation, with every field already read out of the
 * wire payload and typed.
 *
 * <p>{@link McpCoordinationPayload#normalizeStrictResponse} is the only producer.</p>
 */
sealed interface McpCoordinationResponse {

    /**
     * Records the requester's validation of a published implementation.
     *
     * @param capabilityRequestHandle handle of the capability request
     * @param result                  {@code inspect}, {@code accepted} or {@code revision_required}
     * @param reason                  reason text, or {@code null}
     * @param implementationRevision  implementation revision validated
     * @param failedAcceptanceTests   failing acceptance tests named by the caller
     */
    record ImplementationValidation(String capabilityRequestHandle,
            String result,
            String reason,
            int implementationRevision,
            List<String> failedAcceptanceTests) implements McpCoordinationResponse {
    }

    /**
     * Records a reviewer's decision on an immutable review snapshot.
     *
     * @param grantId    review grant
     * @param snapshotId reviewed snapshot
     * @param intentId   reviewed intent
     * @param claimEpoch claim epoch of that intent
     * @param result     review result as sent
     * @param reason     reason text, or {@code null}
     */
    record ReviewValidation(UUID grantId, String snapshotId, UUID intentId, long claimEpoch, String result, String reason) implements McpCoordinationResponse {
    }

    /**
     * Answers a capability request as its owner.
     *
     * @param capabilityRequestHandle handle of the capability request
     * @param response                {@code accept}, {@code revise} or {@code reject}
     * @param revision                revised contract, or {@code null} unless the owner revises
     * @param reason                  reason text, or {@code null}
     * @param contractRevision        contract revision being answered
     */
    record RespondToCapability(String capabilityRequestHandle, String response, CapabilityContract revision, String reason, long contractRevision) implements McpCoordinationResponse {
    }

    /**
     * Answers a coordination request.
     *
     * @param requestId   request ID
     * @param requestText request ID exactly as sent; it is echoed in the reply
     * @param status      status the caller sets
     * @param proposal    proposal text
     */
    record RespondToRequest(UUID requestId, String requestText, CoordinationRequest.Status status, String proposal) implements McpCoordinationResponse {
    }

    /**
     * Acknowledges one inbox item.
     *
     * @param itemId inbox item ID
     */
    record AcknowledgeInbox(UUID itemId) implements McpCoordinationResponse {
    }

    /**
     * Resolves and acknowledges one inbox item.
     *
     * @param itemId     inbox item ID
     * @param resolution status the item resolves to
     * @param proposal   proposal text
     */
    record ResolveInbox(UUID itemId, CoordinationRequest.Status resolution, String proposal) implements McpCoordinationResponse {
    }
}
