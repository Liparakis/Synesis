package org.synesis.cli.command.link;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.synesis.cli.bootstrap.CliRuntime;

import org.synesis.link.identity.NodeIdentity;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Makes a project invitation on the project's runtime and waits for another computer to open it (ADR-0098, "Joining,
 * revised"). The link carries the project's identity and
 * the URL of its code remote, and a computer that opens it is held until it is accepted: by the question this command
 * asks at a terminal, or by {@code synesis link accept} in a script. On Windows it also says when the firewall rule is
 * missing or names another version, which keeps the other computer from dialing this one, and prints the {@code FIX=}.
 */
@Command(name = "invite", description = "Create a project invitation and wait for the other computer to open it.", mixinStandardHelpOptions = true)
public final class LinkInviteCommand extends LinkOperationCommand {

    private static final int REQUEST_POLLS = 10;

    @Option(names = "--expect", description = "Node ID the joining computer must have ('synesis identity show --project' there).")
    private String expect;
    private final Supplier<List<String>> firewallNotes;
    private boolean projectInvitation;

    /**
     * Creates the command.
     *
     * @param runtime composed runtime
     */
    public LinkInviteCommand(CliRuntime runtime) {
        super(runtime);
        this.firewallNotes = LinkFirewallRule::notes;
    }

    LinkInviteCommand(CliRuntime runtime, RuntimeControl.Source source, long pollMillis, Supplier<List<String>> firewallNotes) {
        super(runtime, source, pollMillis);
        this.firewallNotes = firewallNotes;
    }

    @Override
    String begin(RuntimeControl control) throws IOException, RuntimeControl.Rejected {
        Map<String, Object> body = new HashMap<>();
        body.put("project", true);
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
        projectInvitation = "PROJECT".equals(started.get("kind"));
        if (projectInvitation) {
            printCodeRemote(started);
        }
        firewallNotes.get().forEach(runtime.terminal()::stdout);
        return String.valueOf(started.get("operationId"));
    }

    /**
     * At a terminal, asks the owner whether the computer that just opened the invitation may join, and tells the runtime
     * the answer through the same accept and decline the other commands use. Anything but an explicit yes, including the
     * end of input, declines: a join is a trust decision and is never made by default. A script ({@code --porcelain}, no
     * terminal) is not asked and decides with {@code link accept} by hand, as before.
     */
    @Override
    void afterConnected(RuntimeControl control, Path root, String node) throws IOException, RuntimeControl.Rejected, InterruptedException {
        if (!projectInvitation || !runtime.terminal().interactive()) {
            return;
        }
        Map<?, ?> request = awaitRequest(control, node);
        if (request == null) {
            return;
        }
        runtime.terminal().stdout("JOIN_REQUEST=" + node + " REQUESTED_AT=" + request.get("requestedAt") + " EXPIRES_AT=" + request.get("expiresAt"));
        runtime.terminal().stdoutRaw(node + " wants to join " + (root.getFileName() == null ? root : root.getFileName()) + ". Accept? [y/N] ");
        String answer = runtime.terminal().readLine();
        if (answer == null) {
            runtime.terminal().stdout("");
        }
        boolean yes = answer != null && (answer.strip().equalsIgnoreCase("y") || answer.strip().equalsIgnoreCase("yes"));
        LinkDecideCommand.decide(runtime, control, yes ? "accept-join" : "decline-join", node);
    }

    /**
     * The session is usable a moment before the runtime lists its join request, so the request is looked for a few polls.
     */
    private Map<?, ?> awaitRequest(RuntimeControl control, String node) throws IOException, RuntimeControl.Rejected, InterruptedException {
        for (int poll = 0; poll < REQUEST_POLLS; poll++) {
            if (control.read("snapshot").get("onboarding") instanceof Map<?, ?> onboarding && onboarding.get("joinRequests") instanceof List<?> requests) {
                for (Object request : requests) {
                    if (request instanceof Map<?, ?> fields && node.equals(fields.get("nodeId"))) {
                        return fields;
                    }
                }
            }
            Thread.sleep(pollMillis());
        }
        return null;
    }

    private void printCodeRemote(Map<String, Object> started) {
        if (started.get("codeRemoteUrl") instanceof String url) {
            runtime.terminal().stdout("CODE_REMOTE=" + started.get("codeRemoteName"));
            runtime.terminal().stdout("CODE_REMOTE_URL=" + url);
            return;
        }
        runtime.terminal().stdout("CODE_REMOTE_URL=NONE");
        String why = switch (String.valueOf(started.get("codeRemoteReason"))) {
            case "SEVERAL_GIT_REMOTES" -> "this project has several Git remotes and no 'code.remote' names one";
            case "URL_NOT_SHAREABLE" -> "the code remote's URL is not an https://, ssh:// or user@host:path URL";
            case "CODE_REMOTE_MISSING" -> "'code.remote' names a remote this project does not have";
            default -> "this project has no Git remote";
        };
        // Code moves over Link (ADR-0098 step 3); a remote is only a mirror, so its absence is information, not a stop.
        runtime.terminal().stdout("NOTE=" + why + ", so code moves over Link only, with no mirror");
    }
}
