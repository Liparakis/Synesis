package org.synesis.cli.command;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.CoordinationCommand;
import org.synesis.coordination.PredictionEventType;
import org.synesis.coordination.PredictionProjection;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Lists predictions reconstructed from ordered event replay. */
@Command(name = "list", description = "List predictions.", mixinStandardHelpOptions = true)
public final class PredictionListCommand implements Callable<Integer> {
    @Option(names = "--project") private Path project;
    @Option(names = "--endpoint", required = true) private URI endpoint;
    private final CliRuntime runtime;
    /** Creates a prediction list command.
     * @param runtime composed CLI runtime
     */
    public PredictionListCommand(CliRuntime runtime) { this.runtime = runtime; }
    /** Replays and prints prediction states.
     * @return stable process exit code
     */
    @Override public Integer call() {
        try {
            var location = CoordinationCliSupport.project(runtime, project);
            PredictionProjection projection = new PredictionProjection();
            var ids = new LinkedHashMap<UUID, Boolean>();
            for (var event : CoordinationCliSupport.replay(endpoint, 0)) {
                if (event.type() == PredictionEventType.PREDICTION_CREATED) ids.put(event.predictionId(), true);
                projection.apply(event);
            }
            runtime.terminal().stdout("PROJECT_ID=" + location.projectId());
            ids.keySet().forEach(id -> runtime.terminal().stdout("PREDICTION_ID=" + id + " STATE="
                    + projection.state(id).orElseThrow()));
            runtime.terminal().stdout("PREDICTIONS=" + ids.size());
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("PREDICTION_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
