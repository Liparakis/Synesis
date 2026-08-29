package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Releases the exact caller's collaboration lane and claims.
 */
@Command(name = "release", description = "Release this connection's claims.", mixinStandardHelpOptions = true)
public final class CollaborationReleaseCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".")
    private Path project;
    @Option(names = "--provider", defaultValue = "codex")
    private String provider;
    @Option(names = "--connection-instance-id", required = true)
    private String connectionInstanceId;

    /**
     * Creates the command.
     *
     * @param runtime composed CLI runtime
     */
    public CollaborationReleaseCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes the release.
     *
     * @return process exit code
     */
    @Override
    public Integer call() {
        try {
            new WorkspaceCollaborationService().release(project.toAbsolutePath()
                            .normalize(), provider,
                    connectionInstanceId);
            runtime.terminal()
                    .stdout("CLAIMS_RELEASED=true");
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
