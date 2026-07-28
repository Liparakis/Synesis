package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Explicitly releases the exact connection's active claims. */
@Command(name = "release", description = "Release this session's claims.", mixinStandardHelpOptions = true)
public final class CollaborationReleaseCommand implements Callable<Integer> {
    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".") private Path project;
    @Option(names = "--provider", defaultValue = "codex") private String provider;
    @Option(names = "--connection-instance-id", required = true) private String connectionInstanceId;

    /** Creates the command.
     * @param runtime CLI runtime
     */
    public CollaborationReleaseCommand(CliRuntime runtime) { this.runtime = runtime; }

    /** Executes release. @return process exit code */
    @Override public Integer call() {
        try {
            new WorkspaceCollaborationService().release(project.toAbsolutePath().normalize(), provider,
                    connectionInstanceId);
            runtime.terminal().stdout("CLAIMS_RELEASED");
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
