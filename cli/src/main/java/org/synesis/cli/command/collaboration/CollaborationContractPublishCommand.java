package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Publishes a shared contract revision.
 */
@Command(name = "publish", description = "Publish a shared contract.", mixinStandardHelpOptions = true)
public final class CollaborationContractPublishCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private Path project;
    @Option(names = "--provider", defaultValue = "codex", description = "Provider ID of the calling session (default: codex).")
    private String provider;
    @Option(names = "--connection-instance-id", required = true, description = "Connection instance ID of the calling provider session.")
    private String connection;
    @Option(names = "--contract", required = true, description = "Contract ID; publishing an existing ID creates its next revision.")
    private UUID contract;
    @Option(names = "--body", required = true, description = "Contract text.")
    private String body;
    @Option(names = "--selector", split = ",", description = "Comma-separated selector references the contract covers.")
    private List<String> selectors = List.of();

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public CollaborationContractPublishCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes publication. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            var record = new WorkspaceCollaborationService().publishContract(project.toAbsolutePath().normalize(), provider, connection, contract, body, selectors);
            runtime.terminal().stdout("CONTRACT_PUBLISHED=" + record.contractId() + " REVISION=" + record.revision());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("CONTRACT_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
