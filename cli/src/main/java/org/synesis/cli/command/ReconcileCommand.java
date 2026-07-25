package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.provider.ProviderJson;
import org.synesis.workspace.reconcile.ReconciliationPlan;
import org.synesis.workspace.reconcile.ReconciliationPlanEntry;
import org.synesis.workspace.reconcile.ReconciliationPlanStore;
import org.synesis.workspace.reconcile.ReconciliationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the {@code synesis reconcile} lifecycle discovery, plan preparation, inspection, and execution CLI command.
 *
 * @since 1.0
 */
@Command(name = "reconcile", description = "Inspects process liveness, prepares reconciliation plans, and executes crash recovery.", mixinStandardHelpOptions = true)
public final class ReconcileCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final ReconciliationService service;
    private final ReconciliationPlanStore planStore;

    @Option(names = "--dry-run", description = "Generates a read-only reconciliation inspection without mutating state.")
    private boolean dryRun;

    @Option(names = "--prepare", description = "Prepares and persists an immutable reconciliation plan.")
    private boolean prepare;

    @Option(names = "--show-plan", description = "Displays details of a previously prepared reconciliation plan by plan ID.")
    private String showPlan;

    @Option(names = "--execute", description = "Executes a previously prepared reconciliation plan by plan ID.")
    private String executePlan;

    @Option(names = "--json", description = "Formats output as JSON.")
    private boolean json;

    @Option(names = "--verbose", description = "Outputs detailed per-resource diagnostic details.")
    private boolean verbose;

    @Option(names = "--project", description = "Project directory.")
    private String project;

    /**
     * Creates a reconcile command with runtime and default services.
     *
     * @param runtime CLI runtime
     */
    public ReconcileCommand(CliRuntime runtime) {
        this(runtime, new ReconciliationService(), new ReconciliationPlanStore());
    }

    /**
     * Creates a reconcile command with explicit CLI runtime and services.
     *
     * @param runtime   CLI runtime
     * @param service   reconciliation service
     * @param planStore plan store
     */
    public ReconcileCommand(CliRuntime runtime, ReconciliationService service, ReconciliationPlanStore planStore) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.planStore = java.util.Objects.requireNonNull(planStore, "planStore");
    }

    @Override
    public Integer call() {
        if (!dryRun && !prepare && showPlan == null && executePlan == null) {
            runtime.terminal().stderr("Reconciliation execution is not available in this version without --dry-run, --prepare, --show-plan, or --execute.");
            return ExitCodes.LOCAL_CONFIGURATION;
        }

        Path controlRoot = project != null ? Path.of(project) : Path.of(".");

        if (dryRun) {
            return handleDryRun(controlRoot);
        }

        if (prepare) {
            return handlePrepare(controlRoot);
        }

        if (showPlan != null) {
            return handleShowPlan(controlRoot, showPlan);
        }

        if (executePlan != null) {
            return handleExecute(controlRoot, executePlan);
        }

        return ExitCodes.LOCAL_CONFIGURATION;
    }

    private int handleDryRun(Path controlRoot) {
        try {
            ReconciliationService.ReconciliationDiscoverySummary summary = service.discover(controlRoot);

            if (json) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("reconciliationResult", "DRY_RUN");
                map.put("sessionsInspected", summary.totalSessionsInspected());
                map.put("active", summary.activeCount());
                map.put("suspectedStale", summary.suspectedStaleCount());
                map.put("abandonmentEligible", summary.abandonmentEligibleCount());
                map.put("ambiguous", summary.ambiguousCount());
                map.put("recoverableIntegrations", summary.recoverableIntegrations());
                map.put("executableActions", summary.executableActionsCount());
                map.put("mutationsPerformed", 0);
                runtime.terminal().stdout(ProviderJson.write(map));
                return ExitCodes.OK;
            }

            runtime.terminal().stdout("RECONCILIATION_RESULT=DRY_RUN");
            runtime.terminal().stdout("SESSIONS_INSPECTED=" + summary.totalSessionsInspected());
            runtime.terminal().stdout("ACTIVE=" + summary.activeCount());
            runtime.terminal().stdout("SUSPECTED_STALE=" + summary.suspectedStaleCount());
            runtime.terminal().stdout("ABANDONMENT_ELIGIBLE=" + summary.abandonmentEligibleCount());
            runtime.terminal().stdout("AMBIGUOUS=" + summary.ambiguousCount());
            runtime.terminal().stdout("RECOVERABLE_INTEGRATIONS=" + summary.recoverableIntegrations());
            runtime.terminal().stdout("EXECUTABLE_ACTIONS=" + summary.executableActionsCount());
            runtime.terminal().stdout("MUTATIONS_PERFORMED=0");
            runtime.terminal().stdout("NEXT_ACTION=prepare_reconciliation_plan");

            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Reconciliation dry-run failed: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    private int handlePrepare(Path controlRoot) {
        try {
            ReconciliationPlan plan = service.preparePlan(controlRoot);

            if (json) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("reconciliationResult", "PLAN_PREPARED");
                map.put("planId", plan.planId());
                map.put("sessionsInspected", plan.totalInspectedCount());
                map.put("executableActions", plan.executableCount());
                map.put("mutationsPerformed", 1);
                runtime.terminal().stdout(ProviderJson.write(map));
                return ExitCodes.OK;
            }

            runtime.terminal().stdout("RECONCILIATION_RESULT=PLAN_PREPARED");
            runtime.terminal().stdout("PLAN=" + plan.planId());
            runtime.terminal().stdout("SESSIONS_INSPECTED=" + plan.totalInspectedCount());
            runtime.terminal().stdout("EXECUTABLE_ACTIONS=" + plan.executableCount());
            runtime.terminal().stdout("MUTATIONS_PERFORMED=1");
            runtime.terminal().stdout("NEXT_ACTION=review_plan_then_execute");

            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Failed to prepare reconciliation plan: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    private int handleShowPlan(Path controlRoot, String planId) {
        try {
            ReconciliationPlan plan = planStore.load(controlRoot, planId);

            if (json) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("reconciliationResult", "PLAN_LOADED");
                map.put("planId", plan.planId());
                map.put("executableActions", plan.executableCount());
                map.put("mutationsPerformed", 0);
                runtime.terminal().stdout(ProviderJson.write(map));
                return ExitCodes.OK;
            }

            if (verbose) {
                runtime.terminal().stdout("RECONCILIATION_RESULT=PLAN_LOADED");
                runtime.terminal().stdout("PLAN=" + plan.planId());
                runtime.terminal().stdout("PROJECT_ID=" + plan.projectId());
                runtime.terminal().stdout("CONTENT_HASH=" + plan.contentHash());
                runtime.terminal().stdout("EXECUTABLE_ACTIONS=" + plan.executableCount());
                runtime.terminal().stdout("MUTATIONS_PERFORMED=0");
                runtime.terminal().stdout("--- PERSISTED ACTIONS ---");
                for (ReconciliationPlanEntry entry : plan.entries()) {
                    runtime.terminal().stdout("[" + entry.action() + "] target=" + entry.targetResourceId() + " | executable=" + entry.executable());
                }
                return ExitCodes.OK;
            }

            runtime.terminal().stdout("RECONCILIATION_RESULT=PLAN_LOADED");
            runtime.terminal().stdout("PLAN=" + plan.planId());
            runtime.terminal().stdout("EXECUTABLE_ACTIONS=" + plan.executableCount());
            runtime.terminal().stdout("MUTATIONS_PERFORMED=0");
            runtime.terminal().stdout("NEXT_ACTION=execute_with_plan_id");

            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Failed to load reconciliation plan: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    private int handleExecute(Path controlRoot, String planId) {
        try {
            ReconciliationService.ReconciliationExecutionSummary summary = service.executePlan(controlRoot, planId);

            if (json) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("reconciliationResult", summary.resultStatus());
                map.put("planId", summary.planId());
                map.put("executionId", summary.executionId());
                map.put("actionsRequested", summary.actionsRequested());
                map.put("completed", summary.completedCount());
                map.put("skippedStale", summary.skippedStaleCount());
                map.put("skippedAmbiguous", summary.skippedAmbiguousCount());
                map.put("failed", summary.failedCount());
                map.put("controlCheckoutModified", summary.controlCheckoutModified());
                map.put("processTerminations", 0);
                runtime.terminal().stdout(ProviderJson.write(map));
                return summary.failedCount() > 0 ? ExitCodes.LOCAL_CONFIGURATION : ExitCodes.OK;
            }

            runtime.terminal().stdout("RECONCILIATION_RESULT=" + summary.resultStatus());
            runtime.terminal().stdout("PLAN=" + summary.planId());
            runtime.terminal().stdout("EXECUTION=" + summary.executionId());
            runtime.terminal().stdout("ACTIONS_REQUESTED=" + summary.actionsRequested());
            runtime.terminal().stdout("COMPLETED=" + summary.completedCount());
            runtime.terminal().stdout("SKIPPED_STALE=" + summary.skippedStaleCount());
            runtime.terminal().stdout("SKIPPED_AMBIGUOUS=" + summary.skippedAmbiguousCount());
            runtime.terminal().stdout("FAILED=" + summary.failedCount());
            runtime.terminal().stdout("CONTROL_CHECKOUT_MODIFIED=" + summary.controlCheckoutModified());
            runtime.terminal().stdout("PROCESS_TERMINATIONS=0");
            runtime.terminal().stdout("NEXT_ACTION=none");

            return summary.failedCount() > 0 ? ExitCodes.LOCAL_CONFIGURATION : ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Reconciliation execution failed: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
