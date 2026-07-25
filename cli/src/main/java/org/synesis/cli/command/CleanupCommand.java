package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.cleanup.CleanupPlan;
import org.synesis.workspace.cleanup.CleanupPlanEntry;
import org.synesis.workspace.cleanup.CleanupPlanService;
import org.synesis.workspace.provider.ProviderJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the read-only {@code synesis cleanup --dry-run} inspection command.
 *
 * @since 1.0
 */
@Command(name = "cleanup", description = "Inspects lifecycle resources and generates a read-only cleanup dry-run plan.", mixinStandardHelpOptions = true)
public final class CleanupCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final CleanupPlanService planService;

    @Option(names = "--dry-run", description = "Generates a read-only cleanup plan without mutating filesystem or state.")
    private boolean dryRun;

    @Option(names = "--json", description = "Formats output as JSON.")
    private boolean json;

    @Option(names = "--verbose", description = "Outputs detailed per-resource diagnostic details.")
    private boolean verbose;

    @Option(names = "--project", description = "Project directory.")
    private String project;

    /**
     * Creates a cleanup command with runtime and default cleanup plan service.
     *
     * @param runtime CLI runtime
     */
    public CleanupCommand(CliRuntime runtime) {
        this(runtime, new CleanupPlanService());
    }

    /**
     * Creates a cleanup command with explicit CLI runtime and plan service.
     *
     * @param runtime     CLI runtime
     * @param planService cleanup plan application service
     */
    public CleanupCommand(CliRuntime runtime, CleanupPlanService planService) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        this.planService = java.util.Objects.requireNonNull(planService, "planService");
    }

    @Override
    public Integer call() {
        if (!dryRun) {
            runtime.terminal().stderr("Cleanup execution is not available in this version. Use --dry-run.");
            return ExitCodes.LOCAL_CONFIGURATION;
        }

        Path controlRoot = project != null ? Path.of(project) : Path.of(".");
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

        // Default concise output
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
}
