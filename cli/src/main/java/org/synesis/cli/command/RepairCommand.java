package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.doctor.DoctorFinding;
import org.synesis.workspace.doctor.DoctorReport;
import org.synesis.workspace.lifecycle.repair.RepairPlan;
import org.synesis.workspace.lifecycle.repair.RepairPlanEntry;
import org.synesis.workspace.lifecycle.repair.RepairService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command executing the {@code synesis repair} administrative repair workflows:
 * {@code --dry-run}, {@code --prepare}, {@code --show-plan}, {@code --execute}, and {@code --rollback}.
 *
 * @since 1.0
 */
@Command(name = "repair", description = "Manages and executes safe administrative repair plans.", mixinStandardHelpOptions = true)
public final class RepairCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final RepairService repairService;

    @Option(names = "--dry-run", description = "Performs read-only inspection of candidate repairs.")
    private boolean dryRun;

    @Option(names = "--prepare", description = "Prepares and persists an immutable administrative repair plan.")
    private boolean prepare;

    @Option(names = "--show-plan", description = "Inspects a persisted repair plan by ID.")
    private String showPlanId;

    @Option(names = "--execute", description = "Executes a reviewed repair plan by ID.")
    private String executePlanId;

    @Option(names = "--rollback", description = "Rolls back a repair execution by execution ID.")
    private String rollbackExecutionId;

    @Option(names = "--project", description = "Project directory path.")
    private String project;

    /**
     * Creates a repair command with default repair service.
     *
     * @param runtime CLI runtime
     */
    public RepairCommand(CliRuntime runtime) {
        this(runtime, new RepairService());
    }

    /**
     * Creates a repair command with explicit repair service.
     *
     * @param runtime       CLI runtime
     * @param repairService repair service
     */
    public RepairCommand(CliRuntime runtime, RepairService repairService) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.repairService = Objects.requireNonNull(repairService, "repairService");
    }

    @Override
    public Integer call() {
        Path controlRoot = project != null ? Path.of(project) : Path.of(".");

        try {
            if (rollbackExecutionId != null && !rollbackExecutionId.isBlank()) {
                repairService.rollback(controlRoot, rollbackExecutionId);
                runtime.terminal().stdout("REPAIR_RESULT=ROLLED_BACK\nEXECUTION=" + rollbackExecutionId + "\nNEXT_ACTION=run_doctor");
                return ExitCodes.OK;
            }

            if (showPlanId != null && !showPlanId.isBlank()) {
                RepairPlan plan = repairService.showPlan(controlRoot, showPlanId);
                StringBuilder sb = new StringBuilder();
                sb.append("PLAN_ID=").append(plan.planId()).append("\n");
                sb.append("PROJECT_ID=").append(plan.projectId()).append("\n");
                sb.append("SUPPORTED_REPAIRS=").append(plan.supportedRepairsCount()).append("\n");
                sb.append("UNSUPPORTED_FINDINGS=").append(plan.unsupportedCount()).append("\n");
                for (RepairPlanEntry entry : plan.entries()) {
                    sb.append("  [").append(entry.entryId()).append("] ")
                            .append(entry.action().name()).append(" -> ")
                            .append(entry.summary()).append(" (executable=").append(entry.executable()).append(")\n");
                }
                runtime.terminal().stdout(sb.toString());
                return ExitCodes.OK;
            }

            if (executePlanId != null && !executePlanId.isBlank()) {
                RepairService.ExecutionResult result = repairService.executePlan(controlRoot, executePlanId);
                StringBuilder sb = new StringBuilder();
                sb.append("REPAIR_RESULT=").append(result.failedCount() == 0 ? "SUCCESS" : "FAILED").append("\n");
                sb.append("PLAN=").append(result.planId()).append("\n");
                sb.append("EXECUTION=").append(result.executionId()).append("\n");
                sb.append("ENTRIES_REQUESTED=").append(result.entriesRequestedCount()).append("\n");
                sb.append("COMPLETED=").append(result.completedCount()).append("\n");
                sb.append("SKIPPED_STALE=").append(result.skippedStaleCount()).append("\n");
                sb.append("SKIPPED_UNSUPPORTED=").append(result.skippedUnsupportedCount()).append("\n");
                sb.append("FAILED=").append(result.failedCount()).append("\n");
                sb.append("COORDINATION_STATE_MODIFIED=false\n");
                sb.append("CONTROL_CHECKOUT_MODIFIED=false\n");
                sb.append("NEXT_ACTION=run_doctor");
                runtime.terminal().stdout(sb.toString());
                return result.failedCount() == 0 ? ExitCodes.OK : ExitCodes.LOCAL_CONFIGURATION;
            }

            if (prepare) {
                RepairPlan plan = repairService.preparePlan(controlRoot);
                StringBuilder sb = new StringBuilder();
                sb.append("REPAIR_RESULT=PLAN_PREPARED\n");
                sb.append("PLAN=").append(plan.planId()).append("\n");
                sb.append("FINDINGS=").append(plan.entries().size()).append("\n");
                sb.append("SUPPORTED_REPAIRS=").append(plan.supportedRepairsCount()).append("\n");
                sb.append("UNSUPPORTED_FINDINGS=").append(plan.unsupportedCount()).append("\n");
                sb.append("TARGET_MUTATIONS_PERFORMED=0\n");
                sb.append("NEXT_ACTION=review_plan_then_execute");
                runtime.terminal().stdout(sb.toString());
                return ExitCodes.OK;
            }

            DoctorReport report = repairService.dryRun(controlRoot);
            long candidateCount = report.findings().stream().filter(DoctorFinding::repairSupported).count();
            StringBuilder sb = new StringBuilder();
            sb.append("REPAIR_DRY_RUN=COMPLETED\n");
            sb.append("FINDINGS=").append(report.findings().size()).append("\n");
            sb.append("REPAIR_CANDIDATES=").append(candidateCount).append("\n");
            sb.append("MUTATIONS_PERFORMED=0\n");
            sb.append("NEXT_ACTION=").append(candidateCount > 0 ? "prepare_repair_plan" : "no_action");
            runtime.terminal().stdout(sb.toString());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Repair execution failed: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
