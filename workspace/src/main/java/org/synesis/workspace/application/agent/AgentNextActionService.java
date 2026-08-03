package org.synesis.workspace.application.agent;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

import org.synesis.workspace.application.ProjectApplicationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;

/**
 * Application service for retrieving the single highest-priority actionable coordination item
 * for an ambient MCP session.
 *
 * @since 1.0
 */
public final class AgentNextActionService {

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;
    private final AgentWorkflowReducer workflowReducer;

    /**
     * Creates a next-action retrieval application service.
     */
    public AgentNextActionService() {
        this.projectService = new ProjectApplicationService();
        this.readinessService = new WorkspaceReadinessService();
        this.workflowReducer = new AgentWorkflowReducer();
    }

    /**
     * Request parameters for next action resolution.
     *
     * @param projectRoot          control project root path
     * @param provider             provider identifier
     * @param connectionInstanceId connection instance identifier
     */
    public record NextActionRequest(
            Path projectRoot,
            String provider,
            String connectionInstanceId
    ) {
        /**
         * Validates non-null request parameters.
         */
        public NextActionRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        }
    }

    /**
     * Represents a pending coordination item.
     *
     * @param type         item type (SAFETY_FAILURE, DEPENDENCY_INVALIDATED, OWNER_REQUEST, NEEDS_CAPABILITY, VALIDATION_REQUIRED, WAITING_FOR_OWNER)
     * @param capability   capability identifier
     * @param workerId     target worker identifier or provider
     * @param details      additional detail payload
     * @param sequence     sequence number for deterministic ordering
     */
    public record CoordinationItem(
            String type,
            String capability,
            String workerId,
            Map<String, Object> details,
            long sequence
    ) {
    }

    /**
     * Resolves the single highest-priority actionable coordination item for the active session worker.
     *
     * @param request request payload
     * @return concise agent response
     */
    public AgentResponse getNextAction(NextActionRequest request) {
        AgentResponse response = resolveNextAction(request);
        return workflowReducer.decorate(request, response);
    }

    private AgentResponse resolveNextAction(NextActionRequest request) {
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectApplicationService.ProjectLocation location;
        WorkspaceReadinessService.ReadinessResult readiness;
        try {
            location = projectService.locate(root);
            // A completed lane is a durable terminal state.  Do not send its
            // caller back through workspace freshness checks after integration
            // has advanced the control head; the immutable snapshot remains
            // available for audit and recovery, but this authority is closed.
            var exactBinding = new ProviderSessionBindingService().find(
                    location, request.provider(), request.connectionInstanceId());
            if (exactBinding.isPresent() && "COMPLETED".equals(exactBinding.get().status())) {
                return new AgentResponse(AgentStatus.COMPLETED, null, null,
                        Map.of("state", "COMPLETED", "lane", exactBinding.get().sessionId()));
            }
            readiness = readinessService.assess(location, request.provider(), request.connectionInstanceId());
        } catch (Exception ex) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }
        if (!readiness.ready()) {
            return readiness.response();
        }
        ProviderSessionBindingService.Binding binding = readiness.binding();
        Path assignedWorktree = readiness.worktree();

        try {
            org.synesis.link.identity.NodeIdentity callerIdentity = new org.synesis.link.identity.IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            String callerNodeId = callerIdentity.nodeId();
            String callerWorkerId = binding.workerId();
            Path coordDir = location.root().resolve(".synesis/coordination");
            if (Files.exists(coordDir.resolve("events"))) {
                org.synesis.coordination.persistence.PredictionEventStore store = new org.synesis.coordination.persistence.PredictionEventStore(coordDir, location.projectId());
                // Startup reconciliation is deliberately pull-safe: each
                // durable inbox read gives the shared integration pump an
                // opportunity to recover an interrupted attempt or advance
                // the oldest eligible immutable snapshot. The pump owns its
                // project lock and never mutates a worker worktree.
                new org.synesis.workspace.application.integration.IntegrationOrchestrationService()
                        .orchestrateIntegration(root, store, callerIdentity);
                org.synesis.coordination.domain.capability.CapabilityRequestProjection capProj = store.capabilityRequestProjection();
                Map<String, Object> collaboration = collaborationDetails(store, binding.sessionId());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pendingCoordination = (List<Map<String, Object>>) collaboration.get("pendingCoordination");
                if (!pendingCoordination.isEmpty()) {
                    return new AgentResponse(AgentStatus.READY, AgentReason.OWNER_REQUEST_PENDING,
                            AgentNextAction.RESPOND_COORDINATION, collaboration);
                }

                // A session that has not established its own active intent is
                // not eligible to service another lane's capability inbox.
                // Provider models may receive an implementation-available
                // item before they have successfully decoded a claim-bearing
                // ensure_session request.  Returning that item here would
                // allow the unclaimed session to answer as the owner or
                // requester of a different lane.  Keep the response
                // actionable: the caller must refresh/establish its own
                // claim first, while discovery remains available in the
                // bounded result payload.
                String callerParticipant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
                boolean callerHasActiveIntent = store.collaborationProjection().activeIntents().stream()
                        .anyMatch(intent -> intent.participant().equals(callerParticipant));
                if (store.collaborationProjection().activated() && !callerHasActiveIntent) {
                    Map<String, Object> claimRequired = new LinkedHashMap<>(collaboration);
                    claimRequired.put("claimsRequired", true);
                    claimRequired.put("reason", AgentReason.COORDINATION_INTENT_REQUIRED.value());
                    return new AgentResponse(AgentStatus.BLOCKED, AgentReason.COORDINATION_INTENT_REQUIRED,
                            AgentNextAction.ENSURE_SESSION, claimRequired);
                }

                List<org.synesis.coordination.domain.capability.CapabilityRequestRecord> ownerPending = capProj.findPendingForOwner(callerNodeId);

                // Slice 3: Check active integration projection states
                var taskCompProj = store.taskCompletionProjection();
                var activeAttemptOpt = taskCompProj.activeIntegrationAttempt();
                if (activeAttemptOpt.isPresent()) {
                    var att = activeAttemptOpt.get();
                    if ("conflict".equals(att.status())) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("pending", 1);
                        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.REQUEST_HUMAN_HELP, result);
                    }
                }

                // Check if worker's task is waiting for dependencies
                var workerSnapshotOpt = taskCompProj.findLatestSnapshotForWorker(callerNodeId, callerWorkerId);
                if (workerSnapshotOpt.isPresent()) {
                    var state = taskCompProj.taskState(workerSnapshotOpt.get().taskId());
                    if (state == org.synesis.coordination.domain.task.TaskCompletionState.WAITING_FOR_DEPENDENCIES
                            || state == org.synesis.coordination.domain.task.TaskCompletionState.SNAPSHOT_READY) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("pending", 1);
                        return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, result);
                    }
                }
                if (!ownerPending.isEmpty()) {
                    org.synesis.coordination.domain.capability.CapabilityRequestRecord topReq = ownerPending.getFirst();
                    Map<String, Object> contractMap = new LinkedHashMap<>();
                    contractMap.put("inputs", topReq.contract().inputs());
                    contractMap.put("output", topReq.contract().output());
                    contractMap.put("requiredBehavior", topReq.contract().requiredBehavior());
                    contractMap.put("acceptanceTests", topReq.contract().acceptanceTests());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle", topReq.handle().value());
                    result.put("capability", topReq.capability());
                    result.put("authorityLineageId", topReq.authorityLineageId().toString());
                    result.put("contract", contractMap);
                    result.put("pending", ownerPending.size());
                    return new AgentResponse(AgentStatus.READY, null, AgentNextAction.RESPOND_COORDINATION, result);
                }

                // Slice 2: owner must respond to a validation revision
                List<org.synesis.coordination.domain.capability.CapabilityRequestRecord> validationRevList = capProj.findValidationRevisionForOwner(callerNodeId);
                if (!validationRevList.isEmpty()) {
                    org.synesis.coordination.domain.capability.CapabilityRequestRecord topReq = validationRevList.getFirst();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("capabilityRequestHandle", topReq.handle().value());
                    result.put("reason", topReq.reason() != null ? topReq.reason() : "Revision required by requester");
                    result.put("pending", validationRevList.size());
                    return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_FAILED, AgentNextAction.RESPOND_TO_VALIDATION_REVISION, result);
                }

                List<org.synesis.coordination.domain.capability.CapabilityRequestRecord> reqPending = capProj.findPendingForRequester(callerNodeId);
                if (!reqPending.isEmpty()) {
                    org.synesis.coordination.domain.capability.CapabilityRequestRecord topReq = null;
                    for (org.synesis.coordination.domain.capability.CapabilityRequestRecord r : reqPending) {
                        if (r.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REVISION_REQUESTED) {
                            topReq = r;
                            break;
                        } else if (r.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE && topReq == null) {
                            topReq = r;
                        } else if (r.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REJECTED && topReq == null) {
                            topReq = r;
                        } else if (r.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.AWAITING_OWNER && topReq == null) {
                            topReq = r;
                        } else if (r.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.ACCEPTED && topReq == null) {
                            topReq = r;
                        } else if (r.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTING && topReq == null) {
                            topReq = r;
                        }
                    }
                    if (topReq != null) {
                        if (topReq.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REVISION_REQUESTED) {
                            Map<String, Object> contractMap = new LinkedHashMap<>();
                            contractMap.put("inputs", topReq.contract().inputs());
                            contractMap.put("output", topReq.contract().output());
                            contractMap.put("requiredBehavior", topReq.contract().requiredBehavior());
                            contractMap.put("acceptanceTests", topReq.contract().acceptanceTests());

                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("contract", contractMap);
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED, AgentNextAction.REVISE_CAPABILITY_REQUEST, result);
                        } else if (topReq.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE) {
                            // Slice 2: requester must validate the available implementation
                            org.synesis.coordination.domain.integration.ImplementationRevisionRecord implRec = capProj.findLatestImplementation(topReq.handle().value()).orElse(null);
                            int revision = implRec != null ? implRec.revisionNumber() : 1;
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("capability", topReq.capability());
                            result.put("authorityLineageId", topReq.authorityLineageId().toString());
                            result.put("revision", revision);
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.READY, null, AgentNextAction.VALIDATE_IMPLEMENTATION, result);
                        } else if (topReq.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REJECTED) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("reason", topReq.reason() != null ? topReq.reason() : "Capability request rejected by owner");
                            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_REJECTED, AgentNextAction.RETRY, result);
                        } else if (topReq.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.AWAITING_OWNER) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
                        } else if (topReq.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.ACCEPTED
                                || topReq.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTING) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("capabilityRequestHandle", topReq.handle().value());
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.WAITING, AgentReason.IMPLEMENTATION_UNAVAILABLE, AgentNextAction.WAIT, result);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        List<CoordinationItem> items = loadCoordinationItems(assignedWorktree, location.root(), request.provider());
        if (items.isEmpty()) {
            Map<String, Object> collaboration = collaborationDetailsForRequest(location, request);
            return new AgentResponse(AgentStatus.READY, null, null, collaboration);
        }

        // Priority Order:
        // 1. Safety Failure
        // 2. Invalidated Dependency
        // 3. Owner Request Pending
        // 4. Capability Description Required (Needs Capability)
        // 5. Validation Required
        // 6. Waiting for Owner
        CoordinationItem topItem = null;
        int topPriority = Integer.MAX_VALUE;

        for (CoordinationItem item : items) {
            int p = priorityOf(item.type());
            if (p < topPriority) {
                topPriority = p;
                topItem = item;
            } else if (p == topPriority && topItem != null && item.sequence() < topItem.sequence()) {
                topItem = item;
            }
        }

        int pendingCount = items.size();

        if (topItem == null) {
            return AgentResponse.ready("isolated", 0);
        }

        return switch (topItem.type().toUpperCase(java.util.Locale.ROOT)) {
            case "SAFETY_FAILURE" -> new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            case "DEPENDENCY_INVALIDATED" -> {
                Map<String, Object> res = new LinkedHashMap<>();
                if (topItem.capability() != null) {
                    res.put("capability", topItem.capability());
                }
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.DEPENDENCY_INVALIDATED, AgentNextAction.RETRY, res);
            }
            case "OWNER_REQUEST" -> {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("capability", topItem.capability() != null ? topItem.capability() : "unknown");
                req.put("inputs", topItem.details().getOrDefault("inputs", "..."));
                req.put("output", topItem.details().getOrDefault("output", "..."));
                req.put("behavior", topItem.details().getOrDefault("behavior", "..."));
                req.put("acceptanceTest", topItem.details().getOrDefault("acceptanceTest", "..."));

                Map<String, Object> res = new LinkedHashMap<>();
                res.put("request", req);
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_REQUEST_PENDING, null, res);
            }
            case "NEEDS_CAPABILITY" -> {
                List<String> reqFields = List.of("inputs", "output", "behavior", "acceptanceTest");
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("capability", topItem.capability());
                res.put("requiredFields", reqFields);
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.REQUEST_COORDINATION, res);
            }
            case "VALIDATION_REQUIRED" -> {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("capability", topItem.capability());
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, null, res);
            }
            case "WAITING_FOR_OWNER" -> {
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("pending", pendingCount);
                yield new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, res);
            }
            default -> AgentResponse.ready("isolated", pendingCount);
        };
    }

    /** Builds a JSON-safe collaboration discovery and pending-request projection. */
    private Map<String, Object> collaborationDetailsForRequest(ProjectApplicationService.ProjectLocation location,
            NextActionRequest request) {
        try {
            Path coordDir = location.root().resolve(".synesis/coordination");
            if (Files.exists(coordDir.resolve("events"))) {
                var store = new org.synesis.coordination.persistence.PredictionEventStore(coordDir, location.projectId());
                String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(request.connectionInstanceId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var binding = new ProviderSessionBindingService().list(location, request.provider()).stream()
                        .filter(candidate -> fingerprint.equals(candidate.providerInstanceFingerprint()))
                        .findFirst().orElse(null);
                if (binding != null) {
                    return collaborationDetails(store, binding.sessionId());
                }
                return collaborationDetails(store, "");
            }
        } catch (Exception ignored) {
        }
        return Map.of("workspace", "isolated", "pending", 0,
                "participants", List.of(), "intents", List.of(), "pendingCoordination", List.of());
    }

    /** Converts collaboration records to a provider-safe next-action payload. */
    private Map<String, Object> collaborationDetails(
            org.synesis.coordination.persistence.PredictionEventStore store, String sessionId) {
        String participantId = sessionId == null || sessionId.isBlank()
                ? "" : WorkspaceCollaborationService.participantHandle(sessionId);
        List<Map<String, Object>> intents = store.collaborationProjection().activeIntents().stream()
                .map(AgentNextActionService::intentMap).toList();
        Map<String, Object> currentIntent = store.collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participant().equals(participantId))
                .map(AgentNextActionService::intentMap)
                .findFirst()
                .orElse(null);
        List<Map<String, Object>> participants = store.collaborationProjection().participants().stream()
                .map(AgentNextActionService::participantMap).toList();
        List<Map<String, Object>> pending = store.collaborationProjection().requests().stream()
                .filter(request -> request.status() == CoordinationRequest.Status.PENDING)
                .filter(request -> !store.collaborationProjection().inboxAcknowledged(request.requestId()))
                .filter(request -> participantId.isBlank() || request.target().equals(participantId))
                .map(AgentNextActionService::requestMap).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace", "isolated");
        result.put("pending", pending.size());
        result.put("participants", participants);
        result.put("intents", intents);
        result.put("currentParticipant", participantId);
        result.put("currentIntent", currentIntent);
        result.put("pendingCoordination", pending);
        result.put("claimConflicts", List.of());
        return result;
    }

    private static Map<String, Object> intentMap(WorkIntent intent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("intentId", intent.intentId().toString());
        map.put("participant", intent.participant());
        map.put("provider", intent.provider());
        map.put("goal", intent.goal());
        map.put("acceptance", intent.acceptance());
        map.put("selectors", intent.selectors().stream().map(AgentNextActionService::selectorMap).toList());
        map.put("version", intent.version());
        map.put("claimEpoch", intent.version());
        map.put("workGroupId", intent.workGroupId().toString());
        map.put("authorityLineageId", intent.authorityLineageId().toString());
        map.put("status", intent.status().name());
        return map;
    }

    private static Map<String, Object> participantMap(Participant participant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", participant.id());
        map.put("provider", participant.provider());
        map.put("goal", participant.goal());
        map.put("state", participant.state().name());
        map.put("lastVerifiedActivity", participant.lastVerifiedActivity());
        map.put("claims", participant.claims().stream().map(AgentNextActionService::selectorMap).toList());
        return map;
    }

    private static Map<String, Object> requestMap(CoordinationRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", request.requestId().toString());
        map.put("inboxItemId", request.requestId().toString());
        map.put("requester", request.requester());
        map.put("target", request.target());
        map.put("conflictingIntentId", request.conflictingIntentId().toString());
        map.put("kind", request.kind().name());
        map.put("proposal", request.proposal());
        map.put("status", request.status().name());
        return map;
    }

    private static Map<String, Object> selectorMap(ResourceSelector selector) {
        return Map.of("kind", selector.kind().name(), "path", selector.value());
    }

    private static int priorityOf(String type) {
        if (type == null) return 99;
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "SAFETY_FAILURE" -> 1;
            case "DEPENDENCY_INVALIDATED" -> 2;
            case "OWNER_REQUEST" -> 3;
            case "NEEDS_CAPABILITY" -> 4;
            case "VALIDATION_REQUIRED" -> 5;
            case "WAITING_FOR_OWNER" -> 6;
            default -> 99;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<CoordinationItem> loadCoordinationItems(Path assignedWorktree, Path projectRoot, String targetWorker) {
        Path itemsFile = assignedWorktree.resolve(".synesis/local/coordination/items.json");
        if (!Files.exists(itemsFile)) {
            itemsFile = projectRoot.resolve(".synesis/local/coordination/items.json");
        }
        if (!Files.exists(itemsFile)) {
            return List.of();
        }

        try {
            String json = Files.readString(itemsFile);
            Object parsed = ProviderJson.parse(json);
            if (!(parsed instanceof List<?> list)) {
                return List.of();
            }

            List<CoordinationItem> items = new ArrayList<>();
            long seq = 0;
            for (Object obj : list) {
                seq++;
                if (obj instanceof Map<?, ?> map) {
                    String worker = (String) map.get("workerId");
                    boolean matchesWorker = worker == null || worker.isBlank() || worker.equalsIgnoreCase(targetWorker);
                    if (!matchesWorker) {
                        continue;
                    }

                    boolean obsolete = Boolean.TRUE.equals(map.get("obsolete")) || Boolean.TRUE.equals(map.get("completed"));
                    if (obsolete) {
                        continue;
                    }

                    String type = (String) map.get("type");
                    String capability = (String) map.get("capability");
                    Map<String, Object> details = (Map<String, Object>) map.get("details");
                    if (details == null) {
                        details = Map.of();
                    }

                    if (type != null) {
                        items.add(new CoordinationItem(type, capability, worker, details, seq));
                    }
                }
            }
            return items;
        } catch (Exception ex) {
            return List.of();
        }
    }
}
