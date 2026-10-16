package org.synesis.cli.command.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.StatusRenderer;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.ProjectApplicationService;

import picocli.CommandLine;

/**
 * {@code synesis link invite|join|answer} start the operation on the project's running runtime, print the links, and
 * report whether a new session was established (ADR-0098 step 2, slice 4).
 */
@Timeout(60)
class LinkCommandsTest {

    @TempDir
    Path tmp;
    private Path root;
    private FakeRuntime runtime;

    private record Run(int exit, List<String> stdout, List<String> stderr) {

        @Override
        public String toString() {
            return "exit=" + exit + " stdout=" + stdout + " stderr=" + stderr;
        }
    }

    @BeforeEach
    void project() throws Exception {
        root = Files.createDirectories(tmp.resolve("project"));
        new ProjectApplicationService().init(root);
        runtime = new FakeRuntime();
    }

    private Run run(Function<CliRuntime, Callable<Integer>> command, String... arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        List<String> all = new ArrayList<>(List.of("--project", root.toString()));
        all.addAll(List.of(arguments));
        int exit = new CommandLine(command.apply(cli)).execute(all.toArray(String[]::new));
        return new Run(exit, out.toString(StandardCharsets.UTF_8).lines().toList(), err.toString(StandardCharsets.UTF_8).lines().toList());
    }

    private Run invite(String... arguments) {
        return run(cli -> new LinkInviteCommand(cli, project -> runtime, 5), arguments);
    }

    @Test
    void inviteStartsTheInvitationOnTheRuntimePrintsItAndReportsThePeerThatConnected() throws Exception {
        String peer = NodeIdentity.generate().nodeId();
        runtime.connectsAfterReads = 3;
        runtime.peerNode = peer;

        Run invited = invite("--expect", peer);

        assertEquals(ExitCodes.OK, invited.exit(), invited.toString());
        assertEquals(List.of("OPERATION_ID=op-1", "INVITE_LINK=synesis://join/SLB3-test", "EXPIRES_AT=2026-10-16T12:00:00Z", "LINK_STATE=CONNECTED", "PEER_NODE_ID=" + peer), invited.stdout());
        assertEquals(List.of("invite " + Map.of("expectedPeer", peer)), runtime.commands);
    }

    @Test
    void aSessionThatAlreadyExistedIsNotTheOneConnected() throws Exception {
        runtime.existingSession = "old-session";

        Run invited = invite("--wait-seconds", "1");

        assertEquals(ExitCodes.COMMAND_FAILED, invited.exit(), invited.toString());
        assertTrue(invited.stdout().contains("LINK_STATE=WAITING"), invited.toString());
        assertTrue(invited.stdout().stream().noneMatch(line -> line.startsWith("PEER_NODE_ID")), invited.toString());
    }

    @Test
    void anInvitationThatEndsWithoutASessionReportsFailure() throws Exception {
        runtime.endsWithoutSession = true;

        Run invited = invite();

        assertEquals(ExitCodes.COMMAND_FAILED, invited.exit(), invited.toString());
        assertEquals("LINK_STATE=FAILED", invited.stdout().getLast());
    }

    @Test
    void waitingZeroSecondsReturnsAtOnceWithTheLinks() throws Exception {
        Run invited = invite("--wait-seconds", "0");

        assertEquals(ExitCodes.OK, invited.exit(), invited.toString());
        assertEquals("LINK_STATE=PENDING", invited.stdout().getLast());
    }

