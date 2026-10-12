package org.synesis.workspace.application.capability;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.synesis.coordination.application.PeerDependencyPolicy;
import org.synesis.coordination.application.RequesterAnswerPolicy;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestHandleGenerator;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.capability.SecureRandomCapabilityRequestHandleGenerator;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.contract.ContractBinding;
import org.synesis.coordination.domain.contract.ContractChangeRequest;
import org.synesis.coordination.domain.contract.ContractCodec;
import org.synesis.coordination.domain.contract.ContractRecord;
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
 * What a lane that builds on another lane's file does with the public contract over it: bind to
 * a revision, and say what it needs that the contract does not (ADR-0092).
 *
 * <p>The file names the owner (ADR-0090): claims are exclusive, so the open lane of another
 * participant that claims the file is the owner, and the one active contract covering the file is
 * the text. A consumer no longer writes a contract of its own. Each used to, the owner agreed a
 * separate text with each, and two of them contradicted each other.</p>
 *
 * <p>A binding is the consumer's request record, created already agreed. From there it is what it
 * was: the implementation revisions the owner publishes to it, the consumer's validation, and
 * every gate on finish, integration, cancellation and release.</p>
 */
public final class ContractConsumerService {

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;
    private final SessionAuthorityResolver authorityResolver;
    private final CapabilityRequestHandleGenerator handleGenerator;

    /**
     * Creates a service backed by {@link SecureRandomCapabilityRequestHandleGenerator}.
     */
    public ContractConsumerService() {
        this(new SecureRandomCapabilityRequestHandleGenerator());
    }

    /**
     * Creates a service with an injectable handle generator.
     *
     * @param handleGenerator handle generator instance
     */
    public ContractConsumerService(CapabilityRequestHandleGenerator handleGenerator) {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
        this.authorityResolver = new SessionAuthorityResolver(bindingService);
        this.handleGenerator = Objects.requireNonNull(handleGenerator, "handleGenerator");
    }

    /**
     * Binds the caller's lane to the current revision of the contract over a file, or binds it
     * again after the owner published a new revision.
     *
     * <p>The owner lookup, the contract lookup, the existing-binding lookup and the appends all
     * happen under one {@link ProjectAppendLock}, so two processes cannot each record a binding
     * for the same consumer and dependency, or bind to a revision a peer has just replaced.</p>
     *
     * @param request what to bind to
     * @return the binding, or what stands in its way
     */
    public AgentResponse bind(BindRequest request) {
        Objects.requireNonNull(request, "request");
        return CallerAdmission.underAppendLock(request.projectRoot(), projectService, authorityResolver, request.provider(), request.connectionInstanceId(),
                admitted -> bindLocked(request, admitted.location(), admitted.location().openCoordinationStore(), admitted.binding(), admitted.identity()));
    }

    /**
     * Files what the caller's lane needs from a file that its contract does not say, or asks for
     * a contract where none exists yet.
     *
     * @param request the need
     * @return the open change request, or what stands in its way
     */
    public AgentResponse requestChange(ChangeRequest request) {
        Objects.requireNonNull(request, "request");
        return CallerAdmission.underAppendLock(request.projectRoot(), projectService, authorityResolver, request.provider(), request.connectionInstanceId(),
                admitted -> changeLocked(request, admitted.location().openCoordinationStore(), admitted.binding(), admitted.identity()));
    }

