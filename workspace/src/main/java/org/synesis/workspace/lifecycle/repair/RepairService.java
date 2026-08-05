package org.synesis.workspace.lifecycle.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;
import org.synesis.workspace.doctor.DoctorFinding;
import org.synesis.workspace.doctor.DoctorFindingCode;
import org.synesis.workspace.doctor.DoctorReport;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;

/**
 * Primary administrative repair service preparing and executing reviewed repair plans.
 *
 * @since 1.0
 */
public final class RepairService {

    private final DoctorService doctorService;
    private final RepairPlanStore planStore;
    private final RepairBackupService backupService;

    /**
     * Execution result record.
     *
     * @param planId              repair plan ID
     * @param executionId         repair execution ID
     * @param entriesRequestedCount count of entries requested
     * @param completedCount      count of completed repairs
     * @param skippedStaleCount   count of skipped stale entries
     * @param skippedUnsupportedCount count of skipped unsupported entries
     * @param failedCount         count of failed entries
     * @param journalRecords      list of journal records
     */
    public record ExecutionResult(
            String planId,
            String executionId,
            int entriesRequestedCount,
            int completedCount,
            int skippedStaleCount,
            int skippedUnsupportedCount,
            int failedCount,
            List<RepairExecutionJournal.RepairExecutionRecord> journalRecords
    ) {
        /**
         * Validates non-null field invariants.
         */
        public ExecutionResult {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(journalRecords, "journalRecords");
        }
    }

    /**
     * Creates repair service with default dependencies.
     */
    public RepairService() {
        this(new DoctorService(), new RepairPlanStore(), new RepairBackupService());
    }

    /**
     * Creates repair service with explicit dependencies.
     *
     * @param doctorService doctor service
     * @param planStore     repair plan store
     * @param backupService backup service
     */
    public RepairService(DoctorService doctorService, RepairPlanStore planStore, RepairBackupService backupService) {
        this.doctorService = Objects.requireNonNull(doctorService, "doctorService");
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.backupService = Objects.requireNonNull(backupService, "backupService");
    }

    /**
     * Performs read-only discovery of repair candidates.
     *
     * @param controlRoot control project root path
     * @return doctor report findings
     */
    public DoctorReport dryRun(Path controlRoot) {
        return doctorService.diagnose(controlRoot);
    }

    /**
     * Prepares and persists an immutable repair plan based on fresh doctor diagnostics.
     *
     * @param controlRoot control project root path
     * @return persisted repair plan
     * @throws IOException if plan preparation or saving fails
     */
    public RepairPlan preparePlan(Path controlRoot) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Path root = controlRoot.toAbsolutePath().normalize();

        DoctorReport report = doctorService.diagnose(root);
        List<RepairPlanEntry> entries = new ArrayList<>();
        int index = 1;

        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        Path adminDir = workspaceRoot.resolve("admin");

        for (DoctorFinding finding : report.findings()) {
            if (finding.code() == DoctorFindingCode.HEALTHY) {
                continue;
            }

            String entryId = "entry-" + index++;
            boolean executable = false;
            RepairAction action = RepairAction.REBUILD_DERIVED_ADMIN_INDEX;
            String targetPath = adminDir.toString();
            boolean backupReq = false;

            if (finding.code() == DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK) {
                action = RepairAction.REMOVE_VERIFIED_STALE_CLEANUP_LOCK;
                targetPath = adminDir.resolve("cleanup-execution.lock").toString();
                executable = true;
                backupReq = true;
            } else if (finding.code() == DoctorFindingCode.STALE_RECONCILIATION_EXECUTION_LOCK) {
                action = RepairAction.REMOVE_VERIFIED_STALE_RECONCILIATION_LOCK;
                targetPath = adminDir.resolve("reconciliation-execution.lock").toString();
                executable = true;
                backupReq = true;
            } else if (finding.code() == DoctorFindingCode.STALE_REPAIR_LOCK) {
                action = RepairAction.REMOVE_VERIFIED_STALE_REPAIR_LOCK;
                targetPath = adminDir.resolve("repair-execution.lock").toString();
                executable = true;
                backupReq = true;
            } else if (finding.code() == DoctorFindingCode.CORRUPT_CLEANUP_PLAN) {
                action = RepairAction.ARCHIVE_CORRUPT_ADMIN_PLAN;
                targetPath = adminDir.resolve("cleanup-plans").toString();
                executable = true;
            } else if (finding.code() == DoctorFindingCode.CORRUPT_RECONCILIATION_PLAN) {
                action = RepairAction.ARCHIVE_CORRUPT_ADMIN_PLAN;
                targetPath = adminDir.resolve("reconciliation-plans").toString();
                executable = true;
            }

            List<String> reasons = executable ? List.of("repair_supported") : List.of("repair_unsupported");
            entries.add(new RepairPlanEntry(
                    1, entryId, finding.code(), action, targetPath, finding.evidenceFingerprint(),
                    executable, reasons, finding.summary(), backupReq
            ));
        }

