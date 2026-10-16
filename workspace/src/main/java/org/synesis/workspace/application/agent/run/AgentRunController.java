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
            PredictionEventType.LANE_CONTINUATION_ACCEPTED,
            // A message is not work. It resumes the agent it is addressed to, once, by a rule
            // of its own; counted as progress it would resume every stopped agent.
            PredictionEventType.CONVERSATION_MESSAGE_SENT,
            PredictionEventType.CONVERSATION_MESSAGES_DELIVERED);

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
                        agent.exited(observation.lastActivitySequence(agent.spec.provider(), agent.connectionId), seenBeforeExit);
                        listener.accept("AGENT_EXITED=" + agent.label() + " exitCode=" + agent.exitCode);
                    }
                }
                boolean awaitingDeadline = false;
                if (!observation.complete()) {
                    for (Agent agent : agents) {
                        // Only an agent whose own lane is open has something to come back to. Resumed
                        // for another lane's progress, an agent with nothing left looked for work,
                        // announced lanes to verify what was already integrated, and each of those
                        // woke the others: 38 resumes in a run whose work was done.
                        if (!agent.resumable(request.maxResumesPerAgent()) || !agent.holdsOpenLane(observation)) {
                            continue;
                        }
                        if (observation.heldByOperator(agent.spec.provider())) {
                            // Its published work waits for an operator. Resumed, it took its lane
                            // back, published the same work again and was refused again: eleven
                            // times in one real run, each on another lane's progress.
                            continue;
                        }
                        OptionalLong answerDeadline = observation.answerDeadline(agent.spec.provider());
                        boolean deadlinePassed = answerDeadline.isPresent() && now >= answerDeadline.getAsLong() && answerDeadline.getAsLong() != agent.resumedForDeadline;
                        OptionalLong message = agent.newestUnreadMessage(observation);
                        boolean messageWaiting = message.isPresent() && message.getAsLong() > Math.max(agent.stoppedAtSequence, agent.resumedForMessage);
                        if (observation.progressedSince(agent.stoppedAtSequence) || deadlinePassed || messageWaiting) {
                            if (deadlinePassed) {
                                agent.resumedForDeadline = answerDeadline.getAsLong();
                            }
                            // Whatever it is resumed for, it reads its messages then. A message
                            // it stops without acting on does not resume it again.
                            agent.resumedForMessage = Math.max(agent.resumedForMessage, message.orElse(0L));
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
                    Outcome outcome = observation.complete() ? closedOutcome(observation) : stalledOutcome(agents, observation, request.maxResumesPerAgent());
                    // Our agents stopped, but a participant this controller did not start (another
                    // computer's agent, an interactive agent) still holds a lane. Ending now would
                    // leave nobody here to review or integrate its work, so keep polling until it
                    // progresses, ends, or the deadline passes.
                    boolean foreignLaneOpen = outcome == Outcome.STALLED && observation.foreignActiveLaneOpen(agents.stream()
                            .flatMap(agent -> agent.connectionIds.stream().map(identity -> new Observation.Connection(agent.spec.provider(), identity))).toList());
                    if (!foreignLaneOpen) {
                        if (outcome == Outcome.NEEDS_OPERATOR) {
                            observation.operatorRequests().forEach(open -> listener.accept("OPERATOR_REQUEST=" + open));
                        }
                        return new Result(outcome, resumes(agents));
                    }
                }
                if (clockMillis.getAsLong() >= deadline) {
                    return new Result(observation.complete() ? closedOutcome(observation) : Outcome.TIMED_OUT, resumes(agents));
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

    /** With nothing left open, a run is complete only when some of its work was finished rather than all of it cancelled. */
    private static Outcome closedOutcome(Observation observation) {
        return observation.workFinished() ? Outcome.COMPLETED : Outcome.ALL_WORK_CANCELLED;
    }

    private static Outcome stalledOutcome(List<Agent> agents, Observation observation, int maxResumes) {
        if (!observation.workAnnounced()) {
            return Outcome.NO_WORK_ANNOUNCED;
        }
        if (!observation.operatorRequests().isEmpty()) {
            // Every agent has stopped with a request open that none of them may clear. Naming a
            // stall or a resume limit would send the reader to the agents for the cause.
            return Outcome.NEEDS_OPERATOR;
        }
        boolean limitReached = agents.stream()
                .anyMatch(agent -> agent.resumes >= maxResumes && agent.holdsOpenLane(observation) && observation.progressedSince(agent.stoppedAtSequence));
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
         * No lane is open and at least one work group finished its work.
         */
        COMPLETED,
        /**
         * No lane is open and no work group finished any work: every lane was cancelled. Two
         * agents that each gave up the same role ended a run this way with nothing built, and it
         * was reported as completed.
         */
        ALL_WORK_CANCELLED,
        /**
         * Every agent has stopped and resuming any would change nothing: nothing has progressed
         * since it stopped, or no lane it took is still open.
         */
        STALLED,
        /**
         * Every agent has stopped and an operator request is open: what is left waits for someone
         * with authority over the project. Running again after the operator has acted continues.
         */
        NEEDS_OPERATOR,
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
         * Reports whether any work group closed as completed rather than cancelled.
         *
         * @return {@code true} once some announced work was finished
         */
        boolean workFinished();

        /**
         * Reports whether a lane announced through this provider is still open.
         *
         * <p>This no longer decides whether an agent is resumed: two agents of one provider each
         * kept the other awake through it. {@link #holdsOpenLane} asks about the agent's own
         * lane and decides.</p>
         *
         * @param provider provider identifier
         * @return {@code true} while such a lane is open
         */
        boolean hasOpenLane(String provider);

        /**
         * Returns the log head at the last call the agent behind one connection made to Synesis.
         *
         * @param provider             provider of the agent
         * @param connectionInstanceId connection the controller gave that process
         * @return the head at that call, or empty when the connection recorded none
         */
        OptionalLong lastActivitySequence(String provider, String connectionInstanceId);

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

        /**
         * Returns the operator requests that are open, one stable line each: the identifier,
         * then {@code CONDITION=} and the condition.
         *
         * @return open operator requests, oldest first; empty when none is open
         */
        default List<String> operatorRequests() {
            return List.of();
        }

        /**
         * Tells whether every open lane of a provider has published work that an open operator
         * request keeps from being integrated. Nothing an agent of that provider could do would
         * move such a lane, so the controller leaves it stopped until the request closes.
         *
         * @param provider provider identifier
         * @return {@code true} when the provider's lanes wait only for an operator
         */
        default boolean heldByOperator(String provider) {
            return false;
        }

        /**
         * Reports whether the session behind one connection holds a lane that is still open. An
         * agent is resumed for its own lane only: one that never held a lane, or whose lane has
         * ended or was taken over, has nothing to continue.
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection of one of the agent's processes
         * @return {@code true} while that session's lane is open
         */
        boolean holdsOpenLane(String provider, String connectionInstanceId);

        /**
         * One connection the controller gave a process of its own.
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection identity
         */
        record Connection(String provider, String connectionInstanceId) {
        }

        /**
         * Reports whether a lane is open that is held by an active participant this controller did
         * not start: an agent on another computer sharing the log, or an interactive agent. Such a
         * participant may still need a review or an integration that only this computer's agents
         * can give, and its progress is what resumes them. A participant that is suspended,
         * revoked, cancelled, detached or otherwise not active holds nothing that will progress.
         *
         * @param managed every connection this controller gave a process
         * @return {@code true} while another active participant holds an open lane
         */
        default boolean foreignActiveLaneOpen(List<Connection> managed) {
            return false;
        }

        /**
         * Returns the log position of the newest message addressed to the lane that the session
         * behind one connection holds and not yet marked as handed to it (ADR-0093). The
         * controller resumes a stopped agent for such a message, and for that message only once.
         * A message the agent read before it stopped is not one: resumed for it, an agent found
         * nothing and had spent a resume.
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection of the agent's last process
         * @return that position, or empty when the connection holds no lane or its lane has nothing unread
         */
        default OptionalLong newestUnreadMessageFor(String provider, String connectionInstanceId) {
            return OptionalLong.empty();
        }
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
        private long resumedForMessage;
        /** Every connection this agent's processes have had, oldest first. */
        private final List<String> connectionIds = new ArrayList<>();
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
            connectionIds.add(identity);
            return spec.options().withConnection(identity);
        }

        /**
         * Records that the process ended, and how far the agent is taken to have acted on the log:
         * the earlier of its last recorded call to Synesis and the log as it stood shortly before
         * the process ended. The last call bounds it because an agent can take a minute to write
         * its closing message, and work recorded in that time was taken as seen. The time before
         * the end bounds it because an agent that has decided to stop still polls once more, and
         * one left that way with its reviewer's grant in the answer. An agent that never held a
         * lane left no recorded call. Either way it has seen what was there when the process
         * started.
         *
         * @param lastActivity   log head at the last recorded call of the agent, when it has one
         * @param seenBeforeExit log head shortly before the process ended, when the controller
         *                       has been observing that long
         */
        private void exited(OptionalLong lastActivity, OptionalLong seenBeforeExit) {
            exitCode = process.exitCode();
            process.providerSessionId().ifPresent(identifier -> providerSessionId = identifier);
            long seen = seenBeforeExit.orElse(startedAtSequence);
            stoppedAtSequence = Math.max(startedAtSequence, lastActivity.isPresent() ? Math.min(lastActivity.getAsLong(), seen) : seen);
            process = null;
        }

        /**
         * Reports whether a lane this agent took is still open. The lane stays under the
         * connection that took it until a later connection continues it, so every connection
         * the agent has had is asked: a resume that ended before it took the lane back must not
         * leave the agent looking as if it had none, or that lane is never continued.
         */
        private boolean holdsOpenLane(Observation observation) {
            return connectionIds.stream().anyMatch(identity -> observation.holdsOpenLane(spec.provider(), identity));
        }

        /** The newest unread message addressed to a lane any of this agent's connections holds. */
        private OptionalLong newestUnreadMessage(Observation observation) {
            return connectionIds.stream().map(identity -> observation.newestUnreadMessageFor(spec.provider(), identity)).filter(OptionalLong::isPresent)
                    .mapToLong(OptionalLong::getAsLong).max();
        }

        private boolean resumable(int maxResumes) {
            return process == null && providerSessionId != null && resumes < maxResumes;
        }
    }
}