    private AgentResponse bindLocked(BindRequest request, ProjectLocation location, PredictionEventStore store, ProviderSessionBindingService.Binding binding, NodeIdentity identity)
            throws Exception {
        String capability = request.capability();
        if (capability == null || capability.isBlank() || capability.length() > 128) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, AgentNextAction.RETRY, Map.of("reason", "CAPABILITY_NAME_REQUIRED"));
        }
        String requesterNodeId = identity.nodeId();
        String requesterParticipant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        Optional<WorkIntent> requesterIntent = WorkspaceCollaborationService.laneOf(store, requesterParticipant);
        if (requesterIntent.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, Map.of("reason", "COORDINATION_INTENT_REQUIRED"));
        }
        CapabilityRequestProjection projection = store.capabilityRequestProjection();
        Optional<WorkIntent> owner = WorkspaceCollaborationService.claimantOf(store, request.ownerPath(), requesterParticipant);
        if (owner.isEmpty()) {
            Optional<ContractRecord> finished = store.contractProjection().frozenCovering(request.ownerPath());
            if (finished.isPresent()) {
                return bindToIntegratedWork(request, store, projection, binding, identity, finished.get());
            }
            if (integratedDependencies(store, projection, requesterIntent.get(), requesterNodeId, binding).contains(capability)) {
                // Nobody is left to bind to, and what this lane depends on is already in its
                // base. Answering "find an owner" again would send it in a circle.
                return new AgentResponse(AgentStatus.READY, null, null, Map.of("capability", capability, "reason", "CAPABILITY_OWNER_INTEGRATED"));
            }
            return ownerRequired(store, capability, request.ownerPath(), requesterParticipant);
        }
        Optional<ContractRecord> contract = store.contractProjection().activeCovering(request.ownerPath()).filter(candidate -> candidate.ownerLineageId().equals(owner.get().authorityLineageId()));
        if (contract.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("capability", capability);
            result.put("reason", "CONTRACT_NOT_PUBLISHED");
            result.put("owner", CollaborationResponseMapper.laneMap(owner.get()));
            result.put("instruction", "The lane that claims " + request.ownerPath().value() + " has published no contract over it yet. "
                    + "File request_coordination kind contract_change_request with ownerPath and what you need from that file; the owner publishes the contract, and get_next_action shows it to bind to.");
            return new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
        }
        ContractRecord current = contract.get();
        if (request.revision() != current.revision()) {
            Map<String, Object> result = new LinkedHashMap<>(CollaborationResponseMapper.contractMap(current));
            result.put("reason", "CONTRACT_REVISION_STALE");
            result.put("instruction", "Binding accepts one exact revision. Read the contract shown here and bind to revision " + current.revision()
                    + ", or file a contract_change_request if it does not cover what you need.");
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, result);
        }

        Optional<CapabilityRequestRecord> existing = projection.findAllForRequester(requesterNodeId).stream()
                .filter(record -> record.matchesRequester(requesterNodeId, binding.supervisorId(), binding.workerId()))
                .filter(record -> record.capability().equals(capability)).filter(record -> record.state().bindsLanes())
                .findFirst();
        if (existing.isPresent()) {
            CapabilityRequestRecord record = existing.get();
            Optional<ContractBinding> bound = store.contractProjection().binding(record.handle().value());
            if (bound.isEmpty() || !bound.get().contractId().equals(current.contractId())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_LINEAGE_MISMATCH, AgentNextAction.REQUEST_COORDINATION,
                        Map.of("reason", "CAPABILITY_BOUND_TO_ANOTHER_CONTRACT", "capabilityRequestHandle", record.handle().value()));
            }
            if (record.state() == CapabilityLifecycleState.REVISION_REQUESTED) {
                // Binding again: the record already carries the revised text, and accepting it is
                // what a requester's acceptance of an owner revision has always been.
                CapabilityRequestPayload accepted = new CapabilityRequestPayload(record.handle(), record.capability(), requesterNodeId, record.ownerNodeId(), record.authorityLineageId(),
                        record.contract(), CapabilityLifecycleState.ACCEPTED, null, record.contractRevision());
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_ACCEPTED, requesterNodeId, accepted.encode(), identity);
                store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_DEPENDENCY_BOUND, requesterNodeId,
                        ContractCodec.encodeBound(new ContractBinding(record.handle().value(), current.contractId(), current.revision())), identity);
            }
            return boundAnswer(record.handle(), capability, current, owner.get());
        }

        // A lane that has prepared its completion closes without reading its inbox again. A
        // binding recorded against it now would never be served, so it is not recorded; the
        // consumer waits for that lane's work to be integrated instead.
        if (PeerDependencyPolicy.closing(store.taskCompletionProjection(), owner.get())) {
            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("capability", capability, "reason", "CAPABILITY_OWNER_COMPLETING"));
        }
        Optional<ProviderSessionBindingService.Binding> ownerBinding = exactOwnerBinding(location, owner.get(), requesterNodeId);
        if (ownerBinding.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_LINEAGE_MISMATCH, AgentNextAction.REQUEST_COORDINATION, Map.of("reason", "CAPABILITY_OWNER_BINDING_UNAVAILABLE"));
        }
        CapabilityRequestHandle handle = handleGenerator.generate();
        CapabilityRequestPayload created = new CapabilityRequestPayload(handle, capability, requesterNodeId, binding.supervisorId(), binding.workerId(), requesterNodeId,
                ownerBinding.get().supervisorId(), ownerBinding.get().workerId(), owner.get().authorityLineageId(), current.terms(), CapabilityLifecycleState.ACCEPTED, null, 0);
        store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED, requesterNodeId, created.encode(), identity);
        store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_DEPENDENCY_BOUND, requesterNodeId,
                ContractCodec.encodeBound(new ContractBinding(handle.value(), current.contractId(), current.revision())), identity);
        return boundAnswer(handle, capability, current, owner.get());
    }

    /**
     * Records that the caller builds on a file whose owner has finished and been integrated
     * (ADR-0092).
     *
     * <p>Nobody is left to serve a binding or to answer for the file, and nothing is left to
     * validate: the work is in the control branch. The record is created already closed as "the
     * owner finished", which is what makes the dependency count as satisfied from then on. Without
     * it the consumer was told that no lane claims the file yet, and waited for one.</p>
     */
    private AgentResponse bindToIntegratedWork(BindRequest request, PredictionEventStore store, CapabilityRequestProjection projection, ProviderSessionBindingService.Binding binding,
            NodeIdentity identity, ContractRecord finished) throws Exception {
        if (request.revision() != finished.revision()) {
            Map<String, Object> result = new LinkedHashMap<>(CollaborationResponseMapper.contractMap(finished));
            result.put("reason", "CONTRACT_REVISION_STALE");
            result.put("instruction", "The lane that owned this file has finished and its work is integrated; this is its contract as it stands. Bind to revision " + finished.revision() + ".");
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, result);
        }
        String requesterNodeId = identity.nodeId();
        boolean recorded = projection.findAllForRequester(requesterNodeId).stream()
                .filter(record -> record.matchesRequester(requesterNodeId, binding.supervisorId(), binding.workerId()))
                .anyMatch(record -> record.capability().equals(request.capability()) && record.ownerFinishedWithoutAnswer() && record.authorityLineageId().equals(finished.ownerLineageId()));
        if (!recorded) {
            CapabilityRequestPayload closed = new CapabilityRequestPayload(handleGenerator.generate(), request.capability(), requesterNodeId, binding.supervisorId(), binding.workerId(), requesterNodeId, "", "",
                    finished.ownerLineageId(), finished.terms(), CapabilityLifecycleState.SUPERSEDED, CapabilityRequestRecord.OWNER_FINISHED_WITHOUT_ANSWER, 0);
            store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CREATED, requesterNodeId, closed.encode(), identity);
        }
        Map<String, Object> result = new LinkedHashMap<>(CollaborationResponseMapper.contractMap(finished));
        result.put("capability", request.capability());
        result.put("reason", "CAPABILITY_OWNER_INTEGRATED");
        result.put("instruction", "The lane that owned " + request.ownerPath().value() + " has finished and its work is integrated, so there is nothing to wait for and nothing to validate. "
                + "Build against the file as it is in your workspace; if your workspace is stale, refresh it with ensure_session first. The contract shown is what the file was built to.");
        return new AgentResponse(AgentStatus.READY, null, null, result);
    }

    private static AgentResponse boundAnswer(CapabilityRequestHandle handle, String capability, ContractRecord contract, WorkIntent owner) {
        Map<String, Object> result = new LinkedHashMap<>(CollaborationResponseMapper.contractMap(contract));
        result.put("capabilityRequestHandle", handle.value());
        result.put("capability", capability);
        result.put("contractAccepted", true);
        result.put("owner", CollaborationResponseMapper.laneMap(owner));
        return new AgentResponse(AgentStatus.READY, null, null, result);
    }

    private static AgentResponse changeLocked(ChangeRequest request, PredictionEventStore store, ProviderSessionBindingService.Binding binding, NodeIdentity identity) throws Exception {
        if (request.need() == null || request.need().isBlank()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INVALID_PATH, AgentNextAction.RETRY, Map.of("reason", "CONTRACT_CHANGE_NEED_REQUIRED"));
        }
        if (request.need().trim().length() > ContractChangeRequest.MAX_NEED_CHARACTERS) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, Map.of("reason", "CONTRACT_CHANGE_NEED_TOO_LONG",
                    "characters", request.need().trim().length(), "limit", ContractChangeRequest.MAX_NEED_CHARACTERS,
                    "instruction", "State what you need from the file in at most " + ContractChangeRequest.MAX_NEED_CHARACTERS + " characters."));
        }
        String requesterParticipant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        Optional<WorkIntent> requesterIntent = WorkspaceCollaborationService.laneOf(store, requesterParticipant);
        if (requesterIntent.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, Map.of("reason", "COORDINATION_INTENT_REQUIRED"));
        }
        Optional<WorkIntent> owner = WorkspaceCollaborationService.claimantOf(store, request.ownerPath(), requesterParticipant);
        if (owner.isEmpty()) {
            Optional<ContractRecord> finished = store.contractProjection().frozenCovering(request.ownerPath());
            if (finished.isPresent()) {
                // Nobody is left to change the contract, so a need cannot be put to anyone.
                Map<String, Object> result = new LinkedHashMap<>(CollaborationResponseMapper.contractMap(finished.get()));
                result.put("reason", "CONTRACT_OWNER_FINISHED");
                result.put("instruction", "The lane that owned " + request.ownerPath().value() + " has finished and its work is integrated; its contract is frozen and nobody can change it. "
                        + "Bind to it as it stands with contract_bind and build against the integrated file. If the file itself must change, claim it in your own lane.");
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_COORDINATION, result);
            }
            return ownerRequired(store, null, request.ownerPath(), requesterParticipant);
        }
        if (PeerDependencyPolicy.closing(store.taskCompletionProjection(), owner.get())) {
            // An owner that has prepared its completion no longer reads its inbox.
            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("reason", "CAPABILITY_OWNER_COMPLETING", "path", request.ownerPath().value()));
        }
        UUID filer = requesterIntent.get().authorityLineageId();
        Optional<ContractChangeRequest> same = store.contractProjection().changeRequestsFiledBy(filer).stream()
                .filter(open -> open.state() == ContractChangeRequest.State.OPEN && open.path().equals(request.ownerPath()) && open.need().equals(request.need().trim())).findFirst();
        ContractChangeRequest change = same.orElse(null);
        if (change == null) {
            long base = store.contractProjection().activeCovering(request.ownerPath()).map(ContractRecord::revision).orElse(0L);
            change = ContractChangeRequest.open(UUID.randomUUID(), owner.get().authorityLineageId(), request.ownerPath(), filer, request.need().trim(), base);
            store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_CHANGE_REQUESTED, identity.nodeId(), ContractCodec.encodeChangeRequested(change), identity);
        }
        Map<String, Object> result = new LinkedHashMap<>(CollaborationResponseMapper.changeRequestMap(change));
        result.put("pending", 1);
        result.put("owner", CollaborationResponseMapper.laneMap(owner.get()));
        return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
    }

    /**
     * Answers a caller whose named file no other lane claims with what is claimed now. The bare
     * refusal left it to guess or to ask again.
     */
    private static AgentResponse ownerRequired(PredictionEventStore store, String capability, ResourceSelector path, String requesterParticipant) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (capability != null) {
            result.put("capability", capability);
        }
        result.put("lanes", CollaborationResponseMapper.otherLanes(store.collaborationProjection(), requesterParticipant));
        result.put("instruction", "No other lane claims " + path.value() + ", so nobody can answer for it yet. lanes lists what each open lane claims. "
                + "If that file is still to be built, its lane has not announced: call get_next_action, and try again once a lane claims the file.");
        return new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
    }

    /** Finds the one verified session that holds the owner's lane; its scope is what the binding is addressed to. */
    private Optional<ProviderSessionBindingService.Binding> exactOwnerBinding(ProjectLocation location, WorkIntent owner, String ownerNodeId) throws Exception {
        List<ProviderSessionBindingService.Binding> matches = bindingService.list(location, owner.provider()).stream()
                .filter(candidate -> "BOUND".equals(candidate.status()))
                .filter(candidate -> "VERIFIED".equals(candidate.providerTrustState()))
                .filter(candidate -> candidate.worktreePath() != null)
                .filter(candidate -> candidate.nodeId().equals(ownerNodeId))
                .filter(candidate -> WorkspaceCollaborationService.participantHandle(candidate.sessionId()).equals(owner.participant()))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static Set<String> integratedDependencies(PredictionEventStore store, CapabilityRequestProjection projection, WorkIntent requesterIntent, String requesterNodeId,
            ProviderSessionBindingService.Binding binding) {
        List<CapabilityRequestRecord> own = projection.findAllForRequester(requesterNodeId).stream()
                .filter(record -> record.matchesRequester(requesterNodeId, binding.supervisorId(), binding.workerId())).toList();
        return PeerDependencyPolicy.satisfied(store.collaborationProjection(), store.taskCompletionProjection(), requesterIntent, own);
    }

    /**
     * A binding to make.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param capability           the caller's own name for what it depends on, as in its lane's known dependencies
     * @param ownerPath            the file the caller builds on; the lane claiming it is the owner
     * @param revision             the contract revision the caller has read and accepts
     */
    public record BindRequest(Path projectRoot, String provider, String connectionInstanceId, String capability, ResourceSelector ownerPath, long revision) {

        /**
         * Validates the request.
         */
        public BindRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(ownerPath, "ownerPath");
        }
    }

    /**
     * A need to put to the owner of a file.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param ownerPath            the file the caller builds on
     * @param need                 what the caller needs from it, in its own words
     */
    public record ChangeRequest(Path projectRoot, String provider, String connectionInstanceId, ResourceSelector ownerPath, String need) {

        /**
         * Validates the request.
         */
        public ChangeRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(ownerPath, "ownerPath");
        }
    }
}
