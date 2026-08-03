package org.synesis.workspace.application.capability;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.SessionAuthorityResolver;

import org.synesis.workspace.application.ProjectApplicationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestHandleGenerator;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.capability.SecureRandomCapabilityRequestHandleGenerator;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Application service for requesting capabilities and handling requester contract negotiation.
 *
 * <p>Handles coordination requests and capability contract descriptions.
 *
 * @since 1.0
 */
public final class CapabilityRequestService {

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;
    private final SessionAuthorityResolver authorityResolver;
    private final CapabilityRequestHandleGenerator handleGenerator;

    /**
     * Creates a capability request service backed by {@link SecureRandomCapabilityRequestHandleGenerator}.
     */
    public CapabilityRequestService() {
        this(new SecureRandomCapabilityRequestHandleGenerator());
    }

    /**
     * Creates a capability request service with an injectable handle generator.
     *
     * @param handleGenerator handle generator instance
     */
    public CapabilityRequestService(CapabilityRequestHandleGenerator handleGenerator) {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
        this.authorityResolver = new SessionAuthorityResolver(bindingService);
        this.handleGenerator = Objects.requireNonNull(handleGenerator, "handleGenerator");
    }

    /**
     * Request payload for describing or responding to a capability requirement.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param capability           target capability name
     * @param contract             capability contract specification
     * @param requestHandle        public request handle (optional)
     * @param revisionResponse     requester revision response ("accept", "counter", "cancel") (optional)
     * @param ownerAuthorityLineageId optional explicit owner authority lineage
     */
    public record DescribeCapabilityRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId,
            String capability,
            CapabilityContract contract,
            String requestHandle,
            String revisionResponse,
            UUID ownerAuthorityLineageId
    ) {
        /**
         * Validates non-null core parameters.
         */
        public DescribeCapabilityRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        }

        /** Constructs a request without an explicit owner lineage.
         * @param projectRoot project root
         * @param provider provider ID
         * @param connectionInstanceId connection ID
         * @param capability capability
         * @param contract contract
         * @param requestHandle request handle
         * @param revisionResponse revision response
         */
        public DescribeCapabilityRequest(Path projectRoot, String provider, String connectionInstanceId,
                String capability, CapabilityContract contract, String requestHandle, String revisionResponse) {
            this(projectRoot, provider, connectionInstanceId, capability, contract, requestHandle,
                    revisionResponse, null);
        }
    }

    /**
     * Executes the capability request description or requester revision response.
     *
     * @param request request payload
     * @return concise agent response
     */
    public AgentResponse describeRequiredCapability(DescribeCapabilityRequest request) {
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectApplicationService.ProjectLocation location;
        ProviderSessionBindingService.Binding binding;
        NodeIdentity identity;
        try {
            location = projectService.locate(root);
            binding = authorityResolver.resolve(location, request.provider(), request.connectionInstanceId());
            if (binding.worktreePath() == null) {
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.SESSION_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
            }
            identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

            String requesterNodeId = identity.nodeId();

        try {
            Path coordDir = location.root().resolve(".synesis/coordination");
            PredictionEventStore store = new PredictionEventStore(coordDir, location.projectId());
            CapabilityRequestProjection projection = store.capabilityRequestProjection();

            // Mode B: Requester responding to owner revision by handle
            if (request.requestHandle() != null && !request.requestHandle().isBlank() && request.revisionResponse() != null) {
                return handleRequesterRevisionResponse(request, store, projection, requesterNodeId, identity);
            }

            // Mode A: Initial capability description or counter-proposal without handle
            String capability = request.capability();
            if (capability == null || capability.isBlank() || capability.length() > 128) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, AgentNextAction.RETRY, null);
            }
            if (request.contract() == null) {
                return new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, null);
            }

            String requesterParticipant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            Optional<WorkIntent> requesterIntent = currentIntent(store, requesterParticipant);
            if (requesterIntent.isEmpty() && store.collaborationProjection().activated()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED,
                        AgentNextAction.ENSURE_SESSION, Map.of("reason", "COORDINATION_INTENT_REQUIRED"));
            }
            UUID requestedOwnerLineage = request.ownerAuthorityLineageId();
            UUID inferredOwnerLineage = requestedOwnerLineage != null
                    ? requestedOwnerLineage
                    : requesterIntent.isPresent()
                            ? inferUniqueOwnerLineage(store, requesterIntent.get().participant())
                            : null;

            // First-release collaboration lanes are the authoritative owner
            // source.  The older semantic-ownership projection is still
            // honored when present, but a capability request tied to an
            // active foreign lane must not require a second, unrelated task
            // ownership record before it can be durably recorded.
            String ownerNodeId = resolveOwnerNodeId(store, capability, inferredOwnerLineage,
                    requesterParticipant, requesterNodeId);
            if (ownerNodeId == null || ownerNodeId.isBlank()) {
                Map<String, Object> result = Map.of("capability", capability);
                return new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
            }

            UUID ownerLineage = request.ownerAuthorityLineageId() != null
                    ? request.ownerAuthorityLineageId()
                    : requesterIntent.isPresent()
                            ? inferUniqueOwnerLineage(store, requesterIntent.get().participant())
                            : UUID.nameUUIDFromBytes(("synesis-unscoped-owner-lineage:" + ownerNodeId)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (ownerLineage == null) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_LINEAGE_MISMATCH,
                        AgentNextAction.REQUEST_COORDINATION, Map.of("reason", "OWNER_LINEAGE_REQUIRED"));
            }

            // Check existing active request for (requester, capability)
            Optional<CapabilityRequestRecord> activeOpt = projection.findActiveByRequesterAndCapability(requesterNodeId, capability);

            if (activeOpt.isPresent()) {
                CapabilityRequestRecord activeRec = activeOpt.get();
                if (!activeRec.authorityLineageId().equals(ownerLineage)) {
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_LINEAGE_MISMATCH,
                            AgentNextAction.REQUEST_COORDINATION, Map.of("reason", "CAPABILITY_LINEAGE_REASSIGNMENT_REQUIRED"));
                }
                if (activeRec.contract().isEquivalent(request.contract())) {
                    // Equivalent request exists: return idempotent waiting response
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle", activeRec.handle().value());
                    result.put("pending", 1);
                    return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
                }

                if (activeRec.state() == CapabilityLifecycleState.AWAITING_OWNER) {
                    // Requester updates contract while editable
                    CapabilityRequestPayload payload = new CapabilityRequestPayload(
                            activeRec.handle(), capability, requesterNodeId, activeRec.ownerNodeId(),
                            activeRec.authorityLineageId(), request.contract(), CapabilityLifecycleState.AWAITING_OWNER, null);
                    store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, requesterNodeId, payload.encode(), identity);

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle", activeRec.handle().value());
                    result.put("pending", 1);
                    return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
                } else if (activeRec.state() == CapabilityLifecycleState.REVISION_REQUESTED) {
                    // Owner review has produced revision feedback: contract counter-proposal updates contract back to AWAITING_OWNER
                    CapabilityRequestPayload payload = new CapabilityRequestPayload(
                            activeRec.handle(), capability, requesterNodeId, activeRec.ownerNodeId(),
                            activeRec.authorityLineageId(), request.contract(), CapabilityLifecycleState.AWAITING_OWNER, null);
                    store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, requesterNodeId, payload.encode(), identity);

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle", activeRec.handle().value());
                    result.put("pending", 1);
                    return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
                }
            }

            // Create new capability request
            CapabilityRequestHandle handle = handleGenerator.generate();
            CapabilityRequestPayload payload = new CapabilityRequestPayload(
                    handle, capability, requesterNodeId, binding.supervisorId(), binding.workerId(),
                    ownerNodeId, "", "", ownerLineage, request.contract(), CapabilityLifecycleState.AWAITING_OWNER, null);
            store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED, requesterNodeId, payload.encode(), identity);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("capabilityRequestHandle", handle.value());
            result.put("pending", 1);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);

        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
        }
    }

    private AgentResponse handleRequesterRevisionResponse(
            DescribeCapabilityRequest request,
            PredictionEventStore store,
            CapabilityRequestProjection projection,
            String requesterNodeId,
            NodeIdentity identity) throws Exception {

        String handleStr = request.requestHandle();
        Optional<CapabilityRequestRecord> recOpt = projection.findByHandle(handleStr);
        if (recOpt.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY, null);
        }

        CapabilityRequestRecord record = recOpt.get();
        if (!record.requesterNodeId().equals(requesterNodeId)) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
        }

        if (record.state() == CapabilityLifecycleState.CANCELLED
                || record.state() == CapabilityLifecycleState.EXPIRED
                || record.state() == CapabilityLifecycleState.SUPERSEDED
                || record.state() == CapabilityLifecycleState.REJECTED) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, null);
        }

        String revResp = request.revisionResponse().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (revResp) {
            case "accept" -> {
                CapabilityRequestPayload payload = new CapabilityRequestPayload(
                        record.handle(), record.capability(), requesterNodeId, record.ownerNodeId(), record.authorityLineageId(),
                        record.contract(), CapabilityLifecycleState.ACCEPTED, null);
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED, requesterNodeId, payload.encode(), identity);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", record.handle().value());
                result.put("pending", 1);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.IMPLEMENTATION_UNAVAILABLE, AgentNextAction.WAIT, result);
            }
            case "counter" -> {
                if (request.contract() == null) {
                    yield new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, null);
                }
                CapabilityRequestPayload payload = new CapabilityRequestPayload(
                        record.handle(), record.capability(), requesterNodeId, record.ownerNodeId(), record.authorityLineageId(),
                        request.contract(), CapabilityLifecycleState.AWAITING_OWNER, null);
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, requesterNodeId, payload.encode(), identity);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", record.handle().value());
                result.put("pending", 1);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
            }
            case "cancel" -> {
                CapabilityRequestPayload payload = new CapabilityRequestPayload(
                        record.handle(), record.capability(), requesterNodeId, record.ownerNodeId(), record.authorityLineageId(),
                        record.contract(), CapabilityLifecycleState.CANCELLED, "Cancelled by requester");
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CANCELLED, requesterNodeId, payload.encode(), identity);

                Map<String, Object> result = Map.of("request", record.handle().value());
                yield new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, result);
            }
            default -> new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, AgentNextAction.RETRY, null);
        };
    }

    private static String resolveOwnerNodeId(PredictionEventStore store, String capability,
            UUID requestedOwnerLineage, String requesterParticipant, String localNodeId) {
        Optional<OwnershipClaim> claim = store.coordinationProjection().ownership(capability);
        if (claim.isPresent()) {
            return claim.get().ownerNodeId();
        }
        if (requestedOwnerLineage == null) {
            return null;
        }
        boolean foreignActiveLineage = store.collaborationProjection().activeIntents().stream()
                .anyMatch(intent -> !intent.participant().equals(requesterParticipant)
                        && intent.authorityLineageId().equals(requestedOwnerLineage));
        // Provider bindings in the local project share the project node
        // identity; the durable lineage and exact owner session remain the
        // authority boundary for the response and publication steps.
        return foreignActiveLineage ? localNodeId : null;
    }

    private static Optional<WorkIntent> currentIntent(PredictionEventStore store, String participant) {
        return store.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participant().equals(participant))
                .findFirst();
    }

    private static UUID inferUniqueOwnerLineage(PredictionEventStore store, String requesterParticipant) {
        List<WorkIntent> candidates = store.collaborationProjection().activeIntents().stream()
                .filter(intent -> !intent.participant().equals(requesterParticipant))
                .toList();
        if (candidates.size() != 1) {
            return null;
        }
        return candidates.getFirst().authorityLineageId();
    }
}
