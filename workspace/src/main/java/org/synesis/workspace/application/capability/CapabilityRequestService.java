package org.synesis.workspace.application.capability;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.application.PeerDependencyPolicy;
import org.synesis.coordination.application.RequesterAnswerPolicy;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestHandleGenerator;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.capability.SecureRandomCapabilityRequestHandleGenerator;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.session.CallerAdmission;
import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/**
 * Application service for requesting capabilities and handling requester contract negotiation.
 *
 * <p>Handles coordination requests and capability contract descriptions.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class CapabilityRequestService {

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;
    private final SessionAuthorityResolver authorityResolver;
    private final CapabilityRequestHandleGenerator handleGenerator;

    /**
     * Creates a capability request service backed by
     * {@link SecureRandomCapabilityRequestHandleGenerator}.
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

    /** Returns the open lanes of other participants in one lineage: the lanes a request addressed to it reaches. */
    private static List<WorkIntent> ownerIntents(PredictionEventStore store, UUID authorityLineageId, String requesterParticipant) {
        return store.collaborationProjection().activeIntents().stream()
                .filter(intent -> !intent.participant().equals(requesterParticipant))
                .filter(intent -> intent.authorityLineageId().equals(authorityLineageId))
                .toList();
    }

    private Optional<ProviderSessionBindingService.Binding> exactOwnerBinding(ProjectLocation location,
            PredictionEventStore store,
            UUID authorityLineageId,
            String requesterParticipant,
            String ownerNodeId) throws Exception {
        List<WorkIntent> ownerIntents = ownerIntents(store, authorityLineageId, requesterParticipant);
        if (ownerIntents.isEmpty()) {
            return Optional.empty();
        }
        if (ownerIntents.size() != 1) {
            throw new IllegalStateException("CAPABILITY_OWNER_INTENT_AMBIGUOUS");
        }

        WorkIntent ownerIntent = ownerIntents.getFirst();
        List<ProviderSessionBindingService.Binding> matches = bindingService.list(location, ownerIntent.provider()).stream()
                .filter(candidate -> "BOUND".equals(candidate.status()))
                .filter(candidate -> "VERIFIED".equals(candidate.providerTrustState()))
                .filter(candidate -> candidate.worktreePath() != null)
                .filter(candidate -> candidate.nodeId().equals(ownerNodeId))
                .filter(candidate -> WorkspaceCollaborationService.participantHandle(candidate.sessionId()).equals(ownerIntent.participant()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(matches.isEmpty() ? "CAPABILITY_OWNER_BINDING_UNAVAILABLE" : "CAPABILITY_OWNER_BINDING_AMBIGUOUS");
        }
        return Optional.of(matches.getFirst());
    }

    private static String resolveOwnerNodeId(PredictionEventStore store, String capability, UUID requestedOwnerLineage, String requesterParticipant, String localNodeId) {
        Optional<OwnershipClaim> claim = store.coordinationProjection().ownership(capability);
        if (claim.isPresent()) {
            return claim.get().ownerNodeId();
        }
        if (requestedOwnerLineage == null) {
            return null;
        }
        boolean foreignActiveLineage = !ownerIntents(store, requestedOwnerLineage, requesterParticipant).isEmpty();
        // Provider bindings in the local project share the project node
        // identity; the durable lineage and exact owner session remain the
        // authority boundary for the response and publication steps.
        return foreignActiveLineage ? localNodeId : null;
    }

    private static java.util.Set<String> integratedDependencies(PredictionEventStore store,
            CapabilityRequestProjection projection,
            WorkIntent requesterIntent,
            String requesterNodeId,
            ProviderSessionBindingService.Binding binding) {
        java.util.Set<String> requested = projection.findAllForRequester(requesterNodeId).stream()
                .filter(record -> record.matchesRequester(requesterNodeId, binding.supervisorId(), binding.workerId()))
                .filter(record -> !RequesterAnswerPolicy.ownerFinishedWithoutAnswer(record))
                .map(CapabilityRequestRecord::capability)
                .collect(java.util.stream.Collectors.toSet());
        return PeerDependencyPolicy.satisfiedByIntegration(store.collaborationProjection(), store.taskCompletionProjection(), requesterIntent, requested);
    }

    private static Optional<WorkIntent> currentIntent(PredictionEventStore store, String participant) {
        return store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participant)).findFirst();
    }

    static UUID unscopedOwnerLineage(String ownerNodeId) {
        return UUID.nameUUIDFromBytes(("synesis-unscoped-owner-lineage:" + ownerNodeId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static UUID inferUniqueOwnerLineage(PredictionEventStore store, String requesterParticipant) {
        List<WorkIntent> candidates = store.collaborationProjection().activeIntents().stream().filter(intent -> !intent.participant().equals(requesterParticipant)).toList();
        if (candidates.size() != 1) {
            return null;
        }
        return candidates.getFirst().authorityLineageId();
    }

    /**
     * Returns the lineage of the lane a new request is addressed to, or {@code null} when it
     * cannot be told.
     *
     * <p>Claims are exclusive, so the lane that claims the file a requester names is the owner of
     * what that file provides, and that is durable state. A lineage the requester supplies is only
     * its belief. With five lanes open, agents reused the lineage of a lane they had collided with
     * over an unrelated file, and every request for the storage API went to the lane writing the
     * usage notes. A named file therefore decides alone: it is never overruled by a lineage sent
     * with it, and when no other lane claims it the single-other-lane guess is not used either.</p>
     */
    private static UUID addressedLineage(PredictionEventStore store, DescribeCapabilityRequest request, Optional<WorkIntent> requesterIntent, String requesterParticipant) {
        if (request.ownerPath() != null) {
            List<WorkIntent> claimants = store.collaborationProjection().activeIntents().stream()
                    .filter(intent -> !intent.participant().equals(requesterParticipant))
                    .filter(intent -> intent.selectors().stream().anyMatch(request.ownerPath()::overlaps))
                    .toList();
            return claimants.size() == 1 ? claimants.getFirst().authorityLineageId() : null;
        }
        if (request.ownerAuthorityLineageId() != null) {
            return request.ownerAuthorityLineageId();
        }
        return requesterIntent.map(intent -> inferUniqueOwnerLineage(store, intent.participant())).orElse(null);
    }

    /**
     * Answers a request whose owner cannot be told with what is claimed now and how to name the
     * owner. The bare capability name left the requester to guess a lineage or to ask again.
     */
    private static AgentResponse ownerRequired(PredictionEventStore store, DescribeCapabilityRequest request, String requesterParticipant) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capability", request.capability());
        result.put("lanes", CollaborationResponseMapper.otherLanes(store.collaborationProjection(), requesterParticipant));
        result.put("instruction", request.ownerPath() != null
                ? "No other lane claims " + request.ownerPath().value() + ", so nobody can answer this request yet. lanes lists what each open lane claims. "
                        + "If that file is still to be built, its lane has not announced: call get_next_action, and submit the request again once a lane claims the file."
                : "Synesis cannot tell which lane owns this capability. Submit the request again with payload.ownerPath set to the repository file that implements it; "
                        + "the lane that claims that file receives the request. lanes lists what each open lane claims.");
        return new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
    }

    /**
     * Builds the immutable payload for a requester contract revision.
     *
     * @param activeRec     current capability request
     * @param capability    capability name
     * @param requesterNode requester node identity
     * @param contract      revised contract
     * @return revision payload
     */
    private static CapabilityRequestPayload revisedPayload(CapabilityRequestRecord activeRec, String capability, String requesterNode, CapabilityContract contract) {
        return new CapabilityRequestPayload(activeRec.handle(),
                capability,
                requesterNode,
                activeRec.ownerNodeId(),
                activeRec.authorityLineageId(),
                contract,
                CapabilityLifecycleState.AWAITING_OWNER,
                null,
                activeRec.contractRevision());
    }

    private static Map<String, Object> capabilityContractMap(CapabilityContract contract) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("purpose", contract.purpose());
        result.put("inputs", contract.inputs());
        result.put("output", contract.output());
        result.put("requiredBehavior", contract.requiredBehavior());
        result.put("errorSemantics", contract.errorSemantics());
        result.put("sideEffects", contract.sideEffects());
        result.put("invariants", contract.invariants());
        result.put("compatibility", contract.compatibility());
        result.put("performance", contract.performance());
        result.put("concurrency", contract.concurrency());
        result.put("acceptanceTests", contract.acceptanceTests());
        return result;
    }

    /**
     * Executes the capability request description or requester revision response.
     *
     * <p>The exact binding, the capability projection, the active-request lookup, the revision
     * checks, and the append all happen under one {@link ProjectAppendLock}. Deciding from a
     * projection read before the lock would let two processes each record an active request for
     * the same requester and capability, or append a requester reply to a contract revision a peer
     * already answered.
     *
     * @param request request payload
     * @return concise agent response
     */
    public AgentResponse describeRequiredCapability(DescribeCapabilityRequest request) {
        Objects.requireNonNull(request, "request");

        return CallerAdmission.underAppendLock(request.projectRoot(),
                projectService,
                authorityResolver,
                request.provider(),
                request.connectionInstanceId(),
                admitted -> describeLocked(request, admitted.location(), admitted.location().openCoordinationStore(), admitted.binding(), admitted.identity()));
    }

    /** Records or revises a request against the projection loaded under the append lock. */
    private AgentResponse describeLocked(DescribeCapabilityRequest request,
            ProjectLocation location,
            PredictionEventStore store,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity) throws Exception {
        String requesterNodeId = identity.nodeId();
        CapabilityRequestProjection projection = store.capabilityRequestProjection();

        // Mode B: Requester responding to owner revision by handle
        if (request.requestHandle() != null && !request.requestHandle().isBlank() && request.revisionResponse() != null) {
            return handleRequesterRevisionResponse(request, store, projection, requesterNodeId, binding, identity);
        }

        // Mode A: initial capability request or an optimistic update to an awaiting request
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
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, Map.of("reason", "COORDINATION_INTENT_REQUIRED"));
        }
        UUID addressedLineage = addressedLineage(store, request, requesterIntent, requesterParticipant);

        // First-release collaboration lanes are the authoritative owner
        // source.  The older semantic-ownership projection is still
        // honored when present, but a capability request tied to an
        // active foreign lane must not require a second, unrelated task
        // ownership record before it can be durably recorded.
        String ownerNodeId = resolveOwnerNodeId(store, capability, addressedLineage, requesterParticipant, requesterNodeId);
        if (ownerNodeId == null || ownerNodeId.isBlank()) {
            if (requesterIntent.isPresent() && integratedDependencies(store, projection, requesterIntent.get(), requesterNodeId, binding).contains(capability)) {
                // Nobody is left to negotiate with, and what this lane depends on is already in
                // its base. Answering "find an owner" again would send it in a circle.
                return new AgentResponse(AgentStatus.READY, null, null, Map.of("capability", capability, "reason", "CAPABILITY_OWNER_INTEGRATED"));
            }
            return ownerRequired(store, request, requesterParticipant);
        }

        // A project with no lanes has only its semantic owner to address, and no lineage to name.
        UUID ownerLineage = addressedLineage == null && requesterIntent.isEmpty() ? unscopedOwnerLineage(ownerNodeId) : addressedLineage;
        if (ownerLineage == null) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_LINEAGE_MISMATCH, AgentNextAction.REQUEST_COORDINATION, Map.of("reason", "OWNER_LINEAGE_REQUIRED"));
        }

        // Check existing active request for (requester, capability)
        Optional<CapabilityRequestRecord> activeOpt = projection.findAllForRequester(requesterNodeId).stream().filter(record -> record.matchesRequester(requesterNodeId, binding.supervisorId(), binding.workerId()))
                .filter(record -> record.capability().equals(capability)).filter(record -> record.state().isUnderNegotiation())
                .findFirst();

        if (activeOpt.isPresent()) {
            CapabilityRequestRecord activeRec = activeOpt.get();
            if (!activeRec.authorityLineageId().equals(ownerLineage)) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_LINEAGE_MISMATCH, AgentNextAction.REQUEST_COORDINATION, Map.of("reason", "CAPABILITY_LINEAGE_REASSIGNMENT_REQUIRED"));
            }
            if (activeRec.contract().isEquivalent(request.contract())) {
                // Equivalent request exists: return idempotent waiting response
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("capabilityRequestHandle", activeRec.handle().value());
                result.put("contractRevision", activeRec.contractRevision());
                result.put("pending", 1);
                return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
            }

            if (request.contractRevision() < 1 || request.contractRevision() != activeRec.contractRevision()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY,
                        Map.of("capabilityRequestHandle", activeRec.handle().value(), "expectedContractRevision", activeRec.contractRevision()));
            }
            if (activeRec.state() == CapabilityLifecycleState.AWAITING_OWNER) {
                CapabilityRequestPayload payload = revisedPayload(activeRec, capability, requesterNodeId, request.contract());
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, requesterNodeId, payload.encode(), identity);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("capabilityRequestHandle", activeRec.handle().value());
                result.put("contractRevision", activeRec.contractRevision());
                result.put("pending", 1);
                return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
            }
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY,
                    Map.of("capabilityRequestHandle", activeRec.handle().value(), "expectedContractRevision", activeRec.contractRevision()));
        }

        // A lane that has prepared its completion closes without reading its inbox again. A
        // request recorded against it now would never be answered, so it is not recorded; the
        // requester waits for that lane's work to be integrated instead.
        boolean ownerClosing = ownerIntents(store, ownerLineage, requesterParticipant).stream().anyMatch(intent -> PeerDependencyPolicy.closing(store.taskCompletionProjection(), intent));
        if (ownerClosing) {
            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("capability", capability, "reason", "CAPABILITY_OWNER_COMPLETING"));
        }

        // Create new capability request
        CapabilityRequestHandle handle = handleGenerator.generate();
        String ownerSupervisorId = "";
        String ownerWorkerId = "";
        Optional<ProviderSessionBindingService.Binding> activeOwnerBinding = exactOwnerBinding(location, store, ownerLineage, requesterParticipant, ownerNodeId);
        if (activeOwnerBinding.isPresent()) {
            ownerSupervisorId = activeOwnerBinding.get().supervisorId();
            ownerWorkerId = activeOwnerBinding.get().workerId();
        } else {
            OwnershipClaim ownerClaim = store.coordinationProjection().ownership(capability).orElse(null);
            if (ownerClaim == null || ownerClaim.ownerSupervisorId().equals(binding.supervisorId())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_LINEAGE_MISMATCH, AgentNextAction.REQUEST_COORDINATION,
                        Map.of("reason", "CAPABILITY_OWNER_BINDING_UNAVAILABLE"));
            }
            ownerSupervisorId = ownerClaim.ownerSupervisorId();
        }
        CapabilityRequestPayload payload = new CapabilityRequestPayload(handle,
                capability,
                requesterNodeId,
                binding.supervisorId(),
                binding.workerId(),
                ownerNodeId,
                ownerSupervisorId,
                ownerWorkerId,
                ownerLineage,
                request.contract(),
                CapabilityLifecycleState.AWAITING_OWNER,
                null,
                0);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED, requesterNodeId, payload.encode(), identity);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capabilityRequestHandle", handle.value());
        result.put("pending", 1);
        // Shown so a requester that addressed the wrong lane sees it now, not after a silent wait.
        ownerIntents(store, ownerLineage, requesterParticipant).stream().findFirst().ifPresent(owner -> result.put("owner", CollaborationResponseMapper.laneMap(owner)));
        return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
    }

    /**
     * Rejects a requester reply that no longer matches the recorded request, naming which part
     * differs. The caller cannot otherwise tell a superseded revision from a mistyped capability or
     * a contract that is not the one the owner proposed.
     */
    private static AgentResponse staleRevisionResponse(CapabilityRequestRecord record, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reason", reason);
        result.put("capability", record.capability());
        result.put("expectedContractRevision", record.contractRevision());
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, result);
    }

    private AgentResponse handleRequesterRevisionResponse(DescribeCapabilityRequest request,
            PredictionEventStore store,
            CapabilityRequestProjection projection,
            String requesterNodeId,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity)
            throws GeneralSecurityException, IOException {

        String handleStr = request.requestHandle();
        Optional<CapabilityRequestRecord> recOpt = projection.findByHandle(handleStr);
        if (recOpt.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY, null);
        }

        CapabilityRequestRecord record = recOpt.get();
        if (!record.matchesRequester(requesterNodeId, binding.supervisorId(), binding.workerId())) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, null);
        }

        if (request.contractRevision() < 1 || request.contractRevision() != record.contractRevision()) {
            return staleRevisionResponse(record, "CAPABILITY_CONTRACT_REVISION_STALE");
        }
        if (!record.capability().equals(request.capability())) {
            return staleRevisionResponse(record, "CAPABILITY_NAME_MISMATCH");
        }

        if (RequesterAnswerPolicy.ownerFinishedWithoutAnswer(record)) {
            // The answer arrives after the owner stopped waiting for it. Calling that a stale
            // request would leave the requester with nothing to do; say what happened instead.
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("capability", record.capability());
            result.put("reason", RequesterAnswerPolicy.OWNER_FINISHED_WITHOUT_ANSWER);
            result.put("proposedContract", capabilityContractMap(record.contract()));
            result.put("guidance", "The owner stopped waiting for this answer and finished. It may have built a different design than you asked for; the integrated code is what counts. Its files are released, so you can claim them if you need a change.");
            return new AgentResponse(AgentStatus.READY, null, null, result);
        }
        if (!record.state().bindsLanes()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, null);
        }

        String revResp = request.revisionResponse().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (revResp) {
            case "accept" -> {
                if (record.state() != CapabilityLifecycleState.REVISION_REQUESTED) {
                    yield staleRevisionResponse(record, "CAPABILITY_REVISION_NOT_PENDING");
                }
                if (request.contract() == null || !record.contract().isEquivalent(request.contract())) {
                    yield staleRevisionResponse(record, "CAPABILITY_CONTRACT_MISMATCH");
                }
                CapabilityRequestPayload payload = new CapabilityRequestPayload(record.handle(),
                        record.capability(),
                        requesterNodeId,
                        record.ownerNodeId(),
                        record.authorityLineageId(),
                        record.contract(),
                        CapabilityLifecycleState.ACCEPTED,
                        null,
                        record.contractRevision());
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED, requesterNodeId, payload.encode(), identity);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", record.handle().value());
                result.put("pending", 1);
                result.put("capability", record.capability());
                result.put("contractRevision", record.contractRevision());
                result.put("contract", capabilityContractMap(record.contract()));
                result.put("contractAccepted", true);
                yield new AgentResponse(AgentStatus.READY, null, null, result);
            }
            case "counter" -> {
                if (record.state() != CapabilityLifecycleState.REVISION_REQUESTED || request.contract() == null) {
                    yield new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, null);
                }
                CapabilityRequestPayload payload = new CapabilityRequestPayload(record.handle(),
                        record.capability(),
                        requesterNodeId,
                        record.ownerNodeId(),
                        record.authorityLineageId(),
                        request.contract(),
                        CapabilityLifecycleState.AWAITING_OWNER,
                        null,
                        record.contractRevision());
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, requesterNodeId, payload.encode(), identity);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("request", record.handle().value());
                result.put("pending", 1);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
            }
            case "cancel" -> {
                if (!record.state().isUnderNegotiation()) {
                    yield new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, null);
                }
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CANCELLED, requesterNodeId, record.cancellation("Cancelled by requester").encode(), identity);

                Map<String, Object> result = Map.of("request", record.handle().value());
                yield new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, result);
            }
            default -> new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, AgentNextAction.RETRY, null);
        };
    }

    /**
     * Request payload for describing or responding to a capability requirement.
     *
     * @param projectRoot             control project root path
     * @param provider                provider identifier
     * @param connectionInstanceId    connection instance identifier
     * @param capability              target capability name
     * @param contract                capability contract specification
     * @param requestHandle           public request handle (optional)
     * @param revisionResponse        requester revision response ("accept", "counter", "cancel")
     *                                (optional)
     * @param ownerAuthorityLineageId optional explicit owner authority lineage
     * @param contractRevision        exact existing contract revision being answered
     * @param ownerPath               optional file implementing the capability; the lane claiming
     *                                it is the owner, whatever lineage is also sent
     */
    public record DescribeCapabilityRequest(Path projectRoot, String provider, String connectionInstanceId, String capability, CapabilityContract contract, String requestHandle, String revisionResponse,
                                            UUID ownerAuthorityLineageId, long contractRevision, ResourceSelector ownerPath) {

        /**
         * Validates non-null core parameters.
         */
        public DescribeCapabilityRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        }

        /**
         * Constructs a request without an explicit owner lineage.
         *
         * @param projectRoot          project root
         * @param provider             provider ID
         * @param connectionInstanceId connection ID
         * @param capability           capability
         * @param contract             contract
         * @param requestHandle        request handle
         * @param revisionResponse     revision response
         */
        public DescribeCapabilityRequest(Path projectRoot, String provider, String connectionInstanceId, String capability, CapabilityContract contract, String requestHandle, String revisionResponse) {
            this(projectRoot, provider, connectionInstanceId, capability, contract, requestHandle, revisionResponse, null, -1);
        }

        /**
         * Constructs a request that names no owner file.
         *
         * @param projectRoot             project root
         * @param provider                provider ID
         * @param connectionInstanceId    connection ID
         * @param capability              capability
         * @param contract                contract
         * @param requestHandle           request handle
         * @param revisionResponse        revision response
         * @param ownerAuthorityLineageId optional explicit owner authority lineage
         * @param contractRevision        exact existing contract revision being answered
         */
        public DescribeCapabilityRequest(Path projectRoot, String provider, String connectionInstanceId, String capability, CapabilityContract contract, String requestHandle, String revisionResponse,
                UUID ownerAuthorityLineageId, long contractRevision) {
            this(projectRoot, provider, connectionInstanceId, capability, contract, requestHandle, revisionResponse, ownerAuthorityLineageId, contractRevision, null);
        }
    }
}
