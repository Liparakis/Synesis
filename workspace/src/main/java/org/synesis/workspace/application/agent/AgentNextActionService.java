package org.synesis.workspace.application.agent;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

import org.synesis.workspace.application.ProjectApplicationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;

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
    private final TaskSnapshotService snapshotService;

    /**
     * Creates a next-action retrieval application service.
     */
    public AgentNextActionService() {
        this.projectService = new ProjectApplicationService();
        this.readinessService = new WorkspaceReadinessService();
        this.workflowReducer = new AgentWorkflowReducer();
        this.snapshotService = new TaskSnapshotService();
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
            var exactBinding = new ProviderSessionBindingService().find(
                    location, request.provider(), request.connectionInstanceId());
            if (exactBinding.isPresent() && "COMPLETED".equals(exactBinding.get().status())) {
                AgentResponse reviewResponse = completedReviewAction(location, exactBinding.get().sessionId());
                if (reviewResponse != null) {
                    return reviewResponse;
                }
                // A completed lane remains terminal when no review action is
                // available.  It never re-enters workspace readiness or write
                // ownership merely because a sibling lane is still active.
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
                List<Map<String, Object>> reviewActions = (List<Map<String, Object>>) collaboration.get("reviewActions");
                if (!reviewActions.isEmpty()) {
                    Map<String, Object> review = reviewActions.getFirst();
                    String protocolAction = String.valueOf(review.get("nextProtocolAction"));
                    AgentNextAction next = "respond_coordination".equals(protocolAction)
                            ? AgentNextAction.RESPOND_COORDINATION
                            : "wait".equals(protocolAction) ? AgentNextAction.WAIT
                            : AgentNextAction.REQUEST_COORDINATION;
                    Map<String, Object> reviewProjection = new LinkedHashMap<>(collaboration);
                    reviewProjection.put("nextProtocolAction", review.get("nextProtocolAction"));
                    reviewProjection.put("nextProtocolKind", review.get("nextProtocolKind"));
                    reviewProjection.put("nextProtocolPayload", review.get("nextProtocolPayload"));
                    return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED, next, reviewProjection);
                }
                String callerParticipant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
                Map<String, Object> publicationAction = snapshotPublicationAction(
                        store, callerParticipant, assignedWorktree, snapshotService);
                if (publicationAction != null) {
                    return new AgentResponse(AgentStatus.READY, AgentReason.SNAPSHOT_PUBLICATION_REQUIRED,
                            AgentNextAction.FINISH_LANE, publicationAction);
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pendingCoordination = (List<Map<String, Object>>) collaboration.get("pendingCoordination");
                if (!pendingCoordination.isEmpty()) {
                    Map<String, Object> ownerAction = new LinkedHashMap<>(collaboration);
                    Map<String, Object> reviewAcceptance = reviewAcceptanceAction(pendingCoordination);
                    if (reviewAcceptance != null) {
                        ownerAction.putAll(reviewAcceptance);
                    }
                    return new AgentResponse(AgentStatus.READY, AgentReason.OWNER_REQUEST_PENDING,
                            AgentNextAction.RESPOND_COORDINATION, ownerAction);
                }

                Map<String, Object> pendingReviewGrant = pendingReviewGrantAction(store, callerParticipant);
                if (pendingReviewGrant != null) {
                    Map<String, Object> ownerWait = new LinkedHashMap<>(collaboration);
                    ownerWait.putAll(pendingReviewGrant);
                    return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED,
                            AgentNextAction.WAIT, ownerWait);
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
            Map<String, Object> collaboration = new LinkedHashMap<>(collaborationDetailsForRequest(location, request));
            ProjectCommandDiagnostics.Report command = ProjectCommandDiagnostics.inspect(
                    AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
            Map<String, Object> durableCommands = new LinkedHashMap<>();
            durableCommands.put("namespacePresent", command.present());
            durableCommands.put("formatValid", command.formatValid());
            durableCommands.put("newerObjects", command.newerObjectCount());
            durableCommands.put("olderFormats", command.olderFormatCount());
            durableCommands.put("permanentLocks", command.permanentLockCount());
            durableCommands.put("scopes", command.scopeCount());
            durableCommands.put("anchors", command.anchorCount());
            durableCommands.put("requests", command.requestCount());
            durableCommands.put("liveAtCapacity", command.liveAtCapacityCount());
            durableCommands.put("deadAnchors", command.deadAnchorCount());
            durableCommands.put("terminalEligible", command.eligibleTerminalCount());
            durableCommands.put("pinnedEvidence", command.pinnedEvidenceCount());
            durableCommands.put("staleIndex", command.staleIndexCount());
            durableCommands.put("enumerationComplete", command.enumerationComplete());
            durableCommands.put("terminalHistoryCompactions", command.terminalHistoryCompactionCount());
            durableCommands.put("leaseGapRevisionMismatches", command.leaseGapRevisionMismatchCount());
            durableCommands.put("admissionRestarts", command.admissionRestartCount());
            durableCommands.put("cleanCloseDetachBlocked", command.cleanCloseDetachBlockedCount());
            durableCommands.put("deferredMutations", command.deferredMutationCount());
            collaboration.put("durableCommands", durableCommands);
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

    private AgentResponse completedReviewAction(
            ProjectApplicationService.ProjectLocation location, String sessionId) {
        try {
            Path coordination = location.root().resolve(".synesis/coordination");
            if (!Files.exists(coordination.resolve("events"))) {
                return null;
            }
            org.synesis.coordination.persistence.PredictionEventStore store =
                    new org.synesis.coordination.persistence.PredictionEventStore(
                            coordination, location.projectId());
            String participant = WorkspaceCollaborationService.participantHandle(sessionId);
            Set<UUID> completedGroups = completedParticipantWorkGroups(store, participant);
            if (completedGroups.isEmpty()) {
                return null;
            }
            Map<String, Object> collaboration = collaborationDetails(store, sessionId, completedGroups);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> reviewActions =
                    (List<Map<String, Object>>) collaboration.get("reviewActions");
            if (reviewActions.isEmpty()) {
                return null;
            }
            Map<String, Object> review = reviewActions.getFirst();
            String protocolAction = String.valueOf(review.get("nextProtocolAction"));
            AgentNextAction next = "respond_coordination".equals(protocolAction)
                    ? AgentNextAction.RESPOND_COORDINATION
                    : "wait".equals(protocolAction) ? AgentNextAction.WAIT
                    : AgentNextAction.REQUEST_COORDINATION;
            Map<String, Object> projection = new LinkedHashMap<>(collaboration);
            projection.put("reviewOnly", true);
            projection.put("nextProtocolAction", review.get("nextProtocolAction"));
            projection.put("nextProtocolKind", review.get("nextProtocolKind"));
            projection.put("nextProtocolPayload", review.get("nextProtocolPayload"));
            return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_REQUIRED,
                    next, projection);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Set<UUID> completedParticipantWorkGroups(
            org.synesis.coordination.persistence.PredictionEventStore store, String participant) {
        Set<UUID> groups = new LinkedHashSet<>();
        for (TaskSnapshotRecord snapshot : store.taskCompletionProjection().allSnapshots()) {
            if (participant.equals(snapshot.provenance().participant())) {
                groups.add(snapshot.provenance().workGroupId());
            }
        }
        return Set.copyOf(groups);
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
        return collaborationDetails(store, sessionId, null);
    }

    private Map<String, Object> collaborationDetails(
            org.synesis.coordination.persistence.PredictionEventStore store, String sessionId,
            Set<UUID> reviewGroupFilter) {
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
        List<Map<String, Object>> enrichedPending = pending.stream()
                .map(request -> enrichPendingRequest(request, store))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspace", "isolated");
        result.put("pending", enrichedPending.size());
        result.put("participants", participants);
        result.put("intents", intents);
        result.put("groups", store.workGroupProjection().groups().stream()
                .map(AgentNextActionService::workGroupMap).toList());
        result.put("grants", store.workGroupProjection().grants().stream()
                .map(AgentNextActionService::laneGrantMap).toList());
        result.put("snapshots", store.taskCompletionProjection().allSnapshots().stream()
                .map(AgentNextActionService::snapshotMap).toList());
        result.put("currentParticipant", participantId);
        result.put("currentIntent", currentIntent);
        result.put("pendingCoordination", enrichedPending);
        result.put("reviewActions", reviewActions(store, participantId, reviewGroupFilter));
        result.put("claimConflicts", List.of());
        return result;
    }

    private static Map<String, Object> enrichPendingRequest(Map<String, Object> request,
            org.synesis.coordination.persistence.PredictionEventStore store) {
        Map<String, Object> enriched = new LinkedHashMap<>(request);
        Object conflictingIntent = request.get("conflictingIntentId");
        if (conflictingIntent instanceof String intentId) {
            store.collaborationProjection().activeIntents().stream()
                    .filter(intent -> intent.intentId().toString().equals(intentId))
                    .findFirst()
                    .ifPresent(intent -> {
                        enriched.put("intentId", intent.intentId().toString());
                        enriched.put("workGroupId", intent.workGroupId().toString());
                        enriched.put("claimEpoch", intent.version());
                    });
        }
        return enriched;
    }

    private static Map<String, Object> reviewAcceptanceAction(List<Map<String, Object>> pendingCoordination) {
        for (Map<String, Object> request : pendingCoordination) {
            if (!"REVIEW".equals(request.get("kind"))) continue;
            Object requestId = request.get("requestId");
            Object intentId = request.get("intentId");
            Object workGroupId = request.get("workGroupId");
            Object claimEpoch = request.get("claimEpoch");
            if (!(requestId instanceof String) || !(intentId instanceof String)
                    || !(workGroupId instanceof String) || !(claimEpoch instanceof Number)) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("coordinationRequest", requestId);
            payload.put("coordinationStatus", CoordinationRequest.Status.ACCEPTED.name());
            payload.put("proposal", "admitted");
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("requestId", requestId);
            context.put("kind", request.get("kind"));
            context.put("workGroupId", workGroupId);
            context.put("intentId", intentId);
            context.put("claimEpoch", claimEpoch);
            context.put("requester", request.get("requester"));
            context.put("target", request.get("target"));
            return Map.of(
                    "nextProtocolAction", "respond_coordination",
                    "nextProtocolKind", "coordination_response",
                    "nextProtocolPayload", payload,
                    "nextProtocolContext", context);
        }
        return null;
    }

    private static List<Map<String, Object>> reviewActions(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId) {
        return reviewActions(store, participantId, null);
    }

    private static List<Map<String, Object>> reviewActions(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId,
            Set<UUID> reviewGroupFilter) {
        List<Map<String, Object>> actions = new ArrayList<>();
        var projection = store.workGroupProjection();
        for (LaneGrant grant : projection.grants()) {
            if (!grant.targetParticipant().equals(participantId)) continue;
            if (reviewGroupFilter != null && !reviewGroupFilter.contains(grant.workGroupId())) continue;
            TaskSnapshotRecord snapshot = store.taskCompletionProjection().allSnapshots().stream()
                    .filter(value -> value.provenance().workGroupId().equals(grant.workGroupId()))
                    .filter(value -> value.provenance().laneId().equals(grant.targetIntentId()))
                    .filter(value -> value.provenance().claimEpoch() == grant.claimEpoch())
                    .findFirst().orElse(null);
            if (snapshot == null && !projection.grantAvailable(grant.grantId())) {
                Map<String, Object> waiting = new LinkedHashMap<>();
                waiting.put("state", "SNAPSHOT_PENDING");
                waiting.put("nextProtocolAction", "wait");
                waiting.put("nextProtocolKind", "review_validation");
                waiting.put("nextProtocolPayload", Map.of("grantId", grant.grantId().toString(),
                        "workGroupId", grant.workGroupId().toString(), "snapshotRequired", true));
                waiting.put("grant", laneGrantMap(grant));
                actions.add(waiting);
                continue;
            }
            if (projection.reviewValidationForGrant(grant.grantId()).isPresent()) continue;
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("state", projection.grantAvailable(grant.grantId()) ? "GRANT_AVAILABLE" : "VALIDATION_REQUIRED");
            action.put("nextProtocolAction", projection.grantAvailable(grant.grantId())
                    ? "request_coordination" : "respond_coordination");
            action.put("nextProtocolKind", projection.grantAvailable(grant.grantId())
                    ? "work_group_join" : "review_validation");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("grantId", grant.grantId().toString());
            payload.put("intentId", grant.targetIntentId().toString());
            payload.put("claimEpoch", grant.claimEpoch());
            if (projection.grantAvailable(grant.grantId())) {
                payload.put("workGroupId", grant.workGroupId().toString());
                payload.put("targetParticipant", grant.targetParticipant());
            } else {
                payload.put("snapshotId", snapshot.snapshotId());
                payload.put("result", "accepted|rejected");
            }
            action.put("nextProtocolPayload", payload);
            action.put("grant", laneGrantMap(grant));
            if (snapshot != null) action.put("snapshot", snapshotMap(snapshot));
            actions.add(action);
        }
        if (actions.isEmpty() && !participantId.isBlank()) {
            List<WorkIntent> activeIntents = store.collaborationProjection().activeIntents();
            List<TaskSnapshotRecord> snapshots = store.taskCompletionProjection().allSnapshots();
            boolean callerHasActiveIntent = activeIntents.stream()
                    .anyMatch(intent -> intent.participant().equals(participantId));
            for (WorkGroup group : projection.groups()) {
                if (group.status() != WorkGroup.Status.ACTIVE) continue;
                if (reviewGroupFilter != null && !reviewGroupFilter.contains(group.workGroupId())) continue;
                if (callerHasActiveIntent && activeIntents.stream()
                        .noneMatch(intent -> intent.participant().equals(participantId)
                                && intent.workGroupId().equals(group.workGroupId()))) continue;
                WorkIntent owner = activeIntents.stream()
                        .filter(intent -> intent.workGroupId().equals(group.workGroupId()))
                        .filter(intent -> !intent.participant().equals(participantId))
                        .filter(intent -> snapshots.stream().anyMatch(snapshot ->
                                snapshot.provenance().workGroupId().equals(group.workGroupId())
                                        && snapshot.provenance().laneId().equals(intent.intentId())
                                        && snapshot.provenance().claimEpoch() == intent.version()))
                        .findFirst().orElse(null);
                if (owner == null) {
                    owner = activeIntents.stream()
                            .filter(intent -> intent.workGroupId().equals(group.workGroupId()))
                            .findFirst().orElse(null);
                }
                if (owner == null || owner.participant().equals(participantId)) continue;
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("state", "REVIEW_ADMISSION_REQUIRED");
                action.put("nextProtocolAction", "request_coordination");
                action.put("nextProtocolKind", "work_group_join");
                action.put("nextProtocolPayload", Map.of(
                        "workGroupId", group.workGroupId().toString(),
                        "intentId", owner.intentId().toString(),
                        "proposal", "Review the immutable snapshot for this work group"));
                action.put("workGroup", workGroupMap(group));
                actions.add(action);
            }
        }
        return List.copyOf(actions);
    }

    private static Map<String, Object> snapshotPublicationAction(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId,
            Path assignedWorktree, TaskSnapshotService snapshotService) {
        var collaboration = store.collaborationProjection();
        var completion = store.taskCompletionProjection();
        for (var intent : collaboration.activeIntents()) {
            if (!intent.participant().equals(participantId)) continue;
            // REVIEW grant consumption authorizes publication but does not
            // manufacture implementation work.  Keep the projection
            // executable by applying the same read-only source/artifact gate
            // that finish_lane applies while creating the snapshot.
            if (assignedWorktree == null || snapshotService == null) continue;
            try {
                if (!snapshotService.hasPublishableChanges(assignedWorktree)) continue;
            } catch (Exception ignored) {
                // A failed inspection must not turn into a false publication
                // permission.  The normal IMPLEMENT path remains available.
                continue;
            }
            boolean reviewGrantConsumed = store.workGroupProjection().grants().stream()
                    .anyMatch(grant -> grant.workGroupId().equals(intent.workGroupId())
                            && grant.targetIntentId().equals(intent.intentId())
                            && grant.claimEpoch() == intent.version()
                            && !grant.targetParticipant().equals(participantId)
                            && store.workGroupProjection().grantConsumed(grant.grantId()));
            if (!reviewGrantConsumed) continue;
            boolean snapshotPublished = completion.allSnapshots().stream().anyMatch(snapshot ->
                    snapshot.provenance().workGroupId().equals(intent.workGroupId())
                            && snapshot.provenance().laneId().equals(intent.intentId())
                            && snapshot.provenance().claimEpoch() == intent.version());
            if (snapshotPublished) continue;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("snapshotPublicationRequired", true);
            result.put("workGroupId", intent.workGroupId().toString());
            result.put("intentId", intent.intentId().toString());
            result.put("claimEpoch", intent.version());
            result.put("participant", participantId);
            result.put("nextProtocolAction", "finish_lane");
            result.put("nextProtocolPayload", Map.of("summary", "Publish the completed immutable snapshot"));
            return result;
        }
        return null;
    }

    private static Map<String, Object> pendingReviewGrantAction(
            org.synesis.coordination.persistence.PredictionEventStore store, String participantId) {
        var collaboration = store.collaborationProjection();
        var workGroups = store.workGroupProjection();
        var completion = store.taskCompletionProjection();
        for (WorkIntent intent : collaboration.activeIntents()) {
            if (!intent.participant().equals(participantId)) continue;
            WorkGroup group = workGroups.group(intent.workGroupId()).orElse(null);
            if (group == null || group.status() != WorkGroup.Status.ACTIVE) continue;
            boolean snapshotPublished = completion.allSnapshots().stream().anyMatch(snapshot ->
                    snapshot.provenance().workGroupId().equals(intent.workGroupId())
                            && snapshot.provenance().laneId().equals(intent.intentId())
                            && snapshot.provenance().claimEpoch() == intent.version());
            if (snapshotPublished) continue;
            for (LaneGrant grant : workGroups.grants()) {
                if (!grant.workGroupId().equals(intent.workGroupId())
                        || !grant.targetIntentId().equals(intent.intentId())
                        || grant.claimEpoch() != intent.version()
                        || grant.targetParticipant().equals(participantId)
                        || !workGroups.grantAvailable(grant.grantId())
                        || workGroups.reviewValidationForGrant(grant.grantId()).isPresent()) {
                    continue;
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("grantId", grant.grantId().toString());
                payload.put("workGroupId", grant.workGroupId().toString());
                payload.put("intentId", grant.targetIntentId().toString());
                payload.put("claimEpoch", grant.claimEpoch());
                payload.put("targetParticipant", grant.targetParticipant());
                payload.put("snapshotRequired", true);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("state", "REVIEW_GRANT_PENDING");
                result.put("reviewGrantPending", true);
                result.put("reviewGrant", laneGrantMap(grant));
                result.put("workGroup", workGroupMap(group));
                result.put("reviewerParticipant", grant.targetParticipant());
                result.put("nextProtocolAction", "wait");
                result.put("nextProtocolKind", "review_grant_consumption");
                result.put("nextProtocolPayload", payload);
                return result;
            }
        }
        return null;
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

    private static Map<String, Object> workGroupMap(WorkGroup group) {
        return Map.of("workGroupId", group.workGroupId().toString(),
                "projectId", group.projectId().toString(), "goal", group.goal(),
                "acceptance", group.acceptance(), "version", group.version(),
                "status", group.status().name());
    }

    private static Map<String, Object> laneGrantMap(LaneGrant grant) {
        return Map.of("grantId", grant.grantId().toString(),
                "workGroupId", grant.workGroupId().toString(),
                "targetIntentId", grant.targetIntentId().toString(),
                "targetParticipant", grant.targetParticipant(),
                "claimEpoch", grant.claimEpoch(), "singleUse", grant.singleUse());
    }

    private static Map<String, Object> snapshotMap(TaskSnapshotRecord snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", snapshot.taskId().toString());
        map.put("snapshotId", snapshot.snapshotId());
        map.put("baseCommit", snapshot.baseCommit());
        map.put("commitSha", snapshot.commitSha());
        map.put("changedPaths", snapshot.changedPaths());
        map.put("summary", snapshot.summary());
        map.put("createdAtMillis", snapshot.createdAtMillis());
        map.put("laneId", snapshot.provenance().laneId().toString());
        map.put("claimEpoch", snapshot.provenance().claimEpoch());
        map.put("workGroupId", snapshot.provenance().workGroupId().toString());
        map.put("participant", snapshot.provenance().participant());
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
