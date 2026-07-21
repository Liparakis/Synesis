package org.synesis.cli.command;

import java.util.concurrent.Callable;
import java.nio.file.Path;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.diagnostics.ReadinessReport;
import org.synesis.cli.diagnostics.ReadinessInspector;
import org.synesis.cli.exit.ExitCodes;

/**
 * Runs the local-only {@code synesis doctor} readiness report.
 */
@Command(name = "doctor", description = "Inspect local readiness without repair or networking.", mixinStandardHelpOptions = true)
public final class DoctorCommand implements Callable<Integer> {
    @Option(names = "--project", description = "Project directory.") private String project;
    private final CliRuntime runtime;

    /**
     * Creates a doctor command with one manually composed runtime.
     *
     * @param runtime manually composed CLI runtime
     */
    public DoctorCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Runs all bounded local checks. @return 0, 10, or 13
     */
    @Override
    public Integer call() {
        ReadinessReport report;
        org.synesis.workspace.application.ProjectApplicationService.ProjectLocation location = null;
        try {
            if (project != null) {
                location = runtime.projectService().require(Path.of(project));
                report = new ReadinessInspector(location.profile()).inspect();
            } else {
                report = runtime.readinessInspector().inspect();
            }
        } catch (Exception failure) {
            runtime.terminal().stdout("DOCTOR_RESULT=BROKEN");
            return ExitCodes.LOCAL_CONFIGURATION;
        }
        runtime.terminal().stdout("JAVA_RUNTIME=" + (report.javaReady() ? "PASS" : "FAIL"));
        runtime.terminal().stdout("PROFILE=" + (report.profileReady() ? "PASS" : "FAIL"));
        runtime.terminal().stdout("IDENTITY=" + (report.identityReady() ? report.identityDetail() : "FAIL"));
        runtime.terminal().stdout("CANDIDATES=" + (report.candidatesReady() ? report.candidateDetail() : "FAIL"));
        runtime.terminal().stdout("QUIC_NATIVE=" + (report.quicReady() ? report.quicDetail() : "FAIL"));
        runtime.terminal().stdout("WINDOWS_ACL=" + (isWindows() ? "INFO_UNVERIFIED" : "NOT_APPLICABLE"));
        runtime.terminal().stdout("DOCTOR=" + (report.ready() ? "PASS" : "FAIL"));
        if (location != null) {
            runtime.terminal().stdout("PROJECT_DISCOVERED=PASS");
            runtime.terminal().stdout("PROJECT_METADATA=PASS");
            runtime.terminal().stdout("LOCAL_PROFILE=PASS");
            try {
                var providerReport = runtime.providerService().diagnose(location);
                providerReport.lines().forEach(line -> runtime.terminal().stdout(line));
                runtime.terminal().stdout("DOCTOR_RESULT=" + (report.ready() ? providerReport.result() : "BROKEN"));
                if ("BROKEN".equals(providerReport.result())) return ExitCodes.DOCTOR_BROKEN;
            } catch (Exception failure) {
                runtime.terminal().stdout("DOCTOR_RESULT=BROKEN");
                return ExitCodes.LOCAL_CONFIGURATION;
            }
        }
        if (report.ready()) return ExitCodes.OK;
        return report.profileReady() && report.identityReady() ? ExitCodes.SESSION_FAILED : ExitCodes.LOCAL_CONFIGURATION;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
