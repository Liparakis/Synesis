package org.synesis.workspace.application.capability;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestPayload;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.contract.ContractBinding;
import org.synesis.coordination.domain.contract.ContractChangeRequest;
import org.synesis.coordination.domain.contract.ContractCodec;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.agent.session.CallerAdmission;
import org.synesis.workspace.application.agent.workflow.CollaborationResponseMapper;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

/**
 * What the lane that holds a file does with the public contract over it: publish it, revise it,
 * and answer the change requests against it (ADR-0092).
 *
 * <p>Who may publish is decided here, under the project append lock, against the claims of the
 * calling lane at that moment. A revision is one text for every consumer, so publishing one also
 * tells each consumer bound to the previous revision that its binding has to be made again.</p>
 */
public final class ContractOwnerService {

    private final ProjectApplicationService projectService = new ProjectApplicationService();
    private final SessionAuthorityResolver authorityResolver = new SessionAuthorityResolver(new ProviderSessionBindingService());

    /**
     * Creates the service.
     */
    public ContractOwnerService() {
    }

    /**
     * Publishes a contract over files the caller claims, or the next revision of the one that
     * already covers them.
     *
     * @param request what to publish
     * @return the published revision and whom it concerns
     */
    public AgentResponse publish(PublishRequest request) {
        Objects.requireNonNull(request, "request");
        return CallerAdmission.underAppendLock(request.projectRoot(), projectService, authorityResolver, request.provider(), request.connectionInstanceId(),
                admitted -> publishLocked(request, admitted.location().openCoordinationStore(), admitted.binding(), admitted.identity()));
    }

    /**
     * Answers one change request against a contract of the caller's lane.
     *
     * @param request the answer
     * @return what was recorded
     */
    public AgentResponse answerChange(AnswerRequest request) {
        Objects.requireNonNull(request, "request");
        return CallerAdmission.underAppendLock(request.projectRoot(), projectService, authorityResolver, request.provider(), request.connectionInstanceId(),
                admitted -> answerLocked(request, admitted.location().openCoordinationStore(), admitted.binding(), admitted.identity()));
    }

