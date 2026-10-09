package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Responds to a coordination request addressed to this session.
 */
@Command(name = "respond", description = "Respond to a coordination request.", mixinStandardHelpOptions = true)
public final class CollaborationRespondCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private Path project;
    @Option(names = "--provider", defaultValue = "codex", description = "Provider ID of the calling session (default: codex).")
    private String provider;
    @Option(names = "--connection-instance-id", required = true, description = "Connection instance ID of the calling provider session.")
    private String connection;
    @Option(names = "--request", required = true, description = "ID of the coordination request to answer.")
    private UUID request;
    @Option(names = "--status", required = true, description = "Response: ACCEPTED, REVISED, REJECTED, CANCELLED, or COMPLETED.")
    private CoordinationRequest.Status status;
    @Option(names = "--proposal", defaultValue = "", description = "Revised proposal text (default: empty).")
    private String proposal;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public CollaborationRespondCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes response. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            new WorkspaceCollaborationService().respond(project.toAbsolutePath().normalize(), provider, connection, request, status, proposal);
            runtime.terminal().stdout("COORDINATION_RESPONDED=" + request);
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
