package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;
import org.synesis.workspace.application.provider.ProviderRegistry;

/**
 * Verifies when the controller resumes an agent, and that it stops instead of resuming forever.
 *
 * <p>Time is the controller's own tick: each pause advances a scripted project state by one step,
 * and each fake process lives for a fixed number of ticks.</p>
 */
final class AgentRunControllerTest {

    private static final Path PROJECT = Path.of("project").toAbsolutePath();

    @Test
    void agentIsResumedWhenAnotherLaneProgressesAfterItStoppedAndTheRunEndsWhenWorkCompletes() throws Exception {
        Script script = new Script();
        // claude stops at tick 2. codex makes progress at tick 4, which wakes claude, and its last
        // act at tick 6 completes the work while the resumed claude is still running.
        script.lifetimes(2, 6, 3);
        script.at(0).head(5).announced();
        script.at(4).head(9).progressAfter(5);
        script.at(6).head(14).progressAfter(9).complete();

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
        assertEquals(1, result.resumes());
        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches);
        assertTrue(script.events.contains("AGENT_RESUMED=claude#1 resume=1"), script.events.toString());
        assertEquals(AgentRunController.RESUME_PROMPT, script.prompts.getLast(), "a resumed agent is told only to continue");
    }

    @Test
    void everyProcessGetsItsOwnConnectionAndItsEndIsReportedBeforeTheAgentIsResumed() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 6, 3);
        script.at(0).head(5).announced();
        script.at(4).head(9).progressAfter(5);
        script.at(6).head(14).progressAfter(9).complete();

        script.run(8);

        assertEquals(List.of("open conn-1", "open conn-2", "ended conn-1", "open conn-3", "ended conn-2", "ended conn-3"), script.connections,
                "a resumed process is a new connection, opened only after the previous one was reported ended");
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-1"), script.environments.get(0), "Claude Code passes its environment to the MCP server");
        assertEquals(Map.of(), script.environments.get(1), "Codex is given the identity through its configuration arguments");
        assertTrue(script.commands.get(1).contains("mcp_servers.synesis.env.SYNESIS_MCP_CONNECTION_INSTANCE_ID=conn-2"), script.commands.get(1).toString());
        assertEquals(Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-3"), script.environments.get(2));
    }

    @Test
    void stoppedOwnerIsResumedOnceWhenItsAnswerPeriodEndsEvenThoughNothingWasRecorded() throws Exception {
        Script script = new Script();
        // Both agents stop at tick 1 and the log never moves. claude owns a proposal whose answer
        // period ends at tick 5; resumed then, it finishes and the work completes.
        script.lifetimes(1, 1, 1);
        script.at(0).head(3).announced();
        script.answerDeadlines.put("claude", 5_000L);
        script.at(6).head(8).complete();

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches, "only the provider that owns the proposal is woken, and only once");
        assertEquals(5, script.resumedAtTicks.getFirst(), "the run must wait for the deadline instead of ending while every agent is stopped");
        assertEquals(1, result.resumes());
        assertEquals(AgentRunController.Outcome.COMPLETED, result.outcome());
    }

    @Test
    void ownerIsNotResumedAgainForTheSameDeadline() throws Exception {
        Script script = new Script();
        // The resumed owner stops again without finishing and the deadline stays as it was.
        script.lifetimes(1, 1, 1);
        script.at(0).head(3).announced();
        script.answerDeadlines.put("claude", 3_000L);

        AgentRunController.Result result = script.run(8);

        assertEquals(1, result.resumes());
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
    }

    @Test
    void nothingIsResumedWhenNoWorkProgressedAfterEveryAgentStopped() throws Exception {
        Script script = new Script();
        script.lifetimes(2, 3);
        script.at(0).head(7).announced();

        AgentRunController.Result result = script.run(8);

        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
        assertEquals(0, result.resumes());
        assertEquals(2, script.launches.size(), "an idle project must not be poked");
    }

    @Test
    void agentsThatNeverAnnounceWorkAreReportedAsSuch() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 1);

        assertEquals(AgentRunController.Outcome.NO_WORK_ANNOUNCED, script.run(8).outcome());
    }

    @Test
    void resumesAreBoundedPerAgent() throws Exception {
        Script script = new Script();
        // The peer never stops and keeps producing progress; the first agent stops every tick.
        script.lifetimes(1, 1_000, 1, 1, 1, 1);
        script.at(0).head(1).announced();
        script.endlessProgress = true;

        AgentRunController.Result result = script.run(2, Duration.ofSeconds(40));

        assertEquals(2, result.resumes(), "the limit is per agent");
        assertEquals(AgentRunController.Outcome.TIMED_OUT, result.outcome());
        assertTrue(script.stopped.contains("codex#2"), "a running agent is stopped when the time limit passes");
        assertTrue(script.connections.contains("ended conn-2"), "a stopped agent's connection has ended too: " + script.connections);
    }

    @Test
    void resumeLimitIsNamedWhenProgressIsLeftUnseen() throws Exception {
        Script script = new Script();
        // The peer progresses twice; the first agent may only be resumed for the first. The peer
        // stops long after its own last progress, so it is taken to have seen it.
        script.lifetimes(1, 40, 1);
        script.at(0).head(1).announced();
        script.at(2).head(4).progressAfter(1);
        script.at(4).head(8).progressAfter(4);

        AgentRunController.Result result = script.run(1);

        assertEquals(1, result.resumes());
        assertEquals(AgentRunController.Outcome.RESUME_LIMIT_REACHED, result.outcome());
    }

    @Test
    void agentWhoseProviderNeverAnnouncedASessionCannotBeResumed() throws Exception {
        Script script = new Script();
        script.lifetimes(1, 40);
        script.withoutSessionId.add("claude#1");
        script.at(0).head(1).announced();
        script.at(3).head(6).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(0, result.resumes());
        assertFalse(script.launches.stream().anyMatch(launch -> launch.startsWith("resume claude#1")), script.launches.toString());
    }

    @Test
    void agentWhoseProviderHasNoOpenLaneIsNotResumedForAnotherProvidersProgress() throws Exception {
        Script script = new Script();
        // claude finished its own lane and stopped; codex then makes progress on its lane alone
        // and stops long after it.
        script.lifetimes(1, 40);
        script.closedLaneProviders.add("claude");
        script.at(0).head(1).announced();
        script.at(3).head(6).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(0, result.resumes());
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome(), "codex stopped with its lane still open and nothing further changed");
    }

    @Test
    void agentIsResumedForWorkRecordedShortlyBeforeItsProcessEnded() throws Exception {
        Script script = new Script();
        // codex records work at tick 2. claude's process is still running then and ends at tick
        // 3: a provider writes its closing message after its last look at the project, so the
        // work is in the log before the process exits and claude has not seen it.
        script.lifetimes(3, 2);
        script.closedLaneProviders.add("codex");
        script.at(0).head(1).announced();
        script.at(2).head(5).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(List.of("start claude#1", "start codex#2", "resume claude#1 session-1"), script.launches);
        assertEquals(1, result.resumes(), "the resumed agent has seen that work and is not resumed for it again");
    }

    @Test
    void workRecordedLongBeforeAnAgentStoppedIsTakenAsSeen() throws Exception {
        Script script = new Script();
        script.lifetimes(40, 2);
        script.closedLaneProviders.add("codex");
        script.at(0).head(1).announced();
        script.at(2).head(5).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(0, result.resumes());
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome());
    }

    @Test
    void livenessAndLaneTakeoverAreNotProgress() {
        assertFalse(AgentRunController.isProgress(PredictionEventType.PARTICIPANT_HEARTBEAT));
        assertFalse(AgentRunController.isProgress(PredictionEventType.PARTICIPANT_SUSPENDED));
        assertFalse(AgentRunController.isProgress(PredictionEventType.RECOVERY_SNAPSHOT_HELD));
        assertFalse(AgentRunController.isProgress(PredictionEventType.LANE_CONTINUATION_ACCEPTED));
        assertTrue(AgentRunController.isProgress(PredictionEventType.CAPABILITY_REQUEST_CREATED));
        assertTrue(AgentRunController.isProgress(PredictionEventType.CAPABILITY_IMPLEMENTATION_VALIDATED));
        assertTrue(AgentRunController.isProgress(PredictionEventType.TASK_INTEGRATED));
    }

    @Test
    void requestRejectsMissingAgentsPromptAndLimits() {
        List<AgentRunController.AgentSpec> one = List.of(AgentRunController.AgentSpec.of(ProviderRegistry.find("claude"), new HeadlessOptions(null, null)));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, List.of(), "go", Duration.ofMinutes(1), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, one, " ", Duration.ofMinutes(1), Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, one, "go", Duration.ZERO, Duration.ofSeconds(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new AgentRunController.Request(PROJECT, one, "go", Duration.ofMinutes(1), Duration.ofSeconds(1), -1));
    }

    /** A scripted project and scripted processes driven by the controller's own pauses. */
    private static final class Script {

        private final List<String> launches = new ArrayList<>();
        private final List<String> prompts = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final List<String> stopped = new ArrayList<>();
        private final List<String> withoutSessionId = new ArrayList<>();
        private final List<String> closedLaneProviders = new ArrayList<>();
        private final List<String> connections = new ArrayList<>();
        private final List<Map<String, String>> environments = new ArrayList<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<Integer> resumedAtTicks = new ArrayList<>();
        private final Map<String, Long> answerDeadlines = new java.util.HashMap<>();
        private final List<Integer> lifetimes = new ArrayList<>();
        private final java.util.TreeMap<Integer, State> states = new java.util.TreeMap<>();
        private boolean endlessProgress;
        private int tick;
        private int launched;

        private void lifetimes(Integer... ticks) {
            lifetimes.addAll(List.of(ticks));
        }

        private State at(int atTick) {
            return states.computeIfAbsent(atTick, ignored -> new State());
        }

        private AgentRunController.Result run(int maxResumes) throws Exception {
            return run(maxResumes, Duration.ofMinutes(10));
        }

        private AgentRunController.Result run(int maxResumes, Duration timeout) throws Exception {
            AgentRunController.Connections named = new AgentRunController.Connections() {

                private int opened;

                @Override
                public String open() {
                    String identity = "conn-" + ++opened;
                    connections.add("open " + identity);
                    return identity;
                }

                @Override
                public void ended(String connectionInstanceId) {
                    connections.add("ended " + connectionInstanceId);
                }
            };
            AgentRunController controller = new AgentRunController(this::start, this::observe, named, () -> tick * 1_000L, millis -> tick++);
            HeadlessOptions options = new HeadlessOptions(null, null);
            AgentRunController.Request request = new AgentRunController.Request(PROJECT,
                    List.of(AgentRunController.AgentSpec.of(ProviderRegistry.find("claude"), options), AgentRunController.AgentSpec.of(ProviderRegistry.find("codex"), options)),
                    "Read the goal and do your part.",
                    timeout,
                    Duration.ofSeconds(1),
                    maxResumes);
            return controller.run(request, events::add);
        }

        private AgentRunController.RunningAgent start(List<String> command, Path workingDirectory, Map<String, String> environment, String prompt, String label,
                Function<String, Optional<String>> sessionIdReader) {
            assertEquals(PROJECT, workingDirectory);
            commands.add(command);
            environments.add(environment);
            boolean resume = command.contains("--resume") || command.contains("resume");
            if (resume) {
                resumedAtTicks.add(tick);
            }
            String session = "session-" + label.substring(label.indexOf('#') + 1);
            launches.add(resume ? "resume " + label + " " + session : "start " + label);
            prompts.add(prompt);
            if (resume) {
                assertTrue(command.contains(session), "the agent's own provider session is resumed: " + command);
            }
            int endsAt = tick + (launched < lifetimes.size() ? lifetimes.get(launched) : 1);
            launched++;
            return new AgentRunController.RunningAgent() {

                private boolean halted;

                @Override
                public boolean isAlive() {
                    return !halted && tick < endsAt;
                }

                @Override
                public int exitCode() {
                    return 0;
                }

                @Override
                public Optional<String> providerSessionId() {
                    return withoutSessionId.contains(label) ? Optional.empty() : Optional.of(session);
                }

                @Override
                public void stop() {
                    halted = true;
                    stopped.add(label);
                }
            };
        }

        private AgentRunController.Observation observe() {
            State state = states.floorEntry(tick) == null ? new State() : states.floorEntry(tick).getValue();
            boolean announced = states.headMap(tick, true).values().stream().anyMatch(candidate -> candidate.announced);
            long head = endlessProgress ? tick + 1L : state.head;
            return new AgentRunController.Observation() {

                @Override
                public long headSequence() {
                    return head;
                }

                @Override
                public boolean workAnnounced() {
                    return announced;
                }

                @Override
                public boolean complete() {
                    return state.complete;
                }

                @Override
                public boolean hasOpenLane(String provider) {
                    return !closedLaneProviders.contains(provider);
                }

                @Override
                public boolean progressedSince(long sequence) {
                    return endlessProgress ? sequence < head : sequence <= state.progressAfter && state.progressAfter >= 0;
                }

                @Override
                public OptionalLong answerDeadline(String provider) {
                    Long deadline = answerDeadlines.get(provider);
                    return deadline == null ? OptionalLong.empty() : OptionalLong.of(deadline);
                }
            };
        }
    }

    /** Project state from one tick until the next scripted one. */
    private static final class State {

        private long head;
        private long progressAfter = -1;
        private boolean announced;
        private boolean complete;

        private State head(long value) {
            head = value;
            return this;
        }

        /** Work was recorded after this many events, so an agent that saw no further is behind. */
        private State progressAfter(long sequence) {
            progressAfter = sequence;
            return this;
        }

        private State announced() {
            announced = true;
            return this;
        }

        private State complete() {
            complete = true;
            return this;
        }
    }
}
