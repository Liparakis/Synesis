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
 * Offers an active claim to another participant for explicit acceptance.
 */
@Command(name = "handoff", description = "Offer a claim handoff.", mixinStandardHelpOptions = true)
public final class CollaborationHandoffCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private Path project;
    @Option(names = "--provider", defaultValue = "codex", description = "Provider ID of the calling session (default: codex).")
    private String provider;
    @Option(names = "--connection-instance-id", required = true, description = "Connection instance ID of the calling provider session.")
    private String connection;
    @Option(names = "--intent", required = true, description = "ID of the work intent whose claims are handed off.")
    private UUID intent;
    @Option(names = "--to", required = true, description = "Participant handle (agt_...) of the active receiver.")
    private String target;
    @Option(names = "--proposal", required = true, description = "Handoff proposal text.")
    private String proposal;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public CollaborationHandoffCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes handoff offer. @return process exit code.
     */
    @Override
    public Integer call() {
        try {
            var request = new WorkspaceCollaborationService().handoff(project.toAbsolutePath().normalize(), provider, connection, intent, target, proposal);
            runtime.terminal().stdout("HANDOFF_OFFERED=" + request.requestId());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
