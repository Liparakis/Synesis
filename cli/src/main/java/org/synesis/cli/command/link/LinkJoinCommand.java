package org.synesis.cli.command.link;

import java.io.IOException;
import java.util.Map;

import org.synesis.cli.bootstrap.CliRuntime;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Joins an invitation from the project's runtime, so that the connection stays with it.
 */
@Command(name = "join", description = "Join an invitation from the other computer.", mixinStandardHelpOptions = true)
public final class LinkJoinCommand extends LinkOperationCommand {

    @Parameters(index = "0", arity = "1", description = "The synesis://join/... link.")
    private String invitation;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public LinkJoinCommand(CliRuntime runtime) {
        super(runtime);
    }

    LinkJoinCommand(CliRuntime runtime, RuntimeControl.Source source, long pollMillis) {
        super(runtime, source, pollMillis);
    }

    @Override
    String begin(RuntimeControl control) throws IOException, RuntimeControl.Rejected {
        Map<String, Object> started = control.command("join", Map.of("inviteUri", invitation));
        runtime.terminal().stdout("OPERATION_ID=" + started.get("operationId"));
        runtime.terminal().stdout("HOST_NODE_ID=" + started.get("peerIdentity"));
        runtime.terminal().stdout("ANSWER_LINK=" + started.get("answerUri"));
        runtime.terminal().stdout("EXPIRES_AT=" + started.get("expiresAt"));
        return String.valueOf(started.get("operationId"));
    }
}
