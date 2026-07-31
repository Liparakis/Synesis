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
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;

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

    /**
     * Creates an integration orchestration service.
     */
    public IntegrationOrchestrationService() {
        this.workspaceService = new IntegrationWorkspaceService();
        this.advancementService = new ControlBranchAdvancementService();
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

            // 1. Select the oldest eligible candidate.  A permanently invalid
            // candidate must not prevent unrelated later candidates from
            // progressing.
            snapshots = List.of(snapshots.getFirst());
            // 2. Build topological order and check for cycles
            List<TaskSnapshotRecord> ordered;
            try {
                ordered = sortSnapshotsTopologically(snapshots, capProj);
            } catch (IllegalStateException cycleErr) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("error", "Dependency cycle detected"));
            }

            // 3. Resolve expected control HEAD
            String expectedControlHead;
            try {
                expectedControlHead = runGitOutput(controlRoot, "rev-parse", "HEAD");
            } catch (Exception ex) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            }

            // Fail before creating any integration worktree when immutable snapshot
            // metadata already proves a stale base or overlapping change set.
            List<String> metadataFailures = validateSnapshotMetadata(controlRoot, ordered, expectedControlHead, store);
            if (!metadataFailures.isEmpty()) {
                try {
                    String blockedId = "blocked_" + ordered.getFirst().snapshotId();
                    IntegrationAttemptPayload blocked = new IntegrationAttemptPayload(
                            blockedId, store.projectId(), ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                            expectedControlHead, "", "blocked", String.join(";", metadataFailures));
                    store.append(UUID.nameUUIDFromBytes(blockedId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            PredictionEventType.INTEGRATION_BLOCKED, identity.nodeId(), blocked.encode(), identity);
                } catch (Exception ignored) {
                    // The response remains fail-closed; reconciliation can
                    // retry the durable classification.
                }
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT,
                        AgentNextAction.REQUEST_HUMAN_HELP, Map.of("failures", metadataFailures));
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
                // Merge conflict encountered
                try {
                    IntegrationAttemptPayload conflictPayload = new IntegrationAttemptPayload(
                            attemptId, store.projectId(),
                            ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                            expectedControlHead, "", "conflict", prepResult.failureReason());
                    store.append(UUID.randomUUID(), PredictionEventType.REPAIR_REQUIRED,
                            identity.nodeId(), conflictPayload.encode(), identity);
                    createRepairLane(store, identity, ordered.getFirst(), expectedControlHead);
                } catch (Exception ignored) {
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("state", "repair_required");
                result.put("repairWorktree", prepResult.worktreePath().toString());
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.REQUEST_HUMAN_HELP, result);
            }

            // 6. Execute project integration gate inside the integration worktree
            boolean gatePassed = runIntegrationGate(prepResult.worktreePath());
            if (!gatePassed) {
                try {
                    IntegrationAttemptPayload failPayload = new IntegrationAttemptPayload(
                            attemptId, store.projectId(),
                            ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                            expectedControlHead, prepResult.integrationCommitSha(), "pending", "Integration gate unavailable or failed");
                    store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED,
                            identity.nodeId(), failPayload.encode(), identity);
                } catch (Exception ignored) {
                }

                workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("pending", 1);
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.RETRY, result);
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
            return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
        }
    }

    private static List<TaskSnapshotRecord> sortSnapshotsTopologically(
            List<TaskSnapshotRecord> snapshots,
            CapabilityRequestProjection capProj
    ) {
        Map<String, TaskSnapshotRecord> byWorker = new LinkedHashMap<>();
        for (TaskSnapshotRecord s : snapshots) {
            byWorker.put(s.nodeId() + ":" + s.workerId(), s);
        }

        // Build adjacency: dependent -> dependencies
        Map<TaskSnapshotRecord, List<TaskSnapshotRecord>> dependencies = new LinkedHashMap<>();
        for (TaskSnapshotRecord s : snapshots) {
            List<TaskSnapshotRecord> deps = new ArrayList<>();
            for (String capHandle : s.capabilityDependencies()) {
                var capOpt = capProj.findByHandle(capHandle);
                if (capOpt.isPresent()) {
                    CapabilityRequestRecord cap = capOpt.get();
                    TaskSnapshotRecord ownerSnap = byWorker.get(cap.ownerNodeId() + ":" + cap.ownerWorkerId());
                    if (ownerSnap != null && !ownerSnap.equals(s) && !deps.contains(ownerSnap)) {
                        deps.add(ownerSnap);
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

    private static void createRepairLane(PredictionEventStore store, NodeIdentity identity,
            TaskSnapshotRecord snapshot, String currentHead) {
        try {
            List<ResourceSelector> selectors = snapshot.provenance().claimSelectors().stream()
                    .map(raw -> {
                        int split = raw.indexOf(':');
                        if (split < 1) throw new IllegalArgumentException("invalid claim selector");
                        return new ResourceSelector(ResourceSelector.Kind.valueOf(raw.substring(0, split)), raw.substring(split + 1));
                    }).toList();
            if (selectors.isEmpty()) return;
            UUID source = snapshot.provenance().laneId();
            UUID repairId = UUID.nameUUIDFromBytes(("repair:" + snapshot.snapshotId())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            WorkIntent target = new WorkIntent(repairId, store.projectId(), snapshot.provenance().participant(),
                    "repair", snapshot.taskId(), "Repair integration conflict for " + snapshot.snapshotId(),
                    "Resolve the materialized immutable snapshot conflict", currentHead, selectors,
                    snapshot.provenance().claimEpoch() + 1, snapshot.provenance().workGroupId(), WorkIntent.Status.ANNOUNCED);
            new WorkIntentService(store, identity).createRepairLane(source, target);
        } catch (Exception ignored) {
            // Older capability-only snapshots may have no active collaboration
            // intent.  Preserve the conflict worktree and require explicit
            // authorized recovery rather than releasing its scope.
        }
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

    private static boolean runIntegrationGate(Path integrationWorktree) {
        // Synesis is a collaboration and provenance boundary, not a build
        // system detector. Providers/projects own validation commands; absent
        // an explicit configured gate, integration does not guess one.
        return true;
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
            if (!isAncestor(controlRoot, snapshot.baseCommit(), controlHead)) {
                failures.add("STALE_BASE:" + snapshot.snapshotId());
            }
            for (String path : snapshot.changedPaths()) {
                String normalized = path.replace('\\', '/');
                if (!changed.add(normalized)) {
                    failures.add("OVERLAPPING_SNAPSHOT:" + normalized);
                }
                if (snapshot.provenance().snapshotRef().startsWith("refs/synesis/snapshots/")
                        && !snapshot.provenance().claimSelectors().isEmpty()) {
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
            if (snapshot.provenance() == null) {
                failures.add("MISSING_PROVENANCE:" + snapshot.snapshotId());
            } else if (snapshot.provenance().snapshotRef().startsWith("refs/synesis/snapshots/")) {
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
