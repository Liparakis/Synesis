package org.synesis.cli.command.lifecycle;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.ErrorShape;
import org.synesis.cli.daemon.ProjectRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.doctor.DoctorRenderer;
import org.synesis.workspace.doctor.DoctorReport;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.doctor.DoctorStatus;
import org.synesis.workspace.lifecycle.repair.RepairService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the {@code synesis doctor} repository, runtime, environment and network diagnostic command.
 *
 * <p>Doctor changes nothing unless {@code --fix} is given; then it makes only the repairs {@link DoctorFixes} lists and
 * reports how many changed something.
 *
 * @since 1.0
 */
@Command(name = "doctor", description = "Executes diagnostics for repository, runtime, environment, network, durable state, and administrative health; --fix makes the safe repairs.", mixinStandardHelpOptions = true)
public final class DoctorCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final DoctorService doctorService;
    private final RepairService repairService;
    private final ProjectRuntime projectRuntime;

    @Option(names = "--json", description = "Formats diagnostic output as JSON.")
    private boolean json;

    @Option(names = "--verbose", description = "Outputs detailed per-finding diagnostic explanation and recommendations.")
    private boolean verbose;

    @Option(names = "--strict", description = "Enforces strict health checking, returning non-zero exit code if WARNING or worse.")
    private boolean strict;

    @Option(names = "--fix", description = "Makes the safe repairs, then reports again: stale Synesis lock files and corrupt plan files, a connected agent's out-of-date managed files, and a shared project's stopped runtime. Never touches your files, the firewall, or operator requests.")
    private boolean fix;

    @Option(names = "--project", description = "Project directory path.")
    private String project;

    /**
     * Creates a doctor command with CLI runtime and the default doctor service, which reads the network through the
     * installation daemon and the firewall.
     *
     * @param runtime CLI runtime
     */
    public DoctorCommand(CliRuntime runtime) {
        this(runtime, new DoctorService(new DoctorNetworkProbe()), new RepairService(), ProjectRuntime.DAEMON);
    }

    /**
     * Creates a doctor command with explicit CLI runtime and doctor service.
     *
     * @param runtime       CLI runtime
     * @param doctorService doctor service
     */
    public DoctorCommand(CliRuntime runtime, DoctorService doctorService) {
        this(runtime, doctorService, new RepairService(), ProjectRuntime.DAEMON);
    }

    DoctorCommand(CliRuntime runtime, DoctorService doctorService, RepairService repairService, ProjectRuntime projectRuntime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.doctorService = Objects.requireNonNull(doctorService, "doctorService");
        this.repairService = Objects.requireNonNull(repairService, "repairService");
        this.projectRuntime = Objects.requireNonNull(projectRuntime, "projectRuntime");
    }

    @Override
    public Integer call() {
        Path controlRoot = project != null ? Path.of(project) : Path.of(".");

        try {
            DoctorReport report = doctorService.diagnose(controlRoot);
            int mutations = 0;
            if (fix) {
                DoctorFixes.Outcome outcome = new DoctorFixes(runtime, repairService, projectRuntime).apply(report, controlRoot.toAbsolutePath().normalize());
                // JSON stays one document on standard output, so the repairs are told on standard error there.
                Consumer<String> told = json ? runtime.terminal()::stderr : runtime.terminal()::stdout;
                told.accept("FIXES_APPLIED=" + outcome.applied().size());
                outcome.applied().forEach(line -> told.accept("FIX_APPLIED=" + line));
                told.accept("FIXES_SKIPPED=" + outcome.skipped().size());
                outcome.skipped().forEach(line -> told.accept("FIX_SKIPPED=" + line));
                mutations = outcome.mutations();
                report = doctorService.diagnose(controlRoot);
            }

            String rendered = json ? DoctorRenderer.renderJson(report, mutations)
                    : verbose ? DoctorRenderer.renderVerbose(report, mutations) : DoctorRenderer.renderConcise(report, mutations);
            if (json) {
                runtime.terminal().stdout(rendered);
            } else {
                // One line at a time, so a person's terminal can lay each key out.
                rendered.lines().forEach(runtime.terminal()::stdout);
            }

            if (strict && report.overallStatus() != DoctorStatus.HEALTHY) {
                return ExitCodes.LOCAL_CONFIGURATION;
            }

            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Doctor diagnostic execution failed: " + failure.getMessage());
            ErrorShape.print(runtime.terminal()::stderr, "DOCTOR_FAILED");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
