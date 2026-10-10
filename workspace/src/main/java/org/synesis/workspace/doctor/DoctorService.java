package org.synesis.workspace.doctor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupEligibilityService;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleInventoryService;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;
import org.synesis.workspace.lifecycle.command.ProjectCommandDiagnostics;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.workspace.migration.ProjectMigrationService;
import org.synesis.workspace.migration.ProviderConfigMigrationService;

/**
 * Primary read-only diagnostic service discovering repository, runtime, durable state, and
 * administrative health.
 *
 * <p>DoctorService is read-only by construction: it performs zero file creations, zero file
 * modifications,
 * zero file deletions, zero process terminations, and zero lock acquisitions.
 *
 * @since 1.0
 */
public final class DoctorService {

    /**
     * Everything about a finding that depends only on its code. The evidence fingerprint seed and
     * details vary per instance and are supplied to {@link #finding}.
     */
    private record Facts(DoctorSeverity severity, DoctorConfidence confidence, String summary, String explanation, String affectedResourceType, boolean repairSupported,
                         DoctorRecommendation recommendation) {
    }

    private static Map.Entry<DoctorFindingCode, Facts> facts(DoctorFindingCode code, DoctorSeverity severity, DoctorConfidence confidence, String summary, String explanation, String affectedResourceType,
            boolean repairSupported, DoctorRecommendation recommendation) {
        return Map.entry(code, new Facts(severity, confidence, summary, explanation, affectedResourceType, repairSupported, recommendation));
    }

