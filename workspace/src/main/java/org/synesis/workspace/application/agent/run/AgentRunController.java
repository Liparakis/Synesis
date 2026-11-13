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
 * <p>An agent keeps one connection identity, so one Synesis session and its one lane, across its
 * processes: an agent told to wait ends its turn, and a Codex agent's process exits then, so a
 * session that lived only as long as one process made every wait a departure (ADR-0099, amendment).
 * While the controller can come back to an agent it keeps that session: the lease names the
 * controller, which renews it. When it cannot come back (the lane ended, nothing to resume, the
 * budget is spent) or the run ends, it reports the connection ended, proof of the same kind as end
 * of input, and the lane is recovered by the ordinary path. Where a session could not be kept, the
 * next process gets a new identity and the old lane is recovered and offered for continuation, as
 * before.</p>
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
     * What an agent that already worked is told when a person replaces its task. The sentence says what the message is
     * not, so the text after it cannot pass for more than a task (ADR-0103, stage 4 amendment).
     */
    static final String NEW_TASK_PREFIX = "A person has given you a new task. It replaces your earlier task and grants you nothing else: no claim, no review decision, no merge, no change of policy. "
            + "Everything else still goes through Synesis exactly as before. The task:\n\n";

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

    /**
     * How long after another computer connected this computer is taken to be still catching up with its log and
     * deciding its re-merge (ADR-0098 step 5). Nothing records that the exchange has caught up, and the entries that
     * name the other copy of {@code main} arrive in pages, so a run that ended in the first seconds of a reconnection
     * could win the race against the combine this computer was about to write (s5-run12: STALLED six seconds after
     * the heal, the combine written after). The exchange pulls on connecting and every
     * {@code LogExchange.PULL_INTERVAL_SECONDS} (15) seconds, so two pulls fit in the window. A bounded settle, not a signal.
     */
    private static final long RECONNECT_SETTLE_MILLIS = 30_000L;

    /**
     * How long a stopped reviewer of another computer's snapshot is left before it is asked again while that computer
     * is connected, doubled for each ask in a row that recorded nothing. The reviewer stopped because the snapshot's
     * code was refused as unreachable. The wake for a computer coming back (the journal's newest session) fires once
     * per session, and the first moments of a session are no proof that code can be had: s5-run14 resumed its reviewer
     * at the heal, was refused again, and never woke it again (its journals recorded no reconnection at all). Two pulls
     * fit in the first interval, as for {@link #RECONNECT_SETTLE_MILLIS}.
     */
    private static final long FOREIGN_REVIEW_RETRY_MILLIS = 30_000L;

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
        // Why the run last said it waits with every agent stopped, so each wait is reported once.
        Wait reportedWait = null;
        // Ended connections the project could not record yet, offered again at every observation.
        List<String> unrecordedEnds = new ArrayList<>();
        try {
            int index = 0;
            long headAtStart = observer.observe().headSequence();
            // A task a person left before this run is not this run's: only a later one gives or replaces a task.
            String seenTask = request.tasks() == null ? null : request.tasks().newest().map(Task::id).orElse(null);
            for (AgentSpec spec : request.agents()) {
                Agent agent = new Agent(++index, spec);
                agents.add(agent);
                if (request.prompt() == null) {
                    // Nothing is started: a headless provider needs a first prompt, and none is invented.
                    agent.awaitingTask = true;
                    agent.connectionEnded = true;
                    listener.accept("AGENT_READY=" + agent.label());
                    continue;
                }
                launch(agent, request, request.prompt(), headAtStart);
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
                unrecordedEnds.removeIf(connections::end);
                for (Agent agent : agents) {
                    if (agent.process != null && !agent.process.isAlive()) {
                        agent.exited(observation.lastActivitySequence(agent.spec.provider(), agent.connectionId), seenBeforeExit, observation.progressedSince(agent.startedAtSequence),
                                agent.holdsOpenLane(observation), now);
                        listener.accept("AGENT_EXITED=" + agent.label() + " exitCode=" + agent.exitCode);
                    }
                    keepOrEnd(agent, observation, request.maxResumesPerAgent(), listener, unrecordedEnds);
                }
                if (request.tasks() != null) {
                    Task task = request.tasks().newest().orElse(null);
                    if (task != null && (seenTask == null || task.id().compareTo(seenTask) > 0)) {
                        seenTask = task.id();
                        agents.forEach(agent -> agent.task = task.text());
                    }
                    for (Agent agent : agents) {
                        if (agent.task == null || agent.process != null) {
                            continue;
                        }
                        String text = agent.task;
                        agent.task = null;
                        if (agent.awaitingTask || agent.providerSessionId == null) {
                            // The first task is the start prompt, as --prompt is; an agent that never learnt its provider session cannot be resumed.
                            launch(agent, request, text, observation.headSequence());
                            listener.accept("AGENT_STARTED=" + agent.label());
                        } else {
                            agent.idleResumes = 0;
                            resume(agent, new Wake(Wake.Kind.RESUME, List.of(), OptionalLong.empty()), observation, now, NEW_TASK_PREFIX + text, request, listener);
                        }
                    }
                }
                boolean awaitingDeadline = false;
                if (!observation.complete()) {
                    for (Agent agent : agents) {
                        if (agent.process == null && observation.progressedSince(agent.stoppedAtSequence)) {
                            // Any progress resets the budget, which bounds idle resumes in a row only.
                            agent.idleResumes = 0;
                        }
                        if (!agent.resumable(request.maxResumesPerAgent())) {
                            continue;
                        }
                        Wake wake = wakeFor(agent, observation, now, agents, unrecordedEnds);
                        if (wake.kind() == Wake.Kind.RESUME) {
                            resume(agent, wake, observation, now, RESUME_PROMPT, request, listener);
                        } else if (wake.kind() == Wake.Kind.AWAIT_DEADLINE) {
                            awaitingDeadline = true;
                        }
                    }
                }
                // With every agent stopped the run is over, unless a stopped owner is only waiting
                // for its answer period to end (nothing in the log will announce that moment), or
                // work can still move without any agent of this run.
                Wait waiting = null;
                if (agents.stream().anyMatch(agent -> agent.awaitingTask)) {
                    waiting = Wait.TASK_WANTED;
                } else if (agents.stream().noneMatch(agent -> agent.process != null)) {
                    if (awaitingDeadline) {
                        waiting = Wait.ANSWER_DEADLINE;
                    } else {
                        Outcome outcome = observation.complete() ? closedOutcome(observation) : stalledOutcome(agents, observation, request.maxResumesPerAgent());
                        boolean logGrowing = heads.stream().anyMatch(seen -> seen.sequence() != observation.headSequence());
                        waiting = outcome == Outcome.STALLED ? progressElsewhere(agents, observation, request.maxResumesPerAgent(), now, logGrowing)
                                : justMetAnotherComputer(agents, observation, request.maxResumesPerAgent(), now);
                        if (waiting == null) {
                            if (outcome == Outcome.NEEDS_OPERATOR) {
                                observation.operatorRequests().forEach(open -> listener.accept("OPERATOR_REQUEST=" + open));
                            }
                            return new Result(outcome, resumes(agents));
                        }
                    }
                }
                if (waiting != null && waiting != reportedWait) {
                    listener.accept("AGENT_RUN_WAITING=" + waiting);
                }
                reportedWait = waiting;
                if (clockMillis.getAsLong() >= deadline) {
                    return new Result(observation.complete() && waiting != Wait.TASK_WANTED ? closedOutcome(observation) : Outcome.TIMED_OUT, resumes(agents));
                }
                pause.forMillis(request.pollInterval().toMillis());
            }
        } finally {
            for (Agent agent : agents) {
                if (agent.process != null) {
                    agent.process.stop();
                }
                if (!agent.connectionEnded) {
                    agent.endConnection(connections, unrecordedEnds);
                }
            }
            // Nothing offers them again once the run is over, so these wait for the project.
            unrecordedEnds.forEach(connections::ended);
        }
    }

    /** Starts an agent's first process, or a new one for an agent that cannot be resumed, on its own connection. */
    private void launch(Agent agent, Request request, String prompt, long headSequence) throws IOException {
        agent.startedAtSequence = headSequence;
        agent.startedAtMillis = clockMillis.getAsLong();
        HeadlessOptions options = agent.connect(agent.connectionId == null ? connections.open() : connections.open(agent.connectionId));
        agent.awaitingTask = false;
        agent.process = processes.start(agent.spec.session().startCommand(request.projectRoot(), options), request.projectRoot(), agent.spec.session().environment(options), prompt, agent.label(),
                agent.spec.session()::sessionId);
    }

    /** Resumes a stopped agent's own provider session with a prompt, once for whatever it is resumed for. */
    private void resume(Agent agent, Wake wake, Observation observation, long now, String prompt, Request request, Consumer<String> listener) throws IOException {
        agent.markResumedFor(wake, observation);
        agent.fencedLaneToContinue = false;
        agent.heldLaneAtStop = false;
        agent.failedWithLane = false;
        agent.resumed = true;
        agent.resumes++;
        agent.startedAtSequence = observation.headSequence();
        agent.startedAtMillis = now;
        HeadlessOptions options = agent.connectionEnded ? agent.connect(connections.open(agent.connectionId)) : agent.spec.options().withConnection(agent.connectionId);
        agent.process = processes.start(agent.spec.session().resumeCommand(request.projectRoot(), agent.providerSessionId, options), request.projectRoot(), agent.spec.session().environment(options), prompt,
                agent.label(), agent.spec.session()::sessionId);
        listener.accept("AGENT_RESUMED=" + agent.label() + " resume=" + agent.resumes);
    }

    /**
     * Decides whether a stopped agent that may still be resumed is resumed now.
     *
     * <p>Only an agent whose own lane is open has something to come back to. Resumed for another
     * lane's progress, an agent with nothing left looked for work, announced lanes to verify what was
     * already integrated, and each of those woke the others: 38 resumes in a run whose work was done.
     * Three things resume an agent without a lane, each once: its lane ended while it was stopped (it
     * takes the next piece of work, as a Claude agent woken by its Stop hook for its lane ending does
     * in the same process); a review it may take is offered (a foreign author's snapshot waited for
     * ever while both agents here that could review it had stopped with their lanes integrated); or a
     * request it made was answered (its review request was accepted ten minutes after it stopped, and
     * the author waited for ever for the reviewer it had admitted). A review dispute it is a party to resumes it too,
     * once for each state of the dispute: the reviewers settle it between themselves (ADR-0098 step 7).</p>
     *
     * <p>One thing resumes an agent with something to come back to although the log did not move: another computer
     * of the project connected after the agent stopped. A reviewer whose author's computer was cut off read nothing,
     * recorded no verdict and stopped; when the cable came back nothing was written anywhere, and the review waited
     * for ever. Each such connection resumes it once.</p>
     *
     * <p>That connection is no proof that the code can be had yet, and the journal may record no reconnection at all
     * (s5-run14: the PC's reviewer was resumed twice at the heal, refused again, and never woken again, so neither run
     * ended). So a reviewer of a snapshot another computer published, while that computer is connected, is also
     * resumed again some time after it stopped, the time doubling for each such resume that recorded nothing
     * ({@link #FOREIGN_REVIEW_RETRY_MILLIS}).</p>
     */
    private static Wake wakeFor(Agent agent, Observation observation, long now, List<Agent> agents, List<String> unrecordedEnds) {
        boolean holdsLane = agent.holdsOpenLane(observation);
        boolean laneEnded = agent.heldLaneAtStop && !holdsLane;
        // Records which reviews it is offered now (reviewsToTry forgets the ones taken since).
        List<String> reviews = laneEnded || holdsLane && !agent.laneAwaitsOnlyReview(observation) ? List.of() : agent.reviewsToTry(observation.reviewsOffered(agent.spec.provider(), agent.connectionIds), agents);
        boolean forItself = laneEnded || !reviews.isEmpty() || agent.answerWaiting(observation) || agent.disputeUnseen(observation);
        // A lane whose published work waits for an operator is not resumed for its lane: resumed, it took
        // its lane back, published the same work again and was refused again, eleven times in one real
        // run, each on another lane's progress.
        boolean laneMayMove = holdsLane && !observation.heldByOperator(agent.spec.provider());
        OptionalLong deadline = laneMayMove ? observation.answerDeadline(agent.spec.provider()) : OptionalLong.empty();
        boolean deadlinePassed = deadline.isPresent() && now >= deadline.getAsLong() && deadline.getAsLong() != agent.resumedForDeadline;
        // Only once its end is recorded: the close is what lets its lane be held for continuation.
        boolean fencedLaneWaits = agent.fencedLaneToContinue && holdsLane && !unrecordedEnds.contains(agent.connectionId);
        boolean forItsLane = laneMayMove && (fencedLaneWaits || agent.failedWithLane || observation.progressedSince(agent.stoppedAtSequence) || deadlinePassed || agent.unreadMessageWaiting(observation));
        // No entry says that another computer came back, yet what it holds was refused as unreachable while it was
        // away: a reviewer could not read the snapshot it holds the grant for, and stopped (ADR-0098 step 4, item 23).
        boolean computerCameBack = agent.computerCameBackUnseen(observation) && (laneMayMove || observation.holdsRequestOrGrant(agent.spec.provider(), agent.connectionId));
        // That wake is spent by a session's first moments, and a journal that records no reconnection never fires it.
        // A reviewer of another computer's snapshot is asked again, with growing pauses, for as long as that computer is
        // connected and the review is not given; the asks that record nothing are bounded by --max-resumes, after which
        // its session ends and the review is released like any gone reviewer's.
        boolean foreignReviewRetry = observation.otherComputerConnected() && observation.holdsReviewOfAnotherComputer(agent.spec.provider(), agent.connectionId)
                && now - agent.stoppedAtMillis >= FOREIGN_REVIEW_RETRY_MILLIS << Math.min(agent.idleResumes, 6);
        if (forItself || forItsLane || computerCameBack || foreignReviewRetry) {
            return new Wake(Wake.Kind.RESUME, reviews, deadlinePassed ? deadline : OptionalLong.empty());
        }
        return new Wake(deadline.isPresent() && now < deadline.getAsLong() ? Wake.Kind.AWAIT_DEADLINE : Wake.Kind.STAY, List.of(), OptionalLong.empty());
    }

    /**
     * What {@link #wakeFor} decided for one stopped agent.
     *
     * @param kind           resumed now, left stopped until its answer deadline, or left stopped
     * @param reviews        the reviews it is resumed to look at
     * @param deadlinePassed the answer deadline it is resumed for, when that is one reason
     */
    private record Wake(Kind kind, List<String> reviews, OptionalLong deadlinePassed) {

        private enum Kind {
            RESUME,
            AWAIT_DEADLINE,
            STAY
        }
    }

    /**
     * Keeps the session of an agent the controller can still come back to, and otherwise ends its
     * connection, once. A running agent's session is kept as soon as its MCP server has opened it, so
     * that its end of input no longer closes it. A session that could not be kept was closed by its
     * own MCP server, so it is ended and the agent's next process gets a new identity.
     *
     * <p>A stopped agent comes back to its open lane, and also to a request it made or a review grant
     * it holds: only the session that asked may use the answer (ADR-0099, Decision 4), and a closed
     * session is never reopened, so ending it would leave the grant to nobody and the author waiting
     * for the reviewer it admitted.</p>
     */
    private void keepOrEnd(Agent agent, Observation observation, int maxResumes, Consumer<String> listener, List<String> unrecordedEnds) {
        if (agent.connectionEnded) {
            return;
        }
        // A fenced session refuses every call of its agent, so an agent resumed on it idled until the run
        // ended. Its lane can be continued only by a new session, through the ordinary continuation: the
        // connection is ended, and the agent is resumed once on a new one, where that is offered to it.
        boolean fenced = agent.process == null && observation.sessionFenced(agent.spec.provider(), agent.connectionId);
        boolean mayComeBack = !fenced && (agent.process != null || agent.mayComeBack(observation, maxResumes));
        if (mayComeBack && connections.keep(agent.connectionId)) {
            if (!agent.sessionKept) {
                agent.sessionKept = true;
                listener.accept("AGENT_SESSION_KEPT=" + agent.label());
            }
            return;
        }
        if (agent.process == null) {
            agent.fencedLaneToContinue = fenced && agent.holdsOpenLane(observation);
            agent.endConnection(connections, unrecordedEnds);
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
        boolean limitReached = agents.stream().anyMatch(agent -> agent.idleResumes >= maxResumes && agent.holdsOpenLane(observation)
                && (observation.progressedSince(agent.stoppedAtSequence) || agent.unreadMessageWaiting(observation)));
        return limitReached ? Outcome.RESUME_LIMIT_REACHED : Outcome.STALLED;
    }

    /**
     * Why a run whose agents have all stopped and saw nothing progress may still see its work move
     * without them, or {@code null} when it cannot. Either way the wait is bounded by the run's own
     * deadline, and the progress it waits for is what resumes the agents here.
     *
     * <p>A participant this controller did not start (another computer's agent, an interactive
     * agent) may still hold a lane, and ending now would leave nobody here to review or integrate
     * its work. Or nothing foreign holds a lane yet, but another computer sharing the log is
     * connected and an agent here can come back to an open lane of its own, or to a request it made
     * (ADR-0099, fix 10), which that computer may answer: that computer's agents
     * can still claim the review or give the admission the lane waits for. In the first run across
     * two homes both agents here published, waited for a review, and stopped before the other
     * computer's agents had claimed anything; the run ended STALLED although those agents went on
     * to review.</p>
     */
    private static Wait progressElsewhere(List<Agent> agents, Observation observation, int maxResumes, long now, boolean logGrowing) {
        if (observation.foreignActiveLaneOpen(agents.stream()
                .flatMap(agent -> agent.connectionIds.stream().map(identity -> new Observation.Connection(agent.spec.provider(), identity))).toList())) {
            return Wait.FOREIGN_LANE_OPEN;
        }
        if (observation.otherComputerConnected() && agents.stream().anyMatch(agent -> agent.mayComeBack(observation, maxResumes))) {
            return Wait.OTHER_COMPUTER_CONNECTED;
        }
        // Known but away: the wake of ADR-0099 fix 11 continues the run when it comes back, and the run's own
        // deadline bounds the wait (ADR-0102 item 6).
        if (observation.otherComputerKnown() && agents.stream().anyMatch(agent -> agent.mayComeBack(observation, maxResumes))) {
            return Wait.OTHER_COMPUTER_AWAY;
        }
        // The copies of main have not met: another computer's head is known here and not held (this computer or the
        // other re-merges next, and a combine lane may follow that an agent here is woken for), or that computer
        // connected moments ago and its log may not be here yet. Only agents that can be woken make it worth waiting.
        if (observation.otherComputerKnown() && agents.stream().anyMatch(agent -> agent.resumable(maxResumes))
                && (observation.mainMissesAnotherComputersHead() || justReconnected(observation, now))) {
            return Wait.COPIES_MEETING;
        }
        // Entries are still arriving from a connected computer: what it writes next, the closing of the work it just
        // landed, may be all that is left, and no agent here is needed for it (s5-run34: STALLED a moment after this
        // main took the other computer's re-merge, seconds before that computer's entries closed the work groups).
        // A log quiet for the window (UNSEEN_BEFORE_EXIT_MILLIS, the span the controller remembers) is a stall.
        if (observation.otherComputerConnected() && logGrowing) {
            return Wait.COPIES_MEETING;
        }
        return null;
    }

    /**
     * Why a run whose work is all closed still waits: another computer connected moments ago, and its log, with the
     * head of its {@code main} that this copy does not hold yet, may not be here (s5-run16's scripted counterpart: a run
     * that ended within a second of the heal, before the re-merge of the other copy opened a combine for its agents).
     * {@code null} when it does not, so a run that nothing is joining ends as before.
     */
    private static Wait justMetAnotherComputer(List<Agent> agents, Observation observation, int maxResumes, long now) {
        return observation.complete() && observation.otherComputerKnown() && justReconnected(observation, now) && agents.stream().anyMatch(agent -> agent.resumable(maxResumes)) ? Wait.COPIES_MEETING
                : null;
    }

    private static boolean justReconnected(Observation observation, long now) {
        OptionalLong since = observation.otherComputerConnectedSince();
        return since.isPresent() && now - since.getAsLong() < RECONNECT_SETTLE_MILLIS;
    }

    /** Why a run whose agents have all stopped keeps waiting, as printed in {@code AGENT_RUN_WAITING}. */
    private enum Wait {
        /** A stopped owner's answer period has not ended. */
        ANSWER_DEADLINE,
        /** A participant this controller did not start holds an open lane. */
        FOREIGN_LANE_OPEN,
        /** Another computer of the project is connected and an agent here can come back to its lane or its request. */
        OTHER_COMPUTER_CONNECTED,
        /** Another computer of the project is known but has no open session with this one, and an agent here can come back to its lane or its request. */
        OTHER_COMPUTER_AWAY,
        /** Another computer's copy of main is not met yet, or that computer has only just connected: a combine may still be written. */
        COPIES_MEETING,
        /** Agents were started to wait for a task, and no person has given one. */
        TASK_WANTED
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
         * since it stopped, or no lane it took is still open, no agent without a lane has a
         * review it may take and has not tried, and no request an agent made was answered since
         * it stopped. Nothing is left that could progress
         * without them either: no foreign participant holds a lane, and no other computer of the
         * project is connected while an agent here could come back to a lane of its own.
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
         * An agent whose lane is open was resumed as often in a row as allowed, and nothing was recorded
         * in the log while any of those processes ran.
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
         * Returns a new connection identity for an agent that had an earlier connection, which has ended. The agent's
         * next session is the same agent as its earlier one, and a review it was owed or is barred from is owed or
         * barred for the next as well, so a project that can tell must be told (ADR-0098 step 5, item 30).
         *
         * @param earlier the agent's previous connection, whose end was recorded
         * @return identity the process's Synesis MCP server will use
         */
        default String open(String earlier) {
            return open();
        }

        /**
         * Records that a connection has ended: no process of this controller will use it again. Waits
         * a bounded time for the project when it is busy.
         *
         * @param connectionInstanceId identity returned by {@link #open()}
         */
        void ended(String connectionInstanceId);

        /**
         * Records that a connection has ended, as {@link #ended} does, but makes one attempt (which may
         * wait the project lock's two seconds) instead of many. The controller offers an end that could
         * not be recorded again at each observation,
         * and waits for it when the run ends, so a busy project never leaves the lease of an agent that
         * has ended naming the controller.
         *
         * @param connectionInstanceId identity returned by {@link #open()}
         * @return {@code true} when the end is recorded or there was nothing to record; {@code false}
         *         when the project could not record it now
         */
        default boolean end(String connectionInstanceId) {
            ended(connectionInstanceId);
            return true;
        }

        /**
         * Keeps the session of a connection alive while the controller can still start a process on
         * it, whether or not one is running now. Called at every observation, so it must be cheap
         * when nothing needs renewing.
         *
         * @param connectionInstanceId identity returned by {@link #open()}
         * @return {@code true} when the session is kept or nothing could be decided this time;
         *         {@code false} when it cannot be kept (not opened yet, already closed, or not
         *         started by this controller), which leaves the connection to end when its process
         *         does
         */
        default boolean keep(String connectionInstanceId) {
            return false;
        }
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
         * Reports whether the lane behind one connection has nothing left to write: it is a producer lane whose
         * snapshot is published and waits for review. Such an agent is idle, and a review it may take is offered to it
         * as to an agent with no lane (s6-run1: the only agent of each computer held such a lane, neither was woken for
         * the other's snapshot, and both runs waited until their time ran out).
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection of one of the agent's processes
         * @return {@code true} while that session's lane only waits for its review
         */
        default boolean laneAwaitsOnlyReview(String provider, String connectionInstanceId) {
            return false;
        }

        /**
         * Reports whether the session behind one connection is fenced for recovery: its participant is
         * suspended or held for recovery, so the MCP server refuses its calls with
         * {@code SESSION_EPOCH_FENCED} and its lane can only be continued by a new session. A kept
         * session gets there only when its keeper stopped renewing for longer than the grace period.
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection of one of the agent's processes
         * @return {@code true} while that session is fenced
         */
        default boolean sessionFenced(String provider, String connectionInstanceId) {
            return false;
        }

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
         * Reports whether another computer sharing this project's log is connected to this one:
         * for the leader a member, for a member its leader. Its agents can claim and serve lanes
         * that no agent here can, such as the review a lane here waits for. A computer whose
         * Synesis process died may still be reported connected, so a caller bounds its wait.
         *
         * @return {@code true} while another computer of the project is connected
         */
        default boolean otherComputerConnected() {
            return false;
        }

        /**
         * Reports whether the project has another computer at all, connected or away: for the leader its members,
         * for a member its leader, as the project's replication settings name them. Empty or unreadable settings
         * report {@code false}, so a single-computer project ends as it always did.
         *
         * @return {@code true} while another computer is a peer of the project
         */
        default boolean otherComputerKnown() {
            return false;
        }

        /**
         * Reports whether this copy of {@code main} lacks a head another computer advanced to, as this computer's logs
         * name it (ADR-0098 step 5). Then a fast-forward or a re-merge is still to come, and a re-merge that Git cannot
         * finish opens a combine lane for an agent here. Unreadable state reports {@code false}.
         *
         * @return {@code true} while another computer's advanced head is not in this copy
         */
        default boolean mainMissesAnotherComputersHead() {
            return false;
        }

        /**
         * Returns when the newest session with another computer of this project began, for a computer that is
         * connected now. A computer coming back is written in no log, yet it is what an agent refused
         * {@code CODE_REMOTE_UNAVAILABLE} while it was away needs; a stopped agent with something to come back to is
         * resumed once for each such session that began after it stopped.
         *
         * @return epoch milliseconds, or empty when no other computer is connected
         */
        default OptionalLong otherComputerConnectedSince() {
            return OptionalLong.empty();
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

        /**
         * Returns the log position at which the operator last closed a request that the lane held by the
         * session behind one connection put to it (ADR-0093). The closing is not work and resumes nobody
         * else, but the lane that asked may be waiting for that answer, so it resumes that lane's stopped
         * agent once, as a message addressed to it does.
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection of the agent's last process
         * @return that position, or empty when the connection holds no lane or the operator closed nothing it asked
         */
        default OptionalLong newestOperatorAnswerFor(String provider, String connectionInstanceId) {
            return OptionalLong.empty();
        }

        /**
         * Returns the snapshots waiting for a review that the agent behind these connections may take
         * up, as {@code get_next_action} offers them to a session that holds no lane: review required,
         * its author active, no reviewer holding it, and authored in its lineage by none of the
         * current sessions behind these connections. A stopped agent with no lane of its own is
         * resumed for such a snapshot, and for each one once while it stays offered (ADR-0099). The
         * same holds for a lane of an away computer, by lane ID, offered to an agent with no lane
         * while this computer is stalled (ADR-0098 step 7 item 10; ADR-0099 fix 15).
         *
         * @param provider              provider identifier
         * @param connectionInstanceIds every connection the agent's processes have had
         * @return the snapshots' identifiers, empty when there are none, a connection holds a lane,
         *         or no connection's session is known
         */
        default List<String> reviewsOffered(String provider, List<String> connectionInstanceIds) {
            return List.of();
        }

        /**
         * Returns the log position of the newest answer (accepted, rejected or otherwise resolved) to
         * a coordination request that the session behind one connection made. The answer is not
         * addressed to a lane, so it resumes a stopped agent whether or not it holds one, and each
         * answer once (ADR-0099). An accepted review request's grant is part of its answer and is not
         * counted again.
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection of one of the agent's processes
         * @return that position, or empty when that session made no request that was answered
         */
        default OptionalLong newestAnswerFor(String provider, String connectionInstanceId) {
            return OptionalLong.empty();
        }

        /**
         * Reports whether the session behind one connection still holds a coordination request that
         * is pending, or a review grant it has not finished: what refuses a fresh session on that
         * connection with {@code SESSION_AUTHORITY_OPEN} (ADR-0099, Decision 4). Only that session can
         * use them, so the controller keeps it while it may resume its agent. A verdict it recorded on a snapshot
         * not yet settled counts too: only that session may replace it if another reviewer disagrees (ADR-0098
         * step 7).
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection of the agent's current process
         * @return {@code true} while that session holds such a request, grant or verdict
         */
        default boolean holdsRequestOrGrant(String provider, String connectionInstanceId) {
            return false;
        }

        /**
         * Reports whether the session behind one connection holds a review grant it has not finished for a snapshot
         * that another computer's agent published. Reading that snapshot is refused ({@code CODE_REMOTE_UNAVAILABLE})
         * while the Link is down, and nothing in the log says when it has come back, so a reviewer that stopped
         * without a verdict has to be asked again (ADR-0098 step 5, item 29).
         *
         * @param provider             provider identifier
         * @param connectionInstanceId connection of the agent's current process
         * @return {@code true} while that session holds such a grant
         */
        default boolean holdsReviewOfAnotherComputer(String provider, String connectionInstanceId) {
            return false;
        }

        /**
         * Returns the review disputes the sessions behind these connections are parties to, as reviewer or author:
         * one key per snapshot and set of verdicts, so a dispute whose verdicts change is a new one to see (ADR-0098
         * step 7).
         *
         * @param provider              provider identifier
         * @param connectionInstanceIds every connection the agent's processes have had
         * @return the keys, empty when there is no dispute
         */
        default List<String> disputesFor(String provider, List<String> connectionInstanceIds) {
            return List.of();
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
     * @param prompt             the one prompt every agent starts with, or {@code null} to start none until a person
     *                           gives a task through {@code tasks}
     * @param timeout            overall time limit
     * @param pollInterval       time between observations
     * @param maxResumesPerAgent how many resumes in a row of one agent may record nothing in the log
     * @param tasks              where a person gives or replaces the task later, or {@code null} for none
     */
    public record Request(Path projectRoot, List<AgentSpec> agents, String prompt, Duration timeout, Duration pollInterval, int maxResumesPerAgent, TaskInbox tasks) {

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
            if (prompt == null ? tasks == null : prompt.isBlank()) {
                throw new IllegalArgumentException("a prompt is required");
            }
            if (timeout.isZero() || timeout.isNegative() || pollInterval.isZero() || pollInterval.isNegative() || maxResumesPerAgent < 0) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }

        /**
         * A request whose agents start with a prompt and are never given another task.
         *
         * @param projectRoot        control project root
         * @param agents             agents to start, in order
         * @param prompt             the one prompt every agent starts with
         * @param timeout            overall time limit
         * @param pollInterval       time between observations
         * @param maxResumesPerAgent how many resumes in a row of one agent may record nothing in the log
         */
        public Request(Path projectRoot, List<AgentSpec> agents, String prompt, Duration timeout, Duration pollInterval, int maxResumesPerAgent) {
            this(projectRoot, agents, prompt, timeout, pollInterval, maxResumesPerAgent, null);
        }
    }

    /**
     * A task a person gave the agents after they started.
     *
     * @param id   orders tasks: a later task has a greater identifier
     * @param text what the agents are to work on
     */
    public record Task(String id, String text) {
    }

    /**
     * Where a person's tasks are left for the agents. A task is the text of a prompt and nothing more: it is not an
     * entry of the coordination log, so it is no claim, review decision, approval, merge or policy
     * (ADR-0103, stage 4 amendment).
     */
    @FunctionalInterface
    public interface TaskInbox {

        /**
         * Returns the newest task.
         *
         * @return the task, or empty when none was given
         * @throws IOException when it cannot be read
         */
        Optional<Task> newest() throws IOException;
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
        /** When its current or last process started. */
        private long startedAtMillis;
        /** When it is taken to have last looked at the project: as for {@link #stoppedAtSequence}, in time. */
        private long lastLookMillis;
        /** When the controller saw its last process end. */
        private long stoppedAtMillis;
        /** The start of the other computer's session it was last resumed for. */
        private long resumedForConnection = Long.MIN_VALUE;
        private long resumedForDeadline = Long.MIN_VALUE;
        private long resumedForMessage;
        /** The newest answer to a request of its own that it was resumed after. */
        private long resumedForAnswer;
        /** The snapshots waiting for review that it was resumed, without a lane, to look at. */
        private final Set<String> resumedForReview = new java.util.HashSet<>();
        /** The review disputes, each in the state it was in, that it was resumed for. */
        private final Set<String> resumedForDispute = new java.util.HashSet<>();
        /** Every connection this agent's processes have had, oldest first. */
        private final List<String> connectionIds = new ArrayList<>();
        private int exitCode;
        private int resumes;
        /** Processes in a row during which nothing was recorded in the log; any progress resets it. */
        private int idleResumes;
        /** Whether its current or last process was a resume rather than its first start. */
        private boolean resumed;
        /** Whether its lane was open when its process last ended. */
        private boolean heldLaneAtStop;
        /**
         * Its process ended with a failure exit code while its lane was open, and it has not been resumed since. A provider
         * that fails (s9a-run3: the API refused the agent of the re-merge's combine lane) records nothing as it goes, so no
         * progress would ever wake it, and every computer waits for its lane.
         */
        private boolean failedWithLane;
        /** Whether its current connection has been reported ended. */
        private boolean connectionEnded;
        /** Whether the session of its current connection has been kept at least once. */
        private boolean sessionKept;
        /** Whether its last session was ended because it was fenced for recovery, its lane still to continue. */
        private boolean fencedLaneToContinue;
        /** Whether it was registered to wait for its first task and has no process yet. */
        private boolean awaitingTask;
        /** A task a person gave that it has not been started or resumed with, because its process was running. */
        private String task;

        private Agent(int index, AgentSpec spec) {
            this.index = index;
            this.spec = spec;
        }

        private String label() {
            return spec.provider() + "#" + index;
        }

        /** Gives this agent a new connection identity, for its processes from now on. */
        private HeadlessOptions connect(String identity) {
            connectionId = identity;
            connectionIds.add(identity);
            connectionEnded = false;
            sessionKept = false;
            return spec.options().withConnection(identity);
        }

        /** Reports its current connection ended; an end the project could not record yet is kept to be offered again. */
        private void endConnection(Connections connections, List<String> unrecordedEnds) {
            if (!connections.end(connectionId)) {
                unrecordedEnds.add(connectionId);
            }
            connectionEnded = true;
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
         * @param progressed     whether anything was recorded in the log while the process ran
         * @param holdsLane      whether the agent's lane is open now
         * @param now            when the controller saw that the process had ended
         */
        private void exited(OptionalLong lastActivity, OptionalLong seenBeforeExit, boolean progressed, boolean holdsLane, long now) {
            exitCode = process.exitCode();
            if (resumed) {
                idleResumes = progressed ? 0 : idleResumes + 1;
            }
            heldLaneAtStop = holdsLane;
            failedWithLane = holdsLane && exitCode != 0;
            process.providerSessionId().ifPresent(identifier -> providerSessionId = identifier);
            long seen = seenBeforeExit.orElse(startedAtSequence);
            stoppedAtSequence = Math.max(startedAtSequence, lastActivity.isPresent() ? Math.min(lastActivity.getAsLong(), seen) : seen);
            lastLookMillis = Math.max(startedAtMillis, now - UNSEEN_BEFORE_EXIT_MILLIS);
            stoppedAtMillis = now;
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

        /** Whether every lane it holds only waits for its review: it has nothing to write, and may review. */
        private boolean laneAwaitsOnlyReview(Observation observation) {
            return holdsOpenLane(observation) && connectionIds.stream().filter(identity -> observation.holdsOpenLane(spec.provider(), identity))
                    .allMatch(identity -> observation.laneAwaitsOnlyReview(spec.provider(), identity));
        }

        /** The newest unread message, or operator answer, addressed to a lane any of this agent's connections holds. */
        private OptionalLong newestUnreadMessage(Observation observation) {
            return connectionIds.stream().flatMap(identity -> java.util.stream.Stream.of(observation.newestUnreadMessageFor(spec.provider(), identity),
                    observation.newestOperatorAnswerFor(spec.provider(), identity))).filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong).max();
        }

        /** Whether a message for its lane arrived that it has not been resumed for. */
        private boolean unreadMessageWaiting(Observation observation) {
            OptionalLong message = newestUnreadMessage(observation);
            return message.isPresent() && message.getAsLong() > Math.max(stoppedAtSequence, resumedForMessage);
        }

        /** The newest answer to a request any of this agent's sessions made. */
        private OptionalLong newestAnswer(Observation observation) {
            return connectionIds.stream().map(identity -> observation.newestAnswerFor(spec.provider(), identity)).filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong).max();
        }

        /**
         * Records what it is resumed for, so that none of it resumes it again. Whatever it is resumed
         * for, it reads its messages and the answers to its requests then.
         */
        private void markResumedFor(Wake wake, Observation observation) {
            wake.deadlinePassed().ifPresent(passed -> resumedForDeadline = passed);
            resumedForReview.addAll(wake.reviews());
            resumedForDispute.addAll(observation.disputesFor(spec.provider(), connectionIds));
            resumedForMessage = Math.max(resumedForMessage, newestUnreadMessage(observation).orElse(0L));
            resumedForAnswer = Math.max(resumedForAnswer, newestAnswer(observation).orElse(0L));
            observation.otherComputerConnectedSince().ifPresent(since -> resumedForConnection = Math.max(resumedForConnection, since));
        }

        /** Whether another computer connected after it last looked, and it has not been resumed for that connection. */
        private boolean computerCameBackUnseen(Observation observation) {
            OptionalLong since = observation.otherComputerConnectedSince();
            return since.isPresent() && since.getAsLong() > Math.max(lastLookMillis, resumedForConnection);
        }

        /** Whether it is a party to a review dispute, in a state it has not been resumed for. */
        private boolean disputeUnseen(Observation observation) {
            List<String> disputes = observation.disputesFor(spec.provider(), connectionIds);
            resumedForDispute.retainAll(disputes);
            return !resumedForDispute.containsAll(disputes);
        }

        /** Whether a request it made was answered after it stopped, and it has not been resumed since. */
        private boolean answerWaiting(Observation observation) {
            OptionalLong answer = newestAnswer(observation);
            return answer.isPresent() && answer.getAsLong() > Math.max(stoppedAtSequence, resumedForAnswer);
        }

        /**
         * Of the reviews offered to it now, those it has not been resumed for and that no running agent
         * of the run was resumed for. Each agent tries a review once and one agent at a time, so a
         * review nobody here takes costs at most one resume per agent. A review it is no longer
         * offered (taken, then given up) is forgotten, so being offered it again is a new offer.
         */
        private List<String> reviewsToTry(List<String> offered, List<Agent> agents) {
            resumedForReview.retainAll(offered);
            return offered.stream().filter(snapshot -> !resumedForReview.contains(snapshot))
                    .filter(snapshot -> agents.stream().noneMatch(other -> other.process != null && other.resumedForReview.contains(snapshot))).toList();
        }

        private boolean resumable(int maxResumes) {
            return process == null && providerSessionId != null && idleResumes < maxResumes;
        }

        /**
         * Whether, stopped, it has something to come back to: a lane it took that is still open, or a
         * request or review grant that only its current session may use (ADR-0099, Decision 4).
         */
        private boolean mayComeBack(Observation observation, int maxResumes) {
            return resumable(maxResumes) && (holdsOpenLane(observation) || observation.holdsRequestOrGrant(spec.provider(), connectionId));
        }
    }
}
