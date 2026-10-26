package org.synesis.workspace.doctor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ReplicationSettings;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.project.ProjectPolicyService;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.infrastructure.process.ProcessInspector;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupEligibilityService;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecycleInventoryService;
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
 * zero file deletions, zero process terminations, and zero lock acquisitions. The only process it starts is
 * {@code git --version}; the connected agents' own status checks and the operator-request projection only read.
 *
 * @since 1.0
 */
public final class DoctorService {

    /**
     * Everything about a finding that depends only on its code. The evidence fingerprint seed and
     * details vary per instance and are supplied to {@link #finding}.
     */
    private record Facts(DoctorSeverity severity, DoctorConfidence confidence, String summary, String explanation, String affectedResourceType, boolean repairSupported,
                         DoctorRecommendation recommendation, DoctorGroup group, String fix) {
    }

    /**
     * The group of the findings that existed before the groups did and do not belong to the project itself.
     */
    private static final Map<DoctorFindingCode, DoctorGroup> EARLIER_GROUPS = Map.of(
            DoctorFindingCode.MISSING_INSTALLED_LAUNCHER, DoctorGroup.ENVIRONMENT,
            DoctorFindingCode.PROVIDER_MIGRATION_REQUIRED, DoctorGroup.ENVIRONMENT,
            DoctorFindingCode.PROVIDER_CONFIG_MALFORMED, DoctorGroup.ENVIRONMENT,
            DoctorFindingCode.PROVIDER_CONFIG_SYNSESIS_ENTRY_DUPLICATED, DoctorGroup.ENVIRONMENT,
            DoctorFindingCode.STALE_SESSION_LEASE, DoctorGroup.WORK_GROUPS,
            DoctorFindingCode.AMBIGUOUS_SESSION_LIVENESS, DoctorGroup.WORK_GROUPS,
            DoctorFindingCode.CLEANUP_RECOMMENDED, DoctorGroup.WORK_GROUPS);

    /**
     * A finding that existed before the groups: its fix is its recommendation's.
     */
    private static Map.Entry<DoctorFindingCode, Facts> facts(DoctorFindingCode code, DoctorSeverity severity, DoctorConfidence confidence, String summary, String explanation, String affectedResourceType,
            boolean repairSupported, DoctorRecommendation recommendation) {
        return Map.entry(code, new Facts(severity, confidence, summary, explanation, affectedResourceType, repairSupported, recommendation, EARLIER_GROUPS.getOrDefault(code, DoctorGroup.PROJECT), null));
    }

    /**
     * A finding of one of the groups, with the one command or step that resolves it.
     */
    private static Map.Entry<DoctorFindingCode, Facts> facts(DoctorGroup group, DoctorFindingCode code, DoctorSeverity severity, DoctorConfidence confidence, String summary, String explanation,
            String affectedResourceType, DoctorRecommendation recommendation, String fix) {
        return Map.entry(code, new Facts(severity, confidence, summary, explanation, affectedResourceType, false, recommendation, group, fix));
    }

