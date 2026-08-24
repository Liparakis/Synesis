package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.SessionAuthorityResolver;
import org.synesis.workspace.application.project.ProjectProcessExecutor;
import org.synesis.workspace.lifecycle.GitProcessRunner;

/**
 * Resolves the immutable snapshot authorized by a consumed REVIEW grant.
 *
 * <p>A review participant may still have legitimate uncommitted work in its
 * own lane when another participant integrates a snapshot.  That lane must
 * remain protected and cannot be rebound merely to make review reads work.
 * This service therefore creates a separate, disposable detached worktree at
 * the exact immutable snapshot commit.  It grants no write ownership and is
 * never used for patches or lane completion.</p>
 *
 * @since 1.0
 */
public final class ReviewSnapshotAccessService {

    /**
     * Result of resolving the caller's current review snapshot.
     *
     * @param access resolved immutable review access, or {@code null}
     * @param error fail-closed error code, or {@code null} when access is absent
     */
    public record AccessResult(Access access, String error) {
        /** Validates that an access result is either available, absent, or denied. */
        public AccessResult {
            if (access != null && error != null) {
                throw new IllegalArgumentException("review access cannot contain access and error");
            }
        }

        /**
         * Returns whether a consumed grant authorizes snapshot access.
         *
         * @return true when the immutable snapshot workspace is available
         */
        public boolean available() {
            return access != null;
        }

        /**
         * Returns whether a review record was present but failed closed.
         *
         * @return true when access was denied due to invalid review state
         */
        public boolean denied() {
            return access == null && error != null;
        }
    }

    /**
     * Authorized immutable review workspace.
     *
     * @param grant consumed single-use REVIEW grant
     * @param snapshot immutable task snapshot authorized by the grant
     * @param worktreePath detached disposable review worktree
     */
    public record Access(LaneGrant grant, TaskSnapshotRecord snapshot, Path worktreePath) {
        /** Validates access values and normalizes the workspace path. */
        public Access {
            Objects.requireNonNull(grant, "grant");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(worktreePath, "worktreePath");
            worktreePath = worktreePath.toAbsolutePath().normalize();
        }
    }

    private final ProjectApplicationService projectService;
    private final ProviderSessionBindingService bindingService;
    private final SessionAuthorityResolver authorityResolver;
    private final ProjectProcessExecutor processExecutor;

    /** Creates an immutable review access service. */
    public ReviewSnapshotAccessService() {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
        this.authorityResolver = new SessionAuthorityResolver(bindingService);
        this.processExecutor = new ProjectProcessExecutor();
    }

