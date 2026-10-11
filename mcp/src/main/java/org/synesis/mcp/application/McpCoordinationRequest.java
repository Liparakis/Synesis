package org.synesis.mcp.application;

import java.util.List;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;

/**
 * One validated {@code request_coordination} operation, with every field already read out of the
 * wire payload and typed.
 *
 * <p>{@link McpCoordinationPayload#normalizeStrictCoordination} is the only producer, so the wire
 * kinds and the operations they dispatch to are listed once, here.</p>
 */
sealed interface McpCoordinationRequest {

    /**
     * Describes a capability the caller needs from another participant, or answers a revision of one.
     *
     * @param capability               nonblank capability name
     * @param contract                 decoded capability contract
     * @param capabilityRequestHandle  handle of the request being revised, or {@code null}
     * @param revisionResponse         {@code accept}, {@code counter} or {@code cancel}, or {@code null}
     * @param ownerAuthorityLineageId  owner lineage as sent, or {@code null}; parsed by the service call because a
     *                                 malformed value is reported as a retryable dispatch failure
     * @param contractRevision         contract revision being answered, or {@code -1} when none
     * @param ownerPath                file whose claimant owns the capability, or {@code null}
     */
    record DescribeCapability(String capability,
            CapabilityContract contract,
            String capabilityRequestHandle,
            String revisionResponse,
            String ownerAuthorityLineageId,
            long contractRevision,
            ResourceSelector ownerPath) implements McpCoordinationRequest {
    }

    /** Reads the collaboration status together with the published contracts and dependencies. */
    record Status() implements McpCoordinationRequest {
    }

    /**
     * Publishes a collaboration contract.
     *
     * @param contractId contract ID
     * @param body       contract text
     * @param selectors  selectors the contract covers
     */
    record PublishContract(UUID contractId, String body, List<String> selectors) implements McpCoordinationRequest {
    }

    /**
     * Opens a contract or review request against another participant's intent.
     *
     * @param intentId   intent the request concerns
     * @param kind       request kind
     * @param proposal   proposal text
     * @param snapshotId review candidate the request is bound to, or {@code null}
     */
    record OpenRequest(UUID intentId, CoordinationRequest.Kind kind, String proposal, String snapshotId) implements McpCoordinationRequest {
    }

    /**
     * Offers a claim handoff.
     *
     * @param intentId intent being handed off
     * @param target   target participant
     * @param proposal proposal text
     */
    record Handoff(UUID intentId, String target, String proposal) implements McpCoordinationRequest {
    }

    /**
     * Creates a work group.
     *
     * @param workGroupId  group ID
     * @param goal         group goal
     * @param acceptance   group acceptance text
     * @param reviewPolicy review policy object as sent, or {@code null}; decoded by the service call because an
     *                     unsupported combination is reported as a retryable dispatch failure
     */
    record CreateWorkGroup(UUID workGroupId, String goal, String acceptance, Object reviewPolicy) implements McpCoordinationRequest {
    }

    /**
     * Consumes a targeted lane grant.
     *
     * @param grantId    grant ID
     * @param intentId   intent the grant admits
     * @param claimEpoch claim epoch of that intent
     */
    record ConsumeLaneGrant(UUID grantId, UUID intentId, long claimEpoch) implements McpCoordinationRequest {
    }

    /**
     * Continues a lane from a source intent.
     *
     * @param grantId        grant ID, or {@code null} when the caller continues without one
     * @param sourceIntentId intent being continued
     * @param claimEpoch     claim epoch of that intent
     */
    record ContinueLane(UUID grantId, UUID sourceIntentId, long claimEpoch) implements McpCoordinationRequest {
    }
}
