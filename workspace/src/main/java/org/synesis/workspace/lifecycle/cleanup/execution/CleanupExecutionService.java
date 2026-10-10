package org.synesis.workspace.lifecycle.cleanup.execution;

import org.synesis.workspace.lifecycle.cleanup.plan.CleanupClassification;

import org.synesis.workspace.lifecycle.cleanup.plan.PersistedCleanupPlan;

import org.synesis.workspace.lifecycle.cleanup.plan.PersistedCleanupPlanEntry;

import org.synesis.workspace.lifecycle.cleanup.inventory.CleanupReason;

import org.synesis.workspace.lifecycle.cleanup.plan.CleanupPlanStore;

import org.synesis.workspace.lifecycle.cleanup.plan.RetentionPolicy;

import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;

import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleResourceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;

/**
 * Execution engine for executing reviewed cleanup plans safely with strict precondition
 * re-verification, lock guards, execution journals, and entry isolation.
 *
 * @since 1.0
 */
public final class CleanupExecutionService {

    private final ProjectApplicationService projectService;
    private final CleanupPlanStore planStore;
    private final LifecyclePathVerifier pathVerifier;
    private final LifecycleQuarantineService quarantineService;

    /**
     * Creates an execution service with default dependencies.
     */
    public CleanupExecutionService() {
        this(new ProjectApplicationService(), new CleanupPlanStore(), new LifecyclePathVerifier(), new RetentionPolicy(), new LifecycleQuarantineService());
    }

