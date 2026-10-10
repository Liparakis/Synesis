package org.synesis.workspace.application.agent.workflow;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.synesis.coordination.application.PeerDependencyPolicy;
import org.synesis.coordination.application.RequesterAnswerPolicy;
import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.integration.ValidationContextRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

/**
 * Projects capability-request work for the caller: unmet dependencies first, then what the caller
 * owes as owner, then what it must do as requester, together with the contract maps these
 * responses share.
 *
 * <p>Separate because the capability negotiation lifecycle is one vocabulary that the lane
 * completion rules also consult, and it has no dependency on review or publication evidence.</p>
 */
final class CapabilityActionProjection {

    /**
     * Reason reported when the requester asked for a revision without stating why.
     */
    private static final String DEFAULT_REVISION_REASON = "Revision required by requester";

    /**
     * Reason reported when the owner rejected a capability request without stating why.
     */
    private static final String DEFAULT_REJECTION_REASON = "Capability request rejected by owner";

    /**
     * Capability-request fields a requester must supply, as reported by the durable path.
     */
    private static final List<String> DURABLE_CAPABILITY_FIELDS = List.of("purpose", "inputs", "output", "requiredBehavior", "errorSemantics", "sideEffects", "invariants", "compatibility", "performance", "concurrency", "acceptanceTests");

    /**
     * Capability states in which a requester has something to act on or wait for. A
     * {@code REVISION_REQUESTED} request outranks all of them and is handled separately.
     */
    private static final Set<CapabilityLifecycleState> REQUESTER_ACTIONABLE_STATES = Set.of(CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE,
            CapabilityLifecycleState.REJECTED,
            CapabilityLifecycleState.AWAITING_OWNER,
            CapabilityLifecycleState.ACCEPTED,
            CapabilityLifecycleState.IMPLEMENTING,
            CapabilityLifecycleState.VALIDATING);

    private static final String CAPABILITY_OWNER_COMPLETING = "CAPABILITY_OWNER_COMPLETING";

    /**
     * The one call that asks Synesis to project the caller's exact publication or finish action.
     */
    private static final Map<String, Object> COMPLETION_REQUEST = Map.of("tool", "get_next_action", "arguments", Map.of("completionRequested", true));

    private CapabilityActionProjection() {
    }

    /**
     * Resolves capability-request work: unmet dependencies first, then owner duties, then the
     * requester's view of its own requests.
     *
     * @param store                 current project projection
     * @param actor                 capability identity of the caller
     * @param callerIntent          the caller's active intent, or {@code null} when none exists
     * @param coordinationActivated whether collaboration is active, which restricts requests to the
     *                              caller's authority lineage
     * @return a capability action, or {@code null} when nothing needs attention
     */
    static AgentResponse capabilityAction(PredictionEventStore store, CapabilityActor actor, WorkIntent callerIntent, boolean coordinationActivated) {
        CapabilityRequestProjection capabilities = store.capabilityRequestProjection();
        Map<String, CapabilityRequestRecord> localDependencies = Map.of();
        Set<String> locallyOwnedDependencies = Set.of();
        if (callerIntent != null) {
            LocalDependencyResolution resolution = localAcceptedDependencies(capabilities, actor, callerIntent);
            if (resolution.ambiguity() != null) {
                return capabilityContractAmbiguity(resolution.ambiguity(), resolution.ambiguousRecords());
            }
            localDependencies = resolution.records();
            locallyOwnedDependencies = resolution.locallyOwned();
            AgentResponse missingDependency = missingDependencyResponse(store,
                    actor,
                    callerIntent,
                    localDependencies,
                    locallyOwnedDependencies);
            if (missingDependency != null) {
                return withResolvedDependencies(missingDependency, localDependencies);
            }
        }
        Predicate<CapabilityRequestRecord> inCallerLineage = inCallerLineage(coordinationActivated, callerIntent);

        AgentResponse ownerResponse = ownerCapabilityAction(capabilities, actor, inCallerLineage);
        if (ownerResponse != null) {
            return withResolvedDependencies(ownerResponse, localDependencies);
        }
        AgentResponse requesterResponse = requesterCapabilityAction(capabilities, actor);
        if (requesterResponse != null) {
            return withResolvedDependencies(requesterResponse, localDependencies);
        }
        return localDependencies.isEmpty() ? null : localDependencyReadyResponse(localDependencies, callerIntent);
    }

