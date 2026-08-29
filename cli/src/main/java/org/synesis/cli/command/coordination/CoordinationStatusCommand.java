package org.synesis.cli.command.coordination;


import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.persistence.PredictionEventStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Inspects durable coordination state without mutating it.
 */
@Command(name = "status", description = "Inspect durable coordination state.", mixinStandardHelpOptions = true)
public final class CoordinationStatusCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory.")
    private Path project;
    @Option(names = "--data", description = "Coordinator state directory.")
    private Path data;

    /**
     * Creates a status command.
     *
     * @param runtime composed CLI runtime
     */
    public CoordinationStatusCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Prints the current sequence and projections.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            var store = new PredictionEventStore(CoordinationCliSupport.data(location, data), location.projectId());
            runtime.terminal()
                    .stdout("COORDINATION_STATUS=PASS");
            runtime.terminal()
                    .stdout("PROJECT_ID=" + location.projectId());
            runtime.terminal()
                    .stdout("PROJECT_SEQUENCE=" + store.headSequence());
            runtime.terminal()
                    .stdout("PREDICTIONS=" + store.projection()
                            .snapshot()
                            .size());
            runtime.terminal()
                    .stdout("TASKS=" + store.coordinationProjection()
                            .tasks()
                            .size());
            runtime.terminal()
                    .stdout("OWNERSHIPS=" + store.coordinationProjection()
                            .ownerships()
                            .size());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal()
                    .stderr("COORDINATION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
