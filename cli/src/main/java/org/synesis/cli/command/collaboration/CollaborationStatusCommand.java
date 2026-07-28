package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Lists active work intents and their claimed selectors. */
@Command(name = "status", description = "Show active participants and claims.", mixinStandardHelpOptions = true)
public final class CollaborationStatusCommand implements Callable<Integer> {
    private final CliRuntime runtime;
    @Option(names = "--project", defaultValue = ".")
    private Path project;

    /**
     * Creates the command.
     * @param runtime composed CLI runtime
     */
    public CollaborationStatusCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /** Executes the status query. @return process exit code */
    @Override
    public Integer call() {
        try {
            var snapshot = new WorkspaceCollaborationService().status(project.toAbsolutePath().normalize());
            for (var intent : snapshot.intents()) {
                runtime.terminal().stdout("PARTICIPANT=" + intent.participant() + " PROVIDER=" + intent.provider()
                        + " INTENT=" + intent.intentId() + " CLAIMS=" + intent.selectors());
            }
            for (var participant : snapshot.participants()) {
                runtime.terminal().stdout("AGENT=" + participant.id() + " PROVIDER=" + participant.provider()
                        + " STATE=" + participant.state() + " GOAL=" + participant.goal());
            }
            for (var request : snapshot.requests()) {
                runtime.terminal().stdout("REQUEST=" + request.requestId() + " FROM=" + request.requester()
                        + " TO=" + request.target() + " STATUS=" + request.status() + " KIND=" + request.kind());
            }
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