    private static final Map<DoctorFindingCode, Facts> FACTS = Map.ofEntries(
            facts(DoctorGroup.ENVIRONMENT, DoctorFindingCode.GIT_MISSING, DoctorSeverity.ERROR, DoctorConfidence.CONFIRMED, "Git is not available", "Running 'git --version' failed, so Synesis cannot work with any repository.",
                    "git", DoctorRecommendation.HUMAN_REVIEW_REQUIRED, "install Git from https://git-scm.com, then open a new terminal and run 'synesis doctor' again"),
            facts(DoctorGroup.ENVIRONMENT, DoctorFindingCode.AGENT_NOT_ON_PATH, DoctorSeverity.WARNING, DoctorConfidence.SUSPECTED, "A connected agent is not on the PATH",
                    "The agent connected to this project was not found on the PATH of this terminal; it may still start from where you normally launch it.", "agent", DoctorRecommendation.HUMAN_REVIEW_REQUIRED,
                    "install the agent, or add its folder to the PATH, then open a new terminal"),
            facts(DoctorGroup.ENVIRONMENT, DoctorFindingCode.PROVIDER_FILES_STALE, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "A connected agent's Synesis files are out of date",
                    "The hook, launcher, profile or skill Synesis wrote for the agent is missing or does not match this Synesis.", "provider_config", DoctorRecommendation.PREPARE_REPAIR_PLAN,
                    "run 'synesis doctor --fix', which writes the agent's managed files again like 'synesis provider install <provider>'"),
            facts(DoctorGroup.NETWORK, DoctorFindingCode.PROJECT_RUNTIME_NOT_RUNNING, DoctorSeverity.WARNING, DoctorConfidence.SUSPECTED, "The project's runtime is not running",
                    "This project is shared with another computer, and the installation daemon holds no runtime for it; a runtime started by hand with 'synesis coordination serve' would not show.", "runtime", DoctorRecommendation.PREPARE_REPAIR_PLAN,
                    "run 'synesis doctor --fix' to start it"),
            facts(DoctorGroup.NETWORK, DoctorFindingCode.LINK_FIREWALL_MISSING, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "No firewall rule lets another computer connect",
                    "Windows Firewall has no 'Synesis Link' rule, so another computer cannot dial this one.", "firewall", DoctorRecommendation.HUMAN_REVIEW_REQUIRED,
                    "run the Synesis installer again with 'Let other computers connect to this one' ticked; 'synesis link invite' prints the exact netsh command"),
            facts(DoctorGroup.NETWORK, DoctorFindingCode.LINK_FIREWALL_STALE, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "The firewall rule names another Synesis version",
                    "The 'Synesis Link' rule names another version's java.exe, so another computer cannot dial this one.", "firewall", DoctorRecommendation.HUMAN_REVIEW_REQUIRED,
                    "run the Synesis installer again with 'Let other computers connect to this one' ticked; 'synesis link invite' prints the exact netsh command"),
            facts(DoctorGroup.WORK_GROUPS, DoctorFindingCode.OPERATOR_REQUESTS_OPEN, DoctorSeverity.INFO, DoctorConfidence.ADVICE, "The project is waiting for a person",
                    "One or more operator requests are open: work that stopped until a person answers.", "operator_request", DoctorRecommendation.NO_ACTION, "run 'synesis operator list'"),
            facts(DoctorGroup.PROJECT, DoctorFindingCode.PROJECT_FILE_POLICY_IGNORED, DoctorSeverity.INFO, DoctorConfidence.ADVICE, "The project file disagrees with the signed policy",
                    "The validation or review in .synesis/project.json differs from the policy the owner signed; the signed policy is used and the file is ignored.", "project_file", DoctorRecommendation.HUMAN_REVIEW_REQUIRED,
                    "run 'synesis project policy show' to see the policy in force, and restore the file from Git if you did not change it"),
            facts(DoctorGroup.PROJECT, DoctorFindingCode.POLICY_NOT_RECEIVED, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "The owner's signed policy has not arrived",
                    "This computer has not applied the owner's signed policy, so it will not integrate or run a gate until it does.", "policy", DoctorRecommendation.HUMAN_REVIEW_REQUIRED,
                    "connect this computer to the owner's computer and wait for the log to sync"),
            facts(DoctorGroup.PROJECT, DoctorFindingCode.POLICY_ADOPTION_NEEDS_CONFIRMATION, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "The policy in the project file is not signed yet",
                    "The policy in .synesis/project.json differs from the committed one, or is not committed, so nothing was signed.", "policy", DoctorRecommendation.HUMAN_REVIEW_REQUIRED,
                    "run 'synesis project policy adopt --from head' to sign the committed policy, or '--from file' for the working file"),
            facts(DoctorFindingCode.HEALTHY, DoctorSeverity.INFO, DoctorConfidence.CONFIRMED, "System and repository healthy", "All checked components operate within expected parameters.", "project", false,
                    DoctorRecommendation.NO_ACTION),
            facts(DoctorFindingCode.MISSING_INSTALLED_LAUNCHER, DoctorSeverity.WARNING, DoctorConfidence.CONFIRMED, "Missing installed launcher",
                    "The installer's Synesis bin directory exists but holds no launcher.", "installation", false, DoctorRecommendation.REINSTALL_SYNESIS),
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
    private final Supplier<Path> launcherLocation;
    private final ProviderApplicationService providerService = new ProviderApplicationService();
    private final BooleanSupplier gitAvailable;
    private final Predicate<String> executableOnPath;
    private final NetworkProbe network;

    /**
     * Creates a doctor service with default dependencies.
     */
    public DoctorService() {
        this(NetworkProbe.UNKNOWN);
    }

    /**
     * Creates a doctor service that reads the network of a shared project through the given probe.
     *
     * @param network what only the calling process can observe about the runtime and the firewall
     */
    public DoctorService(NetworkProbe network) {
        this(new ProjectApplicationService(), new LifecycleInventoryService(), new CleanupEligibilityService(), new SessionLeaseService(), new SessionLeaseStore(), ProcessInspector.system(),
                new ProviderApplicationService()::launcherPath, DoctorService::gitStarts, DoctorService::onPath, network);
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
        this(projectService, inventoryService, eligibilityService, leaseService, leaseStore, processInspector, new ProviderApplicationService()::launcherPath);
    }

    DoctorService(ProjectApplicationService projectService,
            LifecycleInventoryService inventoryService,
            CleanupEligibilityService eligibilityService,
            SessionLeaseService leaseService,
            SessionLeaseStore leaseStore,
            ProcessInspector processInspector,
            Supplier<Path> launcherLocation) {
        this(projectService, inventoryService, eligibilityService, leaseService, leaseStore, processInspector, launcherLocation, DoctorService::gitStarts, DoctorService::onPath, NetworkProbe.UNKNOWN);
    }

    DoctorService(ProjectApplicationService projectService,
            LifecycleInventoryService inventoryService,
            CleanupEligibilityService eligibilityService,
            SessionLeaseService leaseService,
            SessionLeaseStore leaseStore,
            ProcessInspector processInspector,
            Supplier<Path> launcherLocation,
            BooleanSupplier gitAvailable,
            Predicate<String> executableOnPath,
            NetworkProbe network) {
        this.gitAvailable = Objects.requireNonNull(gitAvailable, "gitAvailable");
        this.executableOnPath = Objects.requireNonNull(executableOnPath, "executableOnPath");
        this.network = Objects.requireNonNull(network, "network");
        this.launcherLocation = Objects.requireNonNull(launcherLocation, "launcherLocation");
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
        return finding(code, fingerprintSeed, details, null);
    }

    /**
     * @param fix what a person does about this instance, or {@code null} for the code's own fix
     */
    private static DoctorFinding finding(DoctorFindingCode code, String fingerprintSeed, Map<String, String> details, String fix) {
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
                details,
                facts.group(),
                fix != null ? fix : facts.fix() != null ? facts.fix() : facts.recommendation().fix());
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

        checkGit(findings);

        if (projectIdStr != null) {
            checkConnectedProviders(root, findings);
        }

        boolean shared = Files.exists(root.resolve(".synesis/coordination").resolve(ReplicationSettings.FILE));
        if (shared) {
            checkNetwork(root, findings);
        }

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

        return new DoctorReport(1, reportId, projectIdStr != null ? projectIdStr : "unbound", now, status, critical, errors, warnings, info, cleanupRec, reconcRec, repairAvail, Collections.unmodifiableList(findings), shared);
    }

