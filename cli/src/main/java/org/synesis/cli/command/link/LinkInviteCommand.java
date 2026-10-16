package org.synesis.cli.command.link;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.link.identity.NodeIdentity;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Waits, on the project's runtime, for another computer that holds the same project to connect.
 */
@Command(name = "invite", description = "Create an invitation and wait for the other computer to join it.", mixinStandardHelpOptions = true)
public final class LinkInviteCommand extends LinkOperationCommand {

    @Option(names = "--expect", description = "Node ID the joining computer must have ('synesis identity show --project' there).")
    private String expect;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public LinkInviteCommand(CliRuntime runtime) {
        super(runtime);
    }

    LinkInviteCommand(CliRuntime runtime, RuntimeControl.Source source, long pollMillis) {
        super(runtime, source, pollMillis);
    }

    @Override
    String begin(RuntimeControl control) throws IOException, RuntimeControl.Rejected {
        Map<String, Object> body = new HashMap<>();
        if (expect != null) {
            if (!NodeIdentity.isNodeId(expect)) {
                throw new RuntimeControl.Rejected("INVALID_NODE_ID");
            }
            body.put("expectedPeer", expect);
        }
        Map<String, Object> started = control.command("invite", body);
        runtime.terminal().stdout("OPERATION_ID=" + started.get("operationId"));
        runtime.terminal().stdout("INVITE_LINK=" + started.get("inviteUri"));
        runtime.terminal().stdout("EXPIRES_AT=" + started.get("expiresAt"));
        return String.valueOf(started.get("operationId"));
    }
}
