package org.synesis.cli.command.speculation;


import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.command.coordination.CoordinationCliSupport;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Invalidates a prediction and removes any local speculation workspace.
 */
@Command(name = "invalidate", description = "Invalidate a prediction.", mixinStandardHelpOptions = true)
public final class SpeculationInvalidateCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--prediction", required = true, description = "ID of the prediction to invalidate.")
    private UUID prediction;

    /**
     * Creates an invalidation command.
     *
     * @param runtime composed CLI runtime
     */
    public SpeculationInvalidateCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Appends invalidation and best-effort closes local speculation. @return stable exit code
     */
    @Override
    public Integer call() {
        try {
            var signer = CoordinationCliSupport.Signer.resolve(runtime, project, profile);
            signer.submit(endpoint, prediction, PredictionEventType.PREDICTION_INVALIDATED, new byte[0]);
            try {
                SpeculationRetireCommand.close(signer.location(), prediction);
            } catch (java.io.IOException ignored) {
            }
            runtime.terminal().stdout("PREDICTION_INVALIDATED=true");
            return ExitCodes.OK;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "SPECULATION_ERROR", failure);
        }
    }
}
