package org.synesis.cli.command.coordination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.ControllerConnections;
import org.synesis.workspace.application.agent.run.EventLogObserver;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessSession;

/**
 * Scenario S2 as the real run s5-run16 ran it (PC and VM, build remaster86, 2026-10-20): the higher computer's two agents are
 * started by a run controller, not stepped by the test. Apart, both computers change the same line of one file and the higher
 * computer's second agent writes a file of its own. Once an agent's lane is integrated and it has nothing to come back to, the
 * controller ends its connection, so its session is closed; a resumed agent gets a new connection and a new session. After
 * the heal the higher computer re-merges, Git cannot merge the file, one of its two agents takes the combine and the
 * re-merge's reviews are owed to the two original reviewers, both of which were closed long ago. s5-run16: the grants were
 * issued and released within 200 ms, the only live agent held the combine lane, the other was never woken, and the re-merge was
 * never reviewed. {@link OverlapAndCombineScenarioTest} drives six agents by hand, with a live reviewer for everything, and never ends a
 * connection.
 */
@Timeout(600)
final class CombineUnderRunControllerScenarioTest {

    private static final String COMBINED = "one\ntwo, by the higher computer\ntwo, by the lower computer\nthree\n";

    @TempDir
    Path temp;
    private ScenarioLab lab;
    private ExecutorService threads;
    private final Map<String, ScenarioAgent> byConnection = new ConcurrentHashMap<>();
    private final Map<String, AgentScript> scripts = new ConcurrentHashMap<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger resumedConnections = new AtomicInteger();
    private Computer high;

    /** What a controller-started agent does when its process begins, by the agent's label. */
    @FunctionalInterface
    private interface AgentScript {

        void begin(ScenarioAgent agent);
    }

    @BeforeEach
    void linkedComputers() throws Exception {
        lab = ScenarioLab.open(temp);
        threads = Executors.newCachedThreadPool();
        high = lab.higher();
    }

    @AfterEach
    void stop() {
        threads.shutdownNow();
        lab.close();
    }

    @Disabled("ADR-0098 step 5 item 30 (s5-run16): not fixed yet; the scenario stalls with the combine written and nobody woken for it or for its re-merge reviews")
    @Test
    void theCombineLandsAndMainEndsEqualWhenTheHigherComputersTwoAgentsAreRunByARunControllerThatEndsTheirConnections() throws Exception {
        Computer low = lab.lower();
        ScenarioAgent low1 = lab.agent(low, "low-1");
        ScenarioAgent low2 = lab.agent(low, "low-2");
        scripts.put("codex#1", agent -> {
            agent.claim("Change shared.txt on the higher computer", "shared.txt");
            agent.edit("shared.txt", "two", "two, by the higher computer");
            agent.publish();
        });
        scripts.put("codex#2", agent -> {
            agent.claim("Write high.txt", "high.txt");
            agent.create("high.txt", "written by the higher computer\n");
            agent.publish();
        });

        // Apart: the lower computer's agents are driven here, the higher computer's agents are started by its run controller.
        lab.split();
        low1.claim("Change shared.txt on the lower computer", "shared.txt");
        low2.claim("Write low.txt", "low.txt");
        low1.edit("shared.txt", "two", "two, by the lower computer");
        low2.create("low.txt", "written by the lower computer\n");
        low1.publish();
        low2.publish();
        AgentRunController controller = new AgentRunController(this::process, new EventLogObserver(high.location), connections(), System::currentTimeMillis, Thread::sleep);
        AgentRunController.Request request = new AgentRunController.Request(high.location.root(),
                List.of(spec(), spec()), "go", Duration.ofMinutes(8), Duration.ofMillis(300), 8);
        threads.submit(() -> {
            try {
                return controller.run(request, events::add);
            } catch (InterruptedException | IOException | RuntimeException failed) {
                events.add("RUN_FAILED " + failed);
                return null;
            }
        });
        lab.drive("the lower computer's two changes are on its main", () -> lab.hasOnBranch(low, "low.txt") && lab.onBranch(low, "shared.txt").contains("by the lower computer"), low1, low2);
        lab.await("the higher computer's two changes are on its main; events " + events, () -> lab.hasOnBranch(high, "high.txt") && lab.onBranch(high, "shared.txt").contains("by the higher computer"));
        lab.await("the controller has ended both agents' first processes; events " + events,
                () -> events.contains("AGENT_EXITED=codex#1 exitCode=0") && events.contains("AGENT_EXITED=codex#2 exitCode=0"));

        // The Link comes back. The higher computer re-merges onto the lower one's main and the combine, and its reviews, must land.
        lab.heal();
        lab.drive("both computers hold the same combined main; events " + events, () -> lab.mainsEqual() && COMBINED.equals(lab.onBranch(low, "shared.txt")) && lab.hasOnBranch(low, "high.txt")
                && lab.hasOnBranch(high, "low.txt"), low1, low2);
        assertEquals(COMBINED, lab.onBranch(high, "shared.txt"));
        assertTrue(events.stream().anyMatch(event -> event.startsWith("AGENT_RESUMED=")), "the controller woke a stopped agent: " + events);
    }

