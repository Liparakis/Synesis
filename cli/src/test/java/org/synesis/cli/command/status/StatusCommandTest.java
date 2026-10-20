package org.synesis.cli.command.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.bootstrap.CliRuntime;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.cli.terminal.ConsoleTerminal;
import org.synesis.cli.terminal.HumanTerminal;
import org.synesis.cli.terminal.StatusRenderer;
import org.synesis.cli.terminal.Terminal;

import org.synesis.coordination.application.MembershipService;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.conversation.ConversationMessage;
import org.synesis.coordination.domain.operator.OperatorRequest;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.LoopbackExchange;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.replication.LeaderReplication;

import org.synesis.link.identity.NodeIdentity;
import org.synesis.link.onboarding.Onboarding;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.ProjectLocation;

import picocli.CommandLine;

/**
 * What {@code synesis status} prints for an empty project, for one with an agent and a lane, for one with a request that
 * needs a person, for a shared one with a connected and an away computer, and while the runtime is stopped.
 */
final class StatusCommandTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:05:00Z");

    @TempDir
    Path tmp;
    private Path root;
    private ProjectLocation location;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private record Run(int exit, List<String> stdout, List<String> stderr) {

        @Override
        public String toString() {
            return "exit=" + exit + " stdout=" + stdout + " stderr=" + stderr;
        }
    }

    @BeforeEach
    void project() throws Exception {
        root = Files.createDirectories(tmp.resolve("project"));
        location = new ProjectApplicationService().init(root).location();
    }

    private Run run(boolean person, StatusCommand.EventWatch events, String... arguments) {
        out.reset();
        err.reset();
        ConsoleTerminal console = new ConsoleTerminal(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        Terminal terminal = person ? new HumanTerminal(console, false) : console;
        CliRuntime runtime = new CliRuntime(new Onboarding(tmp.resolve("profile"), new StatusRenderer(terminal)), terminal);
        List<String> all = new ArrayList<>(List.of("--project", root.toString()));
        all.addAll(List.of(arguments));
        int exit = new CommandLine(new StatusCommand(runtime, events, Clock.fixed(NOW, ZoneOffset.UTC))).execute(all.toArray(String[]::new));
        return new Run(exit, out.toString(StandardCharsets.UTF_8).lines().toList(), err.toString(StandardCharsets.UTF_8).lines().toList());
    }

    private Run porcelain() {
        return run(false, (server, after, onEvent) -> {
            throw new AssertionError("nothing is followed without --watch");
        });
    }

    private Run screen() {
        return run(true, (server, after, onEvent) -> {
            throw new AssertionError("nothing is followed without --watch");
        });
    }

    private Path coordination() {
        return location.coordinationDirectory();
    }

    private void announce(String participant, String provider, String goal, String path) throws Exception {
        UUID intent = UUID.randomUUID();
        WorkIntent lane = new WorkIntent(intent, location.projectId(), participant, provider, UUID.randomUUID(), goal, "tests pass", "base-commit",
                List.of(ResourceSelector.pathExact(path)), 1, UUID.randomUUID(), WorkIntent.defaultAuthorityLineage(intent), WorkIntent.Status.ANNOUNCED, WorkIntent.Role.PRODUCER, List.of());
        assertTrue(new WorkIntentService(location.openCoordinationStore(), location.loadOrCreateIdentity()).announce(lane).acquired());
    }

    private void raise(OperatorRequest request) throws Exception {
        NodeIdentity identity = location.loadOrCreateIdentity();
        location.openCoordinationStore().append(UUID.randomUUID(), PredictionEventType.OPERATOR_REQUEST_RAISED, identity.nodeId(), request.encodeRaised(), identity);
    }

    private static List<String> listing(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.map(path -> folder.relativize(path) + ":" + (Files.isRegularFile(path) ? path.toFile().length() : -1) + ":" + path.toFile().lastModified()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void anEmptyProjectSaysSoAndStatusChangesNothing() throws Exception {
        List<String> before = listing(root.resolve(".synesis"));
        String self = new org.synesis.link.identity.IdentityBootstrap(location.linkDirectory()).inspect().identityPresent()
                ? new org.synesis.link.identity.IdentityBootstrap(location.linkDirectory()).inspect().nodeId() : "UNKNOWN";

        Run porcelain = porcelain();
        Run screen = screen();

        assertEquals(List.of("STATUS_PROJECT_ID=" + location.projectId(), "STATUS_PROJECT_NAME=project", "STATUS_PROJECT_SHARING=SINGLE_COMPUTER", "STATUS_OWNER=NONE",
                "STATUS_THIS_COMPUTER=" + self, "STATUS_RUNTIME=STOPPED", "STATUS_AGENTS=0", "STATUS_NEEDS=0", "STATUS_COMPUTERS=0"), porcelain.stdout(), porcelain.toString());
        assertEquals(List.of("Project    project  (on this computer only)",
                "Runtime    not running; a project on one computer does not need it",
                "Agents     none yet",
                "Needs you  nothing",
                "Computers  this one only"), screen.stdout(), screen.toString());
        assertEquals(0, porcelain.exit() + screen.exit(), porcelain + " " + screen);
        assertEquals(before, listing(root.resolve(".synesis")), "status writes nothing: no identity, no folder, no file");
    }

    @Test
    void anAgentWithALaneIsShownWithItsFilesAndTheirWords() throws Exception {
        location.loadOrCreateIdentity();
        announce("agt_alpha", "claude", "Write primes package", "primes/primes.py");

        Run porcelain = porcelain();
        Run screen = screen();

        assertEquals("STATUS_AGENTS=1", porcelain.stdout().get(6), porcelain.toString());
        assertEquals("STATUS_AGENT=agt_alpha PROVIDER=claude STATE=ACTIVE ROLE=producer FILES=primes/primes.py WAITING_ON= LANE=Write primes package", porcelain.stdout().get(7), porcelain.toString());
        assertEquals(List.of("Project    project  (on this computer only)",
                "Runtime    not running; a project on one computer does not need it",
                "Agents     1 (1 working)",
                "             claude agt_alpha  working as producer: Write primes package",
                "               files: primes/primes.py",
                "Needs you  nothing",
                "Computers  this one only"), screen.stdout(), screen.toString());
    }

    @Test
    void whatNeedsAPersonIsListedAndAnAgentsTextCannotAddLinesOrDriveTheTerminal() throws Exception {
        location.loadOrCreateIdentity();
        announce("agt_alpha", "claude", "Goal\nSTATUS_RUNTIME=FORGED\u001b[2Jdone", "a.txt");
        raise(OperatorRequest.askedByLane("opr_lane000000000001", UUID.randomUUID().toString(), "Which license?\nSTATUS_NEEDS=0", "participant agt_x"));

        Run porcelain = porcelain();
        Run screen = screen();

        assertEquals(1, porcelain.stdout().stream().filter(line -> line.startsWith("STATUS_RUNTIME=")).count(), porcelain.toString());
        assertEquals(1, porcelain.stdout().stream().filter(line -> line.startsWith("STATUS_NEEDS=")).count(), porcelain.toString());
        assertTrue(porcelain.stdout().contains("STATUS_NEED=OPERATOR ID=opr_lane000000000001 SUMMARY=Which license? STATUS_NEEDS=0"), porcelain.toString());
        assertTrue(porcelain.stdout().stream().anyMatch(line -> line.startsWith("STATUS_AGENT=agt_alpha") && line.endsWith("LANE=Goal STATUS_RUNTIME=FORGED [2Jdone")), porcelain.toString());
        assertTrue(screen.stdout().stream().noneMatch(line -> line.indexOf('\u001b') >= 0) && porcelain.stdout().stream().noneMatch(line -> line.indexOf('\u001b') >= 0), "no escape character is printed");
        assertTrue(screen.stdout().contains("Needs you  1 operator request(s), 0 computer(s) asking to join"), screen.toString());
        assertTrue(screen.stdout().contains("             operator request opr_lane0000...: Which license? STATUS_NEEDS=0"), screen.toString());
        assertTrue(screen.stdout().contains("               see them: synesis operator list"), screen.toString());
    }

    /** A project owned by this computer, with a computer connected and one away, one that asks to join, and the runtime reachable or not. */
    private record Shared(NodeIdentity self, NodeIdentity connected, NodeIdentity away, NodeIdentity asking) {
    }

    private Shared share() throws Exception {
        NodeIdentity self = location.loadOrCreateIdentity();
        NodeIdentity connected = NodeIdentity.generate();
        NodeIdentity away = NodeIdentity.generate();
        NodeIdentity asking = NodeIdentity.generate();
        MembershipService membership = new MembershipService(location.openCoordinationStore(), self);
        membership.share(Optional.empty());
        for (NodeIdentity member : List.of(connected, away)) {
            membership.requestJoin(member.nodeId());
            membership.answer(member.nodeId(), true);
        }
        membership.requestJoin(asking.nodeId());
        // What the connected computer's log last brought in: one entry of its own, arrived four seconds ago.
        PredictionEventStore theirs = new PredictionEventStore(tmp.resolve("their-logs"), location.projectId());
        UUID sender = UUID.randomUUID();
        theirs.append(UUID.randomUUID(), PredictionEventType.CONVERSATION_MESSAGE_SENT, connected.nodeId(), ConversationMessage.encodeSent(UUID.randomUUID(), sender, List.of(sender, UUID.randomUUID()), "hello"), connected);
        location.openCoordinationStore().placeCopies(connected.nodeId(), theirs.entriesAfter(PredictionEventStore.OWN_LOG, 0, 100, Integer.MAX_VALUE));
        try (Stream<Path> copies = Files.list(coordination().resolve("peers").resolve(connected.nodeId()).resolve("events"))) {
            for (Path copy : copies.toList()) {
                Files.setLastModifiedTime(copy, FileTime.from(NOW.minusSeconds(4)));
            }
        }
        Files.write(coordination().resolve(LeaderReplication.MEMBERS_FILE), List.of(connected.nodeId() + " CONNECTED 2026-10-20T10:00:00Z", away.nodeId() + " DISCONNECTED 2026-10-20T09:00:00Z"));
        return new Shared(self, connected, away, asking);
    }

    /** A server that accepts every connection, as the runtime does: a socket nobody accepts from refuses the second caller on some systems. */
    private static ServerSocket accepting() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().start(() -> {
            try {
                while (true) {
                    server.accept().close();
                }
            } catch (IOException closed) {
                // The test closed the server.
            }
        });
        return server;
    }

    private static String short12(String node) {
        return node.length() > 13 ? node.substring(0, 12) + "..." : node;
    }

    @Test
    void aSharedProjectShowsItsOwnerItsComputersAndWhoAsksToJoin() throws Exception {
        Shared shared = share();
        try (ServerSocket listening = accepting()) {
            LoopbackExchange.recordEndpoint(coordination(), new InetSocketAddress(InetAddress.getLoopbackAddress(), listening.getLocalPort()));

            Run porcelain = porcelain();
            Run screen = screen();

            List<String> machine = porcelain.stdout();
            assertTrue(machine.contains("STATUS_PROJECT_SHARING=SHARED") && machine.contains("STATUS_OWNER=" + shared.self().nodeId()) && machine.contains("STATUS_THIS_COMPUTER=" + shared.self().nodeId())
                    && machine.contains("STATUS_RUNTIME=RUNNING"), porcelain.toString());
            assertTrue(machine.contains("STATUS_NEED=JOIN ID=" + shared.asking().nodeId() + " SUMMARY=asks to join"), porcelain.toString());
            assertTrue(machine.contains("STATUS_COMPUTERS=2"), porcelain.toString());
            assertTrue(machine.contains("STATUS_COMPUTER=" + shared.connected().nodeId() + " STATE=CONNECTED SINCE=2026-10-20T10:00:00Z LAST_ENTRY_RECEIVED=2026-10-20T10:04:56Z"), porcelain.toString());
            assertTrue(machine.contains("STATUS_COMPUTER=" + shared.away().nodeId() + " STATE=DISCONNECTED SINCE=2026-10-20T09:00:00Z LAST_ENTRY_RECEIVED=NONE"), porcelain.toString());
            assertFalse(machine.stream().anyMatch(line -> line.startsWith("STATUS_COMPUTER=" + shared.self().nodeId())), "this computer is not one of the other computers");

            List<String> expected = new ArrayList<>(List.of("Project    project  (shared with other computers, owner: this computer)", "Runtime    running", "Agents     none yet",
                    "Needs you  0 operator request(s), 1 computer(s) asking to join",
                    "             join request from " + short12(shared.asking().nodeId()) + ": asks to join",
                    "               answer them: synesis link requests", "Computers  2 other(s)"));
            expected.addAll(Stream.of(
                    short12(shared.connected().nodeId()) + "  connected for 5 min; last entries from it arrived 4 s ago|" + shared.connected().nodeId(),
                    short12(shared.away().nodeId()) + "  not connected for 1 h; no entries from it yet|" + shared.away().nodeId())
                    .sorted((a, b) -> a.substring(a.indexOf('|') + 1).compareTo(b.substring(b.indexOf('|') + 1))).map(line -> "             " + line.substring(0, line.indexOf('|'))).toList());
            assertEquals(expected, screen.stdout(), screen.toString());
        }
    }

    @Test
    void whileTheRuntimeIsStoppedNoConnectionIsClaimedAndNothingIsStarted() throws Exception {
        Shared shared = share();
        // The file a runtime that has stopped left behind names an address nobody listens on.
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        LoopbackExchange.recordEndpoint(coordination(), new InetSocketAddress(InetAddress.getLoopbackAddress(), port));

        Run porcelain = porcelain();
        Run screen = screen();

        assertTrue(porcelain.stdout().contains("STATUS_RUNTIME=STOPPED"), porcelain.toString());
        assertTrue(porcelain.stdout().contains("STATUS_COMPUTER=" + shared.connected().nodeId() + " STATE=RUNTIME_STOPPED SINCE=NONE LAST_ENTRY_RECEIVED=2026-10-20T10:04:56Z"), porcelain.toString());
        assertTrue(screen.stdout().contains("Runtime    not running; other computers reach this one only while it runs (synesis ui --no-browser starts it)"), screen.toString());
        assertTrue(screen.stdout().contains("             " + short12(shared.connected().nodeId()) + "  connection unknown, the runtime is not running; last entries from it arrived 4 s ago"), screen.toString());
    }

    @Test
    void aProjectWithAnEarlierRoleFileNamesItsLeaderOrItsMembersAsItsComputers() throws Exception {
        String leader = NodeIdentity.generate().nodeId();
        String member = NodeIdentity.generate().nodeId();

        org.synesis.coordination.persistence.ReplicationSettings.member(leader).write(coordination());
        Run asMember = porcelain();
        org.synesis.coordination.persistence.ReplicationSettings.leader(java.util.Set.of(member)).write(coordination());
        Run asLeader = porcelain();

        assertTrue(asMember.stdout().containsAll(List.of("STATUS_PROJECT_SHARING=SHARED", "STATUS_OWNER=" + leader, "STATUS_COMPUTERS=1",
                "STATUS_COMPUTER=" + leader + " STATE=RUNTIME_STOPPED SINCE=NONE LAST_ENTRY_RECEIVED=NONE")), asMember.toString());
        assertTrue(asLeader.stdout().containsAll(List.of("STATUS_PROJECT_SHARING=SHARED", "STATUS_COMPUTERS=1",
                "STATUS_COMPUTER=" + member + " STATE=RUNTIME_STOPPED SINCE=NONE LAST_ENTRY_RECEIVED=NONE")), asLeader.toString());
    }

    @Test
    void watchNeedsTheRuntimeAndSaysHowToStartIt() {
        Run watch = run(false, (server, after, onEvent) -> {
            throw new AssertionError("no server runs, so nothing is followed");
        }, "--watch");

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, watch.exit(), watch.toString());
        assertTrue(watch.stdout().contains("STATUS_RUNTIME=STOPPED"), "the status is drawn once first: " + watch);
        assertEquals(List.of("ERROR=WATCH_NEEDS_RUNTIME", "MESSAGE=--watch follows the project's event stream, and the project's runtime is not running", "FIX=synesis ui --no-browser"), watch.stderr());
    }

    @Test
    void watchDrawsAgainAfterEachEventFromTheCurrentHead() throws Exception {
        location.loadOrCreateIdentity();
        announce("agt_alpha", "claude", "first goal", "a.txt");
        long head = location.openCoordinationStore().headSequence();
        AtomicLong followedFrom = new AtomicLong(-1);
        try (ServerSocket listening = accepting()) {
            LoopbackExchange.recordEndpoint(coordination(), new InetSocketAddress(InetAddress.getLoopbackAddress(), listening.getLocalPort()));

            Run watch = run(false, (server, after, onEvent) -> {
                followedFrom.set(after);
                assertEquals(listening.getLocalPort(), server.getPort());
                announce("agt_beta", "codex", "second goal", "b.txt");
                onEvent.run();
                onEvent.run();
            }, "--watch");

            assertEquals(0, watch.exit(), watch.toString());
            assertEquals(head, followedFrom.get(), "only what happens from now on is followed");
            assertEquals(List.of(1L, 2L, 2L), watch.stdout().stream().filter(line -> line.startsWith("STATUS_AGENTS=")).map(line -> Long.parseLong(line.substring("STATUS_AGENTS=".length()))).toList(),
                    "one draw before, and one after each event, each from the files as they are then: " + watch);
        }
    }

    @Test
    void aFolderThatIsNotAProjectIsRefusedByName() throws Exception {
        root = Files.createDirectories(tmp.resolve("empty"));

        Run refused = porcelain();

        assertEquals(ExitCodes.LOCAL_CONFIGURATION, refused.exit(), refused.toString());
        assertEquals(List.of("ERROR=PROJECT_NOT_CONFIGURED"), refused.stderr(), refused.toString());
        assertEquals(List.of(), refused.stdout());
    }
}
