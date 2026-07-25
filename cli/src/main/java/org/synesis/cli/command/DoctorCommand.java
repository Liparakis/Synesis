package org.synesis.cli.command;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.doctor.DoctorRenderer;
import org.synesis.workspace.doctor.DoctorReport;
import org.synesis.workspace.doctor.DoctorService;
import org.synesis.workspace.doctor.DoctorStatus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Runs the read-only {@code synesis doctor} repository and runtime diagnostic command.
 *
 * <p>Doctor is read-only by construction and performs zero state mutations.
 *
 * @since 1.0
 */
@Command(name = "doctor", description = "Executes read-only diagnostics for repository, runtime, durable state, and administrative health.", mixinStandardHelpOptions = true)
public final class DoctorCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    private final DoctorService doctorService;

    @Option(names = "--json", description = "Formats diagnostic output as JSON.")
    private boolean json;

    @Option(names = "--verbose", description = "Outputs detailed per-finding diagnostic explanation and recommendations.")
    private boolean verbose;

    @Option(names = "--strict", description = "Enforces strict health checking, returning non-zero exit code if WARNING or worse.")
    private boolean strict;

    @Option(names = "--project", description = "Project directory path.")
    private String project;

    /**
     * Creates a doctor command with CLI runtime and default doctor service.
     *
     * @param runtime CLI runtime
     */
    public DoctorCommand(CliRuntime runtime) {
        this(runtime, new DoctorService());
    }

    /**
     * Creates a doctor command with explicit CLI runtime and doctor service.
     *
     * @param runtime       CLI runtime
     * @param doctorService doctor service
     */
    public DoctorCommand(CliRuntime runtime, DoctorService doctorService) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.doctorService = Objects.requireNonNull(doctorService, "doctorService");
    }

    @Override
    public Integer call() {
        Path controlRoot = project != null ? Path.of(project) : Path.of(".");

        try {
            DoctorReport report = doctorService.diagnose(controlRoot);

            if (json) {
                runtime.terminal().stdout(DoctorRenderer.renderJson(report));
            } else if (verbose) {
                runtime.terminal().stdout(DoctorRenderer.renderVerbose(report));
            } else {
                runtime.terminal().stdout(DoctorRenderer.renderConcise(report));
            }

            if (strict && report.overallStatus() != DoctorStatus.HEALTHY) {
                return ExitCodes.LOCAL_CONFIGURATION;
            }

            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("Doctor diagnostic execution failed: " + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
