package org.synesis.cli.command.operator;

import java.util.Optional;
import java.util.concurrent.Callable;

import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.command.ProjectOptions;
import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.workspace.application.conversation.ConversationService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Closes a request an agent put to the operator (ADR-0093). Synesis cannot observe that an
 * agent's question was answered, so the operator says so. A request Synesis raised itself is not
 * closed here: it closes when its condition is observed not to hold.
 */
@Command(name = "close", description = "Close a request an agent put to the operator, once you have dealt with it.", mixinStandardHelpOptions = true)
public final class OperatorCloseCommand implements Callable<Integer> {

    private final CliRuntime runtime;

    @Option(names = "--project", defaultValue = ".", description = "Project directory (default: .).")
    private String project;

    @Option(names = "--because", defaultValue = "Closed by the operator.", description = "What you did about it. Kept with the request.")
    private String because;

    @Parameters(index = "0", description = "Request identifier, as `synesis operator list` prints it.")
    private String request;

    /**
     * Creates the command.
     *
     * @param runtime CLI runtime
     */
    public OperatorCloseCommand(CliRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Integer call() {
        try {
            Optional<String> refusal = new ConversationService().closeOperatorRequest(ProjectOptions.locate(runtime, project), request, because);
            if (refusal.isPresent()) {
                runtime.terminal().stderr("OPERATOR_REQUEST_NOT_CLOSED=" + refusal.get());
                return ExitCodes.COMMAND_FAILED;
            }
            runtime.terminal().stdout("OPERATOR_REQUEST_CLOSED=" + request);
            return ExitCodes.OK;
        } catch (Exception failure) {
            runtime.terminal().stderr("OPERATOR_ERROR=" + failure.getMessage());
            return ExitCodes.LOCAL_CONFIGURATION;
        }
    }
}
