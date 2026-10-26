package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.coordination.replication.ReplicationJournal;

import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessSession;

/**
 * Scenario S3 of ADR-0102 item 1, from the real run s5-run14 (PC and VM, 2026-10-19): a review across computers that
 * begins before the Link fails and is finished after it comes back. The reviewer on one computer takes the grant for a
 * snapshot the other computer's agent published, the Link fails, the reviewer is refused the snapshot's code and stops
 * without a verdict, and the run controller of its computer must ask it again once the computers are linked.
 *
 * <p>In s5-run14 the journals of both computers recorded no reconnection, so the wake for "a computer came back" never
 * fired, and the reviewer, resumed twice at the heal while the code was still unreachable, was never woken again: both
 * runs printed {@code AGENT_RUN_WAITING=FOREIGN_LANE_OPEN} until their time ran out. The scenario puts the journal back
 * to what it said before the Link failed, which is what the real journals said afterwards, and so exercises the retry
 * and not the journal.</p>
 */
@Timeout(300)
final class CrossComputerReviewScenarioTest {

    @TempDir
    Path temp;
    private ScenarioLab lab;
    private ExecutorService controllerThread;
    /** The agent the run controller starts and resumes: the reviewer of the other computer. */
    private ScenarioAgent reviewer;

    @BeforeEach
    void linkedComputers() throws Exception {
        lab = ScenarioLab.open(temp);
        controllerThread = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() {
        controllerThread.shutdownNow();
        lab.close();
    }

    @Test
    void aReviewerRefusedTheOtherComputersCodeWhileApartIsAskedAgainAfterTheHealAndGivesItsVerdict() throws Exception {
        Computer writing = lab.owner;
        Computer reviewing = lab.friend;
        ScenarioAgent writer = lab.agent(writing, "writer");
        reviewer = lab.agent(reviewing, "reviewer");

        // Step 1. Linked, the writer publishes gcd.txt and the reviewer of the other computer is admitted to review it.
        writer.claim("Write gcd.txt", "gcd.txt");
        writer.create("gcd.txt", "def gcd(a, b): return a\n");
        writer.publish();
        reviewer.joinAsReviewer("reviews/gcd.md", "gcd.txt");
        reviewer.reviews(ScenarioAgent.Review.WITHHOLD, null);
        lab.drive("the reviewer holds the consumed grant for the writer's snapshot and has given no verdict", () -> holdsConsumedGrant(reviewing, reviewer), writer, reviewer);
        assertEquals(0, verdicts(writing), "no verdict yet");

        // Step 2. The Link fails. The reviewer's run starts its agent; it is refused the code and stops without a verdict.
        Path journal = reviewing.location.coordinationDirectory().resolve(ReplicationJournal.FILE);
        String journalBeforeTheSplit = Files.readString(journal);
        lab.split();
        reviewer.reviews(ScenarioAgent.Review.READ_THEN_ACCEPT, "gcd.txt");
        List<String> events = new CopyOnWriteArrayList<>();
        AgentRunController controller = new AgentRunController(this::process, new EventLogObserver(reviewing.location), connections(), System::currentTimeMillis, Thread::sleep);
        AgentRunController.Request request = new AgentRunController.Request(reviewing.location.root(),
                List.of(new AgentRunController.AgentSpec("codex", session(), new HeadlessOptions(null, null))), "go", Duration.ofMinutes(4), Duration.ofMillis(200), 8);
        controllerThread.submit(() -> {
            try {
                return controller.run(request, events::add);
            } catch (InterruptedException | IOException | RuntimeException failed) {
                events.add("RUN_FAILED " + failed);
                return null;
            }
        });
        lab.await("the reviewer's first process ended", () -> events.contains("AGENT_EXITED=codex#1 exitCode=0"));
        assertTrue(reviewer.trace.stream().anyMatch(call -> call.startsWith("read_file") && call.contains("blocked")), "the read was refused: " + reviewer.trace);
        assertEquals(0, verdicts(writing), "it gave no verdict");

        // Step 3. The Link comes back. The journal says what it said before the Link failed, as s5-run14's said at the end.
        lab.heal();
        lab.await("the journal records the session that began", () -> readOrEmpty(journal).lines().count() > journalBeforeTheSplit.lines().count());
        Files.writeString(journal, journalBeforeTheSplit);

        // Step 4. The controller asks the reviewer again, it reads the snapshot over Link and gives its verdict, which the writer's computer holds.
        lab.await("the reviewer's verdict reaches the writer's computer; events " + events, () -> verdicts(writing) == 1);
        assertTrue(events.stream().anyMatch(event -> event.startsWith("AGENT_RESUMED=codex#1")), "the controller resumed the reviewer: " + events);
        lab.drive("the writer's file is on its main", () -> lab.hasOnBranch(writing, "gcd.txt"), writer, reviewer);
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static boolean holdsConsumedGrant(Computer computer, ScenarioAgent agent) {
        try {
            var groups = computer.store().workGroupProjection();
            return groups.grants().stream().anyMatch(grant -> grant.targetParticipant().equals(agent.participant()) && groups.grantConsumed(grant.grantId()));
        } catch (Exception unreadable) {
            return false;
        }
    }

    private static long verdicts(Computer computer) {
        try {
            return computer.store().workGroupProjection().reviewValidations().stream().filter(verdict -> "ACCEPTED".equals(verdict.result())).count();
        } catch (Exception unreadable) {
            return -1;
        }
    }

    /** The reviewer's provider process: its agent follows what its next action recommends until it is told to wait or is refused. */
    private AgentRunController.RunningAgent process(List<String> command, Path workingDirectory, Map<String, String> environment, String prompt, String label,
            Function<String, Optional<String>> sessionIdReader) {
        Thread agent = new Thread(() -> {
            for (int step = 0; step < 12 && reviewer.step() == ScenarioAgent.Step.ACTED; step++) {
                // follows its next action until it is told to wait or is refused
            }
        });
        agent.start();
        return new AgentRunController.RunningAgent() {

            @Override
            public boolean isAlive() {
                return agent.isAlive();
            }

            @Override
            public int exitCode() {
                return 0;
            }

            @Override
            public Optional<String> providerSessionId() {
                return Optional.of("provider-session");
            }

            @Override
            public void stop() {
                agent.interrupt();
            }
        };
    }

    private static AgentRunController.Connections connections() {
        return new AgentRunController.Connections() {

            @Override
            public String open() {
                return "reviewer";
            }

            @Override
            public void ended(String connectionInstanceId) {
            }

            @Override
            public boolean keep(String connectionInstanceId) {
                return true;
            }
        };
    }

    private static HeadlessSession session() {
        return new HeadlessSession() {

            @Override
            public List<String> startCommand(Path projectRoot, HeadlessOptions options) {
                return List.of("agent", "start");
            }

            @Override
            public List<String> resumeCommand(Path projectRoot, String providerSessionId, HeadlessOptions options) {
                return List.of("agent", "resume", providerSessionId);
            }

            @Override
            public Optional<String> sessionId(String outputLine) {
                return Optional.empty();
            }
        };
    }
}
