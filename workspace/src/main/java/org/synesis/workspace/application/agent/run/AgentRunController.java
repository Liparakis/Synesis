package org.synesis.workspace.application.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
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
 * continuation, and never writes to the coordination log. Every decision an agent then makes is
 * still made through Synesis.</p>
 *
 * <p>A resumed agent is a new connection, so Synesis sees a new session; the lane it left is
 * recovered and offered for continuation by the ordinary recovery path. That path starts once a
 * connection is known to have ended. A provider that kills its MCP server instead of closing it
 * leaves no such proof, and Synesis would wait out its abrupt-loss grace period before believing
 * it. The controller started the process and saw it exit, so it gives each process its own
 * connection identity and reports that connection ended: the one fact it adds, and only about
 * its own children.</p>
 *
 * <p>An owner waiting a bounded time for a requester's answer also ends its process. The log does
 * not move when that time passes, so the controller reads the deadline and resumes the owner's
 * provider once for it.</p>
 */
public final class AgentRunController {

    /**
     * The only thing a resumed agent is told. It names no lane, peer, file, or action.
     */
    static final String RESUME_PROMPT = "Continue following this project's coordination rules from where you stopped.";

    /**
     * Events that record liveness or the takeover of an abandoned lane rather than work. Resuming
     * an agent itself produces them, so counting them as progress would make two idle agents wake
     * each other forever.
     */
    private static final Set<PredictionEventType> BOOKKEEPING = Set.of(PredictionEventType.PARTICIPANT_HEARTBEAT,
            PredictionEventType.PARTICIPANT_SUSPENDED,
            PredictionEventType.RECOVERY_SNAPSHOT_HELD,
            PredictionEventType.LANE_CONTINUATION_ACCEPTED);

    /**
     * How long before its process ended an agent is taken to have last looked at the project. A
     * provider ends its process some seconds after its last call, once its closing message is
     * written. Work recorded in that gap is in the log before the process exits although the
     * agent never saw it, and an agent taken to have seen it would never be resumed for it.
     */
    private static final long UNSEEN_BEFORE_EXIT_MILLIS = 30_000L;

    private final AgentProcesses processes;
    private final ProjectObserver observer;
    private final Connections connections;
    private final LongSupplier clockMillis;
    private final Pause pause;

