package org.synesis.workspace.application.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessSession;

/**
 * Starts headless provider agents on one project and resumes an agent that has stopped when the
 * project's coordination state has moved on without it.
 *
 * <p>A headless agent ends its process when it has nothing left to do in one turn, including when
 * it has polled its inbox without change as often as the protocol allows. Nothing then tells it
 * that another lane has since answered, published, or validated something it was waiting for. This
 * controller is that missing signal and nothing more: it reads the durable log, and when work has
 * progressed since an agent stopped it starts that agent's own provider session again with one
 * neutral sentence. It never reads or relays agent conversation, never chooses work, a lane, or a
 * continuation, and never writes coordination state. Every decision an agent then makes is still
 * made through Synesis.</p>
 *
 * <p>A resumed agent is a new connection, so Synesis sees a new session; the lane it left is
 * recovered and offered for continuation by the ordinary recovery path.</p>
 */
public final class AgentRunController {

    /**
     * The only thing a resumed agent is told. It names no lane, peer, file, or action.
     */
    public static final String RESUME_PROMPT = "Continue following this project's coordination rules from where you stopped.";

    /**
     * Events that record liveness or the takeover of an abandoned lane rather than work. Resuming
     * an agent itself produces them, so counting them as progress would make two idle agents wake
     * each other forever.
     */
    private static final Set<PredictionEventType> BOOKKEEPING = Set.of(PredictionEventType.PARTICIPANT_HEARTBEAT,
            PredictionEventType.PARTICIPANT_SUSPENDED,
            PredictionEventType.RECOVERY_SNAPSHOT_HELD,
            PredictionEventType.LANE_CONTINUATION_ACCEPTED);

    private final AgentProcesses processes;
    private final ProjectObserver observer;
    private final LongSupplier clockMillis;
    private final Pause pause;

    /**
     * Creates a controller over explicit ports.
     *
     * @param processes   starts provider processes
     * @param observer    reads the project's durable coordination state
     * @param clockMillis current time in milliseconds
     * @param pause       waits between observations
     */
    public AgentRunController(AgentProcesses processes, ProjectObserver observer, LongSupplier clockMillis, Pause pause) {
        this.processes = Objects.requireNonNull(processes, "processes");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * Reports whether an event is work that another lane may be waiting on.
     *
     * @param type durable event type
     * @return {@code false} for liveness and lane-takeover bookkeeping
     */
    public static boolean isProgress(PredictionEventType type) {
        return !BOOKKEEPING.contains(Objects.requireNonNull(type, "type"));
    }

    /**
     * Runs the agents until the project's work groups complete, nothing can make further progress,
     * or the time limit passes.
     *
     * @param request  agents, prompt, and limits
     * @param listener receives one stable {@code KEY=VALUE} line per controller event
     * @return the outcome and how often agents were resumed
     * @throws IOException          when an agent cannot be started or the project cannot be observed
     * @throws InterruptedException when the controller is interrupted while waiting
     */
    public Result run(Request request, Consumer<String> listener) throws IOException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        long deadline = clockMillis.getAsLong() + request.timeout().toMillis();
        List<Agent> agents = new ArrayList<>();
        try {
            int index = 0;
            for (AgentSpec spec : request.agents()) {
                Agent agent = new Agent(++index, spec);
                agent.process = processes.start(spec.session().startCommand(request.projectRoot(), spec.options()), request.projectRoot(), request.prompt(), agent.label(), spec.session()::sessionId);
                agents.add(agent);
                listener.accept("AGENT_STARTED=" + agent.label());
            }
            long seenWhileRunning = observer.observe().headSequence();
            while (true) {
                Observation observation = observer.observe();
                for (Agent agent : agents) {
                    if (agent.process != null && !agent.process.isAlive()) {
                        agent.exited(seenWhileRunning);
                        listener.accept("AGENT_EXITED=" + agent.label() + " exitCode=" + agent.exitCode);
                    }
                }
                if (!observation.complete()) {
                    for (Agent agent : agents) {
                        if (agent.resumable(request.maxResumesPerAgent()) && observation.hasOpenLane(agent.spec.provider()) && observation.progressedSince(agent.stoppedAtSequence)) {
                            agent.resumes++;
                            agent.process = processes.start(agent.spec.session().resumeCommand(request.projectRoot(), agent.providerSessionId, agent.spec.options()), request.projectRoot(), RESUME_PROMPT,
                                    agent.label(), agent.spec.session()::sessionId);
                            listener.accept("AGENT_RESUMED=" + agent.label() + " resume=" + agent.resumes);
                        }
                    }
                }
                if (agents.stream().noneMatch(agent -> agent.process != null)) {
                    return new Result(observation.complete() ? Outcome.COMPLETED : stalledOutcome(agents, observation, request.maxResumesPerAgent()), resumes(agents));
                }
                if (clockMillis.getAsLong() >= deadline) {
                    return new Result(observation.complete() ? Outcome.COMPLETED : Outcome.TIMED_OUT, resumes(agents));
                }
                seenWhileRunning = observation.headSequence();
                pause.forMillis(request.pollInterval().toMillis());
            }
        } finally {
            for (Agent agent : agents) {
                if (agent.process != null) {
                    agent.process.stop();
                }
            }
        }
    }

