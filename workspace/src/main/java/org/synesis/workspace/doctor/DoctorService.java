package org.synesis.workspace.doctor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.cleanup.CleanupEligibilityService;
import org.synesis.workspace.cleanup.LifecycleInventoryService;
import org.synesis.workspace.cleanup.LifecyclePathVerifier;
import org.synesis.workspace.cleanup.ProcessInspector;
import org.synesis.workspace.lease.SessionLeasePolicy;
import org.synesis.workspace.lease.SessionLeaseRecord;
import org.synesis.workspace.lease.SessionLeaseService;
import org.synesis.workspace.lease.SessionLeaseState;
import org.synesis.workspace.lease.SessionLeaseStore;
import org.synesis.workspace.provider.ProviderJson;
import org.synesis.workspace.migration.ProviderConfigMigrationService;
import org.synesis.workspace.migration.ProjectMigrationService;

/**
 * Primary read-only diagnostic service discovering repository, runtime, durable state, and administrative health.
 *
 * <p>DoctorService is read-only by construction: it performs zero file creations, zero file modifications,
 * zero file deletions, zero process terminations, and zero lock acquisitions.
 *
 * @since 1.0
 */
public final class DoctorService {

    private final ProjectApplicationService projectService;
    private final LifecycleInventoryService inventoryService;
    private final CleanupEligibilityService eligibilityService;
    private final SessionLeaseService leaseService;
    private final SessionLeaseStore leaseStore;
    private final ProcessInspector processInspector;

    /**
     * Creates a doctor service with default dependencies.
     */
    public DoctorService() {
        this(new ProjectApplicationService(), new LifecycleInventoryService(), new CleanupEligibilityService(),
                new SessionLeaseService(), new SessionLeaseStore(), ProcessInspector.system());
    }

