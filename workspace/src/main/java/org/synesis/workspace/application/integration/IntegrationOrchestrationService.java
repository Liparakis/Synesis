package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.control.ControlBranchAdvancementService;
import org.synesis.workspace.application.project.execution.ExecutionRequest;
import org.synesis.workspace.application.project.execution.ExecutionResult;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.SnapshotRefs;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

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
    private final ProviderSessionBindingService bindingService;

    /**
     * Creates an integration orchestration service.
     */
    public IntegrationOrchestrationService() {
        this.workspaceService = new IntegrationWorkspaceService();
        this.advancementService = new ControlBranchAdvancementService();
        this.projectService = new ProjectApplicationService();
        this.processExecutor = new ProjectProcessExecutor();
        this.bindingService = new ProviderSessionBindingService();
    }

    /**
     * Reports whether a commit holds, for every given path, the same content as the validated
     * commit, including the same absence. Anything Git cannot answer counts as a difference.
     *
     * @param repository      repository holding both commits
     * @param validatedCommit commit the requester validated
     * @param validatedPaths  paths that implementation changed; an empty list proves nothing
     * @param candidateCommit commit of the owner-lane snapshot being considered
     * @return {@code true} only when every path is provably identical in both commits
     */
    static boolean carriesValidatedContent(Path repository, String validatedCommit, List<String> validatedPaths, String candidateCommit) {
        return !validatedPaths.isEmpty() && validatedPathsChanged(repository, validatedCommit, validatedPaths, candidateCommit).isEmpty();
    }

    /**
     * Lists the validated paths whose content in a commit is not provably what was validated.
     *
     * @param repository      repository holding both commits
     * @param validatedCommit commit the requester validated
     * @param validatedPaths  paths that implementation changed
     * @param candidateCommit commit being compared
     * @return the paths that differ or cannot be compared, in the given order
     */
    public static List<String> validatedPathsChanged(Path repository, String validatedCommit, List<String> validatedPaths, String candidateCommit) {
        List<String> changed = new ArrayList<>();
        for (String path : validatedPaths) {
            String validated = contentIdentity(repository, validatedCommit, path);
            if (validated == null || !validated.equals(contentIdentity(repository, candidateCommit, path))) {
                changed.add(path);
            }
        }
        return changed;
    }

    /** Returns the blob identity of a path in a commit, a marker when the path is absent, or {@code null} when unknown. */
    private static String contentIdentity(Path repository, String commit, String path) {
        try {
            if (GitProcessRunner.runResult(repository, "cat-file", "-e", commit + "^{commit}").exitCode() != 0) {
                return null;
            }
            GitProcessRunner.Result blob = GitProcessRunner.runResult(repository, "rev-parse", "--verify", "--quiet", commit + ":" + path);
            return blob.exitCode() == 0 ? blob.output() : "ABSENT";
        } catch (IOException | RuntimeException unknown) {
            return null;
        }
    }

    static String blockedAttemptId(String snapshotId) {
        return "blocked_" + snapshotId;
    }

    static UUID blockedEventId(String blockedAttemptId) {
        return UUID.nameUUIDFromBytes(blockedAttemptId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean blockedCandidateAppendFailed(PredictionEventStore store, NodeIdentity identity, TaskSnapshotRecord candidate, String expectedControlHead, List<String> failures) {
        try {
            String blockedId = blockedAttemptId(candidate.snapshotId());
            IntegrationAttemptPayload blocked = new IntegrationAttemptPayload(blockedId, store.projectId(), List.of(candidate.snapshotId()), expectedControlHead, "", "blocked", String.join(";", failures));
            store.append(blockedEventId(blockedId), PredictionEventType.INTEGRATION_BLOCKED, identity.nodeId(), blocked.encode(), identity);
            return false;
        } catch (Exception failure) {
            return true;
        }
    }

    private static boolean isMergeConflict(String failureReason) {
        if (failureReason == null) {
            return false;
        }
        String normalized = failureReason.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("merge conflict") || normalized.contains("cherry-pick conflict") || normalized.contains("unresolved cherry-pick");
    }

    private static void appendAttemptFailure(PredictionEventStore store, NodeIdentity identity, String attemptId, List<TaskSnapshotRecord> snapshots, String base, String commit, String reason) {
        try {
            IntegrationAttemptPayload payload = new IntegrationAttemptPayload(attemptId, store.projectId(), snapshots.stream().map(TaskSnapshotRecord::snapshotId).toList(), base, commit, "failed", reason);
            store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED, identity.nodeId(), payload.encode(), identity);
        } catch (Exception ignored) {
            // The primary response remains actionable; the next reconciliation
            // can inspect the started attempt and retry safely.
        }
    }

    private static List<String> validateSnapshotMetadata(Path controlRoot, List<TaskSnapshotRecord> snapshots, String controlHead, PredictionEventStore store) {
        Set<String> changed = new java.util.HashSet<>();
        List<String> failures = new ArrayList<>();
        if (store.collaborationProjection().requests().stream()
                .anyMatch(request -> request.status() == org.synesis.coordination.domain.collaboration.CoordinationRequest.Status.PENDING && IntegrationCandidatePolicy.liveCoordinationRequest(store, request))) {
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
            WorkIntent laneIntent = IntegrationCandidatePolicy.currentIntentForSnapshot(store, snapshot);
            boolean exactLane = laneIntent != null && laneIntent.intentId().equals(snapshot.provenance().laneId());
            if (laneIntent == null || !laneIntent.taskId().equals(snapshot.taskId()) || !laneIntent.workGroupId().equals(snapshot.provenance().workGroupId()) || !laneIntent.authorityLineageId()
                    .equals(snapshot.provenance().authorityLineageId()) || (exactLane ? laneIntent.version() != snapshot.provenance().claimEpoch() : laneIntent.version() <= snapshot.provenance().claimEpoch())) {
                failures.add("STALE_CLAIM_EPOCH:" + snapshot.snapshotId());
            }
            for (String path : snapshot.changedPaths()) {
                String normalized = path.replace('\\', '/');
                if (!changed.add(normalized)) {
                    failures.add("OVERLAPPING_SNAPSHOT:" + normalized);
                }
                if (!snapshot.provenance().claimSelectors().isEmpty()) {
                    boolean covered = snapshot.provenance().claimSelectors().stream().anyMatch(raw -> {
                        ResourceSelector selector = ResourceSelector.parse(raw).orElse(null);
                        if (selector == null) {
                            return false;
                        }
                        try {
                            return selector.overlaps(ResourceSelector.pathExact(normalized));
                        } catch (RuntimeException invalid) {
                            return false;
                        }
                    });
                    if (!covered) {
                        failures.add("UNCOVERED_PATH:" + normalized);
                    }
                }
            }
            if (SnapshotRefs.isSnapshotRef(snapshot.provenance().snapshotRef())) {
                var prepared = store.taskCompletionProjection().findPrepared(snapshot.taskId(), snapshot.provenance().laneId(), snapshot.provenance().claimEpoch());
                if (prepared.isEmpty()) {
                    failures.add("MISSING_PREPARED_OBJECT:" + snapshot.snapshotId());
                } else {
                    try {
                        String preparedCommit = GitProcessRunner.run(controlRoot, "rev-parse", prepared.get().preparedRef());
                        String preparedTree = GitProcessRunner.run(controlRoot, "rev-parse", preparedCommit + "^{tree}");
                        if (!prepared.get().treeHash().equals(preparedTree) || !preparedCommit.equals(snapshot.commitSha())) {
                            failures.add("PREPARED_TREE_MISMATCH:" + snapshot.snapshotId());
                        }
                    } catch (Exception missingPrepared) {
                        failures.add("MISSING_PREPARED_OBJECT:" + snapshot.snapshotId());
                    }
                }
                try {
                    String referenced = GitProcessRunner.run(controlRoot, "rev-parse", snapshot.provenance().snapshotRef());
                    if (!referenced.equals(snapshot.commitSha())) {
                        failures.add("INVALID_PROVENANCE:" + snapshot.snapshotId());
                    }
                } catch (Exception missing) {
                    failures.add("MISSING_SNAPSHOT_REF:" + snapshot.snapshotId());
                }
                for (String reference : snapshot.provenance().contractRevisions()) {
                    int split = reference.lastIndexOf(':');
                    if (split > 0 && reference.substring(0, split).matches("[0-9a-fA-F-]{36}") && reference.substring(split + 1).matches("[0-9]+")) {
                        try {
                            UUID contractId = UUID.fromString(reference.substring(0, split));
                            long revision = Long.parseLong(reference.substring(split + 1));
                            var contract = store.contractProjection().contract(contractId);
                            if (contract == null || contract.revision() != revision || contract.status() != org.synesis.coordination.domain.contract.ContractRecord.Status.ACTIVE) {
                                failures.add("STALE_CONTRACT:" + snapshot.snapshotId());
                            }
                        } catch (RuntimeException invalid) {
                            failures.add("INVALID_CONTRACT_PROVENANCE:" + snapshot.snapshotId());
                        }
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
            return GitProcessRunner.isAncestor(workdir, base, head);
        } catch (Exception failure) {
            return false;
        }
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
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.RETRY, Map.of("failure", "INTEGRATION_LOCK_UNAVAILABLE"));
            }
            // A peer may integrate while this caller waits for the project lock.
            // Eligibility and append validation must use the durable state after acquisition.
            PredictionEventStore current;
            try {
                current = new PredictionEventStore(store.rootDirectory(), store.projectId());
            } catch (IOException | java.security.GeneralSecurityException failure) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("failure", "INTEGRATION_STATE_UNAVAILABLE"));
            }
            return orchestrateIntegrationLocked(lock, controlRoot, current, identity);
        } catch (IOException failure) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.RETRY, Map.of("failure", "INTEGRATION_LOCK_UNAVAILABLE"));
        }
    }

    private AgentResponse orchestrateIntegrationLocked(ProjectAppendLock appendLock, Path controlRoot, PredictionEventStore store, NodeIdentity identity) {

        synchronized (INTEGRATION_LOCK) {
            List<TaskSnapshotRecord> snapshots = store.taskCompletionProjection().eligibleSnapshots();
            if (snapshots.isEmpty()) {
                List<TaskSnapshotRecord> pendingReviews = store.taskCompletionProjection().pendingReviewSnapshots();
                if (!pendingReviews.isEmpty()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("state", TaskCompletionState.REVIEW_PENDING.value());
                    result.put("reviewPending", true);
                    result.put("snapshotIds", pendingReviews.stream().map(TaskSnapshotRecord::snapshotId).toList());
                    return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
                }
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
                        String head = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
                        if (head.equals(attempt.integrationCommitSha())) {
                            List<TaskSnapshotRecord> recovered = attempt.taskSnapshotIds().stream().map(id -> store.taskCompletionProjection().findSnapshotById(id).orElse(null)).filter(Objects::nonNull).toList();
                            if (recovered.size() == attempt.taskSnapshotIds().size()) {
                                var recoveredResult = advancementService.recoverAdvancedControlBranch(controlRoot,
                                        attempt.attemptId(),
                                        attempt.expectedControlHead(),
                                        attempt.integrationCommitSha(),
                                        recovered,
                                        store,
                                        identity);
                                if (recoveredResult.advanced()) {
                                    finalizeIntegratedLanes(appendLock, controlRoot, recovered, store, identity);
                                    return new AgentResponse(AgentStatus.COMPLETED, null, null, Map.of("recovered", true, "attemptId", attempt.attemptId()));
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // Keep the attempt pending; the next reconciliation can
                        // retry without changing the control branch.
                    }
                }
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("attemptId", attempt.attemptId(), "pending", 1));
            }

            // Do not integrate while a durable coordination request affecting
            // the active collaboration graph is unresolved.  This check is
            // intentionally before attempt allocation so a blocked negotiation
            // cannot leave a misleading started attempt behind.
            PredictionEventStore coordinationStore;
            try {
                coordinationStore = new PredictionEventStore(store.rootDirectory(), store.projectId());
            } catch (Exception failure) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.RETRY, Map.of("failure", "COORDINATION_STATE_UNAVAILABLE"));
            }
            var pendingRequests = new WorkIntentService(coordinationStore, identity).requests().stream()
                    .filter(request -> request.status() == org.synesis.coordination.domain.collaboration.CoordinationRequest.Status.PENDING).filter(request -> IntegrationCandidatePolicy.liveCoordinationRequest(coordinationStore, request))
                    .toList();
            if (!pendingRequests.isEmpty()) {
                // This pump has no participant binding. Only the caller's inbox
                // can project an owned request as a response obligation.
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("pending", pendingRequests.size()));
            }

            CapabilityRequestProjection capProj = store.capabilityRequestProjection();

            // Resolve the control head once for this pump.  Candidate
            // classification is durable and deterministic against that head;
            // infrastructure failures remain pending and are retried by a
            // later pump.
            String expectedControlHead;
            try {
                expectedControlHead = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
            } catch (Exception ex) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            }

            // Pick the oldest dependency-ready work group. Structurally
            // invalid candidates are durably blocked and removed from the
            // eligible queue; compatible ready snapshots from that same
            // work group travel through one guarded integration attempt so a
            // project gate can validate their complete change set together.
            List<TaskSnapshotRecord> allSnapshots = store.taskCompletionProjection().allSnapshots();
            List<TaskSnapshotRecord> eligible = IntegrationCandidatePolicy.reviewAcceptedInOrder(store);
            if (eligible.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("state", "review_acceptance_required");
                result.put("reviewAcceptanceRequired", true);
                result.put("snapshotIds", snapshots.stream().map(TaskSnapshotRecord::snapshotId).toList());
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, result);
            }
            List<TaskSnapshotRecord> readyCandidates = new ArrayList<>();
            boolean sawPendingDependency = false;
            for (TaskSnapshotRecord candidate : eligible) {
                IntegrationCandidatePolicy.CandidateResolution resolution = IntegrationCandidatePolicy.resolveCandidate(candidate,
                        allSnapshots,
                        capProj,
                        store.taskCompletionProjection(),
                        (implementation, owner) -> carriesValidatedContent(controlRoot, implementation.commitSha(), implementation.changedPaths(), owner.commitSha()));
                if (!resolution.structuralFailures().isEmpty()) {
                    if (blockedCandidateAppendFailed(store, identity, candidate, expectedControlHead, resolution.structuralFailures())) {
                        return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("failure", "INTEGRATION_BLOCK_APPEND_FAILED"));
                    }
                    continue;
                }
                if (!resolution.ready()) {
                    sawPendingDependency = true;
                    continue;
                }
                List<String> metadataFailures = validateSnapshotMetadata(controlRoot, List.of(candidate), expectedControlHead, store);
                if (!metadataFailures.isEmpty()) {
                    if (blockedCandidateAppendFailed(store, identity, candidate, expectedControlHead, metadataFailures)) {
                        return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("failure", "INTEGRATION_BLOCK_APPEND_FAILED"));
                    }
                    continue;
                }
                readyCandidates.add(candidate);
            }
            if (readyCandidates.isEmpty()) {
                if (sawPendingDependency) {
                    return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("pendingDependencies", eligible.size()));
                }
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("state", "integration_blocked"));
            }
            snapshots = IntegrationCandidatePolicy.oldestWorkGroup(readyCandidates);
            List<TaskSnapshotRecord> ordered;
            try {
                ordered = IntegrationCandidatePolicy.sortTopologically(snapshots, capProj);
            } catch (IllegalStateException cycleErr) {
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("error", "Dependency cycle detected"));
            }

            // 4. Allocate integration attempt ID
            String attemptToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String attemptId = "att_" + attemptToken;

            // 5. Append INTEGRATION_ATTEMPT_STARTED
            try {
                IntegrationAttemptPayload startPayload = new IntegrationAttemptPayload(attemptId, store.projectId(), ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(), expectedControlHead, "", "started", "");
                store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED, identity.nodeId(), startPayload.encode(), identity);
            } catch (Exception ex) {
                return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP, null);
            }

            // 6. Prepare integration worktree and apply snapshots
            var prepResult = workspaceService.prepareIntegrationWorktree(controlRoot, attemptId, expectedControlHead, ordered);

            if (!prepResult.success()) {
                try {
                    String status = isMergeConflict(prepResult.failureReason()) ? "conflict" : "pending";
                    IntegrationAttemptPayload failurePayload = new IntegrationAttemptPayload(attemptId,
                            store.projectId(),
                            ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                            expectedControlHead,
                            "",
                            status,
                            prepResult.failureReason());
                    store.append(UUID.randomUUID(),
                            isMergeConflict(prepResult.failureReason()) ? PredictionEventType.INTEGRATION_CONFLICTED : PredictionEventType.INTEGRATION_ATTEMPT_FAILED,
                            identity.nodeId(),
                            failurePayload.encode(),
                            identity);
                    if (isMergeConflict(prepResult.failureReason())) {
                        store.append(UUID.randomUUID(), PredictionEventType.REPAIR_REQUIRED, identity.nodeId(), failurePayload.encode(), identity);
                    } else {
                        workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
                    }
                } catch (Exception ignored) {
                    // Leave the attempt and immutable candidate recoverable for
                    // the next reconciliation pass.
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("state", isMergeConflict(prepResult.failureReason()) ? "repair_required" : "integration_pending");
                result.put("reason", prepResult.failureReason());
                return new AgentResponse(isMergeConflict(prepResult.failureReason()) ? AgentStatus.BLOCKED : AgentStatus.WAITING,
                        isMergeConflict(prepResult.failureReason()) ? AgentReason.INTEGRATION_CONFLICT : AgentReason.INTEGRATION_PENDING,
                        isMergeConflict(prepResult.failureReason()) ? AgentNextAction.REQUEST_HUMAN_HELP : AgentNextAction.RETRY,
                        result);
            }

            // 6. Execute the project-owned validation argv through the same
            // generic primitive used by run_command and finish_lane.
            ExecutionResult validation = null;
            try {
                ProjectLocation location = projectService.locate(controlRoot);
                if (location.validation() != null) {
                    validation = processExecutor.execute(ExecutionRequest.from(location.validation(), prepResult.worktreePath(), controlRoot));
                }
            } catch (Exception failure) {
                validation = new ExecutionResult(ProjectProcessExecutor.Outcome.COMMAND_START_FAILED, null, "", "", 0, 0, 0, 0, false, false);
            }
            if (validation != null && !validation.succeeded()) {
                try {
                    IntegrationAttemptPayload failPayload = new IntegrationAttemptPayload(attemptId,
                            store.projectId(),
                            ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                            expectedControlHead,
                            prepResult.integrationCommitSha(),
                            "pending",
                            "VALIDATION_FAILED:" + validation.outcome().value());
                    store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED, identity.nodeId(), failPayload.encode(), identity);
                } catch (Exception ignored) {
                }

                workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("phase", "integration");
                result.put("validation", validation.toMap());
                return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_FAILED, AgentNextAction.RETRY, result);
            }

            // Record INTEGRATION_COMMIT_CREATED
            try {
                IntegrationAttemptPayload commitPayload = new IntegrationAttemptPayload(attemptId,
                        store.projectId(),
                        ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                        expectedControlHead,
                        prepResult.integrationCommitSha(),
                        "started",
                        "");
                store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_COMMIT_CREATED, identity.nodeId(), commitPayload.encode(), identity);
            } catch (Exception ignored) {
            }

            // 7. Advance control branch
            var advResult = advancementService.advanceControlBranch(controlRoot, attemptId, expectedControlHead, prepResult.integrationCommitSha(), ordered, store, identity);

            // Cleanup integration worktree after attempt
            workspaceService.removeIntegrationWorktree(prepResult.worktreePath());

            if (advResult.stale()) {
                appendAttemptFailure(store, identity, attemptId, ordered, expectedControlHead, prepResult.integrationCommitSha(), "STALE_CONTROL_HEAD");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("pending", 1);
                return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.INTEGRATION_STALE, AgentNextAction.RETRY, result);
            }

            if (!advResult.advanced()) {
                appendAttemptFailure(store, identity, attemptId, ordered, expectedControlHead, prepResult.integrationCommitSha(), advResult.failureReason());
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("pending", 1);
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.RETRY, result);
            }

            try {
                finalizeIntegratedLanes(appendLock, controlRoot, ordered, store, identity);
            } catch (Exception failure) {
                return new AgentResponse(AgentStatus.FAILED,
                        AgentReason.INTERNAL_FAILURE,
                        AgentNextAction.REQUEST_HUMAN_HELP,
                        Map.of("failure", "INTEGRATION_TERMINALIZATION_FAILED", "message", failure.getMessage() == null ? "unknown" : failure.getMessage()));
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

    /**
     * Finalizes only lanes whose exact immutable snapshots have integrated.
     */
    private void finalizeIntegratedLanes(ProjectAppendLock appendLock, Path controlRoot, List<TaskSnapshotRecord> snapshots, PredictionEventStore store, NodeIdentity identity) throws Exception {
        ProjectLocation location = projectService.locate(controlRoot);
        WorkIntentService intentService = new WorkIntentService(store, identity);
        for (TaskSnapshotRecord snapshot : snapshots) {
            WorkIntent currentIntent = IntegrationCandidatePolicy.currentIntentForSnapshot(store, snapshot);
            String provider = currentIntent == null ? null : currentIntent.provider();
            intentService.releaseAfterIntegration(appendLock, snapshot);
            if (provider != null) {
                bindingService.completeBySessionId(location, provider, snapshot.providerSessionId(), appendLock);
            }
        }
    }
}
