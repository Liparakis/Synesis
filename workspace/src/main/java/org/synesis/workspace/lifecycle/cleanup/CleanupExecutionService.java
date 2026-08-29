package org.synesis.workspace.lifecycle.cleanup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.GitProcessRunner;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;
import org.synesis.workspace.lifecycle.command.ProjectCommandMaintenanceService;

/**
 * Execution engine for executing reviewed cleanup plans safely with strict precondition re-verification,
 * lock guards, execution journals, and entry isolation.
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
        this(new ProjectApplicationService(),
                new CleanupPlanStore(),
                new LifecyclePathVerifier(),
                new RetentionPolicy(),
                new LifecycleQuarantineService());
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
    public CleanupExecutionService(
            ProjectApplicationService projectService,
            CleanupPlanStore planStore,
            LifecyclePathVerifier pathVerifier,
            RetentionPolicy retentionPolicy,
            LifecycleQuarantineService quarantineService
    ) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.pathVerifier = Objects.requireNonNull(pathVerifier, "pathVerifier");
        Objects.requireNonNull(retentionPolicy, "retentionPolicy");
        this.quarantineService = Objects.requireNonNull(quarantineService, "quarantineService");
    }

    private static boolean isGitWorktreeRegistered(Path controlRoot, Path worktreePath) {
        try {
            String output = runGit(controlRoot, "worktree", "list", "--porcelain");
            String normTarget = worktreePath.toAbsolutePath()
                    .normalize()
                    .toString();
            for (String line : output.lines()
                    .toList()) {
                if (line.startsWith("worktree ")) {
                    String wtPath = Path.of(line.substring("worktree ".length())
                                    .trim())
                            .toAbsolutePath()
                            .normalize()
                            .toString();
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
        return GitProcessRunner.run(workdir, args)
                .trim();
    }

    /**
     * Executes a reviewed persisted cleanup plan by plan ID.
     *
     * @param controlRoot control project root path
     * @param planId      persisted plan ID
     * @return execution summary result
     * @throws ProjectApplicationService.ProjectApplicationException if project discovery fails
     * @throws IOException                                           if loading or lock acquisition fails
     */
    @SuppressWarnings("try")
    public CleanupExecutionSummary executePlan(Path controlRoot, String planId)
            throws ProjectApplicationService.ProjectApplicationException, IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath()
                .normalize();
        projectService.locate(root);
        ProjectCommandDiagnostics.Report commandNamespace = ProjectCommandDiagnostics.inspect(
                AdministrativeStateLocator.applicationStateRoot()
                        .resolve("commands"));
        if (commandNamespace.present() && (!commandNamespace.formatValid()
                || commandNamespace.newerObjectCount() > 0 || commandNamespace.corruptObjectCount() > 0)) {
            throw new IOException("COMMAND_NAMESPACE_UNSAFE_CLEANUP_BLOCKED");
        }

        // 1. Acquire project execution lock
        try (var _ = CleanupExecutionLock.acquire(root, planId)) {
            // 2. Load persisted plan
            PersistedCleanupPlan plan = planStore.load(root, planId);

            String executionId = "exec-" + UUID.randomUUID()
                    .toString()
                    .replace("-", "");
            CleanupExecutionJournal journal = new CleanupExecutionJournal(root, executionId);
            Set<String> previouslyCompleted = CleanupExecutionJournal.loadCompletedResourceIds(root, planId);

            int completedCount = 0;
            int alreadyCompletedCount = 0;
            int skippedStaleCount = 0;
            int skippedUnsafeCount = 0;
            int failedCount = 0;
            long bytesReclaimed = 0L;

            List<CleanupExecutionRecord> records = new ArrayList<>();

            for (PersistedCleanupPlanEntry entry : plan.entries()) {
                long now = System.currentTimeMillis();

                // Idempotency check: Already completed?
                if (previouslyCompleted.contains(entry.resourceId())) {
                    alreadyCompletedCount++;
                    CleanupExecutionRecord rec = new CleanupExecutionRecord(
                            executionId, planId, entry.resourceId(), entry.resourceType(),
                            CleanupEntryExecutionState.COMPLETED, CleanupReason.CLEANUP_ENTRY_ALREADY_COMPLETED.code(),
                            now, 0L, "Already completed in previous execution run"
                    );
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                // Initial classification check
                if (!entry.eligible() || entry.classification() != CleanupClassification.CLEANUP_ELIGIBLE) {
                    skippedUnsafeCount++;
                    CleanupExecutionRecord rec = new CleanupExecutionRecord(
                            executionId,
                            planId,
                            entry.resourceId(),
                            entry.resourceType(),
                            CleanupEntryExecutionState.SKIPPED_UNSAFE,
                            CleanupReason.CLEANUP_RESOURCE_RECOVERABLE.code(),
                            now,
                            0L,
                            "Entry is not classified as CLEANUP_ELIGIBLE"
                    );
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                // Check supported executable types
                boolean isWorktree = entry.resourceType() == LifecycleResourceType.WORKER_WORKTREE ||
                        entry.resourceType() == LifecycleResourceType.VALIDATION_WORKTREE ||
                        entry.resourceType() == LifecycleResourceType.INTEGRATION_WORKTREE;
                boolean isTempFile = entry.resourceType() == LifecycleResourceType.TEMPORARY_FILE;
                boolean isOrphanDir = entry.resourceType() == LifecycleResourceType.UNLINKED_EXTERNAL_WORKSPACE;

                if (!isWorktree && !isTempFile && !isOrphanDir) {
                    skippedUnsafeCount++;
                    CleanupExecutionRecord rec = new CleanupExecutionRecord(
                            executionId,
                            planId,
                            entry.resourceId(),
                            entry.resourceType(),
                            CleanupEntryExecutionState.SKIPPED_UNSAFE,
                            CleanupReason.CLEANUP_REQUIRES_HUMAN_REVIEW.code(),
                            now,
                            0L,
                            "Resource type is report-only and cannot be executed in Slice 2"
                    );
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                Path path = entry.resourcePath()
                        .isBlank() ? null : Path.of(entry.resourcePath())
                                            .toAbsolutePath()
                                            .normalize();

                // Path Safety Precondition Verification
                if (path == null || !pathVerifier.verifyPath(root, path)
                        .safe() || !Files.exists(path)) {
                    skippedStaleCount++;
                    CleanupExecutionRecord rec = new CleanupExecutionRecord(
                            executionId, planId, entry.resourceId(), entry.resourceType(),
                            CleanupEntryExecutionState.SKIPPED_STALE, CleanupReason.CLEANUP_PLAN_STALE.code(),
                            now, 0L, "Path safety or existence check failed at execution time"
                    );
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                // Precondition Checks for Git Worktrees
                if (isWorktree) {
                    if (!Files.isDirectory(path) || !Files.exists(path.resolve(".git"))) {
                        skippedStaleCount++;
                        CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                executionId,
                                planId,
                                entry.resourceId(),
                                entry.resourceType(),
                                CleanupEntryExecutionState.SKIPPED_STALE,
                                CleanupReason.CLEANUP_REGISTRATION_CHANGED.code(),
                                now,
                                0L,
                                "Worktree directory or .git is missing"
                        );
                        journal.append(rec);
                        records.add(rec);
                        continue;
                    }

                    // Check porcelain dirty status
                    try {
                        String status = runGit(path, "status", "--porcelain");
                        if (!status.isBlank()) {
                            skippedStaleCount++;
                            CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                    executionId,
                                    planId,
                                    entry.resourceId(),
                                    entry.resourceType(),
                                    CleanupEntryExecutionState.SKIPPED_STALE,
                                    CleanupReason.CLEANUP_RESOURCE_DIRTY.code(),
                                    now,
                                    0L,
                                    "Worktree contains uncommitted changes at execution time"
                            );
                            journal.append(rec);
                            records.add(rec);
                            continue;
                        }
                    } catch (Exception ex) {
                        skippedStaleCount++;
                        CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                executionId,
                                planId,
                                entry.resourceId(),
                                entry.resourceType(),
                                CleanupEntryExecutionState.SKIPPED_STALE,
                                CleanupReason.CLEANUP_GIT_IDENTITY_CHANGED.code(),
                                now,
                                0L,
                                "Git status check failed: " + ex.getMessage()
                        );
                        journal.append(rec);
                        records.add(rec);
                        continue;
                    }

                    // Check HEAD commit SHA match
                    try {
                        String currentHead = runGit(path, "rev-parse", "HEAD");
                        if (!currentHead.equalsIgnoreCase(entry.fingerprint()
                                .gitHead())) {
                            skippedStaleCount++;
                            CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                    executionId,
                                    planId,
                                    entry.resourceId(),
                                    entry.resourceType(),
                                    CleanupEntryExecutionState.SKIPPED_STALE,
                                    CleanupReason.CLEANUP_HEAD_CHANGED.code(),
                                    now,
                                    0L,
                                    "Git HEAD changed from " + entry.fingerprint()
                                            .gitHead() + " to " + currentHead
                            );
                            journal.append(rec);
                            records.add(rec);
                            continue;
                        }
                    } catch (Exception ex) {
                        skippedStaleCount++;
                        CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                executionId, planId, entry.resourceId(), entry.resourceType(),
                                CleanupEntryExecutionState.SKIPPED_STALE, CleanupReason.CLEANUP_HEAD_CHANGED.code(),
                                now, 0L, "Git rev-parse HEAD failed"
                        );
                        journal.append(rec);
                        records.add(rec);
                        continue;
                    }
                }

                // Precondition Checks for Temporary Files
                if (isTempFile) {
                    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                        skippedStaleCount++;
                        CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                executionId,
                                planId,
                                entry.resourceId(),
                                entry.resourceType(),
                                CleanupEntryExecutionState.SKIPPED_STALE,
                                CleanupReason.CLEANUP_PATH_IDENTITY_CHANGED.code(),
                                now,
                                0L,
                                "Target is not a plain regular file"
                        );
                        journal.append(rec);
                        records.add(rec);
                        continue;
                    }
                }

                // Precondition verified -> record in journal
                journal.append(new CleanupExecutionRecord(
                        executionId, planId, entry.resourceId(), entry.resourceType(),
                        CleanupEntryExecutionState.PRECONDITION_VERIFIED, "precondition_verified",
                        now, 0L, "Preconditions re-verified safe"
                ));

                // Execute Operation
                try {
                    if (isWorktree) {
                        // Safe Git Worktree Removal (NO --force!)
                        runGit(root, "worktree", "remove", path.toString());

                        // Postcondition check: Verify worktree registration is gone
                        boolean stillRegistered = isGitWorktreeRegistered(root, path);

                        if (stillRegistered) {
                            failedCount++;
                            CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                    executionId,
                                    planId,
                                    entry.resourceId(),
                                    entry.resourceType(),
                                    CleanupEntryExecutionState.FAILED_REQUIRES_REVIEW,
                                    CleanupReason.CLEANUP_POSTCONDITION_FAILED.code(),
                                    now,
                                    0L,
                                    "Worktree removal postcondition failed: registration still present"
                            );
                            journal.append(rec);
                            records.add(rec);
                        } else {
                            completedCount++;
                            bytesReclaimed += entry.estimatedBytes();
                            CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                    executionId, planId, entry.resourceId(), entry.resourceType(),
                                    CleanupEntryExecutionState.COMPLETED, "worktree_removed",
                                    now, entry.estimatedBytes(), "Git worktree removed successfully"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                    } else if (isTempFile) {
                        // Exact file deletion
                        Files.delete(path);
                        if (Files.exists(path)) {
                            failedCount++;
                            CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                    executionId,
                                    planId,
                                    entry.resourceId(),
                                    entry.resourceType(),
                                    CleanupEntryExecutionState.FAILED_REQUIRES_REVIEW,
                                    CleanupReason.CLEANUP_EXACT_DELETE_FAILED.code(),
                                    now,
                                    0L,
                                    "File deletion failed: file still exists"
                            );
                            journal.append(rec);
                            records.add(rec);
                        } else {
                            // Check if parent directory can be removed safely if empty
                            Path parent = path.getParent();
                            if (parent != null && !parent.equals(root) && Files.isDirectory(parent)) {
                                try (var stream = Files.list(parent)) {
                                    if (stream.findAny()
                                            .isEmpty()) {
                                        Files.delete(parent);
                                    }
                                } catch (Exception ignored) {
                                }
                            }

                            completedCount++;
                            bytesReclaimed += entry.estimatedBytes();
                            CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                    executionId, planId, entry.resourceId(), entry.resourceType(),
                                    CleanupEntryExecutionState.COMPLETED, "file_deleted",
                                    now, entry.estimatedBytes(), "Temporary file deleted successfully"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                    } else if (isOrphanDir) {
                        // Quarantine orphan directory
                        String quarantineId = quarantineService.quarantineResource(root, entry);
                        completedCount++;
                        bytesReclaimed += entry.estimatedBytes();
                        CleanupExecutionRecord rec = new CleanupExecutionRecord(
                                executionId, planId, entry.resourceId(), entry.resourceType(),
                                CleanupEntryExecutionState.COMPLETED, CleanupReason.CLEANUP_QUARANTINE_COMPLETED.code(),
                                now, entry.estimatedBytes(), "Orphan resource quarantined under " + quarantineId
                        );
                        journal.append(rec);
                        records.add(rec);
                    }
                } catch (Exception failure) {
                    failedCount++;
                    CleanupExecutionRecord rec = new CleanupExecutionRecord(
                            executionId,
                            planId,
                            entry.resourceId(),
                            entry.resourceType(),
                            CleanupEntryExecutionState.FAILED_REQUIRES_REVIEW,
                            CleanupReason.CLEANUP_REQUIRES_HUMAN_REVIEW.code(),
                            now,
                            0L,
                            "Execution operation failed: " + failure.getMessage()
                    );
                    journal.append(rec);
                    records.add(rec);
                }
            }

            String resultStatus;
            if (failedCount > 0) {
                resultStatus = "FAILED_REQUIRES_REVIEW";
            } else if (skippedStaleCount > 0) {
                resultStatus = "PARTIAL_SUCCESS";
            } else {
                resultStatus = "SUCCESS";
            }

            return new CleanupExecutionSummary(
                    planId,
                    executionId,
                    plan.entries()
                            .size(),
                    completedCount,
                    alreadyCompletedCount,
                    skippedStaleCount,
                    skippedUnsafeCount,
                    failedCount,
                    bytesReclaimed,
                    resultStatus,
                    Collections.unmodifiableList(records)
            );
        }
    }

    /**
     * Executes the command-namespace terminal retention step through the existing cleanup entry point.
     *
     * @param namespaceRoot host-wide command namespace root
     * @param anchorId      dead process anchor to review and compact
     * @param now           retention clock value
     * @param retention     required diagnostic retention interval
     * @return compacted terminal-history result
     * @throws IOException if command state is live, blocking, pinned, corrupt, or unsupported
     */
    @SuppressWarnings("unused")
    public ProjectCommandMaintenanceService.CleanupResult cleanupDeadCommandAnchor(
            Path namespaceRoot, String anchorId, Instant now, Duration retention) throws IOException {
        return new ProjectCommandMaintenanceService().cleanupDeadAnchor(namespaceRoot, anchorId, now, retention);
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
    public record CleanupExecutionSummary(
            String planId,
            String executionId,
            int totalEntries,
            int completedCount,
            int alreadyCompletedCount,
            int skippedStaleCount,
            int skippedUnsafeCount,
            int failedCount,
            long bytesReclaimed,
            String resultStatus,
            List<CleanupExecutionRecord> records
    ) {

        /**
         * Invariant validation.
         */
        public CleanupExecutionSummary {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(resultStatus, "resultStatus");
            Objects.requireNonNull(records, "records");
        }
    }
}