    /**
     * Creates an execution service with explicit dependencies.
     *
     * @param projectService    project application service
     * @param planStore         cleanup plan store
     * @param pathVerifier      path safety verifier
     * @param retentionPolicy   retention policy configuration
     * @param quarantineService quarantine service
     */
    CleanupExecutionService(ProjectApplicationService projectService,
            CleanupPlanStore planStore,
            LifecyclePathVerifier pathVerifier,
            RetentionPolicy retentionPolicy,
            LifecycleQuarantineService quarantineService) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.pathVerifier = Objects.requireNonNull(pathVerifier, "pathVerifier");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        this.quarantineService = Objects.requireNonNull(quarantineService, "quarantineService");
    }

    private static boolean isGitWorktreeRegistered(Path controlRoot, Path worktreePath) {
        try {
            String output = runGit(controlRoot, "worktree", "list", "--porcelain");
            String normTarget = worktreePath.toAbsolutePath().normalize().toString();
            for (String line : output.lines().toList()) {
                if (line.startsWith("worktree ")) {
                    String wtPath = Path.of(line.substring("worktree ".length()).trim()).toAbsolutePath().normalize().toString();
                    if (wtPath.equals(normTarget)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String runGit(Path workdir, String... args) throws IOException {
        return GitProcessRunner.run(workdir, args).trim();
    }

    /**
     * Executes a reviewed persisted cleanup plan by plan ID.
     *
     * @param controlRoot control project root path
     * @param planId      persisted plan ID
     * @return execution summary result
     * @throws ProjectApplicationService.ProjectApplicationException if project discovery fails
     * @throws IOException                                           if loading or lock acquisition
     *                                                               fails
     */
    @SuppressWarnings("try")
    public CleanupExecutionSummary executePlan(Path controlRoot, String planId) throws ProjectApplicationService.ProjectApplicationException, IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath().normalize();
        projectService.locate(root);
        ProjectCommandDiagnostics.Report commandNamespace = ProjectCommandDiagnostics.inspect(AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
        if (commandNamespace.present() && (!commandNamespace.formatValid() || commandNamespace.newerObjectCount() > 0 || commandNamespace.corruptObjectCount() > 0)) {
            throw new IOException("COMMAND_NAMESPACE_UNSAFE_CLEANUP_BLOCKED");
        }

        // 1. Acquire project execution lock
        try (var _ = CleanupExecutionLock.acquire(root, planId)) {
            // 2. Load persisted plan
            PersistedCleanupPlan plan = planStore.load(root, planId);

            String executionId = "exec-" + UUID.randomUUID().toString().replace("-", "");
            Run run = new Run(executionId, planId, new CleanupExecutionJournal(root, executionId));
            Set<String> previouslyCompleted = CleanupExecutionJournal.loadCompletedResourceIds(root, planId);

            for (PersistedCleanupPlanEntry entry : plan.entries()) {
                long now = System.currentTimeMillis();

                if (previouslyCompleted.contains(entry.resourceId())) {
                    run.alreadyCompleted(entry, now);
                    continue;
                }

                Outcome blocked = verifyPreconditions(root, entry);
                if (blocked != null) {
                    run.record(entry, now, blocked);
                    continue;
                }

                run.journalOnly(entry, now, new Outcome(CleanupEntryExecutionState.PRECONDITION_VERIFIED, "precondition_verified", "Preconditions re-verified safe", 0L));
                run.record(entry, now, execute(root, entry));
            }

            String resultStatus;
            if (run.failedCount > 0) {
                resultStatus = "FAILED_REQUIRES_REVIEW";
            } else if (run.skippedStaleCount > 0) {
                resultStatus = "PARTIAL_SUCCESS";
            } else {
                resultStatus = "SUCCESS";
            }

            return new CleanupExecutionSummary(planId,
                    executionId,
                    plan.entries().size(),
                    run.completedCount,
                    run.alreadyCompletedCount,
                    run.skippedStaleCount,
                    run.skippedUnsafeCount,
                    run.failedCount,
                    run.bytesReclaimed,
                    resultStatus,
                    Collections.unmodifiableList(run.records));
        }
    }

    private static boolean isWorktree(PersistedCleanupPlanEntry entry) {
        return entry.resourceType() == LifecycleResourceType.WORKER_WORKTREE || entry.resourceType() == LifecycleResourceType.VALIDATION_WORKTREE
                || entry.resourceType() == LifecycleResourceType.INTEGRATION_WORKTREE;
    }

    private static boolean isTempFile(PersistedCleanupPlanEntry entry) {
        return entry.resourceType() == LifecycleResourceType.TEMPORARY_FILE;
    }

    private static boolean isOrphanDir(PersistedCleanupPlanEntry entry) {
        return entry.resourceType() == LifecycleResourceType.UNLINKED_EXTERNAL_WORKSPACE;
    }

    private static Path resolvePath(PersistedCleanupPlanEntry entry) {
        return entry.resourcePath().isBlank() ? null : Path.of(entry.resourcePath()).toAbsolutePath().normalize();
    }

    /**
     * Re-verifies at execution time what the plan assumed.
     *
     * @return the reason the entry must not run, or {@code null} when it may
     */
    private Outcome verifyPreconditions(Path root, PersistedCleanupPlanEntry entry) {
        if (!entry.eligible() || entry.classification() != CleanupClassification.CLEANUP_ELIGIBLE) {
            return Outcome.skippedUnsafe(CleanupReason.CLEANUP_RESOURCE_RECOVERABLE, "Entry is not classified as CLEANUP_ELIGIBLE");
        }
        if (!isWorktree(entry) && !isTempFile(entry) && !isOrphanDir(entry)) {
            return Outcome.skippedUnsafe(CleanupReason.CLEANUP_REQUIRES_HUMAN_REVIEW, "Resource type is report-only and cannot be executed in Slice 2");
        }

        Path path = resolvePath(entry);
        if (path == null || !pathVerifier.verifyPath(root, path).safe() || !Files.exists(path)) {
            return Outcome.skippedStale(CleanupReason.CLEANUP_PLAN_STALE, "Path safety or existence check failed at execution time");
        }
        if (isWorktree(entry)) {
            return verifyWorktree(path, entry);
        }
        if (isTempFile(entry) && (!Files.isRegularFile(path) || Files.isSymbolicLink(path))) {
            return Outcome.skippedStale(CleanupReason.CLEANUP_PATH_IDENTITY_CHANGED, "Target is not a plain regular file");
        }
        return null;
    }

    private static Outcome verifyWorktree(Path path, PersistedCleanupPlanEntry entry) {
        if (!Files.isDirectory(path) || !Files.exists(path.resolve(".git"))) {
            return Outcome.skippedStale(CleanupReason.CLEANUP_REGISTRATION_CHANGED, "Worktree directory or .git is missing");
        }

        try {
            String status = runGit(path, "status", "--porcelain");
            if (!status.isBlank()) {
                return Outcome.skippedStale(CleanupReason.CLEANUP_RESOURCE_DIRTY, "Worktree contains uncommitted changes at execution time");
            }
        } catch (Exception ex) {
            return Outcome.skippedStale(CleanupReason.CLEANUP_GIT_IDENTITY_CHANGED, "Git status check failed: " + ex.getMessage());
        }

        try {
            String currentHead = runGit(path, "rev-parse", "HEAD");
            if (!currentHead.equalsIgnoreCase(entry.fingerprint().gitHead())) {
                return Outcome.skippedStale(CleanupReason.CLEANUP_HEAD_CHANGED, "Git HEAD changed from " + entry.fingerprint().gitHead() + " to " + currentHead);
            }
        } catch (Exception ex) {
            return Outcome.skippedStale(CleanupReason.CLEANUP_HEAD_CHANGED, "Git rev-parse HEAD failed");
        }
        return null;
    }

    private Outcome execute(Path root, PersistedCleanupPlanEntry entry) {
        Path path = resolvePath(entry);
        try {
            if (isWorktree(entry)) {
                return removeWorktree(root, path, entry);
            }
            if (isTempFile(entry)) {
                return deleteTemporaryFile(root, path, entry);
            }
            String quarantineId = quarantineService.quarantineResource(root, entry);
            return Outcome.completed(CleanupReason.CLEANUP_QUARANTINE_COMPLETED.code(), "Orphan resource quarantined under " + quarantineId, entry.estimatedBytes());
        } catch (Exception failure) {
            return Outcome.failed(CleanupReason.CLEANUP_REQUIRES_HUMAN_REVIEW, "Execution operation failed: " + failure.getMessage());
        }
    }

    private static Outcome removeWorktree(Path root, Path path, PersistedCleanupPlanEntry entry) throws IOException {
        // Safe Git worktree removal: never --force.
        runGit(root, "worktree", "remove", path.toString());

        if (isGitWorktreeRegistered(root, path)) {
            return Outcome.failed(CleanupReason.CLEANUP_POSTCONDITION_FAILED, "Worktree removal postcondition failed: registration still present");
        }
        return Outcome.completed("worktree_removed", "Git worktree removed successfully", entry.estimatedBytes());
    }

    private static Outcome deleteTemporaryFile(Path root, Path path, PersistedCleanupPlanEntry entry) throws IOException {
        Files.delete(path);
        if (Files.exists(path)) {
            return Outcome.failed(CleanupReason.CLEANUP_EXACT_DELETE_FAILED, "File deletion failed: file still exists");
        }

        // Check if parent directory can be removed safely if empty
        Path parent = path.getParent();
        if (parent != null && !parent.equals(root) && Files.isDirectory(parent)) {
            try (var stream = Files.list(parent)) {
                if (stream.findAny().isEmpty()) {
                    Files.delete(parent);
                }
            } catch (Exception ignored) {
            }
        }
        return Outcome.completed("file_deleted", "Temporary file deleted successfully", entry.estimatedBytes());
    }

    /**
     * What happened to one plan entry, as it is journaled.
     *
     * @param state   journaled state
     * @param reason  precondition reason code
     * @param details diagnostic details
     * @param bytes   bytes reclaimed
     */
    private record Outcome(CleanupEntryExecutionState state, String reason, String details, long bytes) {

        static Outcome skippedStale(CleanupReason reason, String details) {
            return new Outcome(CleanupEntryExecutionState.SKIPPED_STALE, reason.code(), details, 0L);
        }

        static Outcome skippedUnsafe(CleanupReason reason, String details) {
            return new Outcome(CleanupEntryExecutionState.SKIPPED_UNSAFE, reason.code(), details, 0L);
        }

        static Outcome failed(CleanupReason reason, String details) {
            return new Outcome(CleanupEntryExecutionState.FAILED_REQUIRES_REVIEW, reason.code(), details, 0L);
        }

        static Outcome completed(String reason, String details, long bytes) {
            return new Outcome(CleanupEntryExecutionState.COMPLETED, reason, details, bytes);
        }
    }

    /**
     * One execution run: its journal, the records it has written, and the counters of its summary.
     */
    private static final class Run {

        private final String executionId;
        private final String planId;
        private final CleanupExecutionJournal journal;
        private final List<CleanupExecutionRecord> records = new ArrayList<>();
        private int completedCount;
        private int alreadyCompletedCount;
        private int skippedStaleCount;
        private int skippedUnsafeCount;
        private int failedCount;
        private long bytesReclaimed;

        Run(String executionId, String planId, CleanupExecutionJournal journal) {
            this.executionId = executionId;
            this.planId = planId;
            this.journal = journal;
        }

        void alreadyCompleted(PersistedCleanupPlanEntry entry, long now) throws IOException {
            alreadyCompletedCount++;
            add(entry, now, new Outcome(CleanupEntryExecutionState.COMPLETED, CleanupReason.CLEANUP_ENTRY_ALREADY_COMPLETED.code(), "Already completed in previous execution run", 0L));
        }

        /**
         * Journals the outcome of an entry, adds it to the result, and counts it by state.
         */
        void record(PersistedCleanupPlanEntry entry, long now, Outcome outcome) throws IOException {
            switch (outcome.state()) {
                case COMPLETED -> {
                    completedCount++;
                    bytesReclaimed += outcome.bytes();
                }
                case SKIPPED_STALE -> skippedStaleCount++;
                case SKIPPED_UNSAFE -> skippedUnsafeCount++;
                case FAILED_REQUIRES_REVIEW -> failedCount++;
                default -> throw new IllegalArgumentException("Outcome state is not a result: " + outcome.state());
            }
            add(entry, now, outcome);
        }

        /**
         * Journals a step that is not a result of the entry and does not appear in the summary.
         */
        void journalOnly(PersistedCleanupPlanEntry entry, long now, Outcome outcome) throws IOException {
            journal.append(toRecord(entry, now, outcome));
        }

        private void add(PersistedCleanupPlanEntry entry, long now, Outcome outcome) throws IOException {
            CleanupExecutionRecord record = toRecord(entry, now, outcome);
            journal.append(record);
            records.add(record);
        }

        private CleanupExecutionRecord toRecord(PersistedCleanupPlanEntry entry, long now, Outcome outcome) {
            return new CleanupExecutionRecord(executionId, planId, entry.resourceId(), entry.resourceType(), outcome.state(), outcome.reason(), now, outcome.bytes(), outcome.details());
        }
    }

    /**
     * Execution summary output.
     *
     * @param planId                plan identifier
     * @param executionId           execution run identifier
     * @param totalEntries          total entries evaluated
     * @param completedCount        count of newly completed entries
     * @param alreadyCompletedCount count of previously completed entries
     * @param skippedStaleCount     count of entries skipped due to staleness
     * @param skippedUnsafeCount    count of entries skipped due to safety/ineligibility
     * @param failedCount           count of failed entries
     * @param bytesReclaimed        total bytes reclaimed
     * @param resultStatus          overall execution status code
     * @param records               list of per-entry execution records
     */
    public record CleanupExecutionSummary(String planId, String executionId, int totalEntries, int completedCount, int alreadyCompletedCount, int skippedStaleCount, int skippedUnsafeCount, int failedCount,
                                          long bytesReclaimed, String resultStatus, List<CleanupExecutionRecord> records) {

        /**
         * Requires non-null plan ID, execution ID, result status, and records.
         */
        public CleanupExecutionSummary {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(resultStatus, "resultStatus");
            Objects.requireNonNull(records, "records");
        }
    }
}
