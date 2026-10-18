package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptRecord;
import org.synesis.coordination.domain.integration.ValidationRefusalPayload;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskCompletionProjection;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.coordination.persistence.ProjectIntegrationLock;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentFailures;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeRemote;
import org.synesis.workspace.application.control.ControlBranchAdvancementService;
import org.synesis.workspace.application.project.execution.ExecutionRequest;
import org.synesis.workspace.application.project.execution.ExecutionResult;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.execution.ProjectCommandSpec;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.SnapshotRefs;
import org.synesis.workspace.infrastructure.json.ProviderJson;
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
 * <p>One pass at a time runs per project ({@link ProjectIntegrationLock}), and only on the leader's
 * log. A pass holds the append lock only for its short first part (choosing the work and recording
 * the attempt as started) and its short last part (recording how it ended and advancing the
 * branch). The worktree, the cherry-picks and the test gate run between them with the lock free,
 * so no other writer waits for a merge. The last part records an outcome only if what the attempt
 * started from still holds, replayed after the lock is taken; otherwise the attempt is ended as
 * pending and a later pass chooses again (ADR-0098, invariant 8).</p>
 *
 * @since 1.0
 */
public final class IntegrationOrchestrationService {

    /** Why an attempt ended when the process that ran it died before recording how it ended. */
    private static final String INTERRUPTED = "INTERRUPTED";

    /** Why a finished slow part was not recorded: what the attempt started from has changed. */
    private static final String STALE_BASIS = "STALE_ATTEMPT_BASIS:";

    /** How many times the append lock is waited for (two seconds each) before an attempt is left for the next pass. */
    private static final int RECORDING_LOCK_TRIES = 5;

    private static final System.Logger LOG = System.getLogger(IntegrationOrchestrationService.class.getName());