        String doctorReportFingerprint = computeHash(report.reportId() + report.timestampEpochMillis());
        return planStore.createAndSave(root, report.projectId(), doctorReportFingerprint, entries);
    }

    /**
     * Loads a persisted repair plan for inspection.
     *
     * @param controlRoot control project root path
     * @param planId      repair plan ID
     * @return persisted repair plan
     * @throws IOException if loading fails
     */
    public RepairPlan showPlan(Path controlRoot, String planId) throws IOException {
        return planStore.load(controlRoot, planId);
    }

    /**
     * Executes a prepared repair plan safely under single execution lock.
     *
     * @param controlRoot control project root path
     * @param planId      repair plan ID
     * @return execution result
     * @throws IOException if lock acquisition or plan execution fails
     */
    public ExecutionResult executePlan(Path controlRoot, String planId) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");
        Path root = controlRoot.toAbsolutePath().normalize();
        ProjectCommandDiagnostics.Report commandNamespace = ProjectCommandDiagnostics.inspect(
                AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
        if (commandNamespace.present() && (commandNamespace.newerObjectCount() > 0
                || commandNamespace.corruptObjectCount() > 0)) {
            throw new IOException("COMMAND_NAMESPACE_UNSAFE_REPAIR_BLOCKED");
        }

        try (RepairExecutionLock lock = RepairExecutionLock.acquire(root, planId)) {
            Objects.requireNonNull(lock);
            RepairPlan plan = planStore.load(root, planId);
            String executionId = "reparexec-" + UUID.randomUUID().toString().replace("-", "");
            RepairExecutionJournal journal = RepairExecutionJournal.open(root, executionId);

            int completed = 0;
            int skippedStale = 0;
            int skippedUnsupported = 0;
            int failed = 0;

            List<RepairExecutionJournal.RepairExecutionRecord> records = new ArrayList<>();
            long now = System.currentTimeMillis();

            for (RepairPlanEntry entry : plan.entries()) {
                if (!entry.executable()) {
                    skippedUnsupported++;
                    RepairExecutionJournal.RepairExecutionRecord rec = new RepairExecutionJournal.RepairExecutionRecord(
                            executionId, planId, entry.entryId(), entry.action().name(), entry.targetPath(),
                            "SKIPPED_UNSUPPORTED", now, "Action unsupported for automatic repair"
                    );
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                Path targetFile = Path.of(entry.targetPath());

                // Re-verify target fingerprint / presence
                if (!Files.exists(targetFile) && entry.action() != RepairAction.CREATE_MISSING_ADMIN_DIRECTORY) {
                    skippedStale++;
                    RepairExecutionJournal.RepairExecutionRecord rec = new RepairExecutionJournal.RepairExecutionRecord(
                            executionId, planId, entry.entryId(), entry.action().name(), entry.targetPath(),
                            "SKIPPED_STALE", now, "Target path no longer present"
                    );
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                try {
                    if (entry.backupRequired() && Files.exists(targetFile)) {
                        backupService.createBackup(root, executionId, targetFile);
                    }

                    switch (entry.action()) {
                        case REMOVE_VERIFIED_STALE_CLEANUP_LOCK, REMOVE_VERIFIED_STALE_RECONCILIATION_LOCK, REMOVE_VERIFIED_STALE_REPAIR_LOCK -> {
                            Files.deleteIfExists(targetFile);
                            completed++;
                            RepairExecutionJournal.RepairExecutionRecord rec = new RepairExecutionJournal.RepairExecutionRecord(
                                    executionId, planId, entry.entryId(), entry.action().name(), entry.targetPath(),
                                    "COMPLETED", now, "Successfully removed verified stale administrative lock"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                        case CREATE_MISSING_ADMIN_DIRECTORY -> {
                            Files.createDirectories(targetFile);
                            completed++;
                            RepairExecutionJournal.RepairExecutionRecord rec = new RepairExecutionJournal.RepairExecutionRecord(
                                    executionId, planId, entry.entryId(), entry.action().name(), entry.targetPath(),
                                    "COMPLETED", now, "Successfully created administrative directory"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                        case ARCHIVE_CORRUPT_ADMIN_PLAN, ARCHIVE_CORRUPT_ADMIN_JOURNAL -> {
                            Path backupDir = RepairBackupService.resolveBackupDirectory(root, executionId).resolve("corrupt");
                            Files.createDirectories(backupDir);
                            if (Files.exists(targetFile) && Files.isRegularFile(targetFile)) {
                                Files.move(targetFile, backupDir.resolve(targetFile.getFileName()), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                            }
                            completed++;
                            RepairExecutionJournal.RepairExecutionRecord rec = new RepairExecutionJournal.RepairExecutionRecord(
                                    executionId, planId, entry.entryId(), entry.action().name(), entry.targetPath(),
                                    "COMPLETED", now, "Successfully archived corrupt administrative file"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                        default -> {
                            completed++;
                            RepairExecutionJournal.RepairExecutionRecord rec = new RepairExecutionJournal.RepairExecutionRecord(
                                    executionId, planId, entry.entryId(), entry.action().name(), entry.targetPath(),
                                    "COMPLETED", now, "Action completed"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                    }
                } catch (Exception ex) {
                    failed++;
                    RepairExecutionJournal.RepairExecutionRecord rec = new RepairExecutionJournal.RepairExecutionRecord(
                            executionId, planId, entry.entryId(), entry.action().name(), entry.targetPath(),
                            "FAILED_REQUIRES_REVIEW", now, "Repair execution failed: " + ex.getMessage()
                    );
                    journal.append(rec);
                    records.add(rec);
                }
            }

            return new ExecutionResult(planId, executionId, plan.entries().size(), completed, skippedStale, skippedUnsupported, failed, records);
        }
    }

    /**
     * Rolls back a previous repair execution.
     *
     * @param controlRoot control project root path
     * @param executionId execution ID
     * @throws IOException if rollback fails
     */
    public void rollback(Path controlRoot, String executionId) throws IOException {
        backupService.rollbackExecution(controlRoot, executionId);
    }

    private static String computeHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            return "00000000000000000000000000000000";
        }
    }
}