    private static Outcome stalledOutcome(List<Agent> agents, Observation observation, int maxResumes) {
        if (!observation.workAnnounced()) {
            return Outcome.NO_WORK_ANNOUNCED;
        }
        boolean limitReached = agents.stream().anyMatch(agent -> agent.resumes >= maxResumes && observation.hasOpenLane(agent.spec.provider()) && observation.progressedSince(agent.stoppedAtSequence));
        return limitReached ? Outcome.RESUME_LIMIT_REACHED : Outcome.STALLED;
    }

    private static int resumes(List<Agent> agents) {
        return agents.stream().mapToInt(agent -> agent.resumes).sum();
    }

    /**
     * How a run ended.
     */
    public enum Outcome {
        /**
         * Every work group is complete and no lane is open.
         */
        COMPLETED,
        /**
         * Every agent has stopped and nothing has progressed since, so resuming any would change nothing.
         */
        STALLED,
        /**
         * Every agent stopped without any work having been announced.
         */
        NO_WORK_ANNOUNCED,
        /**
         * Work progressed that an agent should see, but that agent has been resumed as often as allowed.
         */
        RESUME_LIMIT_REACHED,
        /**
         * The time limit passed with work unfinished; running agents were stopped.
         */
        TIMED_OUT
    }

    /**
     * Waits between observations.
     */
    @FunctionalInterface
    public interface Pause {

        /**
         * Waits.
         *
         * @param millis time to wait
         * @throws InterruptedException when interrupted
         */
        void forMillis(long millis) throws InterruptedException;
    }

    /**
     * Starts provider processes.
     */
    @FunctionalInterface
    public interface AgentProcesses {

        /**
         * Starts one provider process.
         *
         * @param command          argv from the provider's headless contract
         * @param workingDirectory control project root
         * @param prompt           text written to the process's standard input, which is then closed
         * @param label            stable name of the agent, for logs
         * @param sessionIdReader  recognises the provider session identifier in an output line
         * @return the running process
         * @throws IOException when the process cannot be started
         */
        RunningAgent start(List<String> command, Path workingDirectory, String prompt, String label, java.util.function.Function<String, Optional<String>> sessionIdReader) throws IOException;
    }

    /**
     * One started provider process.
     */
    public interface RunningAgent {

        /**
         * Reports whether the process is still running.
         *
         * @return {@code true} while running
         */
        boolean isAlive();

        /**
         * Returns the exit code of a finished process.
         *
         * @return exit code
         */
        int exitCode();

        /**
         * Returns the provider session identifier, once the provider has announced it.
         *
         * @return identifier, or empty when none was seen
         */
        Optional<String> providerSessionId();

        /**
         * Stops the process and its descendants.
         */
        void stop();
    }

    /**
     * Reads the project's durable coordination state.
     */
    @FunctionalInterface
    public interface ProjectObserver {

        /**
         * Observes the project now.
         *
         * @return the observation
         * @throws IOException when the log cannot be read
         */
        Observation observe() throws IOException;
    }