    private static AgentResponse publishLocked(PublishRequest request, PredictionEventStore store, ProviderSessionBindingService.Binding binding, NodeIdentity identity)
            throws IOException, GeneralSecurityException {
        Optional<WorkIntent> owner = callerIntent(store, binding);
        if (owner.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, Map.of("reason", "COORDINATION_INTENT_REQUIRED"));
        }
        UUID lineage = owner.get().authorityLineageId();
        List<String> notClaimed = request.paths().stream().filter(path -> !claimed(owner.get(), path)).map(ResourceSelector::value).toList();
        if (request.paths().isEmpty() || !notClaimed.isEmpty()) {
            return refused("CONTRACT_PATH_NOT_CLAIMED", Map.of("paths", notClaimed, "claims", owner.get().selectors().stream().map(ResourceSelector::value).toList()),
                    "A contract covers only files this lane claims. Name paths inside your claims, or extend the lane's claims with ensure_session first.");
        }
        List<ContractRecord> touched = store.contractProjection().ownedBy(lineage).stream()
                .filter(contract -> contract.state() == ContractRecord.State.ACTIVE && request.paths().stream().anyMatch(contract::covers)).toList();
        if (touched.size() > 1) {
            return refused("CONTRACT_PATHS_SPAN_CONTRACTS", Map.of("contracts", touched.stream().map(contract -> contract.contractId().toString()).toList()),
                    "These paths belong to more than one of your contracts. Publish each contract with its own paths.");
        }
        ContractRecord prior = touched.isEmpty() ? null : touched.getFirst();
        // A revision can add files and never drops one: a consumer bound over a file would lose
        // the promise it relied on.
        LinkedHashSet<ResourceSelector> paths = new LinkedHashSet<>(prior == null ? List.of() : prior.paths());
        paths.addAll(request.paths());
        boolean unchanged = prior != null && prior.terms().isEquivalent(request.terms()) && paths.size() == prior.paths().size();
        ContractRecord published = unchanged ? prior
                : new ContractRecord(prior == null ? UUID.randomUUID() : prior.contractId(), lineage, prior == null ? 1 : prior.revision() + 1, List.copyOf(paths), request.terms(),
                        ContractRecord.State.ACTIVE);
        int toldToBindAgain = 0;
        if (!unchanged) {
            store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_PUBLISHED, identity.nodeId(), ContractCodec.encodePublished(published), identity);
            for (ContractBinding bound : store.contractProjection().bindings(published.contractId())) {
                Optional<CapabilityRequestRecord> record = store.capabilityRequestProjection().findByHandle(bound.handle());
                if (record.isEmpty() || !record.get().state().bindsLanes()) {
                    continue;
                }
                // One text for every consumer: each binding to the earlier revision waits to be
                // made again, and what was validated under it no longer counts.
                CapabilityRequestPayload revised = new CapabilityRequestPayload(record.get().handle(), record.get().capability(), record.get().requesterNodeId(), record.get().ownerNodeId(),
                        record.get().authorityLineageId(), published.terms(), CapabilityLifecycleState.REVISION_REQUESTED,
                        "The owner published revision " + published.revision() + " of the contract.", record.get().contractRevision());
                store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_REQUEST_CONTRACT_REVISED, identity.nodeId(), revised.encode(), identity);
                toldToBindAgain++;
            }
        }
        List<String> answered = new ArrayList<>();
        List<String> notAnswered = new ArrayList<>();
        for (UUID id : request.answers()) {
            Optional<ContractChangeRequest> change = store.contractProjection().changeRequest(id);
            boolean answerable = change.isPresent() && change.get().state() == ContractChangeRequest.State.OPEN && change.get().ownerLineageId().equals(lineage)
                    && published.covers(change.get().path());
            if (!answerable) {
                notAnswered.add(id.toString());
                continue;
            }
            store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_CHANGE_ANSWERED, identity.nodeId(),
                    ContractCodec.encodeChangeAnswered(id, ContractChangeRequest.State.ACCEPTED, published.revision(), ""), identity);
            answered.add(id.toString());
        }
        Map<String, Object> result = new LinkedHashMap<>(CollaborationResponseMapper.contractMap(published));
        result.put("published", !unchanged);
        result.put("bindingsToMakeAgain", toldToBindAgain);
        result.put("changeRequestsAnswered", answered);
        if (!notAnswered.isEmpty()) {
            result.put("changeRequestsNotAnswered", notAnswered);
        }
        result.put("openChangeRequests", store.contractProjection().openChangeRequestsFor(lineage).stream().map(CollaborationResponseMapper::changeRequestMap).toList());
        return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
    }

    private static AgentResponse answerLocked(AnswerRequest request, PredictionEventStore store, ProviderSessionBindingService.Binding binding, NodeIdentity identity)
            throws IOException, GeneralSecurityException {
        Optional<WorkIntent> owner = callerIntent(store, binding);
        if (owner.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED, AgentNextAction.ENSURE_SESSION, Map.of("reason", "COORDINATION_INTENT_REQUIRED"));
        }
        UUID lineage = owner.get().authorityLineageId();
        Optional<ContractChangeRequest> found = store.contractProjection().changeRequest(request.changeRequestId());
        if (found.isEmpty()) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY, Map.of("reason", "CONTRACT_CHANGE_REQUEST_NOT_FOUND"));
        }
        ContractChangeRequest change = found.get();
        if (!change.ownerLineageId().equals(lineage)) {
            return refused("CONTRACT_CHANGE_REQUEST_NOT_YOURS", Map.of(), "This change request is addressed to another lane. Only the lane that claims the file answers it.");
        }
        ContractChangeRequest.State wanted = request.accepted() ? ContractChangeRequest.State.ACCEPTED : ContractChangeRequest.State.DECLINED;
        if (change.state() != ContractChangeRequest.State.OPEN) {
            // The same answer again is the answer already recorded; any other comes too late.
            return change.state() == wanted ? new AgentResponse(AgentStatus.COMPLETED, null, null, CollaborationResponseMapper.changeRequestMap(change))
                    : new AgentResponse(AgentStatus.BLOCKED, AgentReason.STALE_REQUEST, AgentNextAction.RETRY, CollaborationResponseMapper.changeRequestMap(change));
        }
        if (request.accepted()) {
            Optional<ContractRecord> covering = store.contractProjection().activeCovering(change.path()).filter(contract -> contract.ownerLineageId().equals(lineage));
            if (covering.isEmpty()) {
                return refused("CONTRACT_NOT_PUBLISHED", Map.of("path", change.path().value()),
                        "Accepting a change request means a revision covers it. Publish the contract over this file with contract_publish, naming this change request in answers.");
            }
            store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_CHANGE_ANSWERED, identity.nodeId(),
                    ContractCodec.encodeChangeAnswered(change.id(), wanted, covering.get().revision(), ""), identity);
        } else {
            if (request.reason() == null || request.reason().isBlank()) {
                return refused("CONTRACT_CHANGE_DECLINED_WITHOUT_REASON", Map.of(), "Say why it is declined. The consumer reads the reason and decides whether to bind anyway or to cancel.");
            }
            store.append(UUID.randomUUID(), PredictionEventType.CONTRACT_CHANGE_ANSWERED, identity.nodeId(), ContractCodec.encodeChangeAnswered(change.id(), wanted, 0, request.reason()), identity);
        }
        Map<String, Object> result = new LinkedHashMap<>(CollaborationResponseMapper.changeRequestMap(store.contractProjection().changeRequest(change.id()).orElse(change)));
        result.put("openChangeRequests", store.contractProjection().openChangeRequestsFor(lineage).size());
        return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
    }

    private static Optional<WorkIntent> callerIntent(PredictionEventStore store, ProviderSessionBindingService.Binding binding) {
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        return store.collaborationProjection().activeIntents().stream().filter(intent -> intent.participant().equals(participant)).findFirst();
    }

    /** Tells whether a path lies inside what a lane claims: the claimed file itself, or anything under a claimed subtree. */
    static boolean claimed(WorkIntent lane, ResourceSelector path) {
        return lane.selectors().stream().anyMatch(claim -> claim.kind() == ResourceSelector.Kind.PATH_SUBTREE
                ? path.value().equals(claim.value()) || path.value().startsWith(claim.value() + "/")
                : path.kind() == ResourceSelector.Kind.PATH_EXACT && path.value().equals(claim.value()));
    }

    private static AgentResponse refused(String reason, Map<String, Object> detail, String instruction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reason", reason);
        result.putAll(detail);
        result.put("instruction", instruction);
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.RETRY, result);
    }

    /**
     * A contract or revision to publish.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param paths                claimed files or subtrees the contract covers
     * @param terms                what the owner promises about them
     * @param answers              change requests this revision covers
     */
    public record PublishRequest(Path projectRoot, String provider, String connectionInstanceId, List<ResourceSelector> paths, CapabilityContract terms, List<UUID> answers) {

        /**
         * Validates the request.
         */
        public PublishRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(terms, "terms");
            paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
            answers = answers == null ? List.of() : List.copyOf(answers);
        }
    }

    /**
     * The owner's answer to one change request.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     * @param changeRequestId      change request being answered
     * @param accepted             whether the current revision covers the need
     * @param reason               why it is declined; required when it is
     */
    public record AnswerRequest(Path projectRoot, String provider, String connectionInstanceId, UUID changeRequestId, boolean accepted, String reason) {

        /**
         * Validates the request.
         */
        public AnswerRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(changeRequestId, "changeRequestId");
        }
    }
}