    private static final Map<DoctorFindingCode, Facts> FACTS = Map.ofEntries(
            facts(DoctorFindingCode.HEALTHY, DoctorSeverity.INFO, DoctorConfidence.CONFIRMED, "System and repository healthy", "All checked components operate within expected parameters.", "project", false,
                    DoctorRecommendation.NO_ACTION),
            facts(DoctorFindingCode.MISSING_INSTALLED_LAUNCHER, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Missing installed launcher",
                    "Installed Synesis bin directory does not contain expected launcher script.", "installation", false, DoctorRecommendation.REINSTALL_SYNESIS),
            facts(DoctorFindingCode.PROJECT_NOT_INITIALIZED, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Project not initialized", "Directory is missing .synesis/project.json configuration.", "project", false,
                    DoctorRecommendation.HUMAN_REVIEW_REQUIRED),
            facts(DoctorFindingCode.PROJECT_SCHEMA_UNSUPPORTED, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Project schema unsupported", "Project metadata uses a schema this Synesis build cannot migrate.",
                    "project_schema", false, DoctorRecommendation.PREPARE_PROJECT_MIGRATION),
            facts(DoctorFindingCode.PROJECT_IDENTITY_INVALID, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Invalid project identity", "Failed to locate or parse valid Synesis project identity.", "project", false,
                    DoctorRecommendation.HUMAN_REVIEW_REQUIRED),
            facts(DoctorFindingCode.EVENT_LOG_VERIFICATION_FAILURE, DoctorSeverity.CRITICAL, DoctorConfidence.CONFIRMED, "Event log verification failure",
                    "Signed coordination event log cryptographic signature or digest chain verification failed.", "event_log", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED),
            facts(DoctorFindingCode.DURABLE_STATE_AMBIGUOUS, DoctorSeverity.ERROR, DoctorConfidence.AMBIGUOUS, "Durable state ambiguous", "Failed to read durable coordination event store.", "event_log", false,
                    DoctorRecommendation.HUMAN_REVIEW_REQUIRED),
            facts(DoctorFindingCode.STALE_SESSION_LEASE, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE, "Stale session lease detected",
                    "Provider session lease has missed heartbeats beyond policy threshold.", "session_lease", false, DoctorRecommendation.PREPARE_RECONCILIATION_PLAN),
            facts(DoctorFindingCode.AMBIGUOUS_SESSION_LIVENESS, DoctorSeverity.WARNING, DoctorConfidence.AMBIGUOUS, "Ambiguous session liveness",
                    "Process identity or liveness state for provider session lease is ambiguous.", "session_lease", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED),
            facts(DoctorFindingCode.CLEANUP_RECOMMENDED, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Cleanup recommended", "Eligible lifecycle resources are ready for cleanup review.", "cleanup", false,
                    DoctorRecommendation.RUN_CLEANUP_DRY_RUN),
            facts(DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE, "Stale cleanup execution lock",
                    "Stale cleanup-execution.lock file present from absent process.", "admin_lock", true, DoctorRecommendation.PREPARE_REPAIR_PLAN),
            facts(DoctorFindingCode.STALE_RECONCILIATION_EXECUTION_LOCK, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE, "Stale reconciliation execution lock",
                    "Stale reconciliation-execution.lock file present from absent process.", "admin_lock", true, DoctorRecommendation.PREPARE_REPAIR_PLAN),
            facts(DoctorFindingCode.STALE_REPAIR_LOCK, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE, "Stale repair execution lock", "Stale repair-execution.lock file present from absent process.",
                    "admin_lock", true, DoctorRecommendation.PREPARE_REPAIR_PLAN),
            facts(DoctorFindingCode.CORRUPT_CLEANUP_PLAN, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Corrupt cleanup plan file corrupted", "Corrupt cleanup plan file contains malformed JSON.", "admin_plan",
                    true, DoctorRecommendation.PREPARE_REPAIR_PLAN),
            facts(DoctorFindingCode.CORRUPT_RECONCILIATION_PLAN, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Corrupt reconciliation plan file corrupted",
                    "Corrupt reconciliation plan file contains malformed JSON.", "admin_plan", true, DoctorRecommendation.PREPARE_REPAIR_PLAN),
            facts(DoctorFindingCode.COMMAND_NAMESPACE_UNSAFE, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Durable command namespace unsafe",
                    "Command records, anchors, locks, or compatibility metadata require fail-closed review.", "command_namespace", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED),
            facts(DoctorFindingCode.COMMAND_NAMESPACE_RECONCILIATION_REQUIRED, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE, "Durable command namespace needs reconciliation",
                    "The rebuildable command index or compatibility state is not fully current.", "command_namespace", true, DoctorRecommendation.PREPARE_REPAIR_PLAN),
            facts(DoctorFindingCode.COMMAND_CAPACITY_OR_RETENTION, DoctorSeverity.WARNING, DoctorConfidence.HIGH_CONFIDENCE, "Durable command retention requires review",
                    "Terminal evidence, dead anchors, pins, or live request capacity require the existing cleanup workflow.", "command_namespace", false, DoctorRecommendation.RUN_CLEANUP_DRY_RUN),
            facts(DoctorFindingCode.COMMAND_ADMISSION_DEFERRED, DoctorSeverity.WARNING, DoctorConfidence.AMBIGUOUS, "Durable command admission deferred",
                    "A lease-gap or protected mutation requires reconciliation before mutation resumes.", "command_namespace", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED),
            facts(DoctorFindingCode.PROVIDER_MIGRATION_REQUIRED, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Provider migration required",
                    "Provider MCP configuration does not reference the stable Synesis launcher.", "provider_config", false, DoctorRecommendation.PREPARE_PROVIDER_MIGRATION),
            facts(DoctorFindingCode.PROVIDER_CONFIG_MALFORMED, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Provider config malformed", "Provider MCP configuration is malformed and was not changed.",
                    "provider_config", false, DoctorRecommendation.REVIEW_PROVIDER_CONFIGURATION),
            facts(DoctorFindingCode.PROVIDER_CONFIG_SYNSESIS_ENTRY_DUPLICATED, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Duplicate Synesis provider entries",
                    "Provider configuration contains ambiguous Synesis MCP entries.", "provider_config", false, DoctorRecommendation.HUMAN_REVIEW_REQUIRED),
            facts(DoctorFindingCode.POST_MIGRATION_REPLAY_FAILED, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Post-migration replay failed",
                    "Project migration replay did not prove semantic equivalence.", "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION),
            facts(DoctorFindingCode.MIGRATION_BACKUP_MISSING, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Migration backup unavailable", "Migration rollback evidence is incomplete.",
                    "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION),
            facts(DoctorFindingCode.ACTIVE_SESSION_BLOCKS_MIGRATION, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Active session blocks migration",
                    "Project migration is waiting for incompatible session state to quiesce.", "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION),
            facts(DoctorFindingCode.PROJECT_RESTORATION_PENDING, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Project restoration pending",
                    "Project metadata restoration has not reached a verified terminal state.", "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION),
            facts(DoctorFindingCode.PROJECT_RESTORATION_REQUIRES_REVIEW, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Project restoration requires review",
                    "Project metadata restoration could not be proven safe.", "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION),
            facts(DoctorFindingCode.PROJECT_MIGRATION_RESTORED, DoctorSeverity.INFO, DoctorConfidence.CONFIRMED, "Failed migration restored", "Project metadata was restored after migration failure.",
                    "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION),
            facts(DoctorFindingCode.ROLLBACK_RESTORATION_INCOMPLETE, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Rollback restoration incomplete",
                    "Rollback did not reach a verified restoration terminal state.", "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION),
            facts(DoctorFindingCode.UPDATE_TRANSACTION_INCOMPLETE, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Migration transaction incomplete",
                    "A prepared update or migration journal has not reached a terminal state.", "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION),
            facts(DoctorFindingCode.MIGRATION_STATE_INCOMPLETE, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Migration journal corrupt",
                    "Migration transaction evidence is malformed and requires review.", "migration_transaction", false, DoctorRecommendation.REVIEW_UPDATE_TRANSACTION));

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
        this(new ProjectApplicationService(), new LifecycleInventoryService(), new CleanupEligibilityService(), new SessionLeaseService(), new SessionLeaseStore(), ProcessInspector.system());
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
    public DoctorService(ProjectApplicationService projectService,
            LifecycleInventoryService inventoryService,
            CleanupEligibilityService eligibilityService,
            SessionLeaseService leaseService,
            SessionLeaseStore leaseStore,
            ProcessInspector processInspector) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
        this.eligibilityService = Objects.requireNonNull(eligibilityService, "eligibilityService");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
        this.processInspector = Objects.requireNonNull(processInspector, "processInspector");
    }

    /**
     * Builds a finding from the code's static facts, the seed of its evidence fingerprint, and the
     * per-instance details.
     */
    private static DoctorFinding finding(DoctorFindingCode code, String fingerprintSeed, Map<String, String> details) {
        Facts facts = Objects.requireNonNull(FACTS.get(code), () -> "no facts for " + code);
        return new DoctorFinding(code,
                facts.severity(),
                facts.confidence(),
                facts.summary(),
                facts.explanation(),
                facts.affectedResourceType(),
                facts.repairSupported(),
                facts.recommendation(),
                Sha256.hex(fingerprintSeed),
                details);
    }

    private static DoctorFinding finding(DoctorFindingCode code, String fingerprintSeed) {
        return finding(code, fingerprintSeed, Map.of());
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

        checkInstallationAndRuntime(findings);

        String projectIdStr = checkProjectAndRepository(root, findings);

        checkDurableState(root, projectIdStr, findings);

        checkSessionLeases(root, findings);

        checkCleanupAndStorage(root, findings);

        checkAdministrativeState(root, findings);

        checkMigrationTransactions(findings);

        checkProviderConfiguration(root, findings);

        if (findings.isEmpty()) {
            findings.add(finding(DoctorFindingCode.HEALTHY, "healthy"));
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

        boolean cleanupRec = findings.stream().anyMatch(f -> f.code() == DoctorFindingCode.CLEANUP_RECOMMENDED);
        boolean reconcRec = findings.stream().anyMatch(f -> f.code() == DoctorFindingCode.STALE_SESSION_LEASE);
        boolean repairAvail = findings.stream().anyMatch(DoctorFinding::repairSupported);

        return new DoctorReport(1, reportId, projectIdStr != null ? projectIdStr : "unbound", now, status, critical, errors, warnings, info, cleanupRec, reconcRec, repairAvail, Collections.unmodifiableList(findings));
    }

    private void checkInstallationAndRuntime(List<DoctorFinding> findings) {
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path synesisBin = Path.of(userHome, ".synesis", "bin");
            if (Files.isDirectory(synesisBin)) {
                Path launcherFile = synesisBin.resolve("synesis.bat");
                if (!Files.exists(launcherFile)) {
                    launcherFile = synesisBin.resolve("synesis");
                }
                if (!Files.exists(launcherFile)) {
                    findings.add(finding(DoctorFindingCode.MISSING_INSTALLED_LAUNCHER, "missing_launcher"));
                }
            }
        }
    }

    private String checkProjectAndRepository(Path root, List<DoctorFinding> findings) {
        Path projectJson = root.resolve(".synesis/project.json");
        if (!Files.exists(projectJson)) {
            findings.add(finding(DoctorFindingCode.PROJECT_NOT_INITIALIZED, "proj_not_init"));
            return null;
        }

        try {
            ProjectMigrationService.Entry migration = new ProjectMigrationService().inspect(root);
            if (migration.outcome() == ProjectMigrationService.Outcome.UNSUPPORTED_SCHEMA) {
                findings.add(finding(DoctorFindingCode.PROJECT_SCHEMA_UNSUPPORTED, "project_schema_unsupported"));
                return migration.projectId().isBlank() ? null : migration.projectId();
            }
            ProjectLocation location = projectService.locate(root);
            return location.projectId().toString();
        } catch (Exception ex) {
            findings.add(finding(DoctorFindingCode.PROJECT_IDENTITY_INVALID, "proj_invalid"));
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
                new PredictionEventStore(coordDir, pId);
                // If loaded without exception, store signatures & chain are verified
            } catch (Exception ex) {
                DoctorFindingCode failureCode = DoctorFindingCode.classifyDurableStateFailure(ex);
                if (failureCode == DoctorFindingCode.EVENT_LOG_VERIFICATION_FAILURE) {
                    findings.add(finding(failureCode, "event_log_fail"));
                } else {
                    findings.add(finding(failureCode, "durable_ambiguous"));
                }
            }
        }
    }

    private void checkSessionLeases(Path root, List<DoctorFinding> findings) {
        List<SessionLeaseRecord> leases = leaseStore.listAll(root);
        SessionLeasePolicy policy = new SessionLeasePolicy();

        for (SessionLeaseRecord lease : leases) {
            SessionLeaseState state = leaseService.evaluateLiveness(lease, policy);
            if (state == SessionLeaseState.RECOVERY_ELIGIBLE || state == SessionLeaseState.SUSPECTED_STALE) {
                findings.add(finding(DoctorFindingCode.STALE_SESSION_LEASE, "stale_lease_" + lease.connectionInstanceId()));
            } else if (state == SessionLeaseState.AMBIGUOUS) {
                findings.add(finding(DoctorFindingCode.AMBIGUOUS_SESSION_LIVENESS, "ambiguous_lease_" + lease.connectionInstanceId()));
            }
        }
    }

    private void checkCleanupAndStorage(Path root, List<DoctorFinding> findings) {
        try {
            var inventory = inventoryService.discoverResources(root);
            for (var resource : inventory) {
                var entry = eligibilityService.evaluateResource(root, resource);
                if (entry.eligible()) {
                    findings.add(finding(DoctorFindingCode.CLEANUP_RECOMMENDED, "cleanup_rec"));
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void checkAdministrativeState(Path root, List<DoctorFinding> findings) {
        checkCommandNamespace(findings);
        Path workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(root);
        Path adminDir = workspaceRoot.resolve("admin");

        if (Files.isDirectory(adminDir)) {
            // Cleanup lock check
            Path cleanupLock = adminDir.resolve("cleanup-execution.lock");
            if (Files.exists(cleanupLock) && isLockStale(cleanupLock)) {
                findings.add(finding(DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK, "stale_cleanup_lock"));
            }

            // Reconciliation lock check
            Path reconcLock = adminDir.resolve("reconciliation-execution.lock");
            if (Files.exists(reconcLock) && isLockStale(reconcLock)) {
                findings.add(finding(DoctorFindingCode.STALE_RECONCILIATION_EXECUTION_LOCK, "stale_reconc_lock"));
            }

            // Repair lock check
            Path repairLock = adminDir.resolve("repair-execution.lock");
            if (Files.exists(repairLock) && isLockStale(repairLock)) {
                findings.add(finding(DoctorFindingCode.STALE_REPAIR_LOCK, "stale_repair_lock"));
            }

            // Corrupt cleanup plans
            checkCorruptJsonFiles(adminDir.resolve("cleanup-plans"), DoctorFindingCode.CORRUPT_CLEANUP_PLAN, findings);
            // Corrupt reconciliation plans
            checkCorruptJsonFiles(adminDir.resolve("reconciliation-plans"), DoctorFindingCode.CORRUPT_RECONCILIATION_PLAN, findings);
        }
    }

    private void checkCommandNamespace(List<DoctorFinding> findings) {
        ProjectCommandDiagnostics.Report report = ProjectCommandDiagnostics.inspect(AdministrativeStateLocator.applicationStateRoot().resolve("commands"));
        if (!report.present()) {
            return;
        }
        Map<String, String> details = report.doctorDetails();
        if (!report.formatValid() || report.newerObjectCount() > 0 || report.corruptObjectCount() > 0) {
            findings.add(finding(DoctorFindingCode.COMMAND_NAMESPACE_UNSAFE, "command_namespace_unsafe", details));
        } else if (!report.enumerationComplete() || report.staleIndexCount() > 0 || report.olderFormatCount() > 0) {
            findings.add(finding(DoctorFindingCode.COMMAND_NAMESPACE_RECONCILIATION_REQUIRED, "command_namespace_reconcile", details));
        }
        if (report.eligibleTerminalCount() > 0 || report.pinnedEvidenceCount() > 0 || report.deadAnchorCount() > 0 || report.liveAtCapacityCount() > 0) {
            findings.add(finding(DoctorFindingCode.COMMAND_CAPACITY_OR_RETENTION, "command_namespace_retention", details));
        }
        if (report.leaseGapRevisionMismatchCount() > 0 || report.deferredMutationCount() > 0 || report.cleanCloseDetachBlockedCount() > 0) {
            findings.add(finding(DoctorFindingCode.COMMAND_ADMISSION_DEFERRED, "command_namespace_deferred", details));
        }
    }

    private boolean isLockStale(Path lockFile) {
        try {
            String content = Files.readString(lockFile, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) ProviderJson.parse(content);
            long pid = ((Number) map.get("pid")).longValue();
            var detailsOpt = processInspector.inspectProcess(pid);
            return detailsOpt.isEmpty() || !detailsOpt.get().isLive();
        } catch (Exception ex) {
            return false;
        }
    }

    private void checkCorruptJsonFiles(Path dir, DoctorFindingCode code, List<DoctorFinding> findings) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    ProviderJson.parse(raw);
                } catch (Exception corrupt) {
                    findings.add(finding(code, file.getFileName().toString()));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void checkProviderConfiguration(Path projectRoot, List<DoctorFinding> findings) {
        // Global provider configuration is not a defect in an unrelated or
        // freshly initialized project. Scope migration diagnostics to projects
        // that actually have a durable Synesis provider installation record.
        Path providerState = projectRoot.resolve(".synesis/local/providers");
        try {
            if (!Files.isDirectory(providerState)) {
                return;
            }
            try (var files = Files.list(providerState)) {
                if (files.noneMatch(path -> path.getFileName().toString().endsWith(".json"))) {
                    return;
                }
            }
        } catch (IOException ignored) {
            return;
        }
        for (ProviderConfigMigrationService.Entry entry : new ProviderConfigMigrationService().inspect()) {
            Map<String, String> details = Map.of("provider", entry.provider());
            if (entry.outcome() == ProviderConfigMigrationService.Outcome.MIGRATION_REQUIRED) {
                findings.add(finding(DoctorFindingCode.PROVIDER_MIGRATION_REQUIRED, entry.provider(), details));
            } else if (entry.outcome() == ProviderConfigMigrationService.Outcome.MALFORMED) {
                findings.add(finding(DoctorFindingCode.PROVIDER_CONFIG_MALFORMED, entry.provider() + "_malformed", details));
            } else if (entry.outcome() == ProviderConfigMigrationService.Outcome.DUPLICATE_SYNSESIS_ENTRY) {
                findings.add(finding(DoctorFindingCode.PROVIDER_CONFIG_SYNSESIS_ENTRY_DUPLICATED, entry.provider() + "_duplicate", details));
            }
        }
    }

    private void checkMigrationTransactions(List<DoctorFinding> findings) {
        for (Path directory : ProjectMigrationService.transactionJournalDirectories()) {
            checkTransactionJournals(directory, findings);
        }
    }

    private void checkTransactionJournals(Path directory, List<DoctorFinding> findings) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            for (Path journal : stream.filter(path -> path.getFileName().toString().endsWith(".jsonl")).toList()) {
                String name = journal.getFileName().toString();
                try {
                    for (JournalState state : classifyJournal(readJournal(journal))) {
                        findings.add(finding(state.code(), name + state.seedSuffix()));
                    }
                } catch (Exception corrupt) {
                    findings.add(finding(DoctorFindingCode.MIGRATION_STATE_INCOMPLETE, name + "_corrupt"));
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Reads a transaction journal and checks that every line is one of the three journal dialects:
     * {@code outcome=...}, {@code state=...}, or a JSON object.
     */
    private static List<String> readJournal(Path journal) throws IOException {
        List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("empty journal");
        }
        for (String line : lines) {
            if (!line.contains("outcome=") && !line.contains("state=") && !(ProviderJson.parse(line) instanceof Map<?, ?>)) {
                throw new IOException("invalid journal entry");
            }
        }
        return lines;
    }

    /**
     * One finding a journal's content yields; {@code seedSuffix} is appended to the journal file
     * name to form the evidence fingerprint seed.
     */
    record JournalState(DoctorFindingCode code, String seedSuffix) {
    }

    /**
     * Classifies a validated journal by the markers its writers leave in it, in emission order.
     *
     * @param lines non-empty journal lines
     * @return the findings the journal yields
     */
    static List<JournalState> classifyJournal(List<String> lines) {
        String text = String.join("\n", lines);
        List<JournalState> states = new ArrayList<>();
        if (text.contains("post_migration_replay")) {
            states.add(new JournalState(DoctorFindingCode.POST_MIGRATION_REPLAY_FAILED, "_replay"));
        }
        if (text.contains("backup_missing") || text.contains("restore_failed")) {
            states.add(new JournalState(DoctorFindingCode.MIGRATION_BACKUP_MISSING, "_backup"));
        }
        if (text.contains("active_session_blocks_project_migration")) {
            states.add(new JournalState(DoctorFindingCode.ACTIVE_SESSION_BLOCKS_MIGRATION, "_session"));
        }
        if (text.contains("RESTORE_REQUIRED") && !text.contains("RESTORE_VERIFIED") && !text.contains("RESTORE_FAILED_REQUIRES_REVIEW")) {
            states.add(new JournalState(DoctorFindingCode.PROJECT_RESTORATION_PENDING, "_restore_pending"));
        }
        if (text.contains("RESTORE_FAILED_REQUIRES_REVIEW")) {
            states.add(new JournalState(DoctorFindingCode.PROJECT_RESTORATION_REQUIRES_REVIEW, "_restore_review"));
        }
        if (text.contains("FAILED_RESTORED")) {
            states.add(new JournalState(DoctorFindingCode.PROJECT_MIGRATION_RESTORED, "_restored"));
        }
        if (text.contains("ROLLBACK_PENDING") || text.contains("ROLLBACK_REQUIRES_HUMAN_REVIEW")) {
            states.add(new JournalState(DoctorFindingCode.ROLLBACK_RESTORATION_INCOMPLETE, "_rollback"));
        }
        String last = lines.getLast();
        if (!last.contains("COMPLETED") && !last.contains("SUCCESS") && !last.contains("ROLLED_BACK") && !last.contains("MIGRATED") && !last.contains("UP_TO_DATE") && !last.contains("FAILED_RESTORED")) {
            states.add(new JournalState(DoctorFindingCode.UPDATE_TRANSACTION_INCOMPLETE, ""));
        }
        return states;
    }
}
