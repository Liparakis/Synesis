package org.synesis.cli.command;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.diagnostics.ReadinessReport;
import org.synesis.cli.exit.ExitCodes;

/** Runs the local-only {@code synesis doctor} readiness report. */
@Command(name = "doctor", description = "Inspect local readiness without repair or networking.", mixinStandardHelpOptions = true)
public final class DoctorCommand implements Callable<Integer> {
    private final CliRuntime runtime;

    /**
     * Creates a doctor command with one manually composed runtime.
     * @param runtime manually composed CLI runtime
     */
    public DoctorCommand(CliRuntime runtime) { this.runtime = runtime; }

    /** Runs all bounded local checks. @return 0, 10, or 13 */
    @Override
    public Integer call() {
        ReadinessReport report = runtime.readinessInspector().inspect();
        runtime.terminal().stdout("JAVA_RUNTIME=" + (report.javaReady() ? "PASS" : "FAIL"));
        runtime.terminal().stdout("PROFILE=" + (report.profileReady() ? "PASS" : "FAIL"));
        runtime.terminal().stdout("IDENTITY=" + (report.identityReady() ? report.identityDetail() : "FAIL"));
        runtime.terminal().stdout("CANDIDATES=" + (report.candidatesReady() ? report.candidateDetail() : "FAIL"));
        runtime.terminal().stdout("QUIC_NATIVE=" + (report.quicReady() ? report.quicDetail() : "FAIL"));
        runtime.terminal().stdout("WINDOWS_ACL=" + (isWindows() ? "INFO_UNVERIFIED" : "NOT_APPLICABLE"));
        runtime.terminal().stdout("DOCTOR=" + (report.ready() ? "PASS" : "FAIL"));
        if (report.ready()) return ExitCodes.OK;
        return report.profileReady() && report.identityReady() ? ExitCodes.SESSION_FAILED : ExitCodes.LOCAL_CONFIGURATION;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
