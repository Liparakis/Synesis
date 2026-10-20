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
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.coordination.TestComputers.Computer;

import org.synesis.workspace.application.agent.run.AgentRunController;
import org.synesis.workspace.application.agent.run.ControllerConnections;
import org.synesis.workspace.application.agent.run.EventLogObserver;
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
    /** Held by the run's third process, which stands for an agent that is still working at the heal, so that the run is not over before the combine. */
    private final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
    private final Map<String, ScenarioAgent> byLabel = new ConcurrentHashMap<>();
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

    @Test
    @org.junit.jupiter.api.Disabled("OPEN 2026-10-20: passes 1 in 3 on the Linux gate; ADR-0098 step 5 is PARTIAL under a real cut (s5-run16 to s5-run18), see step 5 item 31")
    void theCombineLandsAndMainEndsEqualWhenTheHigherComputersTwoAgentsAreRunByARunControllerThatEndsTheirConnections() throws Exception {
        Computer low = lab.lower();
        ScenarioAgent low1 = lab.agent(low, "low-1");
        ScenarioAgent low2 = lab.agent(low, "low-2");
        // As in s5-run16 the two changes are integrated one after the other, so the file both computers changed is an advance of its own.
        scripts.put("codex#1", agent -> {
            lab.await("the second agent has published its change", () -> byLabel.containsKey("codex#2") && byLabel.get("codex#2").trace.stream().anyMatch(call -> call.startsWith("finish_lane")));
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
        AgentRunController controller = new AgentRunController(this::process, new EventLogObserver(high.location), new ControllerConnections(high.location.root()), System::currentTimeMillis, Thread::sleep);
        AgentRunController.Request request = new AgentRunController.Request(high.location.root(),
                List.of(spec(), spec(), spec()), "go", Duration.ofMinutes(8), Duration.ofMillis(300), 8);
        threads.submit(() -> {
            try {
                var result = controller.run(request, line -> events.add(java.time.LocalTime.now().toString().substring(0, 8) + " " + line));
                events.add(java.time.LocalTime.now().toString().substring(0, 8) + " RUN_RESULT " + result);
                return result;
            } catch (InterruptedException | IOException | RuntimeException failed) {
                events.add("RUN_FAILED " + failed);
                return null;
            }
        });
        lab.drive("the lower computer's two changes are on its main", () -> lab.hasOnBranch(low, "low.txt") && lab.onBranch(low, "shared.txt").contains("by the lower computer"), low1, low2);
        lab.await("the higher computer's two changes are on its main; events " + events, () -> lab.hasOnBranch(high, "high.txt") && lab.onBranch(high, "shared.txt").contains("by the higher computer"));
        lab.await("the controller has ended both agents' first processes; events " + events,
                () -> events.stream().anyMatch(event -> event.endsWith("AGENT_EXITED=codex#1 exitCode=0")) && events.stream().anyMatch(event -> event.endsWith("AGENT_EXITED=codex#2 exitCode=0")));

        assertTrue(new org.synesis.workspace.lifecycle.lease.SessionLeaseStore().listAllComplete(high.location.root()).isPresent(), "leases readable: " + leaseFiles());
        // The Link comes back. The higher computer re-merges onto the lower one's main and the combine, and its reviews, must land.
        events.add(java.time.LocalTime.now().toString().substring(0, 8) + " HEAL");
        lab.heal();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(4);
        while (!(lab.mainsEqual() && COMBINED.strip().equals(lab.onBranch(low, "shared.txt")) && lab.hasOnBranch(low, "high.txt") && lab.hasOnBranch(high, "low.txt"))) {
            assertTrue(System.nanoTime() < deadline, "the copies never met; events " + events + System.lineSeparator() + grants() + System.lineSeparator() + "heads high " + lab.head(high) + " low " + lab.head(low) + " shared.txt high [" + lab.onBranch(high, "shared.txt") + "] low [" + lab.onBranch(low, "shared.txt") + "] "
                    + System.lineSeparator() + "leases " + leaseFiles() + System.lineSeparator() + offers());
            Thread.sleep(200);
        }
        assertEquals(COMBINED.strip(), lab.onBranch(high, "shared.txt"));
        assertTrue(events.stream().anyMatch(event -> event.contains("AGENT_RESUMED=")), "the controller woke a stopped agent: " + events);
    }

    private String offers() throws Exception {
        StringBuilder out = new StringBuilder();
        var observation = new EventLogObserver(high.location).observe();
        for (var entry : byConnection.entrySet()) {
            {
                out.append(entry.getKey()).append(" as ").append(entry.getValue().participant()).append(" offered ").append(observation.reviewsOffered("codex", List.of(entry.getKey())))
                        .append(" combines ").append(entry.getValue().combinesOffered().size()).append(" connected ").append(observation.otherComputerConnected()).append(" complete ").append(observation.complete()).append(System.lineSeparator());
            }
        }
        return out.toString();
    }

    private String grants() throws Exception {
        var groups = high.store().workGroupProjection();
        StringBuilder out = new StringBuilder();
        for (var grant : groups.grants()) {
            out.append("grant to ").append(grant.targetParticipant()).append(" on ").append(grant.snapshotId()).append(groups.grantConsumed(grant.grantId()) ? " consumed" : "")
                    .append(groups.grantRevoked(grant.identity()) ? " revoked" : "").append(groups.grantAvailable(grant.grantId()) ? " available" : "").append(System.lineSeparator());
        }
        return out.toString();
    }

    private String leaseFiles() {
        Path dir = org.synesis.workspace.lifecycle.lease.SessionLeaseStore.resolveLeasesDirectory(high.location.root());
        try (var files = java.nio.file.Files.list(dir)) {
            String names = dir + " " + files.map(f -> f.getFileName().toString()).sorted().toList();
            for (String file : List.of("continuations.txt", "successions.txt")) {
                names += System.lineSeparator() + file + ":" + System.lineSeparator() + (java.nio.file.Files.exists(dir.resolve(file)) ? java.nio.file.Files.readString(dir.resolve(file)) : "none");
            }
            return names;
        } catch (IOException missing) {
            return dir + " " + missing;
        }
    }

    private static AgentRunController.AgentSpec spec() {
        return new AgentRunController.AgentSpec("codex", session(), new HeadlessOptions(null, null));
    }

    /** One provider process: the agent of its connection runs its script when first started, and then follows what its next action recommends. */
    private AgentRunController.RunningAgent process(List<String> command, Path workingDirectory, Map<String, String> environment, String prompt, String label,
            Function<String, Optional<String>> sessionIdReader) {
        String connection = command.get(command.size() - 1);
        if ("codex#3".equals(label)) {
            return running(new Thread(() -> {
                try {
                    released.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }), connection);
        }
        boolean first = !byLabel.containsKey(label);
        ScenarioAgent agent = byConnection.computeIfAbsent(connection, name -> lab.agent(high, name));
        byLabel.put(label, agent);
        Thread thread = new Thread(() -> {
            try {
                if (first && scripts.containsKey(label)) {
                    scripts.get(label).begin(agent);
                }
                follow(agent);
                events.add(java.time.LocalTime.now().toString().substring(0, 8) + " PROCESS_ENDED " + label + " on " + connection + " as " + agent.participant() + " calls " + agent.trace.size() + " last " + (agent.trace.isEmpty() ? "-" : agent.trace.getLast()));
            } catch (RuntimeException | AssertionError failed) {
                events.add("AGENT_FAILED " + label + " " + failed + " " + agent.trace);
            }
        });
        return running(thread, connection);
    }

    private static AgentRunController.RunningAgent running(Thread thread, String connection) {
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
                if (!"ready".equals(agent.call("ensure_session", (Map<String, Object>) combines.getFirst().get("ensureSession")).get("status"))) {
                    return;
                }
                agent.rewrite("shared.txt", COMBINED);
                agent.finish();
                continue;
            }
            if (agent.step() != ScenarioAgent.Step.ACTED) {
                return;
            }
        }
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
