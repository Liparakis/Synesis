package org.synesis.workspace.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.synesis.workspace.agent.AgentCapabilityResult;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.provider.ProviderJson;

/**
 * Application service for retrieving the single highest-priority actionable coordination item
 * for an ambient MCP session.
 *
 * @since 1.0
 */
public final class AgentNextActionService {

    private final ProjectApplicationService projectService;
    private final WorkspaceReadinessService readinessService;

    /**
     * Creates a next-action retrieval application service.
     */
    public AgentNextActionService() {
        this.projectService = new ProjectApplicationService();
        this.readinessService = new WorkspaceReadinessService();
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
        Objects.requireNonNull(request, "request");

        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        ProjectApplicationService.ProjectLocation location;
        WorkspaceReadinessService.ReadinessResult readiness;
        try {
            location = projectService.locate(root);
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
                org.synesis.coordination.PredictionEventStore store = new org.synesis.coordination.PredictionEventStore(coordDir, location.projectId());
                org.synesis.coordination.CapabilityRequestProjection capProj = store.capabilityRequestProjection();

                List<org.synesis.coordination.CapabilityRequestRecord> ownerPending = capProj.findPendingForOwner(callerNodeId);

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
                    if (state == org.synesis.coordination.TaskCompletionState.WAITING_FOR_DEPENDENCIES
                            || state == org.synesis.coordination.TaskCompletionState.SNAPSHOT_READY) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("pending", 1);
                        return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, result);
                    }
                }
                if (!ownerPending.isEmpty()) {
                    org.synesis.coordination.CapabilityRequestRecord topReq = ownerPending.getFirst();
                    Map<String, Object> contractMap = new LinkedHashMap<>();
                    contractMap.put("inputs", topReq.contract().inputs());
                    contractMap.put("output", topReq.contract().output());
                    contractMap.put("requiredBehavior", topReq.contract().requiredBehavior());
                    contractMap.put("acceptanceTests", topReq.contract().acceptanceTests());

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("request", topReq.handle().value());
                    result.put("capability", topReq.capability());
                    result.put("contract", contractMap);
                    result.put("pending", ownerPending.size());
                    return new AgentResponse(AgentStatus.READY, null, AgentNextAction.RESPOND_TO_OWNER_REQUEST, result);
                }

                // Slice 2: owner must respond to a validation revision
                List<org.synesis.coordination.CapabilityRequestRecord> validationRevList = capProj.findValidationRevisionForOwner(callerNodeId);
                if (!validationRevList.isEmpty()) {
                    org.synesis.coordination.CapabilityRequestRecord topReq = validationRevList.getFirst();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("request", topReq.handle().value());
                    result.put("reason", topReq.reason() != null ? topReq.reason() : "Revision required by requester");
                    result.put("pending", validationRevList.size());
                    return new AgentResponse(AgentStatus.READY, AgentReason.VALIDATION_FAILED, AgentNextAction.RESPOND_TO_VALIDATION_REVISION, result);
                }

                List<org.synesis.coordination.CapabilityRequestRecord> reqPending = capProj.findPendingForRequester(callerNodeId);
                if (!reqPending.isEmpty()) {
                    org.synesis.coordination.CapabilityRequestRecord topReq = null;
                    for (org.synesis.coordination.CapabilityRequestRecord r : reqPending) {
                        if (r.state() == org.synesis.coordination.CapabilityLifecycleState.REVISION_REQUESTED) {
                            topReq = r;
                            break;
                        } else if (r.state() == org.synesis.coordination.CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE && topReq == null) {
                            topReq = r;
                        } else if (r.state() == org.synesis.coordination.CapabilityLifecycleState.REJECTED && topReq == null) {
                            topReq = r;
                        } else if (r.state() == org.synesis.coordination.CapabilityLifecycleState.AWAITING_OWNER && topReq == null) {
                            topReq = r;
                        } else if (r.state() == org.synesis.coordination.CapabilityLifecycleState.ACCEPTED && topReq == null) {
                            topReq = r;
                        } else if (r.state() == org.synesis.coordination.CapabilityLifecycleState.IMPLEMENTING && topReq == null) {
                            topReq = r;
                        }
                    }
                    if (topReq != null) {
                        if (topReq.state() == org.synesis.coordination.CapabilityLifecycleState.REVISION_REQUESTED) {
                            Map<String, Object> contractMap = new LinkedHashMap<>();
                            contractMap.put("inputs", topReq.contract().inputs());
                            contractMap.put("output", topReq.contract().output());
                            contractMap.put("requiredBehavior", topReq.contract().requiredBehavior());
                            contractMap.put("acceptanceTests", topReq.contract().acceptanceTests());

                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("request", topReq.handle().value());
                            result.put("contract", contractMap);
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.READY, AgentReason.REVISION_REQUIRED, AgentNextAction.REVISE_CAPABILITY_REQUEST, result);
                        } else if (topReq.state() == org.synesis.coordination.CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE) {
                            // Slice 2: requester must validate the available implementation
                            org.synesis.coordination.ImplementationRevisionRecord implRec = capProj.findLatestImplementation(topReq.handle().value()).orElse(null);
                            int revision = implRec != null ? implRec.revisionNumber() : 1;
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("request", topReq.handle().value());
                            result.put("capability", topReq.capability());
                            result.put("revision", revision);
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.READY, null, AgentNextAction.VALIDATE_IMPLEMENTATION, result);
                        } else if (topReq.state() == org.synesis.coordination.CapabilityLifecycleState.REJECTED) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("request", topReq.handle().value());
                            result.put("reason", topReq.reason() != null ? topReq.reason() : "Capability request rejected by owner");
                            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.CAPABILITY_REJECTED, AgentNextAction.RETRY, result);
                        } else if (topReq.state() == org.synesis.coordination.CapabilityLifecycleState.AWAITING_OWNER) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("request", topReq.handle().value());
                            result.put("pending", reqPending.size());
                            return new AgentResponse(AgentStatus.WAITING, AgentReason.OWNER_RESPONSE_PENDING, AgentNextAction.WAIT, result);
                        } else if (topReq.state() == org.synesis.coordination.CapabilityLifecycleState.ACCEPTED
                                || topReq.state() == org.synesis.coordination.CapabilityLifecycleState.IMPLEMENTING) {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("request", topReq.handle().value());
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
            return AgentResponse.ready("isolated", 0);
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
                yield new AgentResponse(AgentStatus.NEEDS_CAPABILITY, AgentReason.OWNER_REQUIRED, AgentNextAction.DESCRIBE_REQUIRED_CAPABILITY, res);
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