    /**
     * Builds a predicate that keeps only requests in the caller's authority lineage once
     * collaboration is active.
     *
     * @param coordinationActivated whether collaboration is active
     * @param callerIntent          the caller's active intent, or {@code null} when none exists
     * @return a predicate that accepts every request while collaboration is inactive, otherwise
     *         only requests whose lineage matches the caller's intent
     */
    static Predicate<CapabilityRequestRecord> inCallerLineage(boolean coordinationActivated, WorkIntent callerIntent) {
        return candidate -> !coordinationActivated || callerIntent != null && callerIntent.authorityLineageId().equals(candidate.authorityLineageId());
    }

    /**
     * Asks the caller to request the first known dependency it has not requested yet.
     *
     * @param capabilities capability-request projection
     * @param actor        capability identity of the caller
     * @param intent       the caller's active intent
     * @return a {@code NEEDS_CAPABILITY} response, or {@code null} when every dependency has been
     *         requested (or there are none)
     */
    private static AgentResponse missingDependencyResponse(PredictionEventStore store,
            CapabilityActor actor,
            WorkIntent intent,
            Map<String, CapabilityRequestRecord> locallyResolved,
            Set<String> locallyOwned) {
        if (intent.knownDependencies().isEmpty()) {
            return null;
        }
        List<CapabilityRequestRecord> ownRequests = store.capabilityRequestProjection().findAllForRequester(actor.nodeId()).stream()
                .filter(candidate -> candidate.matchesRequester(actor.nodeId(), actor.supervisorId(), actor.workerId()))
                .toList();
        // A request its owner superseded by finishing no longer stands for the dependency: the
        // lane holds no agreement, so the dependency is judged like one never requested.
        var requestedCapabilities = ownRequests.stream().filter(candidate -> !RequesterAnswerPolicy.ownerFinishedWithoutAnswer(candidate))
                .map(CapabilityRequestRecord::capability).collect(Collectors.toSet());
        Set<String> integrated = PeerDependencyPolicy.satisfiedByIntegration(store.collaborationProjection(), store.taskCompletionProjection(), intent, requestedCapabilities);
        String missingDependency = intent.knownDependencies().stream()
                .filter(dependency -> !requestedCapabilities.contains(dependency) && !locallyResolved.containsKey(dependency)
                        && !locallyOwned.contains(dependency) && !integrated.contains(dependency))
                .findFirst().orElse(null);
        if (missingDependency == null) {
            return integrated.isEmpty() ? null : integratedDependencyResponse(integrated, intent, ownRequests);
        }
        if (PeerDependencyPolicy.awaitingPeerIntegration(store.collaborationProjection(), store.taskCompletionProjection(), intent)) {
            // Every lane that could own this is already closing; a request now could never be
            // answered. Its work reaches this lane when it is integrated.
            Map<String, Object> waiting = new LinkedHashMap<>();
            waiting.put(NextActionKeys.CAPABILITY, missingDependency);
            waiting.put(NextActionKeys.REASON, CAPABILITY_OWNER_COMPLETING);
            waiting.put(NextActionKeys.PENDING, 1);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, waiting);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.CAPABILITY, missingDependency);
        result.put(NextActionKeys.REQUIRED_FIELDS, DURABLE_CAPABILITY_FIELDS);
        result.put(NextActionKeys.PENDING, intent.knownDependencies().size());
        result.put(NextActionKeys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
        result.put(NextActionKeys.KNOWN_DEPENDENCIES, intent.knownDependencies());
        result.put("coordinationKind", "capability_request");
        return new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, result);
    }

    /**
     * Resolves dependencies already accepted from this exact owner in the caller's authority lineage.
     * Equivalent accepted revisions share one deterministic representative; conflicting contracts block.
     */
    private static LocalDependencyResolution localAcceptedDependencies(CapabilityRequestProjection capabilities,
            CapabilityActor actor,
            WorkIntent intent) {
        Map<String, CapabilityRequestRecord> resolved = new LinkedHashMap<>();
        Set<String> locallyOwned = new java.util.LinkedHashSet<>();
        for (String dependency : intent.knownDependencies()) {
            List<CapabilityRequestRecord> owned = capabilities.records().values().stream()
                    .filter(record -> record.capability().equals(dependency))
                    .filter(record -> record.authorityLineageId().equals(intent.authorityLineageId()))
                    .filter(record -> record.matchesOwner(actor.nodeId(), actor.supervisorId(), actor.workerId()))
                    .filter(record -> record.state() != CapabilityLifecycleState.REJECTED
                            && record.state() != CapabilityLifecycleState.CANCELLED
                            && record.state() != CapabilityLifecycleState.EXPIRED
                            && record.state() != CapabilityLifecycleState.SUPERSEDED)
                    .toList();
            if (!owned.isEmpty()) {
                locallyOwned.add(dependency);
            }
            List<CapabilityRequestRecord> accepted = owned.stream()
                    .filter(record -> record.state() == CapabilityLifecycleState.ACCEPTED
                            || record.state() == CapabilityLifecycleState.IMPLEMENTING
                            || record.state() == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                            || record.state() == CapabilityLifecycleState.VALIDATING
                            || record.state() == CapabilityLifecycleState.VALIDATED)
                    .toList();
            if (accepted.isEmpty()) {
                continue;
            }
            CapabilityContract contract = accepted.getFirst().contract();
            if (accepted.stream().anyMatch(candidate -> !contract.isEquivalent(candidate.contract()))) {
                return new LocalDependencyResolution(Map.of(), Set.copyOf(locallyOwned), dependency, accepted);
            }
            CapabilityRequestRecord exact = accepted.stream()
                    .max(Comparator.comparingLong(CapabilityRequestRecord::contractRevision)
                            .thenComparing(candidate -> candidate.handle().value()))
                    .orElseThrow();
            resolved.put(dependency, exact);
        }
        return new LocalDependencyResolution(Collections.unmodifiableMap(new LinkedHashMap<>(resolved)),
                Set.copyOf(locallyOwned), null, List.of());
    }

    private static AgentResponse capabilityContractAmbiguity(String capability, List<CapabilityRequestRecord> records) {
        List<Map<String, Object>> candidates = records.stream().map(record -> Map.<String, Object>of(
                NextActionKeys.CAPABILITY_REQUEST_HANDLE, record.handle().value(),
                "contractRevision", record.contractRevision(),
                NextActionKeys.CONTRACT, capabilityContractMap(record))).toList();
        return new AgentResponse(AgentStatus.BLOCKED,
                AgentReason.POLICY_DENIED,
                AgentNextAction.REQUEST_HUMAN_HELP,
                Map.of("reason", "CAPABILITY_CONTRACT_AMBIGUOUS", NextActionKeys.CAPABILITY, capability, "acceptedContracts", candidates));
    }

    private static AgentResponse withResolvedDependencies(AgentResponse response,
            Map<String, CapabilityRequestRecord> resolved) {
        if (resolved.isEmpty()) {
            return response;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (response.result() instanceof Map<?, ?> fields) {
            fields.forEach((key, value) -> {
                if (key instanceof String name) {
                    result.put(name, value);
                }
            });
        } else if (response.result() != null) {
            result.put("workflowResult", response.result());
        }
        result.put("resolvedDependencies", dependencyContractMaps(resolved));
        return new AgentResponse(response.status(), response.reason(), response.nextAction(), result);
    }

    /**
     * Tells a lane that work it declared a dependency on is already integrated, so it should build
     * against that work in its own refreshed workspace instead of asking for an owner.
     */
    private static AgentResponse integratedDependencyResponse(Set<String> integrated, WorkIntent intent, List<CapabilityRequestRecord> ownRequests) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dependenciesIntegrated", List.copyOf(integrated));
        result.put("dependencyGuidance", "The lane that provided these has completed and its work is integrated. Read it in your workspace and build against it; no capability request is needed.");
        List<Map<String, Object>> wentAhead = ownRequests.stream()
                .filter(RequesterAnswerPolicy::ownerFinishedWithoutAnswer)
                .filter(request -> integrated.contains(request.capability()))
                .map(request -> {
                    Map<String, Object> proposal = new LinkedHashMap<>();
                    proposal.put(NextActionKeys.CAPABILITY, request.capability());
                    proposal.put(NextActionKeys.CAPABILITY_REQUEST_HANDLE, request.handle().value());
                    proposal.put("proposedContract", capabilityContractMap(request));
                    return proposal;
                })
                .toList();
        if (!wentAhead.isEmpty()) {
            // The requester must not assume its own draft was built. What the owner last proposed
            // is the closest statement of what it did; the integrated code is the authority.
            result.put("ownerProceededWithoutAnswer", wentAhead);
            result.put("ownerProceededGuidance", "The owner did not receive your answer to its proposed contract in time and finished. It may have built a different design than you asked for: its last proposal is included, and the integrated code is what counts. The owner's files are released, so you can claim them if you need a change.");
        }
        result.put(NextActionKeys.CURRENT_INTENT, CollaborationResponseMapper.intentMap(intent));
        return new AgentResponse(AgentStatus.READY, null, null, result);
    }

    private static AgentResponse localDependencyReadyResponse(Map<String, CapabilityRequestRecord> resolved,
            WorkIntent intent) {
        return new AgentResponse(AgentStatus.READY,
                null,
                null,
                Map.of("resolvedDependencies", dependencyContractMaps(resolved),
                        NextActionKeys.CURRENT_INTENT,
                        CollaborationResponseMapper.intentMap(intent)));
    }

    private static Map<String, Object> dependencyContractMaps(Map<String, CapabilityRequestRecord> resolved) {
        Map<String, Object> dependencies = new LinkedHashMap<>();
        resolved.forEach((capability, record) -> dependencies.put(capability,
                Map.of(NextActionKeys.CAPABILITY_REQUEST_HANDLE,
                        record.handle().value(),
                        "contractRevision",
                        record.contractRevision(),
                        NextActionKeys.CONTRACT,
                        capabilityContractMap(record))));
        return dependencies;
    }

    /**
     * Resolves what the caller owes as <em>owner</em> of capability requests: answering pending
     * requests, implementing an accepted contract, or responding to a validation revision.
     *
     * @param capabilities    capability-request projection
     * @param actor           capability identity of the caller
     * @param inCallerLineage filter restricting requests to the caller's authority lineage
     * @return an owner action, or {@code null} when the caller owes nothing as owner
     */
    static AgentResponse ownerCapabilityAction(CapabilityRequestProjection capabilities, CapabilityActor actor, Predicate<CapabilityRequestRecord> inCallerLineage) {
        // Local sessions share the node identity, so only the stored supervisor and worker scope
        // separates the assigned owner from a sibling. Projecting by node alone would hand a
        // sibling a reply or publication that the owning services then refuse.
        Predicate<CapabilityRequestRecord> ownedByCaller = candidate -> candidate.matchesOwner(actor.nodeId(), actor.supervisorId(), actor.workerId());
        List<CapabilityRequestRecord> ownerPending = capabilities.findPendingForOwner(actor.nodeId()).stream().filter(ownedByCaller).filter(inCallerLineage).toList();
        if (!ownerPending.isEmpty()) {
            return capabilityOwnerResponse(ownerPending, AgentStatus.READY, null, AgentNextAction.RESPOND_COORDINATION);
        }

        List<CapabilityRequestRecord> validationRevisions = capabilities.findValidationRevisionForOwner(actor.nodeId()).stream().filter(ownedByCaller).filter(inCallerLineage).toList();
        if (!validationRevisions.isEmpty()) {
            CapabilityRequestRecord first = validationRevisions.getFirst();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(NextActionKeys.CAPABILITY_REQUEST_HANDLE, first.handle().value());
            result.put("contractRevision", first.contractRevision());
            result.put(NextActionKeys.REASON, Objects.requireNonNullElse(first.reason(), DEFAULT_REVISION_REASON));
            result.put(NextActionKeys.PENDING, validationRevisions.size());
            return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_FAILED, AgentNextAction.RESPOND_TO_VALIDATION_REVISION, result);
        }

        List<CapabilityRequestRecord> ownerAccepted = capabilities.records().values().stream().filter(ownedByCaller)
                .filter(candidate -> candidate.state() == CapabilityLifecycleState.ACCEPTED || candidate.state() == CapabilityLifecycleState.IMPLEMENTING).filter(inCallerLineage).toList();
        if (!ownerAccepted.isEmpty()) {
            return capabilityOwnerResponse(ownerAccepted, AgentStatus.READY, null, null);
        }
        return null;
    }

    /**
     * Resolves what the caller must do as <em>requester</em> of capability requests.
     *
     * @param capabilities capability-request projection
     * @param actor        capability identity of the caller
     * @return a requester action, or {@code null} when none of the caller's requests needs
     *         attention
     */
    private static AgentResponse requesterCapabilityAction(CapabilityRequestProjection capabilities, CapabilityActor actor) {
        List<CapabilityRequestRecord> pending = capabilities.findPendingForRequester(actor.nodeId()).stream().filter(candidate -> candidate.matchesRequester(actor.nodeId(), actor.supervisorId(), actor.workerId()))
                .toList();
        CapabilityRequestRecord focus = selectRequesterFocus(pending);
        if (focus == null) {
            return null;
        }

        CapabilityLifecycleState state = focus.state();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.CAPABILITY_REQUEST_HANDLE, focus.handle().value());
        result.put("contractRevision", focus.contractRevision());

        if (state == CapabilityLifecycleState.REVISION_REQUESTED) {
            Map<String, Object> contract = capabilityContractMap(focus);
            result.put(NextActionKeys.CAPABILITY, focus.capability());
            result.put(NextActionKeys.CONTRACT, contract);
            result.put(NextActionKeys.PENDING, pending.size());
            // The reply must repeat the handle, capability, revision, and an equivalent contract.
            // Accept, counter, or cancel stays the requester's decision, so this is offered as the
            // exact accept call and not as the recommended tool.
            Map<String, Object> acceptPayload = new LinkedHashMap<>();
            acceptPayload.put(NextActionKeys.CAPABILITY_REQUEST_HANDLE, focus.handle().value());
            acceptPayload.put(NextActionKeys.CAPABILITY, focus.capability());
            acceptPayload.put("contractRevision", focus.contractRevision());
            acceptPayload.put(NextActionKeys.CONTRACT, contract);
            acceptPayload.put("revisionResponse", "accept");
            result.put("revisionDecision", Map.of("required", true, "field", "revisionResponse", "allowedResults", List.of("accept", "counter", "cancel")));
            result.put("acceptRevision", Map.of("tool", "request_coordination", "arguments", Map.of("kind", "capability_request", "payload", acceptPayload)));
            return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED, AgentNextAction.REVISE_CAPABILITY_REQUEST, result);
        }
        if (state == CapabilityLifecycleState.ACCEPTED || state == CapabilityLifecycleState.IMPLEMENTING) {
            result.put(NextActionKeys.CAPABILITY, focus.capability());
            result.put(NextActionKeys.AUTHORITY_LINEAGE_ID, focus.authorityLineageId().toString());
            result.put(NextActionKeys.CONTRACT, capabilityContractMap(focus));
            result.put(NextActionKeys.PENDING, pending.size());
            result.put("contractAccepted", true);
            return new AgentResponse(AgentStatus.READY, null, null, result);
        }
        if (state == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE) {
            // First open only the exact immutable revision; no validation verdict is executable yet.
            ImplementationRevisionRecord implementation = capabilities.findLatestImplementation(focus.handle().value()).orElse(null);
            int revision = implementation != null ? implementation.revisionNumber() : 1;
            String inboxItemId = UUID.nameUUIDFromBytes(("capability:" + focus.handle().value()).getBytes(StandardCharsets.UTF_8)).toString();
            result.put("inboxItemId", inboxItemId);
            result.put(NextActionKeys.CAPABILITY, focus.capability());
            result.put(NextActionKeys.AUTHORITY_LINEAGE_ID, focus.authorityLineageId().toString());
            result.put(NextActionKeys.CONTRACT, capabilityContractMap(focus));
            result.put("revision", revision);
            if (implementation != null) {
                result.put("implementation", Map.of("summary", implementation.summary(),
                        "baseCommit", implementation.baseCommit(),
                        "commitSha", implementation.commitSha(),
                        "changedPaths", implementation.changedPaths()));
            }
            result.put(NextActionKeys.PENDING, pending.size());
            result.put(NextActionKeys.REVIEW_DECISION, Map.of("required", false, "field", "result", "allowedResults", List.of("inspect")));
            result.put("nextProtocolKind", "implementation_validation");
            result.put("nextProtocolPayload", Map.of("inboxItemId",
                    inboxItemId,
                    NextActionKeys.CAPABILITY_REQUEST_HANDLE,
                    focus.handle().value(),
                    "implementationRevision",
                    revision,
                    "result",
                    "inspect"));
            return new AgentResponse(AgentStatus.READY, null, AgentNextAction.VALIDATE_IMPLEMENTATION, result);
        }
        if (state == CapabilityLifecycleState.VALIDATING) {
            ImplementationRevisionRecord implementation = capabilities.findLatestImplementation(focus.handle().value()).orElse(null);
            ValidationContextRecord context = capabilities.findValidationContext(focus.handle()).orElse(null);
            if (implementation == null || context == null || context.revisionNumber() != implementation.revisionNumber() || !context.requesterSessionId().equals(actor.sessionId())) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.TASK_NOT_READY, AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("reason", "VALIDATION_CONTEXT_UNAVAILABLE", NextActionKeys.CAPABILITY_REQUEST_HANDLE, focus.handle().value()));
            }
            result.put("inboxItemId", UUID.nameUUIDFromBytes(("capability:" + focus.handle().value()).getBytes(StandardCharsets.UTF_8)).toString());
            result.put(NextActionKeys.CAPABILITY, focus.capability());
            result.put(NextActionKeys.AUTHORITY_LINEAGE_ID, focus.authorityLineageId().toString());
            result.put(NextActionKeys.CONTRACT, capabilityContractMap(focus));
            result.put("revision", implementation.revisionNumber());
            result.put("implementation", Map.of("summary", implementation.summary(),
                    "baseCommit", implementation.baseCommit(),
                    "commitSha", implementation.commitSha(),
                    "changedPaths", implementation.changedPaths()));
            result.put(NextActionKeys.PENDING, pending.size());
            result.put("validationWorkspaceAvailable", true);
            result.put(NextActionKeys.REVIEW_DECISION, Map.of("required", true,
                    "field", "result",
                    "allowedResults", List.of("accepted", "revision_required"),
                    "revisionReasonRequired", true));
            result.put("validationEvidence", Map.of("toolFields", List.of(NextActionKeys.CAPABILITY_REQUEST_HANDLE, "implementationRevision"),
                    "capabilityRequestHandle", focus.handle().value(),
                    "implementationRevision", implementation.revisionNumber()));
            return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, AgentNextAction.VALIDATE_IMPLEMENTATION, result);
        }
        if (state == CapabilityLifecycleState.REJECTED) {
            result.put(NextActionKeys.REASON, Objects.requireNonNullElse(focus.reason(), DEFAULT_REJECTION_REASON));
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_REJECTED, AgentNextAction.RETRY, result);
        }
        result.put(NextActionKeys.PENDING, pending.size());
        AgentReason reason = state == CapabilityLifecycleState.AWAITING_OWNER ? AgentReason.OWNER_RESPONSE_PENDING : AgentReason.IMPLEMENTATION_UNAVAILABLE;
        return new AgentResponse(AgentStatus.WAITING, reason, AgentNextAction.WAIT, result);
    }

    /**
     * Picks the request a requester should focus on: the first {@code REVISION_REQUESTED} request
     * if any exists, otherwise the first request in an actionable state.
     *
     * @param pending the requester's pending requests, in projection order
     * @return the focus request, or {@code null} when none is in an actionable state
     */
    private static CapabilityRequestRecord selectRequesterFocus(List<CapabilityRequestRecord> pending) {
        CapabilityRequestRecord firstActionable = null;
        for (CapabilityRequestRecord candidate : pending) {
            CapabilityLifecycleState state = candidate.state();
            if (state == CapabilityLifecycleState.REVISION_REQUESTED) {
                return candidate;
            }
            if (firstActionable == null && REQUESTER_ACTIONABLE_STATES.contains(state)) {
                firstActionable = candidate;
            }
        }
        return firstActionable;
    }

    /**
     * Builds the shared owner response for the first pending capability request.
     *
     * @param requests the owner's requests; must not be empty
     * @param status   status to report
     * @param reason   reason to report, or {@code null}
     * @param action   next action to recommend
     * @return the owner response describing the first request and the total pending count
     */
    static AgentResponse capabilityOwnerResponse(List<CapabilityRequestRecord> requests, AgentStatus status, AgentReason reason, AgentNextAction action) {
        CapabilityRequestRecord request = requests.getFirst();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(NextActionKeys.CAPABILITY_REQUEST_HANDLE, request.handle().value());
        result.put(NextActionKeys.CAPABILITY, request.capability());
        result.put(NextActionKeys.AUTHORITY_LINEAGE_ID, request.authorityLineageId().toString());
        result.put("contractRevision", request.contractRevision());
        result.put(NextActionKeys.CONTRACT, capabilityContractMap(request));
        if (request.state() == CapabilityLifecycleState.ACCEPTED || request.state() == CapabilityLifecycleState.IMPLEMENTING) {
            result.put("implementationPending", true);
            result.put("completionRequest", COMPLETION_REQUEST);
        } else if (request.state() == CapabilityLifecycleState.VALIDATED) {
            result.put("implementationValidated", true);
            result.put("completionRequest", COMPLETION_REQUEST);
        }
        result.put(NextActionKeys.PENDING, requests.size());
        return new AgentResponse(status, reason, action, result);
    }

    /**
     * Builds the stable capability-contract object shared by owner and requester responses.
     *
     * @param request the capability request
     * @return every negotiated API term
     */
    private static Map<String, Object> capabilityContractMap(CapabilityRequestRecord request) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("purpose", request.contract().purpose());
        contract.put("inputs", request.contract().inputs());
        contract.put("output", request.contract().output());
        contract.put("requiredBehavior", request.contract().requiredBehavior());
        contract.put("errorSemantics", request.contract().errorSemantics());
        contract.put("sideEffects", request.contract().sideEffects());
        contract.put("invariants", request.contract().invariants());
        contract.put("compatibility", request.contract().compatibility());
        contract.put("performance", request.contract().performance());
        contract.put("concurrency", request.contract().concurrency());
        contract.put("acceptanceTests", request.contract().acceptanceTests());
        return contract;
    }

    private record LocalDependencyResolution(Map<String, CapabilityRequestRecord> records,
            Set<String> locallyOwned,
            String ambiguity,
            List<CapabilityRequestRecord> ambiguousRecords) {

    }
}
