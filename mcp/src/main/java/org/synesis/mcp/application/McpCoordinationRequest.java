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
     * Publishes the public contract over files the caller claims, or its next revision.
     *
     * @param paths   claimed files or subtrees the contract covers
     * @param terms   what the owner promises about them
     * @param answers change requests this revision covers
     */
    record PublishContract(List<ResourceSelector> paths, CapabilityContract terms, List<UUID> answers) implements McpCoordinationRequest {
    }

    /**
     * Binds the caller's lane to one revision of the contract over a file it builds on.
     *
     * @param capability the caller's own name for what it depends on
     * @param ownerPath  the file; the lane claiming it is the owner
     * @param revision   the revision the caller read and accepts
     */
    record BindContract(String capability, ResourceSelector ownerPath, long revision) implements McpCoordinationRequest {
    }

    /**
     * Says what the caller needs from a file that its contract does not.
     *
     * @param ownerPath the file the caller builds on
     * @param need      what it needs, in its own words
     */
    record RequestChange(ResourceSelector ownerPath, String need) implements McpCoordinationRequest {
    }

    /** Reads the collaboration status together with the published contracts and dependencies. */
    record Status() implements McpCoordinationRequest {
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