    /**
     * Creates a controller over explicit ports.
     *
     * @param processes   starts provider processes
     * @param observer    reads the project's durable coordination state
     * @param connections names each process's connection and records that it ended
     * @param clockMillis current time in milliseconds
     * @param pause       waits between observations
     */
    public AgentRunController(AgentProcesses processes, ProjectObserver observer, Connections connections, LongSupplier clockMillis, Pause pause) {
        this.processes = Objects.requireNonNull(processes, "processes");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * Reports whether an event is work that another lane may be waiting on.
     *
     * @param type durable event type
     * @return {@code false} for liveness and lane-takeover bookkeeping
     */
    static boolean isProgress(PredictionEventType type) {
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
        // The log head at each observation younger than UNSEEN_BEFORE_EXIT_MILLIS, oldest first,
        // and the head at the newest observation that is older.
        Deque<HeadAt> heads = new ArrayDeque<>();
        OptionalLong seenBeforeExit = OptionalLong.empty();
        try {
            int index = 0;
            long headAtStart = observer.observe().headSequence();
            for (AgentSpec spec : request.agents()) {
                Agent agent = new Agent(++index, spec);
                agent.startedAtSequence = headAtStart;
                HeadlessOptions options = agent.connect(connections.open());
                agent.process = processes.start(spec.session().startCommand(request.projectRoot(), options), request.projectRoot(), spec.session().environment(options), request.prompt(), agent.label(),
                        spec.session()::sessionId);
                agents.add(agent);
                listener.accept("AGENT_STARTED=" + agent.label());
            }
            while (true) {
                Observation observation = observer.observe();
                long now = clockMillis.getAsLong();
                long unseenFrom = now - UNSEEN_BEFORE_EXIT_MILLIS;
                while (!heads.isEmpty() && heads.getFirst().millis() <= unseenFrom) {
                    seenBeforeExit = OptionalLong.of(heads.removeFirst().sequence());
                }
                heads.addLast(new HeadAt(now, observation.headSequence()));
                for (Agent agent : agents) {
                    if (agent.process != null && !agent.process.isAlive()) {
                        connections.ended(agent.connectionId);
                        agent.exited(seenBeforeExit);
                        listener.accept("AGENT_EXITED=" + agent.label() + " exitCode=" + agent.exitCode);
                    }
                }
                boolean awaitingDeadline = false;
                if (!observation.complete()) {
                    for (Agent agent : agents) {
                        if (!agent.resumable(request.maxResumesPerAgent()) || !observation.hasOpenLane(agent.spec.provider())) {
                            continue;
                        }
                        OptionalLong answerDeadline = observation.answerDeadline(agent.spec.provider());
                        boolean deadlinePassed = answerDeadline.isPresent() && now >= answerDeadline.getAsLong() && answerDeadline.getAsLong() != agent.resumedForDeadline;
                        if (observation.progressedSince(agent.stoppedAtSequence) || deadlinePassed) {
                            if (deadlinePassed) {
                                agent.resumedForDeadline = answerDeadline.getAsLong();
                            }
                            agent.resumes++;
                            agent.startedAtSequence = observation.headSequence();
                            HeadlessOptions options = agent.connect(connections.open());
                            agent.process = processes.start(agent.spec.session().resumeCommand(request.projectRoot(), agent.providerSessionId, options), request.projectRoot(),
                                    agent.spec.session().environment(options), RESUME_PROMPT, agent.label(), agent.spec.session()::sessionId);
                            listener.accept("AGENT_RESUMED=" + agent.label() + " resume=" + agent.resumes);
                        } else if (answerDeadline.isPresent() && now < answerDeadline.getAsLong()) {
                            awaitingDeadline = true;
                        }
                    }
                }
                // With every agent stopped the run is over, unless a stopped owner is only waiting
                // for its answer period to end: nothing in the log will announce that moment.
                if (agents.stream().noneMatch(agent -> agent.process != null) && !awaitingDeadline) {
                    return new Result(observation.complete() ? Outcome.COMPLETED : stalledOutcome(agents, observation, request.maxResumesPerAgent()), resumes(agents));
                }
                if (clockMillis.getAsLong() >= deadline) {
                    return new Result(observation.complete() ? Outcome.COMPLETED : Outcome.TIMED_OUT, resumes(agents));
                }
                pause.forMillis(request.pollInterval().toMillis());
            }
        } finally {
            for (Agent agent : agents) {
                if (agent.process != null) {
                    agent.process.stop();
                    connections.ended(agent.connectionId);
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

    /** The log head the controller observed at one moment. */
    private record HeadAt(long millis, long sequence) {
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
         * @param environment      variables added to the process's environment
         * @param prompt           text written to the process's standard input, which is then closed
         * @param label            stable name of the agent, for logs
         * @param sessionIdReader  recognises the provider session identifier in an output line
         * @return the running process
         * @throws IOException when the process cannot be started
         */
        RunningAgent start(List<String> command, Path workingDirectory, Map<String, String> environment, String prompt, String label, java.util.function.Function<String, Optional<String>> sessionIdReader)
                throws IOException;
    }

    /**
     * Names the Synesis connection of each provider process and records when it has ended.
     */
    public interface Connections {

        /**
         * Returns a new connection identity for one process start.
         *
         * @return identity the process's Synesis MCP server will use
         */
        String open();

        /**
         * Records that the process using a connection has exited.
         *
         * @param connectionInstanceId identity returned by {@link #open()}
         */
        void ended(String connectionInstanceId);
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

        /**
         * Returns when the open lanes of a provider may stop waiting for a requester to answer a
         * contract they proposed.
         *
         * <p>An owner told to wait ends its process like any other idle agent, and the log does
         * not change when the wait is over. This is the one moment the controller must take from
         * a clock instead of from recorded progress.</p>
         *
         * @param provider provider identifier
         * @return the latest such deadline in epoch milliseconds, or empty when none is pending
         */
        OptionalLong answerDeadline(String provider);
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
        private String connectionId;
        private String providerSessionId;
        private long startedAtSequence;
        private long stoppedAtSequence;
        private long resumedForDeadline = Long.MIN_VALUE;
        private int exitCode;
        private int resumes;

        private Agent(int index, AgentSpec spec) {
            this.index = index;
            this.spec = spec;
        }

        private String label() {
            return spec.provider() + "#" + index;
        }

        /** Gives the next process of this agent its own connection identity. */
        private HeadlessOptions connect(String identity) {
            connectionId = identity;
            return spec.options().withConnection(identity);
        }

        /**
         * Records that the process ended. The agent is taken to have seen the log as it stood
         * shortly before the process ended, and never less than what was there when the process
         * started: assuming more could hide the very event it needs to be resumed for, and
         * assuming less costs one extra resume at most.
         *
         * @param seenBeforeExit log head shortly before the process ended, when the controller
         *                       has been observing that long
         */
        private void exited(OptionalLong seenBeforeExit) {
            exitCode = process.exitCode();
            process.providerSessionId().ifPresent(identifier -> providerSessionId = identifier);
            stoppedAtSequence = Math.max(startedAtSequence, seenBeforeExit.orElse(startedAtSequence));
            process = null;
        }

        private boolean resumable(int maxResumes) {
            return process == null && providerSessionId != null && resumes < maxResumes;
        }
    }
}
