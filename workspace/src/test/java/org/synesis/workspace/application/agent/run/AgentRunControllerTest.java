package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;
import org.synesis.workspace.provider.ProviderRegistry;

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
    }

    @Test
    void resumeLimitIsNamedWhenProgressIsLeftUnseen() throws Exception {
        Script script = new Script();
        // The peer progresses twice; the first agent may only be resumed for the first.
        script.lifetimes(1, 5, 1);
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
        script.lifetimes(1, 4);
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
        // claude finished its own lane and stopped; codex then makes progress on its lane alone.
        script.lifetimes(1, 4);
        script.closedLaneProviders.add("claude");
        script.at(0).head(1).announced();
        script.at(3).head(6).progressAfter(1);

        AgentRunController.Result result = script.run(8);

        assertEquals(0, result.resumes());
        assertEquals(AgentRunController.Outcome.STALLED, result.outcome(), "codex stopped with its lane still open and nothing further changed");
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
            AgentRunController controller = new AgentRunController(this::start, this::observe, () -> tick * 1_000L, millis -> tick++);
            HeadlessOptions options = new HeadlessOptions(null, null);
            AgentRunController.Request request = new AgentRunController.Request(PROJECT,
                    List.of(AgentRunController.AgentSpec.of(ProviderRegistry.find("claude"), options), AgentRunController.AgentSpec.of(ProviderRegistry.find("codex"), options)),
                    "Read the goal and do your part.",
                    timeout,
                    Duration.ofSeconds(1),
                    maxResumes);
            return controller.run(request, events::add);
        }

        private AgentRunController.RunningAgent start(List<String> command, Path workingDirectory, String prompt, String label, Function<String, Optional<String>> sessionIdReader) {
            assertEquals(PROJECT, workingDirectory);
            boolean resume = command.contains("--resume") || command.contains("resume");
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
