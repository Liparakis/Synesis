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
 * Opens a coordination request against a conflicting intent.
 */
@Command(name = "request", description = "Request coordination with a conflicting participant.", mixinStandardHelpOptions = true)
public final class CollaborationRequestCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private Path project;
    @Option(names = "--provider", defaultValue = "codex", description = "Provider ID of the calling session (default: codex).")
    private String provider;
    @Option(names = "--connection-instance-id", required = true, description = "Connection instance ID of the calling provider session.")
    private String connection;
    @Option(names = "--conflict", required = true, description = "ID of the conflicting work intent.")
    private UUID conflict;
    @Option(names = "--kind", defaultValue = "CONTRACT", description = "Request kind: CONTRACT, HANDOFF, SCOPE_REVISION, or REVIEW (default: CONTRACT).")
    private CoordinationRequest.Kind kind;
    @Option(names = "--proposal", required = true, description = "Proposal text for resolving the conflict.")
    private String proposal;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public CollaborationRequestCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Executes request. @return process exit code
     */
    @Override
    public Integer call() {
        try {
            var request = new WorkspaceCollaborationService().request(project.toAbsolutePath().normalize(), provider, connection, conflict, kind, proposal);
            runtime.terminal().stdout("COORDINATION_REQUESTED=" + request.requestId());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
