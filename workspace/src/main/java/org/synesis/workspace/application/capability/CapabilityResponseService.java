package org.synesis.workspace.application.capability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/**
 * Application service for capability owners to respond to capability requests.
 *
 * <p>Handles coordination responses and capability-owner responses.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class CapabilityResponseService {

    private final ProjectApplicationService projectService;
    private final SessionAuthorityResolver authorityResolver;

    /**
     * Creates a capability response service.
     */
    public CapabilityResponseService() {
        this.projectService = new ProjectApplicationService();
        this.authorityResolver = new SessionAuthorityResolver(new ProviderSessionBindingService());
    }

    /**
     * Executes an owner capability request response (accept, revise, or reject).
     *
     * <p>The exact binding, the capability projection, the authorization and revision checks, and
     * the append all happen under one {@link ProjectAppendLock}. A reply decided from a projection
     * read before the lock could otherwise be appended after a peer process already answered the
     * same contract revision, leaving a log that no longer replays.
     *
     * @param request response request payload
     * @return concise agent response
     */
    public AgentResponse respondToOwnerRequest(OwnerResponseRequest request) {
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectLocation location;
        NodeIdentity identity;
        try {
            location = projectService.locate(root);
            identity = location.loadOrCreateIdentity();
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        Path coordDir = location.coordinationDirectory();
        ProjectAppendLock appendLock;
        try {
            appendLock = ProjectAppendLock.acquire(coordDir);
        } catch (IOException unavailable) {
            return appendLockUnavailable();
        }
        try (appendLock) {
            if (!appendLock.isHeld()) {
                return appendLockUnavailable();
            }
            ProviderSessionBindingService.Binding binding;
            try {
                binding = authorityResolver.resolve(location, request.provider(), request.connectionInstanceId());
            } catch (Exception ex) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            if (binding.worktreePath() == null) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            PredictionEventStore store = location.openCoordinationStore();
            return respondLocked(request, store, binding, identity);
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        }
    }

    private static AgentResponse appendLockUnavailable() {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.RETRY, Map.of("reason", "EVENT_APPEND_LOCK_UNAVAILABLE"));
    }

    /** Authorizes and appends one owner reply against the projection loaded under the append lock. */
    private static AgentResponse respondLocked(OwnerResponseRequest request,
            PredictionEventStore store,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity) throws IOException, GeneralSecurityException {
        String ownerNodeId = identity.nodeId();
        Optional<CapabilityRequestRecord> recOpt = store.capabilityRequestProjection().findByHandle(request.requestHandle().trim());
        if (recOpt.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY, null);
        }

        CapabilityRequestRecord record = recOpt.get();
        // Local bindings share the project node identity, so the node alone cannot distinguish
        // the assigned owner from a sibling session; the stored supervisor and worker scope must
        // match as well.
        if (!record.matchesOwner(ownerNodeId, binding.supervisorId(), binding.workerId())) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
        }
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        Optional<WorkIntent> ownerIntent = store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participant)).findFirst();
        if (store.collaborationProjection().activated() && (ownerIntent.isEmpty() || !record.authorityLineageId().equals(ownerIntent.get().authorityLineageId()))) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_LINEAGE_MISMATCH, AgentNextAction.ENSURE_SESSION, Map.of("reason", "CAPABILITY_OWNER_LINEAGE_MISMATCH"));
        }

        if (request.contractRevision() < 1 || request.contractRevision() != record.contractRevision()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY,
                    Map.of("expectedContractRevision", record.contractRevision()));
        }

        String respType = request.response().trim().toLowerCase(Locale.ROOT);

        // Idempotency check: repeated identical response returns success
        if (record.state() == CapabilityLifecycleState.ACCEPTED && "accept".equals(respType)) {
            return AgentResponse.ready("isolated", 0);
        }
        if (record.state() == CapabilityLifecycleState.REJECTED && "reject".equals(respType)) {
            return AgentResponse.ready("isolated", 0);
        }

        // Conflicting or late response on terminal/non-awaiting request returns stale_request
        if (record.state() != CapabilityLifecycleState.AWAITING_OWNER) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, null);
        }

        return switch (respType) {
            case "accept" -> {
                CapabilityRequestPayload payload = ownerPayload(record, binding, ownerNodeId, record.contract(), CapabilityLifecycleState.ACCEPTED, null);
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED, ownerNodeId, payload.encode(), identity);

                yield AgentResponse.ready("isolated", 0);
            }
            case "revise" -> {
                if (request.revision() == null) {
                    yield new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, AgentNextAction.RETRY,
                            Map.of("reason", "CAPABILITY_REVISION_REQUIRED"));
                }
                CapabilityRequestPayload payload = ownerPayload(record, binding, ownerNodeId, request.revision(), CapabilityLifecycleState.REVISION_REQUESTED, request.reason());
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, ownerNodeId, payload.encode(), identity);

                yield AgentResponse.ready("isolated", 0);
            }
            case "reject" -> {
                String reason = request.reason() != null && !request.reason().isBlank() ? request.reason() : "Capability request rejected by owner";
                CapabilityRequestPayload payload = ownerPayload(record, binding, ownerNodeId, record.contract(), CapabilityLifecycleState.REJECTED, reason);
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_REJECTED, ownerNodeId, payload.encode(), identity);

                yield AgentResponse.ready("isolated", 0);
            }
            default -> new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, AgentNextAction.RETRY, null);
        };
    }

    /** Builds an owner reply pinned to the contract revision the reply was decided against. */
    private static CapabilityRequestPayload ownerPayload(CapabilityRequestRecord record,
            ProviderSessionBindingService.Binding binding,
            String ownerNodeId,
            CapabilityContract contract,
            CapabilityLifecycleState state,
            String reason) {
        return new CapabilityRequestPayload(record.handle(),
                record.capability(),
                record.requesterNodeId(),
                record.requesterSupervisorId(),
                record.requesterWorkerId(),
                ownerNodeId,
                binding.supervisorId(),
                binding.workerId(),
                record.authorityLineageId(),
                contract,
                state,
                reason,
                record.contractRevision());
    }

    /**
     * Request parameters for an owner capability response.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param requestHandle        public request handle locator
     * @param response             response type ("accept", "revise", "reject")
     * @param revision             revised contract specification (optional, for "revise")
     * @param reason               rejection or revision explanation (optional)
     * @param contractRevision     exact contract revision shown in the owner inbox
     */
    public record OwnerResponseRequest(Path projectRoot,
                                       String provider,
                                       String connectionInstanceId,
                                       String requestHandle,
                                       String response,
                                       CapabilityContract revision,
                                       String reason,
                                       long contractRevision) {

        /**
         * Retains the previous constructor for internal callers; MCP responses must provide a revision.
         *
         * @param projectRoot control project root
         * @param provider provider identifier
         * @param connectionInstanceId exact provider connection
         * @param requestHandle public capability request handle
         * @param response owner's decision
         * @param revision replacement contract when requesting a revision
         * @param reason optional rejection or revision explanation
         */
        public OwnerResponseRequest(Path projectRoot,
                String provider,
                String connectionInstanceId,
                String requestHandle,
                String response,
                CapabilityContract revision,
                String reason) {
            this(projectRoot, provider, connectionInstanceId, requestHandle, response, revision, reason, -1);
        }

        /**
         * Validates non-null request parameters.
         */
        public OwnerResponseRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(requestHandle, "requestHandle");
            Objects.requireNonNull(response, "response");
        }
    }
}
