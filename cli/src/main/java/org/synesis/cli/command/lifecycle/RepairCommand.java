package org.synesis.cli.command.lifecycle;

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
    @SuppressWarnings("unused")
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
                runtime.terminal()
                        .stdout("REPAIR_RESULT=ROLLED_BACK\nEXECUTION=" + rollbackExecutionId
                                + "\nNEXT_ACTION=run_doctor");
                return ExitCodes.OK;
            }

            if (showPlanId != null && !showPlanId.isBlank()) {
                RepairPlan plan = repairService.showPlan(controlRoot, showPlanId);
                StringBuilder sb = new StringBuilder();
                sb.append("PLAN_ID=")
                        .append(plan.planId())
                        .append("\n");
                sb.append("PROJECT_ID=")
                        .append(plan.projectId())
                        .append("\n");
                sb.append("SUPPORTED_REPAIRS=")
                        .append(plan.supportedRepairsCount())
                        .append("\n");
                sb.append("UNSUPPORTED_FINDINGS=")
                        .append(plan.unsupportedCount())
                        .append("\n");
                for (RepairPlanEntry entry : plan.entries()) {
                    sb.append("  [")
                            .append(entry.entryId())
                            .append("] ")
                            .append(entry.action()
                                    .name())
                            .append(" -> ")
                            .append(entry.summary())
                            .append(" (executable=")
                            .append(entry.executable())
                            .append(")\n");
                }
                runtime.terminal()
                        .stdout(sb.toString());
                return ExitCodes.OK;
            }

            if (executePlanId != null && !executePlanId.isBlank()) {
                RepairService.ExecutionResult result = repairService.executePlan(controlRoot, executePlanId);
                String sb = "REPAIR_RESULT=" + (result.failedCount() == 0 ? "SUCCESS" : "FAILED") + "\n"
                        + "PLAN=" + result.planId() + "\n"
                        + "EXECUTION=" + result.executionId() + "\n"
                        + "ENTRIES_REQUESTED=" + result.entriesRequestedCount() + "\n"
                        + "COMPLETED=" + result.completedCount() + "\n"
                        + "SKIPPED_STALE=" + result.skippedStaleCount() + "\n"
                        + "SKIPPED_UNSUPPORTED=" + result.skippedUnsupportedCount() + "\n"
                        + "FAILED=" + result.failedCount() + "\n"
                        + "COORDINATION_STATE_MODIFIED=false\n"
                        + "CONTROL_CHECKOUT_MODIFIED=false\n"
                        + "NEXT_ACTION=run_doctor";
                runtime.terminal()
                        .stdout(sb);
                return result.failedCount() == 0 ? ExitCodes.OK : ExitCodes.LOCAL_CONFIGURATION;
            }

            if (prepare) {
                RepairPlan plan = repairService.preparePlan(controlRoot);
                String sb = "REPAIR_RESULT=PLAN_PREPARED\n"
                        + "PLAN=" + plan.planId() + "\n"
                        + "FINDINGS=" + plan.entries().size() + "\n"
                        + "SUPPORTED_REPAIRS=" + plan.supportedRepairsCount() + "\n"
                        + "UNSUPPORTED_FINDINGS=" + plan.unsupportedCount() + "\n"
                        + "TARGET_MUTATIONS_PERFORMED=0\n"
                        + "NEXT_ACTION=review_plan_then_execute";
                runtime.terminal()
                        .stdout(sb);
                return ExitCodes.OK;
            }

            DoctorReport report = repairService.dryRun(controlRoot);
            long candidateCount = report.findings()
                    .stream()
                    .filter(DoctorFinding::repairSupported)
                    .count();
            String sb = "REPAIR_DRY_RUN=COMPLETED\n"
                    + "FINDINGS=" + report.findings().size() + "\n"
                    + "REPAIR_CANDIDATES=" + candidateCount + "\n"
                    + "MUTATIONS_PERFORMED=0\n"
                    + "NEXT_ACTION=" + (candidateCount > 0 ? "prepare_repair_plan" : "no_action");
            runtime.terminal()
                    .stdout(sb);
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("Repair execution failed: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
