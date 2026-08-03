package org.synesis.workspace.application.integration;
import org.synesis.workspace.application.control.ControlBranchAdvancementService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityRequestProjection;

import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.coordination.domain.prediction.PredictionEventType;

import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectProcessExecutor;

/**
 * Orchestrates the integration pipeline for ready task snapshots.
 *
 * <p>Pipeline steps:
 * <ol>
 *   <li>Builds a dependency graph across task snapshots and topologically sorts them.</li>
 *   <li>Detects cycles and fails closed if invalid.</li>
 *   <li>Prepares a dedicated external integration worktree starting at control HEAD.</li>
 *   <li>Applies task snapshots in deterministic topological order.</li>
 *   <li>Executes the project integration gate (tests &amp; build verification).</li>
 *   <li>Fast-forwards the control branch via {@link ControlBranchAdvancementService}.</li>
 *   <li>Releases semantic ownership and finalizes worker sessions.</li>
 * </ol>
 *
 * @since 1.0
 */
public final class IntegrationOrchestrationService {

    private static final Object INTEGRATION_LOCK = new Object();
    private final IntegrationWorkspaceService workspaceService;
    private final ControlBranchAdvancementService advancementService;
    private final ProjectApplicationService projectService;
    private final ProjectProcessExecutor processExecutor;

    /**
     * Creates an integration orchestration service.
     */
    public IntegrationOrchestrationService() {
        this.workspaceService = new IntegrationWorkspaceService();
        this.advancementService = new ControlBranchAdvancementService();
        this.projectService = new ProjectApplicationService();
        this.processExecutor = new ProjectProcessExecutor();
    }