    /**
     * Resolves the single pending review snapshot authorized for one exact
     * provider connection.
     *
     * <p>No access is returned for an unconsumed, replayed, already-decided,
     * missing, or unrelated grant.  Multiple simultaneous pending review
     * grants are rejected rather than guessed.</p>
     *
     * @param projectRoot          control project root
     * @param provider             provider identifier
     * @param connectionInstanceId exact MCP connection identity
     * @return available, absent, or fail-closed review access
     */
    public AccessResult resolve(Path projectRoot, String provider, String connectionInstanceId) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        Path root = projectRoot.toAbsolutePath().normalize();
        try {
            if (!Files.exists(root.resolve(".synesis/project.json"))) {
                return new AccessResult(null, null);
            }
            ProjectApplicationService.ProjectLocation location = projectService.locate(root);
            ProviderSessionBindingService.Binding binding;
            try {
                binding = authorityResolver.resolveReview(location, provider, connectionInstanceId);
            } catch (Exception noReviewBinding) {
                return new AccessResult(null, null);
            }
            Path coordination = location.root().resolve(".synesis/coordination");
            if (!Files.exists(coordination.resolve("events"))) {
                return new AccessResult(null, null);
            }
            PredictionEventStore store = new PredictionEventStore(coordination, location.projectId());
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            List<GrantSnapshot> candidates = store.workGroupProjection().grants().stream()
                    .filter(grant -> grant.targetParticipant().equals(participant))
                    .filter(grant -> store.workGroupProjection().grantConsumed(grant.grantId()))
                    .filter(grant -> store.workGroupProjection().reviewValidationForGrant(grant.grantId()).isEmpty())
                    .map(grant -> snapshotFor(store, grant))
                    .flatMap(Optional::stream)
                    .toList();
            if (candidates.isEmpty()) {
                return new AccessResult(null, null);
            }
            if (candidates.size() != 1) {
                return new AccessResult(null, "REVIEW_SNAPSHOT_AMBIGUOUS");
            }
            GrantSnapshot candidate = candidates.getFirst();
            verifySnapshotRef(location.root(), candidate.snapshot());
            Path worktree = ensureReviewWorktree(location.root(), location.projectId(),
                    candidate.grant(), candidate.snapshot());
            return new AccessResult(new Access(candidate.grant(), candidate.snapshot(), worktree), null);
        } catch (Exception failure) {
            String message = failure.getMessage();
            return new AccessResult(null, message == null || message.isBlank()
                    ? "REVIEW_SNAPSHOT_ACCESS_FAILED" : message);
        }
    }

    /**
     * Executes a bounded command in the immutable review workspace when the
     * caller has a pending authorized review.  The normal lane command path
     * is used when no review access exists.
     *
     * @param projectRoot          control project root
     * @param provider             provider identifier
     * @param connectionInstanceId exact MCP connection identity
     * @param argv                 direct executable and arguments
     * @param workingDirectory     relative review workspace directory
     * @param timeoutSeconds       bounded timeout, or null for the default
     * @return review command response, or null when no review is pending
     */
    public AgentResponse runReviewCommand(Path projectRoot, String provider, String connectionInstanceId,
            List<String> argv, String workingDirectory, Integer timeoutSeconds) {
        AccessResult resolved = resolve(projectRoot, provider, connectionInstanceId);
        if (!resolved.available()) {
            return resolved.denied()
                    ? blocked(resolved.error()) : null;
        }
        Access access = resolved.access();
        ProjectProcessExecutor.ExecutionResult execution = processExecutor.execute(
                new ProjectProcessExecutor.ExecutionRequest(argv, access.worktreePath(),
                        workingDirectory, timeoutSeconds, projectRoot));
        Map<String, Object> result = new LinkedHashMap<>(execution.toMap());
        result.put("workspace", "immutable_review_snapshot");
        result.put("reviewGrantId", access.grant().grantId().toString());
        result.put("reviewSnapshotId", access.snapshot().snapshotId());
        result.put("reviewCommitSha", access.snapshot().commitSha());
        return new AgentResponse(commandStatus(execution.outcome()), commandReason(execution.outcome()),
                commandNextAction(execution.outcome()), result);
    }

    /**
     * Removes one exact disposable review workspace after its decision.
     *
     * @param projectRoot control project root
     * @param grantId      exact consumed review grant
     */
    public void remove(Path projectRoot, UUID grantId) {
        if (projectRoot == null || grantId == null) {
            return;
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        Path reviewRoot = resolveReviewRoot(root, readProjectId(root));
        Path worktree = reviewRoot.resolve(grantId.toString()).toAbsolutePath().normalize();
        if (!worktree.startsWith(reviewRoot) || !Files.exists(worktree)) {
            return;
        }
        try {
            runGit(root, "worktree", "remove", "--force", worktree.toString());
        } catch (Exception ignored) {
            // The immutable decision remains authoritative; Doctor can report
            // an abandoned disposable review workspace for later reconciliation.
        }
    }

    private static Optional<GrantSnapshot> snapshotFor(PredictionEventStore store, LaneGrant grant) {
        return store.taskCompletionProjection().allSnapshots().stream()
                .filter(snapshot -> snapshot.provenance().workGroupId().equals(grant.workGroupId()))
                .filter(snapshot -> snapshot.provenance().laneId().equals(grant.targetIntentId()))
                .filter(snapshot -> snapshot.provenance().claimEpoch() == grant.claimEpoch())
                .map(snapshot -> new GrantSnapshot(grant, snapshot))
                .findFirst();
    }

    private static void verifySnapshotRef(Path controlRoot, TaskSnapshotRecord snapshot) throws IOException {
        String expectedRef = "refs/synesis/snapshots/" + snapshot.snapshotId();
        if (!expectedRef.equals(snapshot.provenance().snapshotRef())) {
            throw new IOException("REVIEW_SNAPSHOT_REF_MISMATCH");
        }
        String resolved = runGit(controlRoot, "rev-parse", expectedRef + "^{commit}");
        if (!snapshot.commitSha().equals(resolved.trim())) {
            throw new IOException("REVIEW_SNAPSHOT_COMMIT_MISMATCH");
        }
    }

    private static Path ensureReviewWorktree(Path controlRoot, UUID projectId,
            LaneGrant grant, TaskSnapshotRecord snapshot) throws IOException {
        Path reviewRoot = resolveReviewRoot(controlRoot, projectId.toString());
        if (reviewRoot.startsWith(controlRoot) || controlRoot.startsWith(reviewRoot)) {
            throw new IOException("REVIEW_WORKSPACE_PATH_INVALID");
        }
        Files.createDirectories(reviewRoot);
        Path worktree = reviewRoot.resolve(grant.grantId().toString()).toAbsolutePath().normalize();
        if (!worktree.startsWith(reviewRoot)) {
            throw new IOException("REVIEW_WORKSPACE_PATH_INVALID");
        }
        if (Files.exists(worktree)) {
            if (!Files.isDirectory(worktree)) {
                throw new IOException("REVIEW_WORKSPACE_INVALID");
            }
            String head = runGit(worktree, "rev-parse", "HEAD").trim();
            if (!snapshot.commitSha().equals(head)) {
                throw new IOException("REVIEW_WORKSPACE_COMMIT_MISMATCH");
            }
            String dirty = runGit(worktree, "status", "--porcelain", "--untracked-files=all");
            if (dirty.isBlank()) {
                return worktree;
            }
            runGit(controlRoot, "worktree", "remove", "--force", worktree.toString());
        }
        runGit(controlRoot, "worktree", "add", "--detach", worktree.toString(), snapshot.commitSha());
        String head = runGit(worktree, "rev-parse", "HEAD").trim();
        if (!snapshot.commitSha().equals(head)) {
            throw new IOException("REVIEW_WORKSPACE_COMMIT_MISMATCH");
        }
        return worktree;
    }

    private static Path resolveReviewRoot(Path projectRoot, String projectId) {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis").toString();
        }
        return Path.of(base, "Synesis", "workspaces", projectId, "review")
                .toAbsolutePath().normalize();
    }

    private static String readProjectId(Path projectRoot) {
        try {
            return new ProjectApplicationService().locate(projectRoot).projectId().toString();
        } catch (Exception failure) {
            return "unknown-project";
        }
    }

    private static String runGit(Path workdir, String... args) throws IOException {
        return GitProcessRunner.run(workdir, args);
    }

    private static AgentResponse blocked(String error) {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED,
                AgentNextAction.RETRY, Map.of("error", error));
    }

    private static AgentStatus commandStatus(ProjectProcessExecutor.Outcome outcome) {
        return switch (outcome) {
            case COMPLETED -> AgentStatus.COMPLETED;
            case NON_ZERO_EXIT, COMMAND_WORKING_DIRECTORY_INVALID, COMMAND_EXECUTABLE_NOT_FOUND,
                    COMMAND_PERMISSION_DENIED -> AgentStatus.BLOCKED;
            case COMMAND_TIMED_OUT, COMMAND_CANCELLED, COMMAND_START_FAILED, COMMAND_TERMINATED -> AgentStatus.FAILED;
        };
    }

    private static AgentReason commandReason(ProjectProcessExecutor.Outcome outcome) {
        return switch (outcome) {
            case COMPLETED -> null;
            case NON_ZERO_EXIT -> AgentReason.COMMAND_FAILED;
            case COMMAND_WORKING_DIRECTORY_INVALID -> AgentReason.COMMAND_WORKING_DIRECTORY_INVALID;
            case COMMAND_EXECUTABLE_NOT_FOUND -> AgentReason.COMMAND_EXECUTABLE_NOT_FOUND;
            case COMMAND_PERMISSION_DENIED -> AgentReason.COMMAND_PERMISSION_DENIED;
            case COMMAND_TIMED_OUT -> AgentReason.COMMAND_TIMEOUT;
            case COMMAND_CANCELLED -> AgentReason.COMMAND_CANCELLED;
            case COMMAND_START_FAILED -> AgentReason.COMMAND_START_FAILED;
            case COMMAND_TERMINATED -> AgentReason.COMMAND_TERMINATED;
        };
    }

    private static AgentNextAction commandNextAction(ProjectProcessExecutor.Outcome outcome) {
        return switch (outcome) {
            case COMPLETED, NON_ZERO_EXIT, COMMAND_WORKING_DIRECTORY_INVALID,
                    COMMAND_EXECUTABLE_NOT_FOUND, COMMAND_PERMISSION_DENIED -> null;
            case COMMAND_TIMED_OUT, COMMAND_CANCELLED, COMMAND_START_FAILED, COMMAND_TERMINATED ->
                    AgentNextAction.REQUEST_HUMAN_HELP;
        };
    }

    private record GrantSnapshot(LaneGrant grant, TaskSnapshotRecord snapshot) { }
}
