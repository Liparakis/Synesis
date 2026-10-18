package org.synesis.coordination.replication;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import org.synesis.coordination.domain.collaboration.ClaimConflict;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProposalOutcome;
import org.synesis.coordination.persistence.ReplicationCodec;
import org.synesis.coordination.persistence.ReplicationMessage;
import org.synesis.link.session.PeerSession;

/**
 * How this computer exchanges logs with the computers it has a Link session to (ADR-0098 step 4, "Exchange").
 *
 * <p>Each side tells the other when one of the logs it holds grew ({@code NOTICE}), asks which logs the other holds
 * and how far ({@code HEADS}), and pulls, a page at a time, every log the other holds further than this computer's
 * copy ({@code PULL}). It also pulls every {@value #PULL_INTERVAL_SECONDS} seconds whatever it was told, which is
 * also the keepalive of a path through a home router. Any computer serves every log it holds, its own and its
 * copies, because each entry is signed by its writer: a friend's entries reach this computer through a third one
 * with no relay protocol, and a copy is always checked against the writer's chain and signature, never trusted for
 * coming from a peer ({@link PredictionEventStore#placeCopies}).</p>
 *
 * <p>The sender of a request is the authenticated session's remote node, handed in by Link, and only a node the
 * owner admits is served. A copy that differs from what a peer serves is reported loudly and that log is no longer
 * pulled from anyone: a copy is never repaired by guessing.</p>
 */
public final class LogExchange implements AutoCloseable {

    /**
     * Seconds between pulls when no notice arrives (ADR-0098, "Timing").
     */
    public static final int PULL_INTERVAL_SECONDS = 15;

    private static final long NOTICE_CHECK_MILLIS = 200;
    private static final int PAGE_ENTRIES = 256;
    private static final int PAGE_BYTES = 256 * 1024;
    // Link gives up on an exchange after five seconds; waiting a little longer only reports it.
    private static final long ANSWER_WAIT_SECONDS = 8;
    /**
     * How long a claim waits for a connected computer's answer; one that does not answer in time counts as not
     * connected (ADR-0098 step 4, designer item 4).
     */
    private static final long CLAIM_WAIT_MILLIS = 5_000;
    private static final String OVERLAPPING_CLAIM = "OVERLAPPING_CLAIM";
    private static final System.Logger LOGGER = System.getLogger(LogExchange.class.getName());

    /**
     * How the last exchange with a peer went.
     */
    public enum State {
        /**
         * No usable session to the peer.
         */
        DISCONNECTED,
        /**
         * The last pull from the peer succeeded.
         */
        CONNECTED,
        /**
         * A log the peer serves differs from this computer's copy. That log is not pulled again until a person
         * looks at it.
         */
        DIVERGED
    }