    /**
     * One reading of the project's durable coordination state.
     */
    public interface Observation {

        /**
         * Returns the number of events in the log.
         *
         * @return head sequence
         */
        long headSequence();

        /**
         * Reports whether any work group exists.
         *
         * @return {@code true} once work was announced
         */
        boolean workAnnounced();

        /**
         * Reports whether all announced work is finished: at least one work group exists, none is
         * active, and no lane is open.
         *
         * @return {@code true} when nothing is left to do
         */
        boolean complete();

        /**
         * Reports whether a lane announced through this provider is still open.
         *
         * <p>A session can only act on a lane of its own provider: its own, or one it may continue.
         * When none is open, an agent of that provider has finished its part, and resuming it for
         * another provider's progress would only spend a turn finding that out.</p>
         *
         * @param provider provider identifier
         * @return {@code true} while such a lane is open
         */
        boolean hasOpenLane(String provider);

        /**
         * Reports whether work has progressed after a point in the log.
         *
         * @param sequence number of events that had been recorded at that point
         * @return {@code true} when a later event is progress in the sense of
         *         {@link AgentRunController#isProgress(PredictionEventType)}
         */
        boolean progressedSince(long sequence);
    }

    /**
     * One agent to run.
     *
     * @param provider provider identifier
     * @param session  the provider's headless contract
     * @param options  model selection
     */
    public record AgentSpec(String provider, HeadlessSession session, HeadlessOptions options) {

        /**
         * Validates the specification.
         */
        public AgentSpec {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(options, "options");
        }

        /**
         * Builds a specification from a registered provider.
         *
         * @param integration provider integration
         * @param options     model selection
         * @return the specification
         * @throws IllegalArgumentException when the provider has no verified headless mode
         */
        public static AgentSpec of(ProviderIntegration integration, HeadlessOptions options) {
            return new AgentSpec(integration.id(), integration.headlessSession().orElseThrow(() -> new IllegalArgumentException("provider has no headless mode: " + integration.id())), options);
        }
    }

    /**
     * What to run and for how long.
     *
     * @param projectRoot        control project root
     * @param agents             agents to start, in order
     * @param prompt             the one prompt every agent starts with
     * @param timeout            overall time limit
     * @param pollInterval       time between observations
     * @param maxResumesPerAgent how often one agent may be resumed
     */
    public record Request(Path projectRoot, List<AgentSpec> agents, String prompt, Duration timeout, Duration pollInterval, int maxResumesPerAgent) {

        /**
         * Validates the request.
         */
        public Request {
            projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
            agents = List.copyOf(Objects.requireNonNull(agents, "agents"));
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(pollInterval, "pollInterval");
            if (agents.isEmpty()) {
                throw new IllegalArgumentException("at least one agent is required");
            }
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalArgumentException("a prompt is required");
            }
            if (timeout.isZero() || timeout.isNegative() || pollInterval.isZero() || pollInterval.isNegative() || maxResumesPerAgent < 0) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    /**
     * Outcome of a run.
     *
     * @param outcome how the run ended
     * @param resumes total number of times agents were resumed
     */
    public record Result(Outcome outcome, int resumes) {

    }

    /** Mutable bookkeeping for one agent across its provider processes. */
    private static final class Agent {

        private final int index;
        private final AgentSpec spec;
        private RunningAgent process;
        private String providerSessionId;
        private long stoppedAtSequence;
        private int exitCode;
        private int resumes;

        private Agent(int index, AgentSpec spec) {
            this.index = index;
            this.spec = spec;
        }

        private String label() {
            return spec.provider() + "#" + index;
        }

        /**
         * Records that the process ended. The agent is taken to have seen the log only as far as
         * the controller had while the process was still running: assuming more could hide the
         * very event it needs to be resumed for, and assuming less costs one extra resume at most.
         */
        private void exited(long seenWhileRunning) {
            exitCode = process.exitCode();
            process.providerSessionId().ifPresent(identifier -> providerSessionId = identifier);
            stoppedAtSequence = seenWhileRunning;
            process = null;
        }

        private boolean resumable(int maxResumes) {
            return process == null && providerSessionId != null && resumes < maxResumes;
        }
    }
}
