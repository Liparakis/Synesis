package org.synesis.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;

final class BuildInfo {
    private BuildInfo() {
    }

    static String text() {
        Properties values = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream("/synesis-build.properties")) {
            if (input != null) {
                values.load(input);
            }
        } catch (IOException ignored) {
            // Defaults keep diagnostics usable if an unbundled development classpath is used.
        }
        return "SYNESIS_VERSION=" + values.getProperty("version", "0.1.0-dev.local") + System.lineSeparator()
                + "RECORD_FORMAT=" + values.getProperty("recordFormat", "SDR2") + System.lineSeparator()
                + "RECONCILIATION_PROTOCOL=" + values.getProperty("reconciliationProtocol", "PRP1")
                + System.lineSeparator()
                + "BUILD_COMMIT=" + values.getProperty("commit", "UNKNOWN") + System.lineSeparator()
                + "BUILD_TIME=" + values.getProperty("time", "UNKNOWN") + System.lineSeparator()
                + "BUILD_PLATFORM=" + values.getProperty("platform", "UNKNOWN") + System.lineSeparator()
                + "JAVA_RUNTIME=" + values.getProperty("javaRuntime", Runtime.version().toString());
    }
}

/** Emits build metadata embedded in the application bundle. */
@Command(name = "version", description = "Show build and protocol metadata.", mixinStandardHelpOptions = true)
public final class VersionPlaceholderCommand implements Callable<Integer> {
    private final CliRuntime runtime;
    /**
     * Creates the command.
     * @param runtime composed runtime
     */
    public VersionPlaceholderCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Prints bounded build metadata without requiring Git or a project. @return zero */
    @Override public Integer call() {
        runtime.terminal().stdout(BuildInfo.text());
        return ExitCodes.OK;
    }
}
