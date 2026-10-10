package org.synesis.cli.command.prediction;


import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * Records an owner response to one prediction.
 */
@Command(name = "respond", description = "Accept, revise, or reject a prediction.", mixinStandardHelpOptions = true)
public final class PredictionRespondCommand implements Callable<Integer> {

    private final CliRuntime runtime;
    @Option(names = "--project", description = "Initialized project directory (default: current directory).")
    private Path project;
    @Option(names = "--endpoint", required = true, description = "Coordination server endpoint URI, such as http://127.0.0.1:48123/.")
    private URI endpoint;
    @Option(names = "--profile", required = true, description = "Node profile directory holding the signing identity.")
    private Path profile;
    @Option(names = "--supervisor", required = true, description = "ID of the responding supervisor.")
    private String supervisor;
    @Option(names = "--worker", required = true, description = "ID of the responding worker.")
    private String worker;
    @Option(names = "--prediction", required = true, description = "ID of the prediction being answered.")
    private UUID prediction;
    @Option(names = "--action", required = true, description = "receive|exact|equivalent|revise|reject")
    private String action;
    @Option(names = "--reason", defaultValue = "", description = "Free-text reason recorded with the response (default: empty).")
    private String reason;

    /**
     * Creates a prediction response command.
     *
     * @param runtime composed CLI runtime
     */
    public PredictionRespondCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Submits the signed response event.
     *
     * @return stable process exit code
     */
    @Override
    public Integer call() {
        try {
            var signer = CoordinationCliSupport.Signer.resolve(runtime, project, profile);
            PredictionEventType type = switch (action.toLowerCase(java.util.Locale.ROOT)) {
                case "receive" -> PredictionEventType.REQUEST_RECEIVED;
                case "exact" -> PredictionEventType.ACCEPTED_EXACT;
                case "equivalent" -> PredictionEventType.ACCEPTED_EQUIVALENT;
                case "revise" -> PredictionEventType.CONTRACT_REVISED;
                case "reject" -> PredictionEventType.REQUEST_REJECTED;
                default -> throw new IllegalArgumentException("INVALID_RESPONSE_ACTION");
            };
            var event = signer.submit(endpoint, prediction, type, supervisor, worker, reason.getBytes(StandardCharsets.UTF_8));
            runtime.terminal().stdout("PREDICTION_RESPONSE=" + type);
            runtime.terminal().stdout("PREDICTION_ID=" + prediction);
            runtime.terminal().stdout("PROJECT_SEQUENCE=" + event.sequence());
            return ExitCodes.OK;
        } catch (Exception failure) {
            return CoordinationCliSupport.fail(runtime, "PREDICTION_ERROR", failure);
        }
    }
}