    private static final AgentResponse LOCK_UNAVAILABLE = new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.RETRY, Map.of("failure", "INTEGRATION_LOCK_UNAVAILABLE"));

    /** A pass with nothing to integrate: no snapshot is waiting, or this computer is not the project's leader. */
    private static final AgentResponse NOTHING_TO_INTEGRATE = new AgentResponse(AgentStatus.READY, null, AgentNextAction.RETRY, Map.of());

    private final IntegrationWorkspaceService workspaceService;
    private final ControlBranchAdvancementService advancementService;
    private final ProjectApplicationService projectService;
    private final ProjectProcessExecutor processExecutor;
    private final ProviderSessionBindingService bindingService;
    private final Runnable beforeRecording;
    private final RemergeService remerges;

    /** What a pass chose to integrate: the attempt it recorded as started, the head it started from, and the snapshots in apply order. */
    private record Attempt(String id, String expectedControlHead, List<TaskSnapshotRecord> ordered) {
    }

    /**
     * Creates an integration orchestration service.
     */
    public IntegrationOrchestrationService() {
        this(() -> {
        });
    }

    /**
     * Creates a service that runs a hook after the slow part of a pass and before its outcome is
     * recorded. The pass holds no append lock there. A test uses it to change the log, or to die,
     * at the one point where a pass is outside it.
     */
    IntegrationOrchestrationService(Runnable beforeRecording) {
        this.beforeRecording = Objects.requireNonNull(beforeRecording, "beforeRecording");
        this.workspaceService = new IntegrationWorkspaceService();
        this.advancementService = new ControlBranchAdvancementService();
        this.projectService = new ProjectApplicationService();
        this.processExecutor = new ProjectProcessExecutor();
        this.bindingService = new ProviderSessionBindingService();
        this.remerges = new RemergeService(projectService, processExecutor);
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

    /**
     * Records how an attempt ended. {@code pending} returns its snapshots to the state they had,
     * so they are tried again; {@code failed} ends them.
     */
    private static void appendAttemptFailure(PredictionEventStore store, NodeIdentity identity, String attemptId, List<TaskSnapshotRecord> snapshots, String base, String commit, String status, String reason) {
        try {
            IntegrationAttemptPayload payload = new IntegrationAttemptPayload(attemptId, store.projectId(), snapshots.stream().map(TaskSnapshotRecord::snapshotId).toList(), base, commit, status, reason);
            store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED, identity.nodeId(), payload.encode(), identity);
        } catch (Exception ignored) {
            // The primary response remains actionable; the next reconciliation
            // can inspect the started attempt and retry safely.
        }
    }

    /**
     * Tells whether the control branch cannot hold the commit of an attempt found started.
     *
     * <p>The branch is advanced only to a commit the log already names, so an attempt with no
     * commit on record never advanced it. A recorded commit that Git says is not an ancestor of
     * the head was not kept by the branch either, wherever the head has moved since: the
     * operator's own levers move it, a validation policy that is committed or a fix committed by
     * hand. When Git cannot answer, nothing is proven.</p>
     *
     * @return {@code true} only when it is proven that nothing of the attempt reached the branch
     */
    private static boolean branchCannotHold(Path controlRoot, IntegrationAttemptRecord attempt) {
        if (attempt.integrationCommitSha().isBlank()) {
            return true;
        }
        try {
            // Exit 1 is Git's "not an ancestor". Any other exit is a commit or a head it could not read.
            return GitProcessRunner.runResult(controlRoot, "merge-base", "--is-ancestor", attempt.integrationCommitSha(), "HEAD").exitCode() == 1;
        } catch (Exception unanswered) {
            return false;
        }
    }

    /**
     * Ends an attempt that a dead process left started, returning its snapshots to the state they
     * had. The whole attempt runs under the project lock, which the caller holds, so no process is
     * running it; and the caller has proven that nothing of it reached the branch. Left started,
     * it was waited on for ever, and an attempt that runs a test suite is not a rare place for a
     * process to die.
     *
     * @return {@code true} when the log could not take the event, so the attempt still stands
     */
    private static boolean interruptedAttemptNotRecorded(PredictionEventStore store, NodeIdentity identity, IntegrationAttemptRecord attempt) {
        try {
            IntegrationAttemptPayload interrupted = new IntegrationAttemptPayload(attempt.attemptId(), store.projectId(), attempt.taskSnapshotIds(), attempt.expectedControlHead(), attempt.integrationCommitSha(), "pending", INTERRUPTED);
            store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED, identity.nodeId(), interrupted.encode(), identity);
            return false;
        } catch (Exception failure) {
            return true;
        }
    }

    /**
     * Names the event that refuses one snapshot for one attempt after what it records, as the
     * event of a review decision is named after its grant. The name keeps nothing from being
     * recorded twice: the log does not compare event identities. What keeps a snapshot from a
     * second refusal is the projection, which takes a refusal only for a snapshot that could
     * still integrate, and a refused one cannot.
     */
    private static UUID refusalEventId(String attemptId, String snapshotId) {
        return UUID.nameUUIDFromBytes(("integration-validation-refused:" + attemptId + ":" + snapshotId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Decides what a failed validation command means for the snapshots of the attempt it ran on.
     * The attempt has already ended and its worktree is gone.
     *
     * <p>A non-zero exit is a verdict on the combined tree. The snapshot added last is refused
     * for it and its lane publishes again, up to
     * {@link TaskCompletionProjection#VALIDATION_REPAIRS} times; the next pass builds its attempt
     * without that snapshot. A timeout, or a command that could not be started, is no verdict on
     * anybody's work: nothing is refused and the operator is asked. So it is while any snapshot of
     * the attempt belongs to a lane that has used its repairs. That lane's snapshot stays in
     * every attempt, and refusing the one added last would make each lane that publishes after
     * it answer for a failure it may not have caused.</p>
     *
     * <p>A failure is never taken for a pass. The same attempt is made again by a later pass only
     * when the log could not take the refusal or the operator request: that pass finds the
     * snapshots eligible and no request open.</p>
     *
     * @param ordered the snapshots of the attempt, in the order they were applied
     * @param command the validation command that was run, or {@code null} when the project's
     *                metadata could not be read, which is a command not started and no verdict
     */
    private static AgentResponse validationFailed(Path controlRoot, PredictionEventStore store, NodeIdentity identity, String attemptId, List<TaskSnapshotRecord> ordered, String candidateCommit, ProjectCommandSpec command,
            ExecutionResult validation) {
        TaskSnapshotRecord last = ordered.getLast();
        String output = ValidationRefusalPayload.tail(validation.stdout(), validation.stderr());
        boolean verdict = validation.outcome() == ProjectProcessExecutor.Outcome.NON_ZERO_EXIT;
        Optional<TaskSnapshotRecord> outOfRepairs = ordered.stream()
                .filter(snapshot -> store.taskCompletionProjection().validationRepairsLeft(snapshot.taskId(), snapshot.provenance().authorityLineageId()) == 0).findFirst();
        if (verdict && outOfRepairs.isEmpty()) {
            try {
                WorkIntent lane = Objects.requireNonNull(IntegrationCandidatePolicy.currentIntentForSnapshot(store, last), "lane of the refused snapshot");
                ValidationRefusalPayload refusal = new ValidationRefusalPayload(attemptId,
                        last.snapshotId(),
                        last.taskId(),
                        last.provenance().workGroupId(),
                        last.provenance().authorityLineageId(),
                        last.provenance().claimEpoch(),
                        lane.intentId(),
                        lane.version(),
                        lane.participant(),
                        candidateCommit,
                        ValidationRefusalPayload.head(ProviderJson.write(command.argv())),
                        ValidationRefusalPayload.head(command.workingDirectory()),
                        validation.outcome().value(),
                        validation.exitCode(),
                        output);
                store.append(refusalEventId(attemptId, last.snapshotId()), PredictionEventType.INTEGRATION_VALIDATION_REFUSED, identity.nodeId(), refusal.encode(), identity);
            } catch (Exception failure) {
                return AgentFailures.internal(controlRoot, "integration.refuse_snapshot", failure, null);
            }
            ValidationFailureGuard.close(controlRoot, store, identity, "The project's validation command gave a verdict, and the snapshot added last was refused for it.");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", "integration");
            result.put("validation", validation.toMap());
            result.put("refusedSnapshotId", last.snapshotId());
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_FAILED, AgentNextAction.RETRY, result);
        }
        String candidate = "snapshot " + last.snapshotId() + " of attempt " + attemptId;
        String failure = verdict
                ? "The project's validation command still fails on the work waiting to be integrated (attempt " + attemptId + "), and the lane of snapshot " + outOfRepairs.get().snapshotId() + " in it has used its "
                        + TaskCompletionProjection.VALIDATION_REPAIRS + " repairs. "
                : "The project's validation command gave no verdict on the work waiting to be integrated (" + candidate + "): it ended as " + validation.outcome().value() + ". ";
        return ValidationFailureGuard.blocked(ValidationFailureGuard.raise(controlRoot, store, identity, failure, output, candidate + " outcome " + validation.outcome().value()));
    }

    private static List<String> validateSnapshotMetadata(Path controlRoot, List<TaskSnapshotRecord> snapshots, String controlHead, PredictionEventStore store) {
        Set<String> changed = new java.util.HashSet<>();
        List<String> failures = new ArrayList<>();
        boolean exchangesCode = exchangesCode(store);
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
            if (exchangesCode) {
                failures.addAll(undeclaredPaths(controlRoot, snapshot));
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
            } else {
                failures.add("INVALID_SNAPSHOT_REF:" + snapshot.snapshotId());
            }
        }
        return List.copyOf(failures);
    }

    /** Whether commits reach this log's computer from others, so that the paths a snapshot declares were not made here. */
    private static boolean exchangesCode(PredictionEventStore store) {
        try {
            return CodeExchange.of(store.rootDirectory(), store.projectId()).isPresent();
        } catch (CodeRemote.Refused refused) {
            return true;
        }
    }

    /**
     * The paths a snapshot's commit changes that its record does not declare. The record is a member's claim;
     * the claim fence and the overlap check run on it, while the cherry-pick applies what the commit really
     * changes, so the leader reads that from the commit it fetched and refuses a record that hides a path.
     */
    private static List<String> undeclaredPaths(Path controlRoot, TaskSnapshotRecord snapshot) {
        Set<String> declared = new java.util.HashSet<>();
        snapshot.changedPaths().forEach(path -> declared.add(path.replace('\\', '/')));
        List<String> undeclared = new ArrayList<>();
        try {
            String changes = GitProcessRunner.run(controlRoot, "diff-tree", "-r", "--no-commit-id", "--name-only", "--no-renames", "-z", snapshot.commitSha() + "^", snapshot.commitSha());
            for (String path : changes.split("\0")) {
                if (!path.isEmpty() && !declared.contains(path.replace('\\', '/'))) {
                    undeclared.add("UNDECLARED_PATH:" + path);
                }
            }
        } catch (IOException unreadable) {
            undeclared.add("UNVERIFIABLE_PATHS:" + snapshot.snapshotId());
        }
        return undeclared;
    }

    /**
     * Whether a snapshot belongs to a lane this computer introduced: the lanes this computer's pass merges (ADR-0098
     * step 5). The integration entries it writes then act for its own participants only, as attribution requires.
     */
    static boolean ownLane(PredictionEventStore store, NodeIdentity identity, TaskSnapshotRecord snapshot) {
        // This computer's own lanes are introduced in its own log, so an unknown introducer is another computer's.
        return store.collaborationProjection().introducingNode(snapshot.provenance().participantIdentity())
                .map(identity.nodeId()::equals).orElse(false);
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
        return orchestrateIntegration(controlRoot, store, identity, false);
    }

    /**
     * Attempts integration of all ready task snapshots for a control project, on behalf of an
     * agent's call or of the operator.
     *
     * <p>While the project's validation command fails with no lane left to answer for it, an
     * agent's pass starts no attempt: it would only run the same command again on every poll. The
     * operator's recheck is the one pass that does, after the operator has changed something.</p>
     *
     * @param controlRoot   control project root path
     * @param store         prediction event store
     * @param identity      node signing identity
     * @param operatorAsked whether the operator asked for this pass
     * @return agent response indicating integration outcome
     */
    public AgentResponse orchestrateIntegration(Path controlRoot, PredictionEventStore store, NodeIdentity identity, boolean operatorAsked) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(identity, "identity");

        // Every computer merges its own lanes onto its own copy of main (ADR-0098 step 5); another computer's lanes are
        // that computer's to merge.
        AgentResponse response = integrate(controlRoot, store, identity, operatorAsked);
        publishControlBranch(controlRoot, store);
        return response;
    }

    /**
     * Gives a mirror the leader's control branch once a pass has run, outside every lock. It asks the mirror
     * nothing when it is known to hold the head, so a failed push is simply tried again by the next pass, and a
     * mirror that is down never delays the log (ADR-0098). The members learn the head from the log.
     */
    private static void publishControlBranch(Path controlRoot, PredictionEventStore store) {
        try {
            Optional<CodeExchange> exchange = CodeExchange.of(store.rootDirectory(), store.projectId());
            if (exchange.isPresent()) {
                exchange.get().publishControlBranch(controlRoot);
            }
        } catch (CodeRemote.Refused refused) {
            if (CodeRemote.UNAVAILABLE.equals(refused.code())) {
                LOG.log(System.Logger.Level.WARNING, "The control branch was not pushed; the next pass tries again. {0}", refused.getMessage());
            }
        }
    }

    /**
     * Brings in the commits the log names before a pass reads them. The leader's clone holds the lanes of
     * its own computer only; each member keeps its snapshots and serves them over Link, and a mirror may hold
     * them too. The pass is not started when they cannot be had, because its checks would then record work as
     * missing.
     *
     * @return the pass's response when it must not run, {@code null} when it may
     */
    private static AgentResponse fetchCommitsNamedByLog(Path controlRoot, PredictionEventStore store, NodeIdentity identity) {
        Optional<CodeExchange> exchange;
        try {
            exchange = CodeExchange.of(store.rootDirectory(), store.projectId());
        } catch (CodeRemote.Refused unreadable) {
            return new AgentResponse(AgentStatus.BLOCKED, AgentReason.INTEGRATION_CONFLICT, AgentNextAction.REQUEST_HUMAN_HELP, Map.of("failure", unreadable.code(), "message", unreadable.getMessage()));
        }
        if (exchange.isEmpty()) {
            return null;
        }
        Map<String, String> snapshots = new java.util.LinkedHashMap<>();
        try {
            // Only what the pass may integrate: work still waiting for its review fetches nothing, so a computer that is
            // away holds up only its own reviewed work.
            PredictionEventStore current = new PredictionEventStore(store.rootDirectory(), store.projectId());
            IntegrationCandidatePolicy.reviewAcceptedInOrder(current).stream().filter(snapshot -> ownLane(current, identity, snapshot))
                    .forEach(snapshot -> snapshots.put(snapshot.provenance().snapshotRef(), snapshot.commitSha()));
        } catch (IOException | java.security.GeneralSecurityException unreadable) {
            return null;
        }
        try {
            exchange.get().ensureSnapshots(controlRoot, snapshots);
            return null;
        } catch (CodeRemote.Refused refused) {
            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.RETRY, Map.of("pending", 1, "failure", refused.code(), "message", refused.getMessage()));
        }
    }

    private AgentResponse integrate(Path controlRoot, PredictionEventStore store, NodeIdentity identity, boolean operatorAsked) {
        // Before the lock: moving code can take minutes (ADR-0098 step 3), and bringing a commit that is here already
        // changes nothing, so two passes may both ask.
        AgentResponse fetchFailed = fetchCommitsNamedByLog(controlRoot, store, identity);
        if (fetchFailed != null) {
            return fetchFailed;
        }
        try (ProjectIntegrationLock onePass = ProjectIntegrationLock.tryAcquire(store.rootDirectory())) {
            if (onePass == null) {
                // The pass that holds the lock integrates what is ready; this caller waits like any
                // other for an attempt in progress.
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("pending", 1, "integrationRunning", true));
            }
            // A copy of main that diverged from this one is re-merged first, under the same lock (ADR-0098 step 5).
            remerges.run(controlRoot, store, identity);
            AtomicReference<Attempt> started = new AtomicReference<>();
            AgentResponse notStarted = startAttempt(controlRoot, store, identity, operatorAsked, started);
            if (notStarted != null) {
                return notStarted;
            }
            return runAndRecord(controlRoot, store, identity, started.get());
        } catch (IOException failure) {
            return LOCK_UNAVAILABLE;
        }
    }

    /** Unreadable project metadata counts as a declared command: the operator is asked rather than the attempt repeated. */
    private boolean declaresValidation(Path controlRoot) {
        try {
            return projectService.locate(controlRoot).validation() != null;
        } catch (Exception unreadable) {
            return true;
        }
    }

    /**
     * The short first part of a pass, under the append lock: settles what an attempt found started
     * means, chooses what to integrate, and records {@code INTEGRATION_ATTEMPT_STARTED}.
     *
     * @param started receives the attempt when one was started
     * @return the pass's response when no attempt was started, {@code null} when one was
     */
    private AgentResponse startAttempt(Path controlRoot, PredictionEventStore store, NodeIdentity identity, boolean operatorAsked, AtomicReference<Attempt> started) {
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!lock.isHeld()) {
                return LOCK_UNAVAILABLE;
            }
            // A peer may integrate while this caller waits for the project lock.
            // Eligibility and append validation must use the durable state after acquisition.
            PredictionEventStore current;
            try {
                current = new PredictionEventStore(store.rootDirectory(), store.projectId());
            } catch (IOException | java.security.GeneralSecurityException failure) {
                return AgentFailures.internal(controlRoot, "integration.open_state", failure, Map.of("failure", "INTEGRATION_STATE_UNAVAILABLE"));
            }
            return startAttemptLocked(lock, controlRoot, current, identity, operatorAsked, started);
        } catch (IOException failure) {
            return LOCK_UNAVAILABLE;
        }
    }

    private AgentResponse startAttemptLocked(ProjectAppendLock appendLock, Path controlRoot, PredictionEventStore store, NodeIdentity identity, boolean operatorAsked, AtomicReference<Attempt> started) {
        // Looked at on every pass, not only when the branch is advanced: the operator is asked
        // when the stray file appears, and no attempt is started that could only be refused.
        Optional<OperatorRequest> operator = ControlCheckoutGuard.reconcile(controlRoot, store, identity);
        if (operator.isPresent()) {
            return ControlCheckoutGuard.blocked(operator.get());
        }
        ConsumerAbsenceGuard.reconcile(controlRoot, store, identity, System.currentTimeMillis());

        // The whole attempt runs under the project's integration lock, which this pass holds:
        // an attempt found started was left by a process that died in it, and no process is
        // running it. It is completed when the branch already points at its commit, ended as
        // interrupted when the branch cannot hold its commit, and waited on otherwise. This
        // is looked at before the eligible snapshots are: the snapshots of
        // a started attempt are not eligible, so a project whose whole work
        // was in the attempt would otherwise never reach it.
        var activeAttempt = store.taskCompletionProjection().activeIntegrationAttempt(identity.nodeId());
        if (activeAttempt.isPresent() && "started".equals(activeAttempt.get().status())) {
            var attempt = activeAttempt.get();
            try {
                String head = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
                if (!attempt.integrationCommitSha().isBlank() && head.equals(attempt.integrationCommitSha())) {
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
            // An attempt whose recorded commit the branch holds is not interrupted. When the
            // commit is the head, the recovery above completes the attempt, on this pass or a
            // later one. When the head has moved past it, the attempt is waited on and nothing
            // ends the wait, as it was before attempts were ended as interrupted.
            if (!branchCannotHold(controlRoot, attempt) || interruptedAttemptNotRecorded(store, identity, attempt)) {
                return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.WAIT, Map.of("attemptId", attempt.attemptId(), "pending", 1));
            }
            // The process that died left the attempt's worktree behind, and the attempt made
            // next takes a new one.
            workspaceService.removeIntegrationWorktree(IntegrationWorkspaceService.resolveIntegrationRoot(controlRoot).resolve(attempt.attemptId()));
            Optional<IntegrationAttemptRecord> before = store.taskCompletionProjection().integrationAttemptBefore(attempt.attemptId()).filter(previous -> INTERRUPTED.equals(previous.failureReason()));
            if (before.isPresent() && declaresValidation(controlRoot)) {
                // A pass that dies every time gives no verdict and never will. A third attempt
                // would die as these did, and so would every one after it, with nobody told.
                // Where no command is declared the attempt is short and is simply made again:
                // the request would name a command that does not exist and close on the next pass.
                return ValidationFailureGuard.blocked(ValidationFailureGuard.raise(controlRoot, store, identity,
                        "The project's validation command gave no verdict on the work waiting to be integrated: the pass that ran the command died twice (attempt " + before.get().attemptId() + ", then attempt "
                                + attempt.attemptId() + "). ",
                        "", "attempt " + attempt.attemptId() + " interrupted after attempt " + before.get().attemptId()));
            }
        }

        List<TaskSnapshotRecord> snapshots = IntegrationCandidatePolicy.currentRevisions(store).stream().filter(snapshot -> ownLane(store, identity, snapshot)).toList();
        Optional<OperatorRequest> validationFailing = ValidationFailureGuard.reconcile(controlRoot, store, identity, !snapshots.isEmpty());
        if (snapshots.isEmpty()) {
            List<TaskSnapshotRecord> pendingReviews = store.taskCompletionProjection().pendingReviewSnapshots().stream().filter(snapshot -> ownLane(store, identity, snapshot)).toList();
            if (!pendingReviews.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("state", TaskCompletionState.REVIEW_PENDING.value());
                result.put("reviewPending", true);
                result.put("snapshotIds", pendingReviews.stream().map(TaskSnapshotRecord::snapshotId).toList());
                return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
            }
            return NOTHING_TO_INTEGRATE;
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
            return AgentFailures.internal(controlRoot, "integration.read_control_head", ex, null);
        }

        // Pick the oldest dependency-ready work group. Structurally
        // invalid candidates are durably blocked and removed from the
        // eligible queue; compatible ready snapshots from that same
        // work group travel through one guarded integration attempt so a
        // project gate can validate their complete change set together.
        List<TaskSnapshotRecord> allSnapshots = store.taskCompletionProjection().allSnapshots();
        List<TaskSnapshotRecord> eligible = IntegrationCandidatePolicy.reviewAcceptedInOrder(store).stream().filter(snapshot -> ownLane(store, identity, snapshot)).toList();
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

        // The pass is held here, where an attempt would be started, and not before the
        // candidates were classified. Held earlier, a snapshot whose lane had ended was never
        // blocked as stale: it stayed eligible, and the request waited for ever on work that
        // could no longer integrate. Ending the lane is one of the operator's levers.
        if (validationFailing.isPresent() && !operatorAsked) {
            return ValidationFailureGuard.blocked(validationFailing.get());
        }

        String attemptId = "att_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            IntegrationAttemptPayload startPayload = new IntegrationAttemptPayload(attemptId, store.projectId(), ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(), expectedControlHead, "", "started", "");
            store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_STARTED, identity.nodeId(), startPayload.encode(), identity);
        } catch (Exception ex) {
            return AgentFailures.internal(controlRoot, "integration.start_attempt", ex, null);
        }
        started.set(new Attempt(attemptId, expectedControlHead, ordered));
        return null;
    }

    /**
     * The slow part of a pass, with the append lock free for every other writer (ADR-0098,
     * invariant 8): the worktree, the cherry-picks and the project's test gate. Then the lock is
     * taken to record how it ended.
     */
    private AgentResponse runAndRecord(Path controlRoot, PredictionEventStore store, NodeIdentity identity, Attempt attempt) {
        var prepResult = workspaceService.prepareIntegrationWorktree(controlRoot, attempt.id(), attempt.expectedControlHead(), attempt.ordered());

        // The project-owned validation argv runs through the same generic primitive used by
        // run_command and finish_lane.
        ExecutionResult validation = null;
        ProjectCommandSpec command = null;
        if (prepResult.success()) {
            try {
                command = projectService.locate(controlRoot).validation();
                if (command != null) {
                    validation = processExecutor.execute(ExecutionRequest.from(command, prepResult.worktreePath(), controlRoot));
                }
            } catch (Exception failure) {
                validation = new ExecutionResult(ProjectProcessExecutor.Outcome.COMMAND_START_FAILED, null, "", "", 0, 0, 0, 0, false, false);
            }
        }
        beforeRecording.run();

        ProjectAppendLock lock;
        try {
            lock = acquireForRecording(store.rootDirectory());
        } catch (IOException unavailable) {
            // The attempt stays started and no commit is on record: the next pass ends it as
            // interrupted and makes a new one.
            workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
            return LOCK_UNAVAILABLE;
        }
        try (ProjectAppendLock held = lock) {
            PredictionEventStore current = new PredictionEventStore(store.rootDirectory(), store.projectId());
            return recordOutcome(held, controlRoot, current, identity, attempt, prepResult, command, validation);
        } catch (IOException | java.security.GeneralSecurityException failure) {
            workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
            return AgentFailures.internal(controlRoot, "integration.open_state", failure, Map.of("failure", "INTEGRATION_STATE_UNAVAILABLE"));
        }
    }

    /**
     * Takes the append lock for the short last part of a pass. The pass holds minutes of work and
     * recording is cheap, so a lock that other writers keep busy for a while is waited for.
     */
    private static ProjectAppendLock acquireForRecording(Path root) throws IOException {
        IOException busy = null;
        for (int attempt = 0; attempt < RECORDING_LOCK_TRIES; attempt++) {
            try {
                return ProjectAppendLock.acquire(root);
            } catch (IOException failure) {
                busy = failure;
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        throw busy;
    }

    /**
     * Tells why the log and the branch are no longer what the attempt started from. The attempt
     * is recorded as done only when this is empty, so an outcome is never recorded for work that
     * has been decided differently since: another head, a lane whose claim ended or moved to a
     * later epoch, a snapshot that a later one replaced, or a request now open.
     *
     * @return the reasons, empty when the attempt may be recorded
     */
    private static List<String> staleBasis(Path controlRoot, PredictionEventStore store, NodeIdentity identity, Attempt attempt) {
        List<String> reasons = new ArrayList<>();
        Optional<IntegrationAttemptRecord> active = store.taskCompletionProjection().activeIntegrationAttempt(identity.nodeId());
        if (active.isEmpty() || !active.get().attemptId().equals(attempt.id()) || !"started".equals(active.get().status())) {
            reasons.add("ATTEMPT_NOT_ACTIVE");
        }
        String head;
        try {
            head = GitProcessRunner.run(controlRoot, "rev-parse", "HEAD");
        } catch (Exception unreadable) {
            reasons.add("CONTROL_HEAD_UNREADABLE");
            return reasons;
        }
        if (!head.equals(attempt.expectedControlHead())) {
            reasons.add("CONTROL_HEAD_MOVED");
        }
        List<TaskSnapshotRecord> all = store.taskCompletionProjection().allSnapshots();
        for (TaskSnapshotRecord snapshot : attempt.ordered()) {
            if (store.taskCompletionProjection().snapshotState(snapshot.snapshotId()).orElse(null) != TaskCompletionState.INTEGRATING) {
                reasons.add("SNAPSHOT_STATE_CHANGED:" + snapshot.snapshotId());
            }
            if (all.stream().anyMatch(later -> later.taskId().equals(snapshot.taskId()) && later.provenance().authorityLineageId().equals(snapshot.provenance().authorityLineageId())
                    && later.provenance().claimEpoch() > snapshot.provenance().claimEpoch())) {
                reasons.add("SNAPSHOT_SUPERSEDED:" + snapshot.snapshotId());
            }
            // One snapshot at a time, as when the attempt was chosen: the group's snapshots were never
            // checked against each other's paths, and a pass that now did would retry for ever.
            reasons.addAll(validateSnapshotMetadata(controlRoot, List.of(snapshot), head, store));
        }
        return reasons;
    }

    /**
     * The last part of a pass, under the append lock and on a log replayed after it was taken:
     * records how the slow part ended, and advances the branch, only if the attempt's basis still
     * holds.
     */
    private AgentResponse recordOutcome(ProjectAppendLock appendLock, Path controlRoot, PredictionEventStore store, NodeIdentity identity, Attempt attempt, IntegrationWorkspaceService.IntegrationWorktreeResult prepResult,
            ProjectCommandSpec command, ExecutionResult validation) {
        String attemptId = attempt.id();
        String expectedControlHead = attempt.expectedControlHead();
        List<TaskSnapshotRecord> ordered = attempt.ordered();

        List<String> stale = staleBasis(controlRoot, store, identity, attempt);
        if (!stale.isEmpty()) {
            // The work was never wrong, only decided on a basis that has moved: the snapshots go
            // back to the state they had and a later pass chooses again.
            appendAttemptFailure(store, identity, attemptId, ordered, expectedControlHead, prepResult.integrationCommitSha(), "pending", STALE_BASIS + String.join(",", stale));
            workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pending", 1);
            result.put("stale", stale);
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.INTEGRATION_STALE, AgentNextAction.RETRY, result);
        }

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

        if (validation != null && !validation.succeeded()) {
            Exception attemptNotEnded = null;
            try {
                IntegrationAttemptPayload failPayload = new IntegrationAttemptPayload(attemptId,
                        store.projectId(),
                        ordered.stream().map(TaskSnapshotRecord::snapshotId).toList(),
                        expectedControlHead,
                        prepResult.integrationCommitSha(),
                        "pending",
                        "VALIDATION_FAILED:" + validation.outcome().value());
                store.append(UUID.randomUUID(), PredictionEventType.INTEGRATION_ATTEMPT_FAILED, identity.nodeId(), failPayload.encode(), identity);
            } catch (Exception failure) {
                attemptNotEnded = failure;
            }

            workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
            if (attemptNotEnded != null) {
                // Nothing can be refused for an attempt the log still shows as started. The
                // failure is reported as one, and the next pass ends the attempt as interrupted.
                return AgentFailures.internal(controlRoot, "integration.record_validation_failure", attemptNotEnded, null);
            }
            return validationFailed(controlRoot, store, identity, attemptId, ordered, prepResult.integrationCommitSha(), command, validation);
        }
        if (validation != null) {
            ValidationFailureGuard.close(controlRoot, store, identity, "The project's validation command passed.");
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
        } catch (Exception failure) {
            // The branch is advanced only to a commit the log names. A pass that finds this
            // attempt started tells by that commit whether the branch holds it; advanced
            // without it, a process that then died left work on the branch that the next pass
            // took for never integrated.
            workspaceService.removeIntegrationWorktree(prepResult.worktreePath());
            return AgentFailures.internal(controlRoot, "integration.record_commit", failure, null);
        }

        // Advance control branch
        var advResult = advancementService.advanceControlBranch(controlRoot, attemptId, expectedControlHead, prepResult.integrationCommitSha(), ordered, store, identity);

        // Cleanup integration worktree after attempt
        workspaceService.removeIntegrationWorktree(prepResult.worktreePath());

        if (advResult.stale()) {
            // The head moved under the attempt. That says nothing against the snapshots,
            // which are tried again on the new head.
            appendAttemptFailure(store, identity, attemptId, ordered, expectedControlHead, prepResult.integrationCommitSha(), "pending", "STALE_CONTROL_HEAD");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pending", 1);
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.INTEGRATION_STALE, AgentNextAction.RETRY, result);
        }

        if (advResult.dirtyControl()) {
            // A foreign change that appeared during the attempt. Recording the attempt as
            // failed ended the snapshots for good: after a person had cleaned the checkout
            // the work was still never integrated.
            appendAttemptFailure(store, identity, attemptId, ordered, expectedControlHead, prepResult.integrationCommitSha(), "pending", advResult.failureReason());
            Optional<OperatorRequest> raised = ControlCheckoutGuard.reconcile(controlRoot, store, identity);
            if (raised.isPresent()) {
                return ControlCheckoutGuard.blocked(raised.get());
            }
            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.RETRY, Map.of("pending", 1));
        }
        if (!advResult.advanced()) {
            appendAttemptFailure(store, identity, attemptId, ordered, expectedControlHead, prepResult.integrationCommitSha(), "failed", advResult.failureReason());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pending", 1);
            return new AgentResponse(AgentStatus.WAITING, AgentReason.INTEGRATION_PENDING, AgentNextAction.RETRY, result);
        }

        try {
            finalizeIntegratedLanes(appendLock, controlRoot, ordered, store, identity);
        } catch (Exception failure) {
            return AgentFailures.internal(controlRoot, "integration.finalize_lanes", failure,
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
