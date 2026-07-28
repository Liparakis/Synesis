package org.synesis.cli.command.collaboration;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.coordination.persistence.PredictionEventStore;
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
            var location = new ProjectApplicationService().locate(project.toAbsolutePath().normalize());
            var store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
            for (var intent : store.collaborationProjection().activeIntents()) {
                runtime.terminal().stdout("PARTICIPANT=" + intent.participant() + " PROVIDER=" + intent.provider()
                        + " INTENT=" + intent.intentId() + " CLAIMS=" + intent.selectors());
            }
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("COLLABORATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