    /**
     * Attempts integration of all ready task snapshots for a control project.
     *
     * @param controlRoot control project root path
     * @param store       prediction event store
     * @param identity    node signing identity
     * @return agent response indicating integration outcome
     */
    public AgentResponse orchestrateIntegration(Path controlRoot, PredictionEventStore store, NodeIdentity identity) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(identity, "identity");

        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT,
                        AgentNextAction.RETRY, Map.of("failure", "INTEGRATION_LOCK_UNAVAILABLE"));
            }
            return orchestrateIntegrationLocked(controlRoot, store, identity);
        } catch (IOException failure) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT,
                    AgentNextAction.RETRY, Map.of("failure", "INTEGRATION_LOCK_UNAVAILABLE"));
        }
    }

    private AgentResponse orchestrateIntegrationLocked(Path controlRoot, PredictionEventStore store, NodeIdentity identity) {

        synchronized (INTEGRATION_LOCK) {
            List<TaskSnapshotRecord> snapshots = store.taskCompletionProjection().eligibleSnapshots();
            if (snapshots.isEmpty()) {
                return new AgentResponse(AgentStatus.READY, null, AgentNextAction.RETRY, Map.of());
            }

            // A provider retry must not start a second integration attempt
            // while an earlier process still owns the durable attempt. The
            // attempt remains recoverable and its immutable snapshots remain
            // available to the next integration action.
            var activeAttempt = store.taskCompletionProjection().activeIntegrationAttempt();
            if (activeAttempt.isPresent() && "started".equals(activeAttempt.get().status())) {
                var attempt = activeAttempt.get();
                if (!attempt.integrationCommitSha().isBlank()) {
                    try {
                        String head = runGitOutput(controlRoot, "rev-parse", "HEAD");
                        if (head.equals(attempt.integrationCommitSha())) {
                            List<TaskSnapshotRecord> recovered = attempt.taskSnapshotIds().stream()
                                    .map(id -> store.taskCompletionProjection().findSnapshotById(id).orElse(null))
                                    .filter(Objects::nonNull).toList();
                            if (recovered.size() == attempt.taskSnapshotIds().size()) {
                                var recoveredResult = advancementService.recoverAdvancedControlBranch(
                                        controlRoot, attempt.attemptId(), attempt.expectedControlHead(),
                                        attempt.integrationCommitSha(), recovered, store, identity);
                                if (recoveredResult.advanced()) {
                                    return new AgentResponse(AgentStatus.COMPLETED, null, null,
                                            Map.of("recovered", true, "attemptId", attempt.attemptId()));
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // Keep the attempt pending; the next reconciliation can
                        // retry without changing the control branch.
                    }
                }
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING,
                        AgentNextAction.WAIT, Map.of("attemptId", attempt.attemptId(), "pending", 1));
            }

            // Do not integrate while a durable coordination request affecting
            // the active collaboration graph is unresolved.  This check is
            // intentionally before attempt allocation so a blocked negotiation
            // cannot leave a misleading started attempt behind.
            var pendingRequests = new org.synesis.coordination.application.WorkIntentService(store, identity)
                    .requests().stream()
                    .filter(request -> request.status() == org.synesis.coordination.domain.collaboration.CoordinationRequest.Status.PENDING)
                    .toList();
            if (!pendingRequests.isEmpty()) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.OWNER_REQUEST_PENDING,
                        AgentNextAction.RESPOND_COORDINATION,
                        Map.of("pending", pendingRequests.size(), "request", pendingRequests.getFirst().requestId().toString()));
            }

            CapabilityRequestProjection capProj = store.capabilityRequestProjection();

            // Resolve the control head once for this pump.  Candidate
            // classification is durable and deterministic against that head;
            // infrastructure failures remain pending and are retried by a
            // later pump.
            String expectedControlHead;
            try {
                expectedControlHead = runGitOutput(controlRoot, "rev-parse", "HEAD");
            } catch (Exception ex) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            }

            // Pick the oldest dependency-ready candidate.  Structurally
            // invalid candidates are durably blocked and removed from the
            // eligible queue; unrelated candidates are still considered in
            // the same pump invocation.
            List<TaskSnapshotRecord> allSnapshots = store.taskCompletionProjection().allSnapshots();
            List<TaskSnapshotRecord> eligible = store.taskCompletionProjection().eligibleSnapshots().stream()
                    .sorted(java.util.Comparator.comparingLong(TaskSnapshotRecord::createdAtMillis)
                            .thenComparing(TaskSnapshotRecord::snapshotId))
                    .toList();
            TaskSnapshotRecord selected = null;
            boolean blockedCandidate = false;
            while (selected == null && !eligible.isEmpty()) {
                boolean sawPendingDependency = false;
                for (TaskSnapshotRecord candidate : eligible) {
                    CandidateResolution resolution = resolveCandidate(candidate, allSnapshots, capProj,
                            store.taskCompletionProjection());
                    if (!resolution.structuralFailures().isEmpty()) {
                        blockedCandidate = true;
                        if (!appendBlockedCandidate(store, identity, candidate, expectedControlHead,
                                resolution.structuralFailures())) {
                            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                                    AgentNextAction.REQUEST_HUMAN_HELP,
                                    Map.of("failure", "INTEGRATION_BLOCK_APPEND_FAILED"));
                        }
                        continue;
                    }
                    if (!resolution.ready()) {
                        sawPendingDependency = true;
                        continue;
                    }
                    List<String> metadataFailures = validateSnapshotMetadata(controlRoot,
                            List.of(candidate), expectedControlHead, store);
                    if (!metadataFailures.isEmpty()) {
                        blockedCandidate = true;
                        if (!appendBlockedCandidate(store, identity, candidate, expectedControlHead, metadataFailures)) {
                            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE,
                                    AgentNextAction.REQUEST_HUMAN_HELP,
                                    Map.of("failure", "INTEGRATION_BLOCK_APPEND_FAILED"));
                        }
                        continue;
                    }
                    selected = candidate;
                    break;
                }
                if (selected == null) {
                    eligible = store.taskCompletionProjection().eligibleSnapshots().stream()
                            .sorted(java.util.Comparator.comparingLong(TaskSnapshotRecord::createdAtMillis)
                                    .thenComparing(TaskSnapshotRecord::snapshotId))
                            .toList();
                    if (eligible.isEmpty()) {
                        if (sawPendingDependency) {
                            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING,
                                    AgentNextAction.WAIT, Map.of("pendingDependencies", 1));
                        }
                        if (blockedCandidate) {
                            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                                    AgentNextAction.REQUEST_HUMAN_HELP,
                                    Map.of("state", "integration_blocked"));
                        }
                        return new AgentResponse(AgentStatus.READY, null, AgentNextAction.RETRY, Map.of());
                    }
                    // The remaining candidates are all pending dependencies or
                    // were just durably blocked.  Do not spin in one request.
                    if (sawPendingDependency) {
                        return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING,
                                AgentNextAction.WAIT, Map.of("pendingDependencies", eligible.size()));
                    }
                }
            }
            if (selected == null) {
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING,
                        AgentNextAction.WAIT, Map.of("pending", 1));
            }
            snapshots = List.of(selected);
            List<TaskSnapshotRecord> ordered;
            try {
                ordered = sortSnapshotsTopologically(snapshots, capProj);
            } catch (IllegalStateException cycleErr) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("error", "Dependency cycle detected"));
            }

            // 4. Allocate integration attempt ID
            String attemptToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String attemptId = "att_" + attemptToken;

            // 5. Append INTEGRATION_ATTEMPT_STARTED
            try {
                IntegrationAttemptPayload startPayload = new IntegrationAttemptPayload(
                        attemptId, store.projectId(),
                        ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                        expectedControlHead, "", "started", "");
                store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED,
                        identity.nodeId(), startPayload.encode(), identity);
            } catch (Exception ex) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            }

            // 6. Prepare integration worktree and apply snapshots
            var prepResult = workspaceService.prepareIntegrationWorktree(
                    controlRoot, attemptId, expectedControlHead, ordered);

            if (!prepResult.success()) {
                try {
                    String status = isMergeConflict(prepResult.failureReason()) ? "conflict" : "pending";
                    IntegrationAttemptPayload failurePayload = new IntegrationAttemptPayload(
                            attemptId, store.projectId(),
                            ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                            expectedControlHead, "", status, prepResult.failureReason());
                    store.append(UUID.randomUUID(), isMergeConflict(prepResult.failureReason())
                                    ? PredictionEventType.INTEGRATION_CONFLICTED
                                    : PredictionEventType.INTEGRATION_ATTEMPT_FAILED,
                            identity.nodeId(), failurePayload.encode(), identity);
                    if (isMergeConflict(prepResult.failureReason())) {
                        store.append(UUID.randomUUID(), PredictionEventType.REPAIR_REQUIRED,
                                identity.nodeId(), failurePayload.encode(), identity);
                    } else {
                        workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
                    }
                } catch (Exception ignored) {
                    // Leave the attempt and immutable candidate recoverable for
                    // the next reconciliation pass.
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("state", isMergeConflict(prepResult.failureReason())
                        ? "repair_required" : "integration_pending");
                result.put("reason", prepResult.failureReason());
                return new AgentResponse(isMergeConflict(prepResult.failureReason())
                                ? AgentStatus.BLOCKED : AgentStatus.WAITING,
                        isMergeConflict(prepResult.failureReason())
                                ? AgentReason.INTEGRATION_CONFLICT : AgentReason.INTEGRATION_PENDING,
                        isMergeConflict(prepResult.failureReason())
                                ? AgentNextAction.REQUEST_HUMAN_HELP : AgentNextAction.RETRY, result);
            }

            // 6. Execute the project-owned validation argv through the same
            // generic primitive used by run_command and finish_lane.
            ProjectProcessExecutor.ExecutionResult validation = null;
            try {
                ProjectApplicationService.ProjectLocation location = projectService.locate(controlRoot);
                if (location.validation() != null) {
                    validation = processExecutor.execute(ProjectProcessExecutor.ExecutionRequest.from(
                            location.validation(), prepResult.worktreePath(), controlRoot));
                }
            } catch (Exception failure) {
                validation = new ProjectProcessExecutor.ExecutionResult(
                        ProjectProcessExecutor.Outcome.COMMAND_START_FAILED, null, "", "", 0, 0, 0, 0,
                        false, false);
            }
            if (validation != null && !validation.succeeded()) {
                try {
                    IntegrationAttemptPayload failPayload = new IntegrationAttemptPayload(
                            attemptId, store.projectId(),
                            ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                            expectedControlHead, prepResult.integrationCommitSha(), "pending",
                            "VALIDATION_FAILED:" + validation.outcome().value());
                    store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED,
                            identity.nodeId(), failPayload.encode(), identity);
                } catch (Exception ignored) {
                }

                workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("phase", "integration");
                result.put("validation", validation.toMap());
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_FAILED,
                        AgentNextAction.RETRY, result);
            }

            // Record INTEGRATION_COMMIT_CREATED
            try {
                IntegrationAttemptPayload commitPayload = new IntegrationAttemptPayload(
                        attemptId, store.projectId(),
                        ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                        expectedControlHead, prepResult.integrationCommitSha(), "started", "");
                store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_COMMIT_CREATED,
                        identity.nodeId(), commitPayload.encode(), identity);
            } catch (Exception ignored) {
            }

            // 7. Advance control branch
            var advResult = advancementService.advanceControlBranch(
                    controlRoot, attemptId, expectedControlHead,
                    prepResult.integrationCommitSha(), ordered, store, identity);

            // Cleanup integration worktree after attempt
            workspaceService.removeIntegrationWorktree(prepResult.worktreePath());

            if (advResult.stale()) {
                appendAttemptFailure(store, identity, attemptId, ordered, expectedControlHead,
                        prepResult.integrationCommitSha(), "STALE_CONTROL_HEAD");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("pending", 1);
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.INTEGRATION_STALE, AgentNextAction.RETRY, result);
            }

            if (!advResult.advanced()) {
                appendAttemptFailure(store, identity, attemptId, ordered, expectedControlHead,
                        prepResult.integrationCommitSha(), advResult.failureReason());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("pending", 1);
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.RETRY, result);
            }

            // Success! Fully integrated
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("task", "integrated");
            if (validation != null) {
                result.put("validation", validation.toMap());
            }
            return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
        }
    }

    private record CandidateResolution(boolean ready, List<TaskSnapshotRecord> dependencies,
            List<String> structuralFailures) {
        private CandidateResolution {
            dependencies = List.copyOf(dependencies);
            structuralFailures = List.copyOf(structuralFailures);
        }
    }

    private static CandidateResolution resolveCandidate(TaskSnapshotRecord candidate,
            List<TaskSnapshotRecord> allSnapshots, CapabilityRequestProjection capProj,
            org.synesis.coordination.domain.task.TaskCompletionProjection completion) {
        List<TaskSnapshotRecord> dependencies = new ArrayList<>();
        List<String> structuralFailures = new ArrayList<>();
        for (String dependencyHandle : candidate.capabilityDependencies()) {
            CapabilityRequestRecord capability = capProj.findByHandle(dependencyHandle).orElse(null);
            if (capability == null) {
                structuralFailures.add("MISSING_CAPABILITY_DEPENDENCY:" + dependencyHandle);
                continue;
            }
            if (capability.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REJECTED
                    || capability.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.CANCELLED
                    || capability.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.EXPIRED
                    || capability.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.SUPERSEDED) {
                structuralFailures.add("STALE_CAPABILITY_DEPENDENCY:" + dependencyHandle);
                continue;
            }
            var implementation = capProj.findLatestImplementation(dependencyHandle).orElse(null);
            if (implementation == null) {
                continue;
            }
            if (!capability.authorityLineageId().equals(implementation.authorityLineageId())) {
                structuralFailures.add("CAPABILITY_LINEAGE_MISMATCH:" + dependencyHandle);
                continue;
            }
            TaskSnapshotRecord ownerSnapshot = allSnapshots.stream()
                    .filter(snapshot -> snapshot.provenance().authorityLineageId()
                            .equals(capability.authorityLineageId()))
                    .filter(snapshot -> snapshot.commitSha().equals(implementation.commitSha()))
                    .min(java.util.Comparator.comparingLong(TaskSnapshotRecord::createdAtMillis)
                            .thenComparing(TaskSnapshotRecord::snapshotId))
                    .orElse(null);
            if (ownerSnapshot == null) {
                // The contract is known but its immutable implementation has
                // not been published as a task snapshot yet.  This candidate
                // remains pending and will be reconsidered after publication.
                continue;
            }
            TaskCompletionState ownerState = completion.taskState(ownerSnapshot.taskId());
            if (ownerState == TaskCompletionState.INTEGRATED) {
                continue;
            }
            if (ownerState == TaskCompletionState.INTEGRATION_BLOCKED
                    || ownerState == TaskCompletionState.REPAIR_REQUIRED
                    || ownerState == TaskCompletionState.INTEGRATION_FAILED
                    || ownerState == TaskCompletionState.CANCELLED
                    || ownerState == TaskCompletionState.ABANDONED) {
                // A failed prerequisite is recoverable through its own lane;
                // do not permanently invalidate the dependent candidate.
                continue;
            }
            dependencies.add(ownerSnapshot);
        }
        return new CandidateResolution(structuralFailures.isEmpty() && dependencies.isEmpty(),
                dependencies, structuralFailures);
    }

    private static boolean appendBlockedCandidate(PredictionEventStore store, NodeIdentity identity,
            TaskSnapshotRecord candidate, String expectedControlHead, List<String> failures) {
        try {
            String blockedId = "blocked_" + candidate.snapshotId();
            IntegrationAttemptPayload blocked = new IntegrationAttemptPayload(
                    blockedId, store.projectId(), List.of(candidate.snapshotId()), expectedControlHead, "",
                    "blocked", String.join(";", failures));
            store.append(UUID.nameUUIDFromBytes(blockedId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    PredictionEventType.INTEGRATION_BLOCKED, identity.nodeId(), blocked.encode(), identity);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static boolean isMergeConflict(String failureReason) {
        if (failureReason == null) {
            return false;
        }
        String normalized = failureReason.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("merge conflict") || normalized.contains("cherry-pick conflict")
                || normalized.contains("unresolved cherry-pick");
    }

    private static List<TaskSnapshotRecord> sortSnapshotsTopologically(
            List<TaskSnapshotRecord> snapshots,
            CapabilityRequestProjection capProj
    ) {
        Map<UUID, List<TaskSnapshotRecord>> byLineage = new LinkedHashMap<>();
        for (TaskSnapshotRecord s : snapshots) {
            byLineage.computeIfAbsent(s.provenance().authorityLineageId(), ignored -> new ArrayList<>()).add(s);
        }

        // Build adjacency: dependent -> dependencies
        Map<TaskSnapshotRecord, List<TaskSnapshotRecord>> dependencies = new LinkedHashMap<>();
        for (TaskSnapshotRecord s : snapshots) {
            List<TaskSnapshotRecord> deps = new ArrayList<>();
            for (String capHandle : s.capabilityDependencies()) {
                var capOpt = capProj.findByHandle(capHandle);
                if (capOpt.isPresent()) {
                    CapabilityRequestRecord cap = capOpt.get();
                    var ownerCandidates = byLineage.getOrDefault(cap.authorityLineageId(), List.of());
                    var implementation = capProj.findLatestImplementation(cap.handle().value());
                    for (TaskSnapshotRecord ownerSnap : ownerCandidates) {
                        boolean exactPublishedImplementation = implementation.isEmpty()
                                || implementation.get().commitSha().equals(ownerSnap.commitSha());
                        if (exactPublishedImplementation && !ownerSnap.equals(s) && !deps.contains(ownerSnap)) {
                            deps.add(ownerSnap);
                            break;
                        }
                    }
                }
            }
            dependencies.put(s, deps);
        }

        // Kahn's algorithm or DFS topological sort
        List<TaskSnapshotRecord> sorted = new ArrayList<>();
        Set<TaskSnapshotRecord> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<TaskSnapshotRecord> inStack = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (TaskSnapshotRecord node : snapshots) {
            if (!visited.contains(node)) {
                visitDfs(node, dependencies, visited, inStack, sorted);
            }
        }

        return List.copyOf(sorted);
    }

    private static void appendAttemptFailure(PredictionEventStore store, NodeIdentity identity, String attemptId,
            List<TaskSnapshotRecord> snapshots, String base, String commit, String reason) {
        try {
            IntegrationAttemptPayload payload = new IntegrationAttemptPayload(attemptId, store.projectId(),
                    snapshots.stream().map(TaskSnapshotRecord::snapshotId).toList(), base, commit, "failed", reason);
            store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED,
                    identity.nodeId(), payload.encode(), identity);
        } catch (Exception ignored) {
            // The primary response remains actionable; the next reconciliation
            // can inspect the started attempt and retry safely.
        }
    }

    private static void visitDfs(
            TaskSnapshotRecord node,
            Map<TaskSnapshotRecord, List<TaskSnapshotRecord>> dependencies,
            Set<TaskSnapshotRecord> visited,
            Set<TaskSnapshotRecord> inStack,
            List<TaskSnapshotRecord> sorted
    ) {
        if (inStack.contains(node)) {
            throw new IllegalStateException("Dependency cycle detected involving snapshot " + node.snapshotId());
        }
        if (!visited.contains(node)) {
            inStack.add(node);
            List<TaskSnapshotRecord> deps = dependencies.getOrDefault(node, List.of());
            for (TaskSnapshotRecord dep : deps) {
                visitDfs(dep, dependencies, visited, inStack, sorted);
            }
            inStack.remove(node);
            visited.add(node);
            sorted.add(node);
        }
    }

    private static List<String> validateSnapshotMetadata(Path controlRoot, List<TaskSnapshotRecord> snapshots,
            String controlHead, PredictionEventStore store) {
        Set<String> changed = new java.util.HashSet<>();
        List<String> failures = new ArrayList<>();
        if (store.collaborationProjection().requests().stream()
                .anyMatch(request -> request.status() == org.synesis.coordination.domain.collaboration.CoordinationRequest.Status.PENDING)) {
            failures.add("UNRESOLVED_COORDINATION_REQUEST");
        }
        for (TaskSnapshotRecord snapshot : snapshots) {
            if (snapshot.provenance() == null) {
                failures.add("MISSING_PROVENANCE:" + snapshot.snapshotId());
                continue;
            }
            if (!isAncestor(controlRoot, snapshot.baseCommit(), controlHead)) {
                failures.add("STALE_BASE:" + snapshot.snapshotId());
            }
            var laneIntent = store.collaborationProjection().intent(snapshot.provenance().laneId());
            if (laneIntent.isEmpty()
                    || !laneIntent.get().authorityLineageId().equals(snapshot.provenance().authorityLineageId())
                    || laneIntent.get().version() != snapshot.provenance().claimEpoch()) {
                failures.add("STALE_CLAIM_EPOCH:" + snapshot.snapshotId());
            }
            for (String path : snapshot.changedPaths()) {
                String normalized = path.replace('\\', '/');
                if (!changed.add(normalized)) {
                    failures.add("OVERLAPPING_SNAPSHOT:" + normalized);
                }
                if (!snapshot.provenance().claimSelectors().isEmpty()) {
                    boolean covered = snapshot.provenance().claimSelectors().stream().anyMatch(raw -> {
                        int split = raw.indexOf(':');
                        if (split < 1) return false;
                        try {
                            ResourceSelector selector = new ResourceSelector(
                                    ResourceSelector.Kind.valueOf(raw.substring(0, split)), raw.substring(split + 1));
                            return selector.overlaps(ResourceSelector.pathExact(normalized));
                        } catch (RuntimeException invalid) { return false; }
                    });
                    if (!covered) failures.add("UNCOVERED_PATH:" + normalized);
                }
            }
            if (snapshot.provenance().snapshotRef().startsWith("refs/synesis/snapshots/")) {
                var prepared = store.taskCompletionProjection().findPrepared(snapshot.taskId());
                if (prepared.isEmpty()) {
                    failures.add("MISSING_PREPARED_OBJECT:" + snapshot.snapshotId());
                } else {
                    try {
                        String preparedCommit = runGitOutput(controlRoot, "rev-parse", prepared.get().preparedRef());
                        String preparedTree = runGitOutput(controlRoot, "rev-parse", preparedCommit + "^{tree}");
                        if (!prepared.get().treeHash().equals(preparedTree)
                                || !preparedCommit.equals(snapshot.commitSha())) {
                            failures.add("PREPARED_TREE_MISMATCH:" + snapshot.snapshotId());
                        }
                    } catch (Exception missingPrepared) {
                        failures.add("MISSING_PREPARED_OBJECT:" + snapshot.snapshotId());
                    }
                }
                try {
                    String referenced = runGitOutput(controlRoot, "rev-parse", snapshot.provenance().snapshotRef());
                    if (!referenced.equals(snapshot.commitSha())) failures.add("INVALID_PROVENANCE:" + snapshot.snapshotId());
                } catch (Exception missing) {
                    failures.add("MISSING_SNAPSHOT_REF:" + snapshot.snapshotId());
                }
                for (String reference : snapshot.provenance().contractRevisions()) {
                    int split = reference.lastIndexOf(':');
                    if (split > 0 && reference.substring(0, split).matches("[0-9a-fA-F-]{36}")
                            && reference.substring(split + 1).matches("[0-9]+")) {
                        try {
                            UUID contractId = UUID.fromString(reference.substring(0, split));
                            long revision = Long.parseLong(reference.substring(split + 1));
                            var contract = store.contractProjection().contract(contractId);
                            if (contract == null || contract.revision() != revision
                                    || contract.status() != org.synesis.coordination.domain.contract.ContractRecord.Status.ACTIVE) {
                                failures.add("STALE_CONTRACT:" + snapshot.snapshotId());
                            }
                        } catch (RuntimeException invalid) { failures.add("INVALID_CONTRACT_PROVENANCE:" + snapshot.snapshotId()); }
                    }
                }
            } else {
                failures.add("INVALID_SNAPSHOT_REF:" + snapshot.snapshotId());
            }
        }
        return List.copyOf(failures);
    }

    private static boolean isAncestor(Path workdir, String base, String head) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "merge-base", "--is-ancestor", base, head);
            pb.directory(workdir.toFile());
            return pb.start().waitFor() == 0;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String runGitOutput(Path workdir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String arg : args) {
            cmd.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes()).trim();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("git " + args[0] + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git " + args[0] + " interrupted", e);
        }
        return output;
    }
}
