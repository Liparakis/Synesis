package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.cleanup.CleanupExecutionService;
import org.synesis.workspace.cleanup.CleanupPlan;
import org.synesis.workspace.cleanup.CleanupPlanEntry;
import org.synesis.workspace.cleanup.CleanupPlanService;
import org.synesis.workspace.cleanup.CleanupPlanStore;
import org.synesis.workspace.cleanup.PersistedCleanupPlan;
import org.synesis.workspace.cleanup.PersistedCleanupPlanEntry;
import org.synesis.workspace.provider.ProviderJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the {@code synesis cleanup} lifecycle discovery, plan preparation, inspection, and execution CLI command.
 *
 * @since 1.0
 */
@Command(name = "cleanup", description = "Inspects lifecycle resources, prepares immutable plans, and executes safe cleanup.", mixinStandardHelpOptions = true)
public final class CleanupCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final CleanupPlanService planService;
    private final CleanupPlanStore planStore;
    private final CleanupExecutionService executionService;

    @Option(names = "--dry-run", description = "Generates a read-only cleanup plan without mutating filesystem or state.")
    private boolean dryRun;

    @Option(names = "--prepare", description = "Prepares and persists an immutable cleanup plan.")
    private boolean prepare;

    @Option(names = "--show-plan", description = "Displays details of a previously prepared cleanup plan by plan ID.")
    private String showPlan;

    @Option(names = "--execute", description = "Executes a previously prepared cleanup plan by plan ID.")
    private String executePlan;

    @Option(names = "--json", description = "Formats output as JSON.")
    private boolean json;

    @Option(names = "--verbose", description = "Outputs detailed per-resource diagnostic details.")
    private boolean verbose;

    @Option(names = "--project", description = "Project directory.")
    private String project;

    /**
     * Creates a cleanup command with runtime and default application services.
     *
     * @param runtime CLI runtime
     */
    public CleanupCommand(CliRuntime runtime) {
        this(runtime, new CleanupPlanService(), new CleanupPlanStore(), new CleanupExecutionService());
    }

    /**
     * Creates a cleanup command with explicit CLI runtime and services.
     *
     * @param runtime          CLI runtime
     * @param planService      cleanup plan generation service
     * @param planStore        cleanup plan persistence store
     * @param executionService cleanup execution service
     */
    public CleanupCommand(
            CliRuntime runtime,
            CleanupPlanService planService,
            CleanupPlanStore planStore,
            CleanupExecutionService executionService
    ) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.planService = java.util.Objects.requireNonNull(planService, "planService");
        this.planStore = java.util.Objects.requireNonNull(planStore, "planStore");
        this.executionService = java.util.Objects.requireNonNull(executionService, "executionService");
    }

    @Override
    public Integer call() {
        if (!dryRun && !prepare && showPlan == null && executePlan == null) {
            runtime.terminal().stderr("Cleanup execution is not available in this version without --dry-run, --prepare, --show-plan, or --execute.");
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

        return handleExecute(controlRoot, executePlan);

    }

    private int handleDryRun(Path controlRoot) {
        CleanupPlan plan;
        try {
            plan = planService.generatePlan(controlRoot);
        } catch (Exception failure) {
            runtime.terminal().stdout("CLEANUP_RESULT=BROKEN");
            return ExitCodes.LOCAL_CONFIGURATION;
        }

        if (json) {
            Map<String, Object> jsonMap = new LinkedHashMap<>();
            jsonMap.put("cleanupResult", "DRY_RUN");
            jsonMap.put("projectId", plan.projectId());
            jsonMap.put("timestamp", plan.timestamp());
            jsonMap.put("discoveredCount", plan.discoveredCount());
            jsonMap.put("protectedCount", plan.protectedCount());
            jsonMap.put("activeCount", plan.activeCount());
            jsonMap.put("recoverableCount", plan.recoverableCount());
            jsonMap.put("diagnosticRetainedCount", plan.diagnosticRetainedCount());
            jsonMap.put("cleanupEligibleCount", plan.cleanupEligibleCount());
            jsonMap.put("orphanedCount", plan.orphanedCount());
            jsonMap.put("estimatedReclaimableBytes", plan.estimatedReclaimableBytes());
            jsonMap.put("diskBudgetWarning", plan.diskBudgetWarning());
            jsonMap.put("mutationsPerformed", 0);

            List<Map<String, Object>> entriesList = plan.entries().stream().map(e -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", e.resourceType().name());
                item.put("id", e.resourceId());
                item.put("classification", e.classification().name());
                item.put("eligible", e.eligible());
                item.put("reasons", e.reasons());
                item.put("estimatedBytes", e.estimatedBytes());
                item.put("pathSafety", e.pathSafetyCode());
                item.put("proposedAction", e.proposedAction());
                return item;
            }).toList();
            jsonMap.put("entries", entriesList);

            runtime.terminal().stdout(ProviderJson.write(jsonMap));
            return ExitCodes.OK;
        }

        if (verbose) {
            runtime.terminal().stdout("CLEANUP_RESULT=DRY_RUN");
            runtime.terminal().stdout("PROJECT_ID=" + plan.projectId());
            runtime.terminal().stdout("RESOURCES_DISCOVERED=" + plan.discoveredCount());
            runtime.terminal().stdout("PROTECTED=" + plan.protectedCount());
            runtime.terminal().stdout("ACTIVE=" + plan.activeCount());
            runtime.terminal().stdout("RECOVERABLE=" + plan.recoverableCount());
            runtime.terminal().stdout("DIAGNOSTIC_RETAINED=" + plan.diagnosticRetainedCount());
            runtime.terminal().stdout("CLEANUP_ELIGIBLE=" + plan.cleanupEligibleCount());
            runtime.terminal().stdout("ORPHANED=" + plan.orphanedCount());
            runtime.terminal().stdout("ESTIMATED_RECLAIMABLE_BYTES=" + plan.estimatedReclaimableBytes());
            runtime.terminal().stdout("DISK_BUDGET_WARNING=" + plan.diskBudgetWarning());
            runtime.terminal().stdout("MUTATIONS_PERFORMED=0");
            runtime.terminal().stdout("--- ENTRIES ---");
            for (CleanupPlanEntry entry : plan.entries()) {
                runtime.terminal().stdout("[" + entry.classification() + "] " + entry.resourceType() + " | "
                        + entry.resourceId() + " | eligible=" + entry.eligible() + " | action=" + entry.proposedAction()
                        + " | reasons=" + entry.reasons());
            }
            return ExitCodes.OK;
        }

        runtime.terminal().stdout("CLEANUP_RESULT=DRY_RUN");
        runtime.terminal().stdout("PROJECT=READY");
        runtime.terminal().stdout("RESOURCES_DISCOVERED=" + plan.discoveredCount());
        runtime.terminal().stdout("PROTECTED=" + plan.protectedCount());
        runtime.terminal().stdout("ACTIVE=" + plan.activeCount());
        runtime.terminal().stdout("RECOVERABLE=" + plan.recoverableCount());
        runtime.terminal().stdout("DIAGNOSTIC_RETAINED=" + plan.diagnosticRetainedCount());
        runtime.terminal().stdout("CLEANUP_ELIGIBLE=" + plan.cleanupEligibleCount());
        runtime.terminal().stdout("ORPHANED=" + plan.orphanedCount());
        runtime.terminal().stdout("ESTIMATED_RECLAIMABLE_BYTES=" + plan.estimatedReclaimableBytes());
        runtime.terminal().stdout("MUTATIONS_PERFORMED=0");
        runtime.terminal().stdout("NEXT_ACTION=review_with_verbose_output");

        return ExitCodes.OK;
    }

    private int handlePrepare(Path controlRoot) {
        try {
            CleanupPlan rawPlan = planService.generatePlan(controlRoot);
            PersistedCleanupPlan persistedPlan = planStore.createAndSave(controlRoot, rawPlan);

            if (json) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("cleanupResult", "PLAN_PREPARED");
                map.put("planId", persistedPlan.planId());
                map.put("discoveredCount", persistedPlan.totalDiscoveredCount());
                map.put("executableEntries", persistedPlan.totalExecutableCount());
                map.put("estimatedReclaimableBytes", persistedPlan.totalEstimatedReclaimableBytes());
                map.put("mutationsPerformed", 1);
                map.put("resourceMutationsPerformed", 0);
                runtime.terminal().stdout(ProviderJson.write(map));
                return ExitCodes.OK;
            }

            runtime.terminal().stdout("CLEANUP_RESULT=PLAN_PREPARED");
            runtime.terminal().stdout("PLAN=" + persistedPlan.planId());
            runtime.terminal().stdout("RESOURCES_DISCOVERED=" + persistedPlan.totalDiscoveredCount());
            runtime.terminal().stdout("EXECUTABLE_ENTRIES=" + persistedPlan.totalExecutableCount());
            runtime.terminal().stdout("ESTIMATED_RECLAIMABLE_BYTES=" + persistedPlan.totalEstimatedReclaimableBytes());
            runtime.terminal().stdout("MUTATIONS_PERFORMED=1");
            runtime.terminal().stdout("RESOURCE_MUTATIONS_PERFORMED=0");
            runtime.terminal().stdout("NEXT_ACTION=review_plan_then_execute");

            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Failed to prepare cleanup plan: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    private int handleShowPlan(Path controlRoot, String planId) {
        try {
            PersistedCleanupPlan plan = planStore.load(controlRoot, planId);

            if (json) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("cleanupResult", "PLAN_LOADED");
                map.put("planId", plan.planId());
                map.put("discoveredCount", plan.totalDiscoveredCount());
                map.put("executableEntries", plan.totalExecutableCount());
                map.put("estimatedReclaimableBytes", plan.totalEstimatedReclaimableBytes());
                map.put("mutationsPerformed", 0);
                runtime.terminal().stdout(ProviderJson.write(map));
                return ExitCodes.OK;
            }

            if (verbose) {
                runtime.terminal().stdout("CLEANUP_RESULT=PLAN_LOADED");
                runtime.terminal().stdout("PLAN=" + plan.planId());
                runtime.terminal().stdout("PROJECT_ID=" + plan.projectId());
                runtime.terminal().stdout("CONTENT_HASH=" + plan.contentHash());
                runtime.terminal().stdout("RESOURCES_DISCOVERED=" + plan.totalDiscoveredCount());
                runtime.terminal().stdout("EXECUTABLE_ENTRIES=" + plan.totalExecutableCount());
                runtime.terminal().stdout("ESTIMATED_RECLAIMABLE_BYTES=" + plan.totalEstimatedReclaimableBytes());
                runtime.terminal().stdout("MUTATIONS_PERFORMED=0");
                runtime.terminal().stdout("--- PERSISTED ENTRIES ---");
                for (PersistedCleanupPlanEntry entry : plan.entries()) {
                    runtime.terminal().stdout("[" + entry.classification() + "] " + entry.resourceType() + " | "
                            + entry.resourceId() + " | eligible=" + entry.eligible() + " | action=" + entry.proposedOperation());
                }
                return ExitCodes.OK;
            }

            runtime.terminal().stdout("CLEANUP_RESULT=PLAN_LOADED");
            runtime.terminal().stdout("PLAN=" + plan.planId());
            runtime.terminal().stdout("RESOURCES_DISCOVERED=" + plan.totalDiscoveredCount());
            runtime.terminal().stdout("EXECUTABLE_ENTRIES=" + plan.totalExecutableCount());
            runtime.terminal().stdout("ESTIMATED_RECLAIMABLE_BYTES=" + plan.totalEstimatedReclaimableBytes());
            runtime.terminal().stdout("MUTATIONS_PERFORMED=0");
            runtime.terminal().stdout("NEXT_ACTION=execute_with_plan_id");

            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Failed to load cleanup plan: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }

    private int handleExecute(Path controlRoot, String planId) {
        try {
            CleanupExecutionService.CleanupExecutionSummary summary = executionService.executePlan(controlRoot, planId);

            if (json) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("cleanupResult", summary.resultStatus());
                map.put("planId", summary.planId());
                map.put("executionId", summary.executionId());
                map.put("entriesRequested", summary.totalEntries());
                map.put("completed", summary.completedCount());
                map.put("alreadyCompleted", summary.alreadyCompletedCount());
                map.put("skippedStale", summary.skippedStaleCount());
                map.put("skippedUnsafe", summary.skippedUnsafeCount());
                map.put("failed", summary.failedCount());
                map.put("bytesReclaimed", summary.bytesReclaimed());
                map.put("controlCheckoutModified", false);
                map.put("eventLogModified", false);
                runtime.terminal().stdout(ProviderJson.write(map));
                return summary.failedCount() > 0 ? ExitCodes.LOCAL_CONFIGURATION : ExitCodes.OK;
            }

            runtime.terminal().stdout("CLEANUP_RESULT=" + summary.resultStatus());
            runtime.terminal().stdout("PLAN=" + summary.planId());
            runtime.terminal().stdout("EXECUTION=" + summary.executionId());
            runtime.terminal().stdout("ENTRIES_REQUESTED=" + summary.totalEntries());
            runtime.terminal().stdout("COMPLETED=" + summary.completedCount());
            runtime.terminal().stdout("ALREADY_COMPLETED=" + summary.alreadyCompletedCount());
            runtime.terminal().stdout("SKIPPED_STALE=" + summary.skippedStaleCount());
            runtime.terminal().stdout("SKIPPED_UNSAFE=" + summary.skippedUnsafeCount());
            runtime.terminal().stdout("FAILED=" + summary.failedCount());
            runtime.terminal().stdout("BYTES_RECLAIMED=" + summary.bytesReclaimed());
            runtime.terminal().stdout("CONTROL_CHECKOUT_MODIFIED=false");
            runtime.terminal().stdout("EVENT_LOG_MODIFIED=false");
            runtime.terminal().stdout("NEXT_ACTION=none");

            return summary.failedCount() > 0 ? ExitCodes.LOCAL_CONFIGURATION : ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Cleanup execution failed: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
