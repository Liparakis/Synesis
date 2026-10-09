package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Binds an intent to an exact contract revision.
 */
@Command(name = "bind", description = "Bind an intent to an exact contract revision.", mixinStandardHelpOptions = true)
public final class CollaborationContractBindCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private Path project;
    @Option(names = "--provider", defaultValue = "codex", description = "Provider ID of the calling session (default: codex).")
    private String provider;
    @Option(names = "--connection-instance-id", required = true, description = "Connection instance ID of the calling provider session.")
    private String connection;
    @Option(names = "--intent", required = true, description = "Work intent ID to bind.")
    private UUID intent;
    @Option(names = "--contract", required = true, description = "Shared contract ID to bind to.")
    private UUID contract;
    @Option(names = "--revision", required = true, description = "Exact contract revision number to bind.")
    private long revision;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public CollaborationContractBindCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes binding. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            new WorkspaceCollaborationService().bindContract(project.toAbsolutePath().normalize(), provider, connection, intent, contract, revision);
            runtime.terminal().stdout("CONTRACT_BOUND=" + contract + " REVISION=" + revision);
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("CONTRACT_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