    private void checkGit(List<DoctorFinding> findings) {
        if (!gitAvailable.getAsBoolean()) {
            findings.add(finding(DoctorFindingCode.GIT_MISSING, "git_missing"));
        }
    }

    /**
     * Looks at each provider connected to the project, which is one with saved state in it: whether its executable is on
     * the {@code PATH} of this process (suspected only, since the agent may be started from elsewhere), and whether the
     * files Synesis wrote for it are intact. A provider with real-agent validation pending is not stale for that: it is
     * judged by what the status check inspected, not by its state name. No file is read that holds a credential.
     */
    private void checkConnectedProviders(Path root, List<DoctorFinding> findings) {
        ProjectLocation location;
        try {
            location = projectService.locate(root);
        } catch (Exception unreadable) {
            return;
        }
        for (ProviderApplicationService.ProviderRow row : providerService.list(location)) {
            if (!"INSTALLED".equals(row.status())) {
                continue;
            }
            Map<String, String> details = Map.of("provider", row.id());
            if (!executableOnPath.test(row.id())) {
                findings.add(finding(DoctorFindingCode.AGENT_NOT_ON_PATH, "agent_not_on_path_" + row.id(), details,
                        "install " + row.id() + ", or add its folder to the PATH, then open a new terminal"));
            }
            if (filesStale(providerService.status(location, row.id()).values())) {
                findings.add(finding(DoctorFindingCode.PROVIDER_FILES_STALE, "provider_files_stale_" + row.id(), details,
                        "run 'synesis doctor --fix', which writes " + row.id() + "'s managed files again like 'synesis provider install " + row.id() + "'"));
            }
        }
    }

    private static boolean filesStale(Map<String, String> status) {
        String state = status.get("PROVIDER_STATUS");
        return "BROKEN".equals(state) || status.containsKey("ERROR") || "DEGRADED".equals(state) && "NOT_RUN".equals(status.get("SYNTHETIC_BLOCK_CHECK"));
    }

    private void checkNetwork(Path root, List<DoctorFinding> findings) {
        if (network.runtimeRunning(root).filter(running -> !running).isPresent()) {
            findings.add(finding(DoctorFindingCode.PROJECT_RUNTIME_NOT_RUNNING, "project_runtime_not_running"));
        }
        network.firewall().ifPresent(problem -> findings.add(problem == NetworkProbe.Firewall.MISSING
                ? finding(DoctorFindingCode.LINK_FIREWALL_MISSING, "link_firewall_missing") : finding(DoctorFindingCode.LINK_FIREWALL_STALE, "link_firewall_stale")));
    }

