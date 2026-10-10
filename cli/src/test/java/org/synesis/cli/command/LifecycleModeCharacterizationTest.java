package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.command.CliHarness.Result;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Records the mode handling of the lifecycle commands {@code cleanup}, {@code reconcile}, {@code repair}, {@code migrate} and {@code provider migrate}, which differ from each other in small ways.
 *
 * <p>These tests pin current behavior, including odd edges, so a refactor of the shared mode skeleton cannot change it unnoticed.</p>
 */
class LifecycleModeCharacterizationTest {

    private static final List<String> NONE = List.of();
    private static final List<String> CONFLICT = List.of("USAGE_ERROR=CONFLICTING_MODES");

    @TempDir
    Path tmp;
    private String project;

    @BeforeEach
    void initializeProject() throws Exception {
        Path root = tmp.resolve("project");
        Files.createDirectories(root);
        new ProjectApplicationService().init(root);
        project = root.toString();
    }

    private Result run(String... arguments) {
        return CliHarness.run(tmp.resolve("profile"), arguments);
    }

    @Test
    void cleanupWithoutAModeIsRefusedOnStderr() {
        run("cleanup").assertIs(10, NONE, List.of("Cleanup execution is not available in this version without --dry-run, --prepare, --show-plan, or --execute."));
    }

    @Test
    void cleanupRejectsConflictingModes() {
        run("cleanup", "--dry-run", "--prepare").assertIs(2, NONE, CONFLICT);
        run("cleanup", "--show-plan", "a", "--execute", "b").assertIs(2, NONE, CONFLICT);
    }

    @Test
    void cleanupDryRunPlainJsonAndVerbose() {
        run("cleanup", "--dry-run", "--project", project).assertIs(0, List.of("CLEANUP_RESULT=DRY_RUN", "PROJECT=READY", "RESOURCES_DISCOVERED=0", "PROTECTED=0", "ACTIVE=0", "RECOVERABLE=0",
                "DIAGNOSTIC_RETAINED=0", "CLEANUP_ELIGIBLE=0", "ORPHANED=0", "ESTIMATED_RECLAIMABLE_BYTES=0", "MUTATIONS_PERFORMED=0", "NEXT_ACTION=review_with_verbose_output"), NONE);
        run("cleanup", "--dry-run", "--json", "--project", project).assertIs(0, List.of("~\\{\"cleanupResult\":\"DRY_RUN\",\"projectId\":\"" + CliHarness.UUID_SHAPE + "\",\"timestamp\":\\d+,\"discoveredCount\":0,"
                + "\"protectedCount\":0,\"activeCount\":0,\"recoverableCount\":0,\"diagnosticRetainedCount\":0,\"cleanupEligibleCount\":0,\"orphanedCount\":0,\"estimatedReclaimableBytes\":0,"
                + "\"diskBudgetWarning\":false,\"mutationsPerformed\":0,\"entries\":\\[]}"), NONE);
        run("cleanup", "--dry-run", "--verbose", "--project", project).assertIs(0, List.of("CLEANUP_RESULT=DRY_RUN", "~PROJECT_ID=" + CliHarness.UUID_SHAPE, "RESOURCES_DISCOVERED=0", "PROTECTED=0", "ACTIVE=0",
                "RECOVERABLE=0", "DIAGNOSTIC_RETAINED=0", "CLEANUP_ELIGIBLE=0", "ORPHANED=0", "ESTIMATED_RECLAIMABLE_BYTES=0", "DISK_BUDGET_WARNING=false", "MUTATIONS_PERFORMED=0", "--- ENTRIES ---"), NONE);
    }

    @Test
    void cleanupDryRunOnAnUninitializedDirectoryReportsBrokenOnStdout() {
        // Records current behavior: the failure is reported on stdout, not stderr.
        run("cleanup", "--dry-run", "--project", tmp.toString()).assertIs(10, List.of("CLEANUP_RESULT=BROKEN"), NONE);
    }

    @Test
    void cleanupBlankPlanIdIsTreatedAsAModeAndFailsToLoad() {
        run("cleanup", "--show-plan", "", "--project", project).assertIs(10, NONE, List.of("Failed to load cleanup plan: Cleanup plan not found: "));
    }

    @Test
    void reconcileWithoutAModeIsRefusedOnStderr() {
        run("reconcile").assertIs(10, NONE, List.of("Reconciliation execution is not available in this version without --dry-run, --prepare, --show-plan, or --execute."));
    }

    @Test
    void reconcileRejectsConflictingModes() {
        run("reconcile", "--dry-run", "--prepare").assertIs(2, NONE, CONFLICT);
    }