    private static AgentRunController.AgentSpec spec() {
        return new AgentRunController.AgentSpec("codex", session(), new HeadlessOptions(null, null));
    }

    /** One provider process: the agent of its connection runs its script when first started, and then follows what its next action recommends. */
    private AgentRunController.RunningAgent process(List<String> command, Path workingDirectory, Map<String, String> environment, String prompt, String label,
            Function<String, Optional<String>> sessionIdReader) {
        String connection = command.get(command.size() - 1);
        boolean first = !byConnection.containsKey(connection);
        ScenarioAgent agent = byConnection.computeIfAbsent(connection, name -> lab.agent(high, name));
        Thread thread = new Thread(() -> {
            try {
                if (first && scripts.containsKey(label)) {
                    scripts.get(label).begin(agent);
                }
                follow(agent);
                events.add("PROCESS_ENDED " + label + " on " + connection + " as " + agent.participant() + " after " + agent.trace + " next " + agent.next());
            } catch (RuntimeException | AssertionError failed) {
                events.add("AGENT_FAILED " + label + " " + failed + " " + agent.trace);
            }
        });
        thread.start();
        return new AgentRunController.RunningAgent() {

            @Override
            public boolean isAlive() {
                return thread.isAlive();
            }

            @Override
            public int exitCode() {
                return 0;
            }

            @Override
            public Optional<String> providerSessionId() {
                return Optional.of("provider-" + connection);
            }

            @Override
            public void stop() {
                thread.interrupt();
            }
        };
    }

    /** An agent that follows its next action until it is told to wait: it takes a combine it is offered, resolves the file and finishes the lane. */
    @SuppressWarnings("unchecked")
    private static void follow(ScenarioAgent agent) {
        for (int step = 0; step < 40; step++) {
            List<Map<String, Object>> combines = agent.combinesOffered();
            if (!combines.isEmpty()) {
                agent.call("ensure_session", (Map<String, Object>) combines.getFirst().get("ensureSession"));
                agent.rewrite("shared.txt", COMBINED);
                agent.finish();
                continue;
            }
            if (agent.step() != ScenarioAgent.Step.ACTED) {
                return;
            }
        }
    }

    /** The real connections of a run controller, with names the scenario can tell: the first two are the agents', later ones are resumes on a new connection. */
    private AgentRunController.Connections connections() {
        ControllerConnections real = new ControllerConnections(high.location.root());
        SessionLeaseService leases = new SessionLeaseService();
        AtomicInteger opened = new AtomicInteger();
        return new AgentRunController.Connections() {

            @Override
            public String open() {
                int number = opened.incrementAndGet();
                String name = number <= 2 ? "high-" + number : "resumed-" + resumedConnections.incrementAndGet();
                try {
                    leases.reserveKeeper(high.location.root(), name);
                } catch (IOException unwritable) {
                    // kept from the first look, as in the controller
                }
                return name;
            }

            @Override
            public void ended(String connectionInstanceId) {
                real.ended(connectionInstanceId);
            }

            @Override
            public boolean end(String connectionInstanceId) {
                return real.end(connectionInstanceId);
            }

            @Override
            public boolean keep(String connectionInstanceId) {
                return real.keep(connectionInstanceId);
            }
        };
    }

    private static HeadlessSession session() {
        return new HeadlessSession() {

            @Override
            public List<String> startCommand(Path projectRoot, HeadlessOptions options) {
                return List.of("agent", "start", options.connectionInstanceId());
            }

            @Override
            public List<String> resumeCommand(Path projectRoot, String providerSessionId, HeadlessOptions options) {
                return List.of("agent", "resume", options.connectionInstanceId());
            }

            @Override
            public Optional<String> sessionId(String outputLine) {
                return Optional.empty();
            }
        };
    }
}
