package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Acknowledges one durable collaboration inbox item for the exact caller. */
@Command(name = "acknowledge", description = "Acknowledge a durable collaboration inbox item.",
        mixinStandardHelpOptions = true)
public final class CollaborationAcknowledgeCommand implements Callable<Integer> {
    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".") private Path project;
    @Option(names = "--provider", defaultValue = "codex") private String provider;
    @Option(names = "--connection-instance-id", required = true) private String connection;
    @Option(names = "--item", required = true) private UUID item;

    /** Creates the acknowledgement command.
     * @param runtime CLI runtime
     */
    public CollaborationAcknowledgeCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /** Executes the exact-caller acknowledgement.
     * @return process exit code
     */
    @Override
    public Integer call() {
        try {
            new WorkspaceCollaborationService().acknowledgeInbox(project.toAbsolutePath().normalize(), provider,
                    connection, item);
            runtime.terminal().stdout("INBOX_ACKNOWLEDGED=" + item);
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