    @Test
    void reconcileDryRunPlainAndJson() {
        run("reconcile", "--dry-run", "--project", project).assertIs(0, List.of("RECONCILIATION_RESULT=DRY_RUN", "SESSIONS_INSPECTED=0", "ACTIVE=0", "SUSPECTED_STALE=0", "RECOVERY_ELIGIBLE=0", "AMBIGUOUS=0",
                "RECOVERABLE_INTEGRATIONS=0", "EXECUTABLE_ACTIONS=0", "MUTATIONS_PERFORMED=0", "NEXT_ACTION=prepare_reconciliation_plan"), NONE);
        run("reconcile", "--dry-run", "--json", "--project", project).assertIs(0, List.of("{\"reconciliationResult\":\"DRY_RUN\",\"sessionsInspected\":0,\"active\":0,\"suspectedStale\":0,"
                + "\"recoveryEligible\":0,\"ambiguous\":0,\"recoverableIntegrations\":0,\"executableActions\":0,\"mutationsPerformed\":0}"), NONE);
    }

    @Test
    void reconcileBlankPlanIdIsTreatedAsAModeAndFailsToLoad() {
        run("reconcile", "--show-plan", "", "--project", project).assertIs(10, NONE, List.of("Failed to load reconciliation plan: Reconciliation plan not found: "));
    }

    @Test
    void repairWithoutAModeRunsTheDryRun() {
        // Records current behavior: unlike cleanup and reconcile, no mode is not refused; it runs the dry-run against the current directory.
        Result result = run("repair");
        assertEquals(0, result.exit());
        assertEquals(List.of(), result.stderr());
        assertEquals("REPAIR_DRY_RUN=COMPLETED", result.stdout().getFirst());
    }

    @Test
    void repairRejectsConflictingModes() {
        run("repair", "--dry-run", "--execute", "x").assertIs(2, NONE, CONFLICT);
        run("repair", "--rollback", "x", "--show-plan", "y").assertIs(2, NONE, CONFLICT);
    }

    @Test
    void repairDryRunOnAFreshProject() {
        run("repair", "--dry-run", "--project", project).assertIs(0, List.of("REPAIR_DRY_RUN=COMPLETED", "FINDINGS=1", "REPAIR_CANDIDATES=0", "MUTATIONS_PERFORMED=0", "NEXT_ACTION=no_action"), NONE);
    }

    @Test
    void repairTreatsABlankModeValueAsNoModeAndRunsTheDryRun() {
        // Records current behavior: a blank plan or execution id selects the mode for conflict checking but is then ignored.
        List<String> dryRun = List.of("REPAIR_DRY_RUN=COMPLETED", "FINDINGS=1", "REPAIR_CANDIDATES=0", "MUTATIONS_PERFORMED=0", "NEXT_ACTION=no_action");
        run("repair", "--show-plan", "", "--project", project).assertIs(0, dryRun, NONE);
        run("repair", "--execute", "", "--project", project).assertIs(0, dryRun, NONE);
        run("repair", "--rollback", "", "--project", project).assertIs(0, dryRun, NONE);
    }

    @Test
    void repairHasNoJsonOption() {
        run("repair", "--json", "--project", project).assertIs(2, NONE, List.of("Usage error in 'repair'. Run 'synesis help' for valid commands and options."));
    }

    @Test
    void migrateWithoutAModeRunsTheDryRunAgainstTheCurrentDirectory() {
        // The command takes no --project; the working directory of the test JVM is the cli module, which is not an initialized project.
        List<String> dryRun = List.of("PROJECT_MIGRATION_RESULT=DRY_RUN", "PROJECT_SCHEMA=-1", "PROJECT_MIGRATION_STATE=PROJECT_NOT_INITIALIZED");
        run("migrate").assertIs(0, dryRun, NONE);
        run("migrate", "--dry-run").assertIs(0, dryRun, NONE);
    }

    @Test
    void migrateRejectsConflictingModes() {
        run("migrate", "--dry-run", "--prepare").assertIs(2, NONE, CONFLICT);
        run("migrate", "--show-plan", "a", "--execute", "b").assertIs(2, NONE, CONFLICT);
    }

    @Test
    void migrateFailuresPrintTheReviewMarkerOnStderr() {
        List<String> review = List.of("PROJECT_MIGRATION_RESULT=REQUIRES_HUMAN_REVIEW");
        run("migrate", "--show-plan", "").assertIs(10, NONE, review);
        run("migrate", "--execute", "").assertIs(10, NONE, review);
    }

    @Test
    void providerMigrateWithoutAModeRunsTheDryRun() {
        // The provider outcome depends on the configuration visible from the working directory, so only its shape is pinned.
        List<String> dryRun = List.of("PROVIDER_MIGRATION_RESULT=DRY_RUN", "PROVIDERS_INSPECTED=1", "~PROVIDER_CODEX=[A-Z_]+");
        run("provider", "migrate").assertIs(0, dryRun, NONE);
        run("provider", "migrate", "--dry-run").assertIs(0, dryRun, NONE);
    }

    @Test
    void providerMigrateRejectsConflictingModes() {
        run("provider", "migrate", "--dry-run", "--prepare").assertIs(2, NONE, CONFLICT);
        run("provider", "migrate", "--show-plan", "a", "--execute", "b").assertIs(2, NONE, CONFLICT);
    }

    @Test
    void providerMigrateFailuresPrintTheReviewMarkerOnStderr() {
        run("provider", "migrate", "--show-plan", "").assertIs(10, NONE, List.of("PROVIDER_MIGRATION_RESULT=REQUIRES_HUMAN_REVIEW"));
    }
}
