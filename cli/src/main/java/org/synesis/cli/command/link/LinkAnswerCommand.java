package org.synesis.cli.command.link;

import java.io.IOException;
import java.util.Map;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Gives a waiting invitation the answer of the computer that joined it, which is needed when the two computers are on
 * different networks and cannot find each other from the invitation alone.
 */
@Command(name = "answer", description = "Give a waiting invitation the joining computer's answer link.", mixinStandardHelpOptions = true)
public final class LinkAnswerCommand extends LinkOperationCommand {

    @Parameters(index = "0", arity = "1", description = "OPERATION_ID printed by 'synesis link invite'.")
    private String operation;
    @Parameters(index = "1", arity = "1", description = "The synesis://answer/... link printed by 'synesis link join' on the other computer.")
    private String answer;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public LinkAnswerCommand(CliRuntime runtime) {
        super(runtime);
    }

    LinkAnswerCommand(CliRuntime runtime, RuntimeControl.Source source, long pollMillis) {
        super(runtime, source, pollMillis);
    }

    @Override
    String begin(RuntimeControl control) throws IOException, RuntimeControl.Rejected {
        control.command("answer", Map.of("operationId", operation, "answerUri", answer));
        runtime.terminal().stdout("OPERATION_ID=" + operation);
        return operation;
    }
}