    private static boolean gitStarts() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Whether a program of this name sits in a {@code PATH} directory, with the extensions Windows starts.
     */
    private static boolean onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : windows ? List.of(program + ".exe", program + ".cmd", program + ".bat") : List.of(program)) {
                Path candidate = Path.of(directory, name);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Reports an installation whose launcher is gone. The launcher is looked for where provider hooks run it from
     * (the {@code PATH}, a configured one, then the installer's own {@code Synesis/bin}); when none exists the answer is
     * a path inside the installer's directory, and only an existing directory there means an installation that lost
     * its launcher, so a machine with no installation is not reported.
     */
    private void checkInstallationAndRuntime(List<DoctorFinding> findings) {
        Path launcher = launcherLocation.get();
        if (!Files.isRegularFile(launcher) && launcher.getParent() != null && Files.isDirectory(launcher.getParent())) {
            findings.add(finding(DoctorFindingCode.MISSING_INSTALLED_LAUNCHER, "missing_launcher"));
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
            try {
                return projectService.locate(root).projectId().toString();
            } catch (ProjectApplicationService.ProjectApplicationException unreadablePolicy) {
                if (!"POLICY_UNAVAILABLE".equals(unreadablePolicy.code())) {
                    throw unreadablePolicy;
                }
                // The identity is the file's; the log that makes the policy unreadable is reported as the durable-state finding.
                return migration.projectId().isBlank() ? null : migration.projectId();
            }
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
                PredictionEventStore store = new PredictionEventStore(coordDir, pId);
                // If loaded without exception, store signatures & chain are verified
                checkOperatorRequests(store, findings);
                checkPolicy(root, findings);
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

    /**
     * What the signed policy says about the project file and this computer (ADR-0104). Nothing is written: a person signs
     * a decision, and doctor never does.
     */
    private void checkPolicy(Path root, List<DoctorFinding> findings) {
        try {
            ProjectLocation location = projectService.locate(root);
            if (!location.policyReceived()) {
                findings.add(finding(DoctorFindingCode.POLICY_NOT_RECEIVED, "policy_not_received"));
                return;
            }
            ProjectPolicyService.fileDisagreement(location).ifPresent(detail -> findings.add(finding(DoctorFindingCode.PROJECT_FILE_POLICY_IGNORED, "project_file_policy_ignored", Map.of("detail", detail))));
            if (ProjectPolicyService.read(location).snapshot().isEmpty() && ProjectPolicyService.adoption(location).needsConfirmation()) {
                findings.add(finding(DoctorFindingCode.POLICY_ADOPTION_NEEDS_CONFIRMATION, "policy_adoption_needs_confirmation"));
            }
        } catch (Exception unreadable) {
            // A policy that cannot be read is reported by the durable-state findings.
        }
    }

    /**
     * Advice, not a defect: an open operator request is work waiting for a person. A projection that cannot be read adds
     * nothing here, since the store was just verified.
     */
    private static void checkOperatorRequests(PredictionEventStore store, List<DoctorFinding> findings) {
        try {
            int open = store.operatorRequestProjection().open().size();
            if (open > 0) {
                findings.add(finding(DoctorFindingCode.OPERATOR_REQUESTS_OPEN, "operator_requests_open", Map.of("open", Integer.toString(open))));
            }
        } catch (RuntimeException unreadable) {
            // Left to the durable-state findings.
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
        Path adminDir = ProjectAdministrativeLayout.adminDirectory(root);

        if (Files.isDirectory(adminDir)) {
            // Cleanup lock check
            Path cleanupLock = ProjectAdministrativeLayout.cleanupExecutionLock(root);
            if (Files.exists(cleanupLock) && isLockStale(cleanupLock)) {
                findings.add(finding(DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK, "stale_cleanup_lock"));
            }

            // Reconciliation lock check
            Path reconcLock = ProjectAdministrativeLayout.reconciliationExecutionLock(root);
            if (Files.exists(reconcLock) && isLockStale(reconcLock)) {
                findings.add(finding(DoctorFindingCode.STALE_RECONCILIATION_EXECUTION_LOCK, "stale_reconc_lock"));
            }

            // Repair lock check
            Path repairLock = ProjectAdministrativeLayout.repairExecutionLock(root);
            if (Files.exists(repairLock) && isLockStale(repairLock)) {
                findings.add(finding(DoctorFindingCode.STALE_REPAIR_LOCK, "stale_repair_lock"));
            }

            // Corrupt cleanup plans
            checkCorruptJsonFiles(ProjectAdministrativeLayout.cleanupPlans(root), DoctorFindingCode.CORRUPT_CLEANUP_PLAN, findings);
            // Corrupt reconciliation plans
            checkCorruptJsonFiles(ProjectAdministrativeLayout.reconciliationPlans(root), DoctorFindingCode.CORRUPT_RECONCILIATION_PLAN, findings);
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
