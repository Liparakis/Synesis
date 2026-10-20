package org.synesis.cli.command.lifecycle;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.daemon.ProjectRuntime;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.doctor.DoctorFinding;
import org.synesis.workspace.doctor.DoctorFindingCode;
import org.synesis.workspace.doctor.DoctorReport;
import org.synesis.workspace.lifecycle.repair.RepairPlan;
import org.synesis.workspace.lifecycle.repair.RepairService;

/**
 * The repairs {@code synesis doctor --fix} makes (ADR-0103, decision 4). There are exactly three, each one a command that
 * already exists, repeatable without harm, and confined to what Synesis itself wrote or started:
 *
 * <ol>
 *   <li>the existing repair plan for Synesis's own administrative state: stale lock files whose owning process is gone
 *       and corrupt plan files, which are archived rather than deleted;</li>
 *   <li>{@code synesis provider install} for a connected agent whose managed files are out of date;</li>
 *   <li>starting the runtime of a project shared with another computer.</li>
 * </ol>
 *
 * <p>Nothing that deletes a person's work, changes a security setting (the firewall rule), or answers an operator request
 * is done here; the report prints each of those as a {@code FIX=} for a person to run.</p>
 */
final class DoctorFixes {

    private static final Set<DoctorFindingCode> ADMINISTRATIVE = Set.of(DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK, DoctorFindingCode.STALE_RECONCILIATION_EXECUTION_LOCK,
            DoctorFindingCode.STALE_REPAIR_LOCK, DoctorFindingCode.CORRUPT_CLEANUP_PLAN, DoctorFindingCode.CORRUPT_RECONCILIATION_PLAN);

    /**
     * What a run did.
     *
     * @param applied one line for each repair that changed something
     * @param skipped one line for each repair that was needed and could not be made, with its reason
     */
    record Outcome(List<String> applied, List<String> skipped) {

        int mutations() {
            return applied.size();
        }
    }

    private final CliRuntime runtime;
    private final RepairService repairService;
    private final ProjectRuntime projectRuntime;

    DoctorFixes(CliRuntime runtime, RepairService repairService, ProjectRuntime projectRuntime) {
        this.runtime = runtime;
        this.repairService = repairService;
        this.projectRuntime = projectRuntime;
    }

    /**
     * Makes the repairs the report calls for.
     *
     * @param report what the doctor found
     * @param root   the project directory
     * @return what was done and what could not be
     */
    Outcome apply(DoctorReport report, Path root) {
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (report.findings().stream().anyMatch(finding -> ADMINISTRATIVE.contains(finding.code()))) {
            repairAdministrativeState(root, applied, skipped);
        }
        for (DoctorFinding finding : report.findings()) {
            if (finding.code() == DoctorFindingCode.PROVIDER_FILES_STALE) {
                reinstallProvider(root, finding.details().get("provider"), applied, skipped);
            } else if (finding.code() == DoctorFindingCode.PROJECT_RUNTIME_NOT_RUNNING) {
                startRuntime(root, applied, skipped);
            }
        }
        return new Outcome(applied, skipped);
    }

    private void repairAdministrativeState(Path root, List<String> applied, List<String> skipped) {
        try {
            RepairPlan plan = repairService.preparePlan(root);
            if (plan.supportedRepairsCount() == 0) {
                return;
            }
            RepairService.ExecutionResult result = repairService.executePlan(root, plan.planId());
            if (result.completedCount() > 0) {
                applied.add("administrative_state COMPLETED=" + result.completedCount());
            }
            if (result.failedCount() > 0 || result.skippedStaleCount() > 0) {
                skipped.add("administrative_state FAILED=" + result.failedCount() + " CHANGED_SINCE_PLANNED=" + result.skippedStaleCount());
            }
        } catch (IOException failure) {
            skipped.add("administrative_state REASON=" + failure.getMessage());
        }
    }

    private void reinstallProvider(Path root, String provider, List<String> applied, List<String> skipped) {
        try {
            ProjectLocation location = runtime.projectService().require(root);
            var result = runtime.providerService().install(location, provider);
            if (result.exitCode() == 0) {
                applied.add("provider_files_stale PROVIDER=" + provider);
            } else {
                skipped.add("provider_files_stale PROVIDER=" + provider + " REASON=" + result.values().getOrDefault("ERROR", "INSTALL_FAILED"));
            }
        } catch (Exception failure) {
            skipped.add("provider_files_stale PROVIDER=" + provider + " REASON=" + failure.getMessage());
        }
    }

    private void startRuntime(Path root, List<String> applied, List<String> skipped) {
        try {
            projectRuntime.ensure(root);
            applied.add("project_runtime_not_running");
        } catch (IOException failure) {
            skipped.add("project_runtime_not_running REASON=" + failure.getMessage());
        }
    }
}
