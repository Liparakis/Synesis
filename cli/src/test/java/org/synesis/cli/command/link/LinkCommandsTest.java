package org.synesis.cli.command.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
import org.synesis.link.candidate.Candidate;
import org.synesis.link.candidate.CandidateType;
import org.synesis.link.onboarding.Onboarding;
import org.synesis.link.protocol.CompactBootstrapInvitation;
import org.synesis.link.protocol.ProjectInvitation;
import org.synesis.link.protocol.ProtocolVersion;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;
import org.synesis.workspace.discovery.KnownProjectRegistry;

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
        return run(cli -> new LinkInviteCommand(cli, project -> runtime, 5, List::of), arguments);
    }

    @Test
    void inviteStartsTheInvitationOnTheRuntimePrintsItAndReportsThePeerThatConnected() throws Exception {
        String peer = NodeIdentity.generate().nodeId();
        runtime.connectsAfterReads = 3;
        runtime.peerNode = peer;

        Run invited = invite("--expect", peer);

        assertEquals(ExitCodes.OK, invited.exit(), invited.toString());
        assertEquals(List.of("OPERATION_ID=op-1", "INVITE_LINK=synesis://project/SLP1-test", "EXPIRES_AT=2026-10-16T12:00:00Z", "CODE_REMOTE=origin", "CODE_REMOTE_URL=https://example.test/p.git", "LINK_STATE=CONNECTED", "PEER_NODE_ID=" + peer), invited.stdout());
        assertEquals(1, runtime.commands.size());
        assertTrue(runtime.commands.getFirst().startsWith("invite ") && runtime.commands.getFirst().contains("project=true") && runtime.commands.getFirst().contains("expectedPeer=" + peer), runtime.commands.toString());
    }

    @Test
    void aProjectInvitationWithoutAnUrlSaysWhyAndAMemberHostsAPlainInvitation() throws Exception {
        runtime.inviteKind = "NO_URL";

        Run none = invite("--wait-seconds", "0");

        assertTrue(none.stdout().contains("CODE_REMOTE_URL=NONE"), none.toString());
        assertTrue(none.stdout().stream().anyMatch(line -> line.startsWith("NOTE=this project has several Git remotes")), none.toString());

        runtime.inviteKind = "PLAIN";
        Run plain = invite("--wait-seconds", "0");

        assertEquals(ExitCodes.OK, plain.exit(), plain.toString());
        assertEquals(List.of("OPERATION_ID=op-1", "INVITE_LINK=synesis://join/SLB3-test", "EXPIRES_AT=2026-10-16T12:00:00Z", "LINK_STATE=PENDING"), plain.stdout());
    }

    @Test
    void inviteSaysAfterTheLinksWhenTheFirewallKeepsTheOtherComputerFromDialing() throws Exception {
        Run invited = run(cli -> new LinkInviteCommand(cli, project -> runtime, 5, () -> List.of("LINK_FIREWALL=MISSING", "NOTE=fix it")), "--wait-seconds", "0");

        assertEquals(ExitCodes.OK, invited.exit(), invited.toString());
        assertEquals(List.of("OPERATION_ID=op-1", "INVITE_LINK=synesis://project/SLP1-test", "EXPIRES_AT=2026-10-16T12:00:00Z", "CODE_REMOTE=origin", "CODE_REMOTE_URL=https://example.test/p.git", "LINK_FIREWALL=MISSING", "NOTE=fix it", "LINK_STATE=PENDING"), invited.stdout());
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

    private Run runIn(String folder, Function<CliRuntime, Callable<Integer>> command, String... arguments) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ConsoleTerminal terminal = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        CliRuntime cli = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        List<String> all = new ArrayList<>();
        if (folder != null) {
            all.addAll(List.of("--project", folder));
        }
        all.addAll(List.of(arguments));
        int exit = new CommandLine(command.apply(cli)).execute(all.toArray(String[]::new));
        return new Run(exit, out.toString(StandardCharsets.UTF_8).lines().toList(), err.toString(StandardCharsets.UTF_8).lines().toList());
    }

    private String projectLink(String url) throws Exception {
        NodeIdentity host = NodeIdentity.generate();
        var location = new ProjectApplicationService().require(root);
        var connection = CompactBootstrapInvitation.createMinimized(host, ProtocolVersion.V1, Instant.now(), Instant.now().plusSeconds(300),
                new byte[CompactBootstrapInvitation.MINIMIZED_CAPABILITY_BYTES],
                List.of(new Candidate(CandidateType.MANUAL, InetAddress.getByName("203.0.113.7"), 4433, 0)));
        return ProjectInvitation.create(host, connection, location.projectId(), location.createdAt(), url).shareLink();
    }

    @Test
    void joiningAProjectLinkFindsTheProjectInTheRegistryWithoutAFolderAndAsksItsRuntimeToFollow() throws Exception {
        KnownProjectRegistry registry = new KnownProjectRegistry(tmp.resolve("registry.json"));
        registry.register(root);
        String link = projectLink("https://example.test/p.git");
        runtime.connectsAfterReads = 2;
        runtime.peerNode = "sl1-host";

        Run joined = runIn(null, cli -> new LinkJoinCommand(cli, project -> runtime, 5, registry), link);

        assertEquals(ExitCodes.OK, joined.exit(), joined.toString());
        assertEquals(List.of("join-project-link " + Map.of("link", link)), runtime.commands);
        assertTrue(joined.stdout().contains("PROJECT_FOLDER=" + root), joined.toString());
        assertTrue(joined.stdout().contains("LINK_STATE=CONNECTED"), joined.toString());
    }

    @Test
    void joiningAProjectLinkSaysWhenThisComputerCannotFetchFromTheCodeRemoteAndStillJoins() throws Exception {
        KnownProjectRegistry registry = new KnownProjectRegistry(tmp.resolve("registry.json"));
        registry.register(root);
        GitProcessRunner.run(root, "init");
        GitProcessRunner.run(root, "remote", "add", "synesis", tmp.resolve("gone.git").toString());
        runtime.connectsAfterReads = 2;
        runtime.peerNode = "sl1-host";
        runtime.codeRemote = "synesis";

        Run joined = runIn(null, cli -> new LinkJoinCommand(cli, project -> runtime, 5, registry), projectLink("https://example.test/p.git"));

        assertEquals(ExitCodes.OK, joined.exit(), joined.toString());
        assertTrue(joined.stdout().contains("CODE_REMOTE_CHECK=CODE_REMOTE_UNAVAILABLE"), joined.toString());
        assertTrue(joined.stdout().stream().anyMatch(line -> line.startsWith("CODE_REMOTE_DETAIL=") && line.contains("git fetch synesis")), joined.toString());
        assertTrue(joined.stdout().contains("LINK_STATE=CONNECTED"), joined.toString());
    }

    @Test
    void joiningAProjectLinkOfAProjectNotOnThisComputerTellsWhereToCloneFromAndDoesNothing() throws Exception {
        KnownProjectRegistry registry = new KnownProjectRegistry(tmp.resolve("registry.json"));

        Run joined = runIn(null, cli -> new LinkJoinCommand(cli, project -> runtime, 5, registry), projectLink("https://example.test/p.git"));

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, joined.exit(), joined.toString());
        assertEquals(List.of("ERROR=PROJECT_NOT_ON_THIS_COMPUTER"), joined.stderr());
        assertTrue(joined.stdout().contains("CODE_REMOTE_URL=https://example.test/p.git"), joined.toString());
        assertTrue(joined.stdout().stream().anyMatch(line -> line.startsWith("NEXT=") && line.contains("--into")), joined.toString());
        assertEquals(List.of(), runtime.commands);
    }

    @Test
    void joiningAProjectLinkIntoANewFolderMakesItACopyThatGetsTheCodeOverLinkAndAsksItsRuntimeToFollow() throws Exception {
        KnownProjectRegistry registry = new KnownProjectRegistry(tmp.resolve("registry.json"));
        Path into = tmp.resolve("joined");
        String link = projectLink("https://example.test/p.git");
        runtime.connectsAfterReads = 2;
        runtime.peerNode = "sl1-host";

        Run joined = runIn(null, cli -> new LinkJoinCommand(cli, project -> runtime, 5, registry), link, "--into", into.toString());

        assertEquals(ExitCodes.OK, joined.exit(), joined.toString());
        assertEquals(List.of("join-project-link " + Map.of("link", link)), runtime.commands);
        assertTrue(joined.stdout().contains("PROJECT_FOLDER=" + into), joined.toString());
        assertTrue(joined.stdout().stream().anyMatch(line -> line.startsWith("NEXT=") && line.contains("over Link")), joined.toString());
        assertTrue(Files.exists(into.resolve(".synesis/project.json")), "the folder is a copy of the project");
        assertFalse(Files.exists(into.resolve(".git")), "its code comes over Link once the owner accepts");
        assertEquals(List.of(into), registry.projects().stream().map(KnownProjectRegistry.ProjectView::path).toList());
    }

    @Test
    void joiningIntoAFolderThatHoldsAnythingIsRefusedBeforeTheRuntimeIsAsked() throws Exception {
        KnownProjectRegistry registry = new KnownProjectRegistry(tmp.resolve("registry.json"));
        Path into = Files.createDirectories(tmp.resolve("busy"));
        Files.writeString(into.resolve("notes.txt"), "mine\n");

        Run joined = runIn(null, cli -> new LinkJoinCommand(cli, project -> runtime, 5, registry), projectLink(null), "--into", into.toString());

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, joined.exit(), joined.toString());
        assertEquals(List.of("ERROR=PROJECT_FOLDER_NOT_EMPTY"), joined.stderr());
        assertEquals(List.of(), runtime.commands);
        assertFalse(Files.exists(into.resolve(".synesis")), "nothing was written into it");
    }

    @Test
    void joiningAProjectLinkWithAFolderChecksThatFolderIsTheProject() throws Exception {
        KnownProjectRegistry registry = new KnownProjectRegistry(tmp.resolve("registry.json"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        new ProjectApplicationService().init(other);
        String link = projectLink(null);

        Run wrong = runIn(other.toString(), cli -> new LinkJoinCommand(cli, project -> runtime, 5, registry), link);
        Run right = runIn(root.toString(), cli -> new LinkJoinCommand(cli, project -> runtime, 5, registry), link, "--wait-seconds", "0");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, wrong.exit(), wrong.toString());
        assertEquals(List.of("ERROR=PROJECT_IDENTITY_MISMATCH"), wrong.stderr());
        assertEquals(ExitCodes.OK, right.exit(), right.toString());
        assertEquals(List.of("join-project-link " + Map.of("link", link)), runtime.commands);
        assertEquals(List.of(root), registry.projects().stream().map(KnownProjectRegistry.ProjectView::path).toList(), "the chosen folder is registered, the wrong one is not");
    }

    @Test
    void aProjectLinkOfAnotherCompatibilityNumberIsRefusedBeforeTheRuntimeIsAsked() throws Exception {
        Run joined = runIn(root.toString(), cli -> new LinkJoinCommand(cli, project -> runtime, 5), "synesis://project/SLP1-AAAA");

        assertEquals(ExitCodes.INCOMPATIBLE_VERSION, joined.exit(), joined.toString());
        assertEquals(List.of(), runtime.commands);
    }

    @Test
    void requestsListsTheComputersWaitingToJoin() throws Exception {
        runtime.joinRequests = List.of(Map.of("nodeId", "sl1-friend", "requestedAt", "2026-10-16T12:00:00Z", "expiresAt", "2026-10-16T12:10:00Z"));

        Run listed = run(cli -> new LinkRequestsCommand(cli, project -> runtime));

        assertEquals(ExitCodes.OK, listed.exit(), listed.toString());
        assertEquals(List.of("JOIN_REQUESTS=1", "JOIN_REQUEST=sl1-friend REQUESTED_AT=2026-10-16T12:00:00Z EXPIRES_AT=2026-10-16T12:10:00Z"), listed.stdout());
    }

    @Test
    void acceptAndDeclineTellTheRuntimeWhichNodeAndPrintTheOutcome() throws Exception {
        String node = NodeIdentity.generate().nodeId();

        Run accepted = run(cli -> new LinkAcceptCommand(cli, project -> runtime), node);
        Run declined = run(cli -> new LinkDeclineCommand(cli, project -> runtime), node);

        assertEquals(List.of("JOIN_REQUEST=" + node + " STATE=ACCEPTED"), accepted.stdout());
        assertEquals(List.of("JOIN_REQUEST=" + node + " STATE=DECLINED"), declined.stdout());
        assertEquals(List.of("accept-join " + Map.of("nodeId", node), "decline-join " + Map.of("nodeId", node)), runtime.commands);
    }

    @Test
    void decidingOnARequestThatIsGoneIsReportedWithTheRuntimesCode() throws Exception {
        runtime.rejection = "JOIN_REQUEST_UNKNOWN";

        Run accepted = run(cli -> new LinkAcceptCommand(cli, project -> runtime), NodeIdentity.generate().nodeId());
        Run notANode = run(cli -> new LinkDeclineCommand(cli, project -> runtime), "somebody");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, accepted.exit());
        assertEquals(List.of("ERROR=JOIN_REQUEST_UNKNOWN"), accepted.stderr());
        assertEquals(List.of("ERROR=INVALID_NODE_ID"), notANode.stderr());
    }

    /** A runtime whose network view gains a peer after a number of reads. */
    private static final class FakeRuntime implements RuntimeControl {

        final List<String> commands = new ArrayList<>();
        int connectsAfterReads = Integer.MAX_VALUE;
        String peerNode = "sl1-peer";
        String existingSession;
        boolean endsWithoutSession;
        String rejection;
        List<Map<String, Object>> joinRequests = List.of();
        String operation = "op-1";
        String inviteKind = "PROJECT";
        String codeRemote;
        private int networkReads;

        @Override
        public Map<String, Object> command(String name, Map<String, Object> body) throws Rejected {
            if (rejection != null) {
                throw new Rejected(rejection);
            }
            commands.add(name + " " + body);
            return switch (name) {
                case "invite" -> switch (inviteKind) {
                    case "PLAIN" -> Map.of("operationId", "op-1", "inviteUri", "synesis://join/SLB3-test", "expiresAt", "2026-10-16T12:00:00Z");
                    case "NO_URL" -> Map.of("operationId", "op-1", "inviteUri", "synesis://project/SLP1-test", "expiresAt", "2026-10-16T12:00:00Z", "kind", "PROJECT", "codeRemoteReason", "SEVERAL_GIT_REMOTES");
                    default -> Map.of("operationId", "op-1", "inviteUri", "synesis://project/SLP1-test", "expiresAt", "2026-10-16T12:00:00Z", "kind", "PROJECT", "codeRemoteName", "origin",
                            "codeRemoteUrl", "https://example.test/p.git");
                };
                case "join-project-link", "join" -> {
                    Map<String, Object> started = new java.util.HashMap<>(Map.of("operationId", "op-2", "answerUri", "synesis://answer/SLA3-test", "peerIdentity", "sl1-host", "expiresAt", "2026-10-16T12:00:00Z"));
                    if (codeRemote != null) {
                        started.put("codeRemote", codeRemote);
                    }
                    yield started;
                }
                case "accept-join" -> Map.of("state", "ACCEPTED");
                case "decline-join" -> Map.of("state", "DECLINED");
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
            return Map.of("onboarding", Map.of("pendingHosts", pending ? List.of(Map.of("operationId", "op-1")) : List.of(), "pendingJoins", pending ? List.of(Map.of("operationId", "op-2")) : List.of(), "joinRequests", joinRequests));
        }
    }
}