    @Test
    void anExpectedPeerThatIsNotANodeIdIsRefusedBeforeTheRuntimeIsAsked() throws Exception {
        Run invited = invite("--expect", "somebody");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, invited.exit());
        assertEquals(List.of("ERROR=INVALID_NODE_ID"), invited.stderr());
        assertEquals(List.of(), runtime.commands);
    }

    @Test
    void joinPrintsTheAnswerLinkForTheOtherNetworkAndTheHostAndReportsTheConnection() throws Exception {
        runtime.connectsAfterReads = 2;
        runtime.peerNode = "sl1-host";

        Run joined = run(cli -> new LinkJoinCommand(cli, project -> runtime, 5), "synesis://join/SLB3-abc");

        assertEquals(ExitCodes.OK, joined.exit(), joined.toString());
        assertEquals(List.of("OPERATION_ID=op-2", "HOST_NODE_ID=sl1-host", "ANSWER_LINK=synesis://answer/SLA3-test", "EXPIRES_AT=2026-10-16T12:00:00Z", "LINK_STATE=CONNECTED", "PEER_NODE_ID=sl1-host"),
                joined.stdout());
        assertEquals(List.of("join " + Map.of("inviteUri", "synesis://join/SLB3-abc")), runtime.commands);
    }

    @Test
    void answerGivesTheAnswerToTheNamedOperationAndWaitsForTheConnection() throws Exception {
        runtime.connectsAfterReads = 2;
        runtime.peerNode = "sl1-joiner";
        runtime.operation = "op-1";

        Run answered = run(cli -> new LinkAnswerCommand(cli, project -> runtime, 5), "op-1", "synesis://answer/SLA3-x");

        assertEquals(ExitCodes.OK, answered.exit(), answered.toString());
        assertEquals(List.of("OPERATION_ID=op-1", "LINK_STATE=CONNECTED", "PEER_NODE_ID=sl1-joiner"), answered.stdout());
        assertEquals(List.of("answer " + Map.of("operationId", "op-1", "answerUri", "synesis://answer/SLA3-x")), runtime.commands);
    }

    @Test
    void aRefusalOfTheRuntimeIsPrintedWithItsCodeAndTheExitCodeOfThatKindOfFailure() throws Exception {
        runtime.rejection = "INVITE_INVALID";

        Run joined = run(cli -> new LinkJoinCommand(cli, project -> runtime, 5), "synesis://join/SLB3-abc");

        assertEquals(ExitCodes.INVITE_INVALID, joined.exit());
        assertEquals(List.of("ERROR=INVITE_INVALID"), joined.stderr());
    }

    @Test
    void aRuntimeThatCannotBeReachedIsReportedAndNothingIsWaitedFor() throws Exception {
        Run joined = run(cli -> new LinkJoinCommand(cli, project -> {
            throw new IOException("project_runtime_unavailable");
        }, 5), "synesis://join/SLB3-abc");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, joined.exit());
        assertEquals(List.of("ERROR=PROJECT_RUNTIME_UNAVAILABLE"), joined.stderr());
    }

    /** A runtime whose network view gains a peer after a number of reads. */
    private static final class FakeRuntime implements RuntimeControl {

        final List<String> commands = new ArrayList<>();
        int connectsAfterReads = Integer.MAX_VALUE;
        String peerNode = "sl1-peer";
        String existingSession;
        boolean endsWithoutSession;
        String rejection;
        String operation = "op-1";
        private int networkReads;

        @Override
        public Map<String, Object> command(String name, Map<String, Object> body) throws Rejected {
            if (rejection != null) {
                throw new Rejected(rejection);
            }
            commands.add(name + " " + body);
            return switch (name) {
                case "invite" -> Map.of("operationId", "op-1", "inviteUri", "synesis://join/SLB3-test", "expiresAt", "2026-10-16T12:00:00Z");
                case "join" -> Map.of("operationId", "op-2", "answerUri", "synesis://answer/SLA3-test", "peerIdentity", "sl1-host", "expiresAt", "2026-10-16T12:00:00Z");
                default -> Map.of("operationId", operation, "state", "SENDING_TO_JOINER");
            };
        }

        @Override
        public Map<String, Object> read(String route) {
            if (route.equals("network")) {
                networkReads++;
                List<Map<String, Object>> peers = new ArrayList<>();
                if (existingSession != null) {
                    peers.add(Map.of("nodeId", "sl1-old", "sessionId", existingSession, "usable", true));
                }
                if (networkReads > connectsAfterReads) {
                    peers.add(Map.of("nodeId", peerNode, "sessionId", "new-session", "usable", true));
                }
                return Map.of("network", Map.of("peers", peers));
            }
            boolean pending = !endsWithoutSession;
            return Map.of("onboarding", Map.of("pendingHosts", pending ? List.of(Map.of("operationId", "op-1")) : List.of(), "pendingJoins", pending ? List.of(Map.of("operationId", "op-2")) : List.of()));
        }
    }
}