    /**
     * Creates a doctor service with explicit dependencies.
     *
     * @param projectService     project service
     * @param inventoryService   lifecycle inventory service
     * @param eligibilityService cleanup eligibility service
     * @param leaseService       session lease service
     * @param leaseStore         session lease store
     * @param processInspector   process inspector
     */
    public DoctorService(
            ProjectApplicationService projectService,
            LifecycleInventoryService inventoryService,
            CleanupEligibilityService eligibilityService,
            SessionLeaseService leaseService,
            SessionLeaseStore leaseStore,
            ProcessInspector processInspector
    ) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
        this.eligibilityService = Objects.requireNonNull(eligibilityService, "eligibilityService");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
        this.processInspector = Objects.requireNonNull(processInspector, "processInspector");
    }

    /**
     * Evaluates read-only diagnostics over the specified control root.
     *
     * @param controlRoot control project root path
     * @return comprehensive doctor report
     */
    public DoctorReport diagnose(Path controlRoot) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Path root = controlRoot.toAbsolutePath().normalize();
        String reportId = "doc-" + UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();

        List<DoctorFinding> findings = new ArrayList<>();

        // 1. Installation and Runtime Checks
        checkInstallationAndRuntime(root, findings);

        // 2. Project Identity and Repository Checks
        String projectIdStr = checkProjectAndRepository(root, findings);

        // 3. Durable State Checks
        checkDurableState(root, projectIdStr, findings);

        // 4. Sessions, Ownership, and Worktrees
        checkSessionsOwnershipWorktrees(root, findings);

        // 5. Cleanup and Storage Checks
        checkCleanupAndStorage(root, findings);

        // 6. Administrative State Checks
        checkAdministrativeState(root, findings);

        // 6b. Update and migration transaction checks (read-only)
        checkMigrationTransactions(findings);

        // 7. Provider Configuration Checks
        checkProviderConfiguration(findings);

        if (findings.isEmpty()) {
            findings.add(new DoctorFinding(
                    DoctorFindingCode.HEALTHY, DoctorSeverity.INFO, DoctorConfidence.CONFIRMED,
                    "System and repository healthy", "All checked components operate within expected parameters.",
                    "project", false, DoctorRecommendation.NO_ACTION, computeHash("healthy"), Map.of()
            ));
        }

        int critical = (int) findings.stream().filter(f -> f.severity() == DoctorSeverity.CRITICAL).count();
        int errors = (int) findings.stream().filter(f -> f.severity() == DoctorSeverity.ERROR).count();
        int warnings = (int) findings.stream().filter(f -> f.severity() == DoctorSeverity.WARNING).count();
        int info = (int) findings.stream().filter(f -> f.severity() == DoctorSeverity.INFO).count();

        DoctorStatus status;
        if (critical > 0 || findings.stream().anyMatch(f -> f.code() == DoctorFindingCode.EVENT_LOG_VERIFICATION_FAILURE)) {
            status = DoctorStatus.UNSAFE;
        } else if (errors > 0) {
            status = DoctorStatus.UNHEALTHY;
        } else if (warnings > 0) {
            status = DoctorStatus.DEGRADED;
        } else {
            status = DoctorStatus.HEALTHY;
        }

        boolean cleanupRec = findings.stream().anyMatch(f -> f.code() == DoctorFindingCode.CLEANUP_RECOMMENDED || f.code() == DoctorFindingCode.ORPHANED_RESOURCE_DETECTED);
        boolean reconcRec = findings.stream().anyMatch(f -> f.code() == DoctorFindingCode.STALE_SESSION_LEASE || f.code() == DoctorFindingCode.ABANDONED_INTEGRATION_ATTEMPT);
        boolean repairAvail = findings.stream().anyMatch(DoctorFinding::repairSupported);

        return new DoctorReport(
                1, reportId, projectIdStr != null ? projectIdStr : "unbound", now, status,
                critical, errors, warnings, info, cleanupRec, reconcRec, repairAvail,
                Collections.unmodifiableList(findings)
        );
    }

    private void checkInstallationAndRuntime(Path root, List<DoctorFinding> findings) {
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path synesisBin = Path.of(userHome, ".synesis", "bin");
            if (Files.isDirectory(synesisBin)) {
                Path launcherFile = synesisBin.resolve("synesis.bat");
                if (!Files.exists(launcherFile)) {
                    launcherFile = synesisBin.resolve("synesis");
                }
                if (!Files.exists(launcherFile)) {
                    findings.add(new DoctorFinding(
                            DoctorFindingCode.MISSING_INSTALLED_LAUNCHER, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED,
                            "Missing installed launcher", "Installed Synesis bin directory does not contain expected launcher script.",
                            "installation", false, DoctorRecommendation.REINSTALL_SYNESIS, computeHash("missing_launcher"), Map.of()
                    ));
                }
            }
        }
    }

    private String checkProjectAndRepository(Path root, List<DoctorFinding> findings) {
        Path projectJson = root.resolve(".synesis/project.json");
        if (!Files.exists(projectJson)) {
            findings.add(new DoctorFinding(
                    DoctorFindingCode.PROJECT_NOT_INITIALIZED, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED,
                    "Project not initialized", "Directory is missing .synesis/project.json configuration.",
                    "project", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED, computeHash("proj_not_init"), Map.of()
            ));
            return null;
        }

        try {
            ProjectMigrationService.Entry migration = new ProjectMigrationService().inspect(root);
            if (migration.outcome() == ProjectMigrationService.Outcome.UNSUPPORTED_SCHEMA) {
                findings.add(new DoctorFinding(
                        DoctorFindingCode.PROJECT_SCHEMA_UNSUPPORTED, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED,
                        "Project schema unsupported", "Project metadata uses a schema this Synesis build cannot migrate.",
                        "project_schema", false, DoctorRecommendation.PREPARE_PROJECT_MIGRATION, computeHash("project_schema_unsupported"), Map.of()));
                return migration.projectId().isBlank() ? null : migration.projectId();
            }
            ProjectApplicationService.ProjectLocation location = projectService.locate(root);
            return location.projectId().toString();
        } catch (Exception ex) {
            findings.add(new DoctorFinding(
                    DoctorFindingCode.PROJECT_IDENTITY_INVALID, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED,
                    "Invalid project identity", "Failed to locate or parse valid Synesis project identity.",
                    "project", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED, computeHash("proj_invalid"), Map.of()
            ));
            return null;
        }
    }

    private void checkDurableState(Path root, String projectIdStr, List<DoctorFinding> findings) {
        if (projectIdStr == null) {
            return;
        }
        Path coordDir = root.resolve(".synesis/coordination");
        if (Files.isDirectory(coordDir)) {
            try {
                UUID pId = UUID.fromString(projectIdStr);
                PredictionEventStore store = new PredictionEventStore(coordDir, pId);
                // If loaded without exception, store signatures & chain are verified
            } catch (java.security.GeneralSecurityException secEx) {
                findings.add(new DoctorFinding(
                        DoctorFindingCode.EVENT_LOG_VERIFICATION_FAILURE, DoctorSeverity.CRITICAL, DoctorConfidence.CONFIRMED,
                        "Event log verification failure", "Signed coordination event log cryptographic signature or digest chain verification failed.",
                        "event_log", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED, computeHash("event_log_fail"), Map.of()
                ));
            } catch (Exception ex) {
                findings.add(new DoctorFinding(
                        DoctorFindingCode.DURABLE_STATE_AMBIGUOUS, DoctorSeverity.ERROR, DoctorConfidence.AMBIGUOUS,
                        "Durable state ambiguous", "Failed to read durable coordination event store.",
                        "event_log", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED, computeHash("durable_ambiguous"), Map.of()
                ));
            }
        }
    }

    private void checkSessionsOwnershipWorktrees(Path root, List<DoctorFinding> findings) {
        List<SessionLeaseRecord> leases = leaseStore.listAll(root);
        SessionLeasePolicy policy = new SessionLeasePolicy();

        for (SessionLeaseRecord lease : leases) {
            SessionLeaseState state = leaseService.evaluateLiveness(lease, policy);
            if (state == SessionLeaseState.ABANDONMENT_ELIGIBLE || state == SessionLeaseState.SUSPECTED_STALE) {
                findings.add(new DoctorFinding(
                        DoctorFindingCode.STALE_SESSION_LEASE, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE,
                        "Stale session lease detected", "Provider session lease has missed heartbeats beyond policy threshold.",
                        "session_lease", false, DoctorRecommendation.PREPARE_RECONCILIATION_PLAN, computeHash("stale_lease_" + lease.connectionInstanceId()), Map.of()
                ));
            } else if (state == SessionLeaseState.AMBIGUOUS) {
                findings.add(new DoctorFinding(
                        DoctorFindingCode.AMBIGUOUS_SESSION_LIVENESS, DoctorSeverity.WARNING, DoctorConfidence.AMBIGUOUS,
                        "Ambiguous session liveness", "Process identity or liveness state for provider session lease is ambiguous.",
                        "session_lease", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED, computeHash("ambiguous_lease_" + lease.connectionInstanceId()), Map.of()
                ));
            }
        }
    }

    private void checkCleanupAndStorage(Path root, List<DoctorFinding> findings) {
        try {
            var inventory = inventoryService.discoverResources(root);
            for (var resource : inventory) {
                var entry = eligibilityService.evaluateResource(root, resource);
                if (entry.eligible()) {
                    findings.add(new DoctorFinding(
                            DoctorFindingCode.CLEANUP_RECOMMENDED, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED,
                            "Cleanup recommended", "Eligible lifecycle resources are ready for cleanup review.",
                            "cleanup", false, DoctorRecommendation.RUN_CLEANUP_DRY_RUN, computeHash("cleanup_rec"), Map.of()
                    ));
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void checkAdministrativeState(Path root, List<DoctorFinding> findings) {
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        Path adminDir = workspaceRoot.resolve("admin");

        if (Files.isDirectory(adminDir)) {
            // Cleanup lock check
            Path cleanupLock = adminDir.resolve("cleanup-execution.lock");
            if (Files.exists(cleanupLock) && isLockStale(cleanupLock)) {
                findings.add(new DoctorFinding(
                        DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE,
                        "Stale cleanup execution lock", "Stale cleanup-execution.lock file present from absent process.",
                        "admin_lock", true, DoctorRecommendation.PREPARE_REPAIR_PLAN, computeHash("stale_cleanup_lock"), Map.of()
                ));
            }

            // Reconciliation lock check
            Path reconcLock = adminDir.resolve("reconciliation-execution.lock");
            if (Files.exists(reconcLock) && isLockStale(reconcLock)) {
                findings.add(new DoctorFinding(
                        DoctorFindingCode.STALE_RECONCILIATION_EXECUTION_LOCK, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE,
                        "Stale reconciliation execution lock", "Stale reconciliation-execution.lock file present from absent process.",
                        "admin_lock", true, DoctorRecommendation.PREPARE_REPAIR_PLAN, computeHash("stale_reconc_lock"), Map.of()
                ));
            }

            // Repair lock check
            Path repairLock = adminDir.resolve("repair-execution.lock");
            if (Files.exists(repairLock) && isLockStale(repairLock)) {
                findings.add(new DoctorFinding(
                        DoctorFindingCode.STALE_REPAIR_LOCK, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE,
                        "Stale repair execution lock", "Stale repair-execution.lock file present from absent process.",
                        "admin_lock", true, DoctorRecommendation.PREPARE_REPAIR_PLAN, computeHash("stale_repair_lock"), Map.of()
                ));
            }

            // Corrupt cleanup plans
            checkCorruptJsonFiles(adminDir.resolve("cleanup-plans"), DoctorFindingCode.CORRUPT_CLEANUP_PLAN, "Corrupt cleanup plan", findings);
            // Corrupt reconciliation plans
            checkCorruptJsonFiles(adminDir.resolve("reconciliation-plans"), DoctorFindingCode.CORRUPT_RECONCILIATION_PLAN, "Corrupt reconciliation plan", findings);
        }
    }

    private boolean isLockStale(Path lockFile) {
        try {
            String content = Files.readString(lockFile, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(content);
            long pid = ((Number) map.get("pid")).longValue();
            var detailsOpt = processInspector.inspectProcess(pid);
            return detailsOpt.isEmpty() || !detailsOpt.get().isLive();
        } catch (Exception ex) {
            return false;
        }
    }

    private void checkCorruptJsonFiles(Path dir, DoctorFindingCode code, String label, List<DoctorFinding> findings) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    ProviderJson.parse(raw);
                } catch (Exception corrupt) {
                    findings.add(new DoctorFinding(
                            code, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED,
                            label + " file corrupted", label + " file contains malformed JSON.",
                            "admin_plan", true, DoctorRecommendation.PREPARE_REPAIR_PLAN, computeHash(file.getFileName().toString()), Map.of()
                    ));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void checkProviderConfiguration(List<DoctorFinding> findings) {
        for (ProviderConfigMigrationService.Entry entry : new ProviderConfigMigrationService().inspect()) {
            if (entry.outcome() == ProviderConfigMigrationService.Outcome.MIGRATION_REQUIRED) {
                findings.add(new DoctorFinding(DoctorFindingCode.PROVIDER_MIGRATION_REQUIRED, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED,
                        "Provider migration required", "Provider MCP configuration does not reference the stable Synesis launcher.",
                        "provider_config", false, DoctorRecommendation.PREPARE_PROVIDER_MIGRATION, computeHash(entry.provider()), Map.of("provider", entry.provider())));
            } else if (entry.outcome() == ProviderConfigMigrationService.Outcome.MALFORMED) {
                findings.add(new DoctorFinding(DoctorFindingCode.PROVIDER_CONFIG_MALFORMED, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED,
                        "Provider config malformed", "Provider MCP configuration is malformed and was not changed.",
                        "provider_config", false, DoctorRecommendation.REVIEW_PROVIDER_CONFIGURATION, computeHash(entry.provider() + "_malformed"), Map.of("provider", entry.provider())));
            } else if (entry.outcome() == ProviderConfigMigrationService.Outcome.DUPLICATE_SYNSESIS_ENTRY) {
                findings.add(new DoctorFinding(DoctorFindingCode.PROVIDER_CONFIG_SYNSESIS_ENTRY_DUPLICATED, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED,
                        "Duplicate Synesis provider entries", "Provider configuration contains ambiguous Synesis MCP entries.",
                        "provider_config", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED, computeHash(entry.provider() + "_duplicate"), Map.of("provider", entry.provider())));
            }
        }
    }

    private void checkMigrationTransactions(List<DoctorFinding> findings) {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            String home = System.getProperty("user.home");
            if (home == null || home.isBlank()) return;
            base = Path.of(home, "AppData", "Local").toString();
        }
        Path admin = Path.of(base, "Synesis", "admin");
        checkTransactionJournals(admin.resolve("update-executions"), findings);
        checkTransactionJournals(admin.resolve("migration-executions"), findings);
    }

    private void checkTransactionJournals(Path directory, List<DoctorFinding> findings) {
        if (!Files.isDirectory(directory)) return;
        try (var stream = Files.list(directory)) {
            for (Path journal : stream.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
                try {
                    List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
                    if (lines.isEmpty()) throw new IOException("empty journal");
                    for (String line : lines) {
                        if (!line.contains("outcome=") && !line.contains("state=") && !(ProviderJson.parse(line) instanceof Map<?, ?>)) {
                            throw new IOException("invalid journal entry");
                        }
                    }
                    String journalText = String.join("\n", lines);
                    if (journalText.contains("post_migration_replay")) {
                        findings.add(new DoctorFinding(DoctorFindingCode.POST_MIGRATION_REPLAY_FAILED, DoctorSeverity.ERROR,
                                DoctorConfidence.CONFIRMED, "Post-migration replay failed", "Project migration replay did not prove semantic equivalence.",
                                "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                                computeHash(journal.getFileName().toString() + "_replay"), Map.of()));
                    }
                    if (journalText.contains("backup_missing") || journalText.contains("restore_failed")) {
                        findings.add(new DoctorFinding(DoctorFindingCode.MIGRATION_BACKUP_MISSING, DoctorSeverity.ERROR,
                                DoctorConfidence.CONFIRMED, "Migration backup unavailable", "Migration rollback evidence is incomplete.",
                                "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                                computeHash(journal.getFileName().toString() + "_backup"), Map.of()));
                    }
                    if (journalText.contains("active_session_blocks_project_migration")) {
                        findings.add(new DoctorFinding(DoctorFindingCode.ACTIVE_SESSION_BLOCKS_MIGRATION, DoctorSeverity.WARNING,
                                DoctorConfidence.CONFIRMED, "Active session blocks migration", "Project migration is waiting for incompatible session state to quiesce.",
                                "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                                computeHash(journal.getFileName().toString() + "_session"), Map.of()));
                    }
                    if (journalText.contains("RESTORE_REQUIRED") && !journalText.contains("RESTORE_VERIFIED")
                            && !journalText.contains("RESTORE_FAILED_REQUIRES_REVIEW")) {
                        findings.add(new DoctorFinding(DoctorFindingCode.PROJECT_RESTORATION_PENDING, DoctorSeverity.WARNING,
                                DoctorConfidence.CONFIRMED, "Project restoration pending", "Project metadata restoration has not reached a verified terminal state.",
                                "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                                computeHash(journal.getFileName().toString() + "_restore_pending"), Map.of()));
                    }
                    if (journalText.contains("RESTORE_FAILED_REQUIRES_REVIEW")) {
                        findings.add(new DoctorFinding(DoctorFindingCode.PROJECT_RESTORATION_REQUIRES_REVIEW, DoctorSeverity.ERROR,
                                DoctorConfidence.CONFIRMED, "Project restoration requires review", "Project metadata restoration could not be proven safe.",
                                "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                                computeHash(journal.getFileName().toString() + "_restore_review"), Map.of()));
                    }
                    if (journalText.contains("FAILED_RESTORED")) {
                        findings.add(new DoctorFinding(DoctorFindingCode.PROJECT_MIGRATION_RESTORED, DoctorSeverity.INFO,
                                DoctorConfidence.CONFIRMED, "Failed migration restored", "Project metadata was restored after migration failure.",
                                "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                                computeHash(journal.getFileName().toString() + "_restored"), Map.of()));
                    }
                    if (journalText.contains("ROLLBACK_PENDING") || journalText.contains("ROLLBACK_REQUIRES_HUMAN_REVIEW")) {
                        findings.add(new DoctorFinding(DoctorFindingCode.ROLLBACK_RESTORATION_INCOMPLETE, DoctorSeverity.ERROR,
                                DoctorConfidence.CONFIRMED, "Rollback restoration incomplete", "Rollback did not reach a verified restoration terminal state.",
                                "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                                computeHash(journal.getFileName().toString() + "_rollback"), Map.of()));
                    }
                    String last = lines.getLast();
                    if (!last.contains("COMPLETED") && !last.contains("SUCCESS") && !last.contains("ROLLED_BACK")
                            && !last.contains("MIGRATED") && !last.contains("UP_TO_DATE") && !last.contains("FAILED_RESTORED")) {
                        findings.add(new DoctorFinding(DoctorFindingCode.UPDATE_TRANSACTION_INCOMPLETE, DoctorSeverity.WARNING,
                                DoctorConfidence.CONFIRMED, "Migration transaction incomplete", "A prepared update or migration journal has not reached a terminal state.",
                                "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                                computeHash(journal.getFileName().toString()), Map.of()));
                    }
                } catch (Exception corrupt) {
                    findings.add(new DoctorFinding(DoctorFindingCode.MIGRATION_STATE_INCOMPLETE, DoctorSeverity.ERROR,
                            DoctorConfidence.CONFIRMED, "Migration journal corrupt", "Migration transaction evidence is malformed and requires review.",
                            "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION,
                            computeHash(journal.getFileName().toString() + "_corrupt"), Map.of()));
                }
            }
        } catch (IOException ignored) {
        }
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