    private final Path root;
    private final UUID projectId;
    private final Predicate<String> admitted;
    private final Predicate<String> copied;
    private final Runnable afterArrivals;
    private final Map<String, PeerSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Long> noticed = new ConcurrentHashMap<>();
    private final Set<String> divergedLogs = ConcurrentHashMap.newKeySet();
    private final Set<String> pullQueued = ConcurrentHashMap.newKeySet();
    /** This computer's own claims whose connected computers are being asked now. */
    private final Map<WorkIntentId, Checking> checking = new ConcurrentHashMap<>();
    /**
     * This computer's own claims that gave way. Each stays in the log until its release is appended, after the
     * agent's process has its answer; meanwhile it must not count against the claim it gave way to.
     */
    private final Set<WorkIntentId> yielded = ConcurrentHashMap.newKeySet();
    /**
     * How long a claim told of by {@link ReplicationMessage.ClaimIntent} stays registered without its check
     * starting: the time from the announcement to the question, with room. A process that died between the two leaves
     * nothing behind for longer. The clock is this computer's own, about its own registry.
     */
    private static final long REGISTERED_CLAIM_MILLIS = 15_000;
    private final ExecutorService requests = Executors.newCachedThreadPool(daemon("synesis-log-exchange-serve"));
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            daemon("synesis-log-exchange"));
    private volatile String ownNode;
    private volatile java.util.function.BiConsumer<String, State> listener = (node, state) -> {
    };
    private PredictionEventStore store;

    /**
     * Starts exchanging one project's logs.
     *
     * @param root          this computer's coordination folder
     * @param projectId     the project
     * @param admitted      whether a node may pull from and be pulled by this computer, read for every request
     * @param copied        whether this computer keeps a copy of a node's log: on the owner's computer only its
     *                      members' (a signature proves who wrote an entry, not that the writer belongs to the
     *                      project); on a member every log the owner's computer serves, until membership is in the
     *                      owner's log (ADR-0098 step 6)
     * @param afterArrivals called on the exchange thread after a pull that brought entries of another computer, so
     *                      this computer can act on what the logs name without an agent of its own (the merge
     *                      driver, a member following {@code main}); it must not block
     */
    public LogExchange(Path root, UUID projectId, Predicate<String> admitted, Predicate<String> copied,
            Runnable afterArrivals) {
        this.root = CoordinationNullException.requireNonNull(root, "root");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.admitted = CoordinationNullException.requireNonNull(admitted, "admitted");
        this.copied = CoordinationNullException.requireNonNull(copied, "copied");
        this.afterArrivals = CoordinationNullException.requireNonNull(afterArrivals, "afterArrivals");
        worker.scheduleWithFixedDelay(this::pullAll, PULL_INTERVAL_SECONDS, PULL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(this::noticeAll, NOTICE_CHECK_MILLIS, NOTICE_CHECK_MILLIS,
                TimeUnit.MILLISECONDS);
        worker.execute(this::takeWaiting);
    }

    /**
     * Exchanges logs over an authenticated session from now on, starting with a pull.
     *
     * @param session an authenticated, control-ready session whose remote node is admitted
     */
    public void attach(PeerSession session) {
        String node = session.remoteNodeId();
        ownNode = session.localNodeId();
        sessions.put(node, session);
        noticed.remove(node);
        session.terminalCompletion().whenComplete((ignored, failure) -> {
            if (sessions.remove(node, session)) {
                setState(node, State.DISCONNECTED);
            }
        });
        requestPull(node);
    }

    /**
     * Registers a claim an agent's process of this computer is about to append (loopback only).
     *
     * @param intent the claim, as the process sends it
     * @return the encoded answer, always empty
     */
    public byte[] registerClaim(ReplicationMessage.ClaimIntent intent) {
        try {
            WorkIntent lane = CollaborationCodec.decodeIntent(intent.intent());
            if (intent.project().equals(projectId)) {
                expireRegistrations();
                checking.computeIfAbsent(lane.identity(), id -> new Checking(lane.selectors()));
            }
        } catch (IOException malformed) {
            return refusal("REPLICATION_MESSAGE_INVALID");
        }
        return ReplicationCodec.encode(new ReplicationMessage.ClaimAnswer(""));
    }

    /** Drops registrations whose check never started. */
    private void expireRegistrations() {
        long now = System.nanoTime();
        checking.values().removeIf(registered -> !registered.running
                && now - registered.since > TimeUnit.MILLISECONDS.toNanos(REGISTERED_CLAIM_MILLIS));
    }

    /**
     * Tells whether this exchange answers a message: a notice, a request for heads, or a pull.
     *
     * @param message a decoded replication message
     * @return true for the kinds served here
     */
    public static boolean serves(ReplicationMessage message) {
        return message instanceof ReplicationMessage.Notice || message instanceof ReplicationMessage.Heads
                || message instanceof ReplicationMessage.Pull || message instanceof ReplicationMessage.ClaimSeen;
    }

    /**
     * Answers a message of a peer, off the Link event loop.
     *
     * @param sender  the authenticated remote node
     * @param message a message {@link #serves} accepts
     * @return the encoded answer
     */
    public CompletableFuture<byte[]> serve(String sender, ReplicationMessage message) {
        return CompletableFuture.supplyAsync(() -> answer(sender, message), requests);
    }

    /**
     * How the last exchange with a peer went.
     *
     * @param node the peer
     * @return its state; disconnected when it never had a session
     */
    public State state(String node) {
        return states.getOrDefault(node, State.DISCONNECTED);
    }

    /**
     * The sessions of admitted peers that are live now, for other protocols that ride on them (Code over Link).
     *
     * @return each peer's node and its session
     */
    public Map<String, PeerSession> liveSessions() {
        Map<String, PeerSession> live = new LinkedHashMap<>();
        sessions.forEach((node, session) -> {
            if (admitted.test(node) && Sessions.live(session)) {
                live.put(node, session);
            }
        });
        return Map.copyOf(live);
    }

    /**
     * Tells a listener whenever the state of a peer is set, on the exchange thread; it must not block.
     *
     * @param watcher receives the peer and its state
     */
    public void watch(java.util.function.BiConsumer<String, State> watcher) {
        listener = CoordinationNullException.requireNonNull(watcher, "watcher");
    }

    private void setState(String node, State state) {
        State previous = states.put(node, state);
        if (previous != state) {
            listener.accept(node, state);
        }
    }

    /**
     * Asks every attached peer for what it holds now, without waiting for the interval.
     */
    public void pullNow() {
        sessions.keySet().forEach(this::requestPull);
    }

    private byte[] answer(String sender, ReplicationMessage message) {
        if (!admitted.test(sender)) {
            return refusal("PROPOSER_NOT_A_MEMBER");
        }
        try {
            return switch (message) {
                case ReplicationMessage.Notice notice -> {
                    if (notice.project().equals(projectId)) {
                        requestPull(sender);
                    }
                    yield ReplicationCodec.encode(new ReplicationMessage.Noticed());
                }
                case ReplicationMessage.Heads heads -> heads.project().equals(projectId)
                        ? ReplicationCodec.encode(new ReplicationMessage.HeadList(heads()))
                        : refusal("PROPOSAL_PROJECT_MISMATCH");
                case ReplicationMessage.Pull pull -> pull.project().equals(projectId)
                        ? ReplicationCodec.encode(new ReplicationMessage.Entries(page(pull)))
                        : refusal("PROPOSAL_PROJECT_MISMATCH");
                case ReplicationMessage.ClaimSeen seen -> seen.project().equals(projectId)
                        ? ReplicationCodec.encode(new ReplicationMessage.ClaimAnswer(judgeClaim(sender, seen)))
                        : refusal("PROPOSAL_PROJECT_MISMATCH");
                default -> refusal("REPLICATION_REQUEST_UNSUPPORTED");
            };
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            // No answer: the peer pulls again at its next notice or interval.
            throw new java.util.concurrent.CompletionException(failure);
        }
    }

    /**
     * Asks every connected computer whether a claim this computer just appended may stand, before its agent is
     * answered (ADR-0098 step 4, "Claims are locks wherever they can be checked"). Called for an agent's process,
     * which reaches this server over loopback.
     *
     * <p>The claim gives way when a connected computer holds an overlapping active claim whose own check has
     * finished, or when both are being checked at once and the other computer's node ID is lower; a computer that
     * asks this one about an overlapping claim while this one checks, with a lower node ID, makes this one give way
     * too, so of two claims made at the same moment exactly the lower node's stands. A computer that does not answer
     * in five seconds counts as not connected.</p>
     *
     * @param seen the claim, as the agent's process sends it
     * @return the encoded answer: empty code when the claim stands, {@code OVERLAPPING_CLAIM} when it gives way
     */
    public byte[] checkClaim(ReplicationMessage.ClaimSeen seen) {
        WorkIntent claim;
        try {
            claim = claimOf(seen);
        } catch (IOException malformed) {
            return refusal("REPLICATION_MESSAGE_INVALID");
        }
        if (claim == null || !seen.project().equals(projectId)) {
            return ReplicationCodec.encode(new ReplicationMessage.ClaimAnswer(""));
        }
        // A question about this claim may have arrived between its append and now, and marked it already.
        Checking mine = checking.computeIfAbsent(claim.identity(), id -> new Checking(claim.selectors()));
        mine.running = true;
        try {
            byte[] question = ReplicationCodec.encode(seen);
            Map<String, CompletableFuture<byte[]>> asked = new LinkedHashMap<>();
            sessions.forEach((node, session) -> {
                if (admitted.test(node) && Sessions.live(session)) {
                    try {
                        asked.put(node, session.requestApplication(question).toCompletableFuture());
                    } catch (IllegalStateException unusable) {
                        // Not connected.
                    }
                }
            });
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLAIM_WAIT_MILLIS);
            String verdict = "";
            for (CompletableFuture<byte[]> answer : asked.values()) {
                try {
                    long left = Math.max(0L, deadline - System.nanoTime());
                    if (ReplicationCodec.decode(answer.get(left, TimeUnit.NANOSECONDS))
                            instanceof ReplicationMessage.ClaimAnswer given && !given.code().isEmpty()) {
                        verdict = given.code();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException | CoordinationException notConnected) {
                    // A computer that does not answer in time counts as not connected.
                }
            }
            if (mine.lost) {
                verdict = OVERLAPPING_CLAIM;
            }
            if (!verdict.isEmpty()) {
                yielded.add(claim.identity());
            }
            return ReplicationCodec.encode(new ReplicationMessage.ClaimAnswer(verdict));
        } finally {
            checking.remove(claim.identity());
        }
    }

    /**
     * Answers another computer's question about its claim: whether this computer holds an overlapping active claim
     * that the other's must give way to.
     */
    private String judgeClaim(String sender, ReplicationMessage.ClaimSeen seen)
            throws IOException, GeneralSecurityException {
        WorkIntent claim = claimOf(seen);
        if (claim == null || !PredictionEvent.decode(seen.entry()).actorNodeId().equals(sender)) {
            // Only a computer's own claim is asked about.
            return "";
        }
        String self = ownNode == null ? "" : ownNode;
        expireRegistrations();
        for (Checking mine : checking.values()) {
            if (overlaps(mine.selectors, claim.selectors())) {
                if (sender.compareTo(self) < 0) {
                    mine.lost = true;
                } else {
                    return OVERLAPPING_CLAIM;
                }
            }
        }
        // The lanes as every process of this computer has written them.
        PredictionEventStore current = store();
        current.catchUp();
        CollaborationProjection lanes = current.collaborationProjection();
        for (ClaimConflict conflict : lanes.conflicts(claim.selectors(), claim.identity())) {
            WorkIntentId lane = new WorkIntentId(UUID.fromString(conflict.intentId()));
            if (checking.containsKey(lane) || yielded.contains(lane)) {
                continue;
            }
            java.util.Optional<String> introducer = lanes.introducingNode(new ParticipantId(conflict.participant()));
            if (introducer.filter(sender::equals).isPresent()) {
                continue;
            }
            return OVERLAPPING_CLAIM;
        }
        return "";
    }

    /** The lane a claim announces, or null when the entry is not a verified announcement. */
    private WorkIntent claimOf(ReplicationMessage.ClaimSeen seen) throws IOException {
        PredictionEvent event = PredictionEvent.decode(seen.entry());
        try {
            if (event.type() != PredictionEventType.WORK_INTENT_ANNOUNCED || !event.projectId().equals(projectId)
                    || !event.verify()) {
                return null;
            }
        } catch (GeneralSecurityException unverifiable) {
            return null;
        }
        return CollaborationCodec.decodeIntent(event.payload());
    }

    private static boolean overlaps(List<ResourceSelector> left, List<ResourceSelector> right) {
        return left.stream().anyMatch(one -> right.stream().anyMatch(one::overlaps));
    }

    /** One own claim being checked, and whether a lower node's claim arrived meanwhile. */
    private static final class Checking {

        private final List<ResourceSelector> selectors;
        private final long since = System.nanoTime();
        private volatile boolean lost;
        private volatile boolean running;

        private Checking(List<ResourceSelector> selectors) {
            this.selectors = selectors;
        }
    }

    /** The heads of every log this computer holds, its own under its node ID. */
    private Map<String, Long> heads() throws IOException, GeneralSecurityException {
        Map<String, Long> heads = new LinkedHashMap<>();
        String self = ownNode;
        for (Map.Entry<String, Long> head : store().logHeads().entrySet()) {
            if (head.getKey().equals(PredictionEventStore.OWN_LOG)) {
                if (self != null) {
                    heads.put(self, head.getValue());
                }
            } else {
                heads.put(head.getKey(), head.getValue());
            }
        }
        return heads;
    }

    private List<byte[]> page(ReplicationMessage.Pull pull) throws IOException, GeneralSecurityException {
        String log = pull.log().equals(ownNode) ? PredictionEventStore.OWN_LOG : pull.log();
        return store().entriesAfter(log, pull.after(), pull.maxEntries(), Math.min(pull.maxBytes(), PAGE_BYTES));
    }

    private void requestPull(String node) {
        if (pullQueued.add(node)) {
            try {
                worker.execute(() -> pull(node));
            } catch (RejectedExecutionException closed) {
                pullQueued.remove(node);
            }
        }
    }

    private void pullAll() {
        sessions.keySet().forEach(this::pull);
    }

    /**
     * Pulls every log the peer holds further than this computer's copy, then applies what can be applied.
     */
    private void pull(String node) {
        pullQueued.remove(node);
        PeerSession session = sessions.get(node);
        if (!Sessions.live(session) || !admitted.test(node)) {
            setState(node, State.DISCONNECTED);
            return;
        }
        int placed = 0;
        try {
            if (!(ask(session, new ReplicationMessage.Heads(projectId)) instanceof ReplicationMessage.HeadList list)) {
                setState(node, State.DISCONNECTED);
                return;
            }
            Map<String, Long> mine = store().logHeads();
            for (Map.Entry<String, Long> head : list.heads().entrySet()) {
                String log = head.getKey();
                if (log.equals(session.localNodeId()) || divergedLogs.contains(log) || !copied.test(log)) {
                    continue;
                }
                long held = mine.getOrDefault(log, 0L);
                while (held < head.getValue()) {
                    if (!(ask(session, new ReplicationMessage.Pull(projectId, log, held, PAGE_ENTRIES, PAGE_BYTES))
                            instanceof ReplicationMessage.Entries entries) || entries.entries().isEmpty()) {
                        break;
                    }
                    try {
                        placed += store().placeCopies(log, entries.entries());
                    } catch (CoordinationException diverged) {
                        if (!diverged.code().equals("LOG_COPY_DIVERGED")) {
                            throw diverged;
                        }
                        LOGGER.log(System.Logger.Level.ERROR, "This computer's copy of the log of " + log
                                + " differs from the one " + node + " serves. It is not pulled again until it is "
                                + "repaired.", diverged);
                        divergedLogs.add(log);
                        setState(node, State.DIVERGED);
                        break;
                    }
                    held = store().logHeads().getOrDefault(log, 0L);
                }
            }
            if (state(node) != State.DIVERGED) {
                setState(node, State.CONNECTED);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Pulling the logs " + node + " holds failed; it will be tried again.",
                    failure);
            setState(node, State.DISCONNECTED);
        }
        if (placed > 0) {
            try {
                afterArrivals.run();
            } catch (RuntimeException hookFailed) {
                // The entries are placed; the hook's owner tries again on its own.
            }
        }
    }

    /** Applies entries placed before a restart that could not be taken then. */
    private void takeWaiting() {
        try {
            store().takeArrivals();
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "Applying the copies of other computers' logs failed.", failure);
        }
    }

    private void noticeAll() {
        if (sessions.isEmpty()) {
            return;
        }
        long held = PredictionEventStore.recordedEvents(root);
        sessions.forEach((node, session) -> {
            if (!admitted.test(node) || !Sessions.live(session) || noticed.getOrDefault(node, -1L) == held) {
                return;
            }
            noticed.put(node, held);
            try {
                session.requestApplication(ReplicationCodec.encode(new ReplicationMessage.Notice(projectId, held)));
            } catch (IllegalStateException unusable) {
                noticed.remove(node);
            }
        });
    }

    private ReplicationMessage ask(PeerSession session, ReplicationMessage request) throws IOException {
        byte[] reply;
        try {
            reply = session.requestApplication(ReplicationCodec.encode(request)).toCompletableFuture()
                    .get(ANSWER_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for a peer", interrupted);
        } catch (ExecutionException | TimeoutException | IllegalStateException lost) {
            throw new IOException("the peer did not answer", lost);
        }
        return ReplicationCodec.decode(reply);
    }

    private synchronized PredictionEventStore store() throws IOException, GeneralSecurityException {
        if (store == null) {
            store = new PredictionEventStore(root, projectId);
        }
        return store;
    }

    private static byte[] refusal(String code) {
        return ReplicationCodec.encode(new ReplicationMessage.Outcome(new ProposalOutcome.Refused(code)));
    }

    /**
     * Stops exchanging. Sessions belong to their owner and are not closed.
     */
    @Override
    public void close() {
        worker.shutdownNow();
        requests.shutdownNow();
        try {
            // A pull still running must not touch the coordination folder after its owner stopped it.
            worker.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
