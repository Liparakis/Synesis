package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.ProjectionView;
import org.synesis.coordination.domain.collaboration.WorkGroupProjection;
import org.synesis.coordination.domain.contract.ContractProjection;
import org.synesis.coordination.domain.conversation.ConversationProjection;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
import org.synesis.coordination.domain.operator.OperatorRequestProjection;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.prediction.PredictionProjection;
import org.synesis.coordination.domain.task.CoordinationProjection;
import org.synesis.coordination.domain.task.TaskCompletionProjection;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationSecurityException;
import org.synesis.link.identity.NodeIdentity;

/**
 * Crash-safe per-project event store: this computer's own log, the copies of the other computers' logs, and the
 * order in which this computer applied them (ADR-0098 step 4).
 *
 * <p>This computer appends only to its own log, {@code events/}, one immutable file per entry, as a single computer
 * always did. The copy of another computer's log is {@code peers/<node>/events/}, byte-identical to its writer's,
 * placed by this computer's coordination server as it arrives. {@code applied.order} records the order in which
 * entries took effect here ({@link AppliedOrder}); every process that opens the store replays that order, so the
 * projections are deterministic, and a line's position is the entry's local revision, which every reader that used
 * "the sequence" as a cursor uses ({@link #headSequence()}, {@link #events()}, {@link #unchangedSince}).</p>
 *
 * <p>An own entry is judged by every rule before it is written ({@link Projections#check}). An entry of another
 * computer is a signed fact, judged only by the rules that make it count for nothing, and held back while it names
 * something not seen yet ({@link ArrivalOrder}). Lines are written under {@code order.lock}, held only around
 * in-memory work and the writes of one append or one batch of arrivals, never across a Git or process call.</p>
 */
public final class PredictionEventStore implements ProjectionView {

    /**
     * Test-only hook; a volatile reference keeps a fault visible to the fresh store opened by a service during the same
     * JVM test.
     */
    private static volatile TestFaultInjector testFaultInjector;

    /**
     * How often one append is made again after another writer took its sequence.
     */
    private static final int MAX_APPEND_ATTEMPTS = 8;

    /**
     * Most entries of one log taken under one hold of the order lock, so a long catch-up never keeps an agent's
     * append waiting for long.
     */
    private static final int ARRIVALS_PER_ROUND = 256;

    /**
     * Root used to derive sibling durable coordination state.
     */
    private final Path rootDirectory;
    /**
     * Project namespace every loaded and appended event must match.
     */
    private final UUID projectId;
    /**
     * Clock used for newly appended event timestamps.
     */
    private final Clock clock;
    /**
     * Whether this computer is the owner's, which merges every lane until ADR-0098 step 5, or a member.
     */
    private final ReplicationRole role;
    /**
     * This computer's own log.
     */
    private final LogFolder own;
    /**
     * The copies of the other computers' logs, by node, as found under {@code peers/}.
     */
    private final Map<String, LogFolder> peers = new TreeMap<>();
    /**
     * The order in which entries took effect here.
     */
    private AppliedOrder order;
    /**
     * Every projection of this copy, and how an entry is judged and applied.
     */
    private Projections projections;
    /**
     * Entries that took effect, in local order: the entry at index {@code n} has local revision {@code n + 1}.
     */
    private final List<PredictionEvent> events = new ArrayList<>();
    /**
     * Arriving entries that counted for nothing, with the rule each broke, in the order they were judged.
     */
    private final List<AppliedOrder.Line> voided = new ArrayList<>();
    /**
     * The highest sequence of each log that has been taken here, applied or counted for nothing.
     */
    private final Map<String, Long> taken = new HashMap<>();
    /**
     * Own entries applied in memory that have no line yet: written before step 4, or by an append still being made
     * or cut short. They always follow every line, and the next writer gives them their lines.
     */
    private int unlinedOwn;
    /**
     * The first held entry of each log, as the last batch of arrivals left it.
     */
    private List<ArrivalOrder.Waiting> waiting = List.of();

    /**
     * Opens or creates a project event store and replays its existing log.
     *
     * @param root      store root directory
     * @param projectId project identifier
     * @throws IOException              when the event directory cannot be read or created
     * @throws GeneralSecurityException when an existing event signature is invalid
     */
    public PredictionEventStore(Path root, UUID projectId) throws IOException, GeneralSecurityException {
        this(root, projectId, Clock.systemUTC());
    }

    /**
     * Opens a store with a supplied clock for deterministic tests.
     *
     * @param root      store root directory
     * @param projectId project identifier
     * @param clock     timestamp source
     * @throws IOException              when the event directory cannot be read or created
     * @throws GeneralSecurityException when an existing event signature is invalid
     */
    public PredictionEventStore(Path root, UUID projectId, Clock clock) throws IOException, GeneralSecurityException {
        this.rootDirectory = CoordinationNullException.requireNonNull(root, "root");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.clock = CoordinationNullException.requireNonNull(clock, "clock");
        this.projections = new Projections(new ProjectId(this.projectId));
        this.own = new LogFolder(root.resolve("events"), this.projectId, null);
        this.order = new AppliedOrder(root);
        Step2Archive.openOnce(root);
        Files.createDirectories(own.directory());
        this.role = ReplicationRole.of(root, this.projectId);
        own.cleanupTemporaryFiles();
        refresh();
    }

    /**
     * Installs a deterministic append fault for package-local crash-boundary tests.
     *
     * @param injector test fault injector, or null to clear it
     */
    static void setTestFaultInjector(TestFaultInjector injector) {
        testFaultInjector = injector;
    }

    /**
     * Clears the deterministic append fault after a crash-boundary test.
     */
    static void clearTestFaultInjector() {
        testFaultInjector = null;
    }

    /**
     * Invokes the test-only hook at the selected append boundary.
     *
     * @throws IOException when the hook simulates a failure
     */
    static void faultAt(AppendBoundary boundary) throws IOException {
        TestFaultInjector injector = testFaultInjector;
        if (injector != null) {
            injector.hit(boundary);
        }
    }

    /**
     * Appends a signed entry to this computer's own log after every rule accepted it.
     *
     * <p>A caller may hold a long-lived store while another process appends through a fresh instance. The append
     * takes the order lock and catches up with every line first, so the entry is judged against everything this
     * computer has applied and not against what this instance saw when it opened. No append waits on another
     * computer (ADR-0098 step 4).</p>
     *
     * @param predictionId prediction identifier
     * @param type         event type
     * @param actorNodeId  actor node identifier
     * @param payload      canonical event payload
     * @param signer       node signing identity
     * @return the persisted event
     * @throws IOException              when the event is refused or cannot be persisted
     * @throws GeneralSecurityException when signing or verification fails
     */
    public synchronized PredictionEvent append(UUID predictionId, PredictionEventType type, String actorNodeId,
            byte[] payload, NodeIdentity signer) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(predictionId, "prediction ID");
        if (role.refusal() != null) {
            throw new CoordinationException(role.refusal());
        }
        try (ProjectAppendLock lock = ProjectAppendLock.acquireNamed(rootDirectory, AppliedOrder.LOCK)) {
            requireHeld(lock);
            for (int attempt = 1; ; attempt++) {
                refresh();
                lineOwnLeftovers();
                List<PredictionEvent> ownEntries = own.verified();
                long sequence = ownEntries.size() + 1L;
                byte[] previous = ownEntries.isEmpty() ? new byte[32] : ownEntries.getLast().digest();
                PredictionEvent event = PredictionEvent.create(new ProjectId(projectId),
                        new PredictionId(predictionId),
                        new EventSequence(sequence),
                        type,
                        new NodeId(actorNodeId),
                        CoordinationNullException.requireNonNull(payload, "payload"),
                        previous,
                        signer,
                        clock.millis());
                if (!event.verify()) {
                    throw new CoordinationSecurityException("event signature verification failed");
                }
                long revision = events.size() + 1L;
                projections.check(event, revision);
                try {
                    own.place(event);
                } catch (FileAlreadyExistsException taken) {
                    // A writer of a build before the order lock; never replaced.
                    if (attempt >= MAX_APPEND_ATTEMPTS) {
                        throw new CoordinationException("event append lost its sequence to other writers");
                    }
                    continue;
                }
                try {
                    order.append(List.of(new AppliedOrder.Line(AppliedOrder.SELF, sequence, "")));
                    take(AppliedOrder.SELF, event);
                } catch (IOException | RuntimeException failed) {
                    // The entry is placed; whether its line or its effect made it, the disk decides on reload.
                    forgetAndReload();
                    throw failed;
                }
                faultAt(AppendBoundary.AFTER_PROJECTION);
                return event;
            }
        }
    }

    /**
     * Places entries of another computer's log, as received, and applies every entry that can be applied now.
     *
     * <p>The whole batch is checked first: each new entry must be this project's, the next of that log, chained to
     * its predecessor, and signed by {@code node}; an entry already held must be byte-identical. If any is not,
     * nothing is placed and the copy is reported as diverged: a copy is never repaired by guessing. Placed entries
     * then wait their turn ({@link ArrivalOrder}); an entry that names something not seen yet waits, with the rest
     * of its log, until what it names arrives, from this or another log.</p>
     *
     * @param node    the computer whose log it is; never this computer
     * @param entries encoded entries of that log in sequence order
     * @return how many entries were new to the copy
     * @throws IOException              with {@code LOG_COPY_DIVERGED} on a gap, a digest mismatch, a bad signature,
     *                                  another signer or a differing entry
     * @throws GeneralSecurityException when signature verification fails
     */
    public synchronized int placeCopies(String node, List<byte[]> entries) throws IOException, GeneralSecurityException {
        LogFolder copy = peer(CoordinationNullException.requireNonNull(node, "node"));
        List<PredictionEvent> held = copy.verified();
        long sequence = held.isEmpty() ? 0L : held.getLast().sequence();
        byte[] digest = held.isEmpty() ? new byte[32] : held.getLast().digest();
        List<PredictionEvent> fresh = new ArrayList<>();
        for (byte[] encoded : entries) {
            PredictionEvent event;
            try {
                event = PredictionEvent.decode(encoded);
            } catch (CoordinationException malformed) {
                throw new CoordinationException("LOG_COPY_DIVERGED", malformed);
            }
            if (event.sequence() <= held.size()) {
                if (!java.util.Arrays.equals(held.get((int) event.sequence() - 1).encoded(), encoded)) {
                    throw new CoordinationException("LOG_COPY_DIVERGED");
                }
                continue;
            }
            if (!copy.follows(event, sequence, digest)) {
                throw new CoordinationException("LOG_COPY_DIVERGED");
            }
            fresh.add(event);
            sequence = event.sequence();
            digest = event.digest();
        }
        for (PredictionEvent event : fresh) {
            copy.placeCopy(event);
        }
        takeArrivals();
        return fresh.size();
    }

    /**
     * Applies every placed entry of another computer's log that can be applied now, and records each in the
     * applied order. Run by the coordination server after placing copies, and on its start, for entries placed
     * before a restart.
     *
     * @return how many entries were taken
     * @throws IOException              when the logs or the order cannot be read or written
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    public synchronized int takeArrivals() throws IOException, GeneralSecurityException {
        int total = 0;
        try (ProjectAppendLock lock = ProjectAppendLock.acquireNamed(rootDirectory, AppliedOrder.LOCK)) {
            requireHeld(lock);
            while (true) {
                refresh();
                lineOwnLeftovers();
                // Copies placed before a restart are found on disk, not only those placed by this instance.
                discoverPeers();
                Map<String, List<PredictionEvent>> pending = new LinkedHashMap<>();
                for (Map.Entry<String, LogFolder> peer : peers.entrySet()) {
                    List<PredictionEvent> copy = peer.getValue().verified();
                    int from = (int) (long) taken.getOrDefault(peer.getKey(), 0L);
                    if (from < copy.size()) {
                        pending.put(peer.getKey(), copy.subList(from, Math.min(copy.size(), from + ARRIVALS_PER_ROUND)));
                    }
                }
                List<AppliedOrder.Line> lines = new ArrayList<>();
                ArrivalOrder.Round round;
                try {
                    round = ArrivalOrder.take(projections, pending, events.size());
                    for (ArrivalOrder.Taken entry : round.taken()) {
                        lines.add(new AppliedOrder.Line(entry.log(), entry.event().sequence(), entry.code()));
                        // Applied to the projections by the round; what is left is the bookkeeping.
                        if (entry.code().isEmpty()) {
                            events.add(entry.event());
                        } else {
                            voided.add(lines.getLast());
                        }
                        taken.put(entry.log(), entry.event().sequence());
                    }
                    order.append(lines);
                } catch (IOException | RuntimeException failed) {
                    // Applied in memory and not recorded, or recorded in part: only the disk is the truth.
                    forgetAndReload();
                    throw failed;
                }
                waiting = round.waiting();
                total += lines.size();
                if (lines.isEmpty() || !morePending()) {
                    return total;
                }
            }
        }
    }

    /**
     * Returns the encoded entries of one log after a sequence, as this computer holds them, for another computer
     * catching up. Any computer serves every log it holds: each entry is signed by its writer.
     *
     * @param log        {@link #OWN_LOG} or the node whose copy is wanted
     * @param sequence   the caller's head of that log
     * @param maxEntries most entries to return, at least one
     * @param maxBytes   soft bound on the encoded size; one entry is returned even if it is larger
     * @return entries from {@code sequence + 1} in order; empty when the caller is current or the log is unknown
     * @throws IOException              when the log cannot be read
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    public synchronized List<byte[]> entriesAfter(String log, long sequence, int maxEntries, int maxBytes)
            throws IOException, GeneralSecurityException {
        LogFolder folder = OWN_LOG.equals(log) ? own : existingPeer(log);
        List<PredictionEvent> held = folder == null ? List.of() : folder.verified();
        List<byte[]> page = new ArrayList<>();
        int bytes = 0;
        for (int index = (int) Math.max(sequence, 0L); index < held.size() && page.size() < Math.max(maxEntries, 1);
                index++) {
            byte[] encoded = held.get(index).encoded();
            if (!page.isEmpty() && bytes + encoded.length > maxBytes) {
                break;
            }
            page.add(encoded);
            bytes += encoded.length;
        }
        return page;
    }

    /**
     * Returns this computer's own entries after a sequence.
     *
     * @param sequence   the caller's head of this computer's log
     * @param maxEntries most entries to return, at least one
     * @param maxBytes   soft bound on the encoded size
     * @return entries in order
     * @throws IOException              when the log cannot be read
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    public List<byte[]> entriesAfter(long sequence, int maxEntries, int maxBytes)
            throws IOException, GeneralSecurityException {
        return entriesAfter(OWN_LOG, sequence, maxEntries, maxBytes);
    }

    /**
     * The name {@link #entriesAfter(String, long, int, int)} and {@link #logHeads()} give this computer's own log.
     */
    public static final String OWN_LOG = AppliedOrder.SELF;

    /**
     * Returns the head of every log this computer holds: its own under {@link #OWN_LOG}, and each copy under its
     * node.
     *
     * @return each log's last sequence
     * @throws IOException              when a log cannot be read
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    public synchronized Map<String, Long> logHeads() throws IOException, GeneralSecurityException {
        discoverPeers();
        Map<String, Long> heads = new TreeMap<>();
        heads.put(OWN_LOG, (long) own.verified().size());
        for (Map.Entry<String, LogFolder> peer : peers.entrySet()) {
            heads.put(peer.getKey(), (long) peer.getValue().verified().size());
        }
        return heads;
    }

    /**
     * Returns what this copy could not take as written: entries of other computers that counted for nothing, with
     * the rule each broke, and the first entry of each log that waits for something it names, with its code.
     * {@code synesis coordination status} shows them.
     *
     * @return one line per problem, {@code <node> <sequence> <code>} or {@code <node> <sequence> waiting [<code>]}
     * @throws IOException              when the logs cannot be read
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    public synchronized List<String> problems() throws IOException, GeneralSecurityException {
        refresh();
        List<String> problems = new ArrayList<>();
        for (AppliedOrder.Line line : voided) {
            problems.add(line.log() + " " + line.sequence() + " " + line.code());
        }
        discoverPeers();
        for (Map.Entry<String, LogFolder> peer : peers.entrySet()) {
            long next = taken.getOrDefault(peer.getKey(), 0L) + 1L;
            if (next <= peer.getValue().verified().size()) {
                // The code is known to the process that last tried it, the coordination server; others see the wait.
                String code = waiting.stream().filter(held -> held.log().equals(peer.getKey())).map(ArrivalOrder.Waiting::code)
                        .findFirst().orElse("");
                problems.add((peer.getKey() + " " + next + " waiting " + code).strip());
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Tells whether this computer is a member, which leaves merging to the owner's computer until ADR-0098 step 5.
     * A log whose replication settings cannot be read counts as a member's, so it never merges.
     *
     * @return true for a member
     */
    public boolean isReplicationMember() {
        return role.isMember();
    }

    /**
     * Brings the projections up to every line of the applied order, then applies own entries that have no line
     * yet. The lines are read again after the own log is listed: a line is always written before the own entry
     * that follows it in the order is placed, so a line missed by the first read is seen by the second.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void refresh() throws IOException, GeneralSecurityException {
        try {
            catchUpWithDisk();
        } catch (IOException | GeneralSecurityException | RuntimeException failed) {
            // A line half applied would leave the projections ahead of what this instance counts.
            forgetState();
            throw failed;
        }
    }

    /**
     * Brings this instance up to every line written by any process of this computer, as an open would. A
     * long-lived instance, such as the coordination server's, calls it before reading the projections.
     *
     * @throws IOException              when the logs or the order cannot be read
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    public synchronized void catchUp() throws IOException, GeneralSecurityException {
        refresh();
    }

    /**
     * Forgets everything applied in memory and reads it again from disk: what is on disk is the only truth after a
     * failure part way through applying.
     */
    private void forgetAndReload() {
        forgetState();
        try {
            catchUpWithDisk();
        } catch (IOException | GeneralSecurityException | RuntimeException unreadable) {
            // The next call reads again from nothing and reports what it finds.
            forgetState();
        }
    }

    private void forgetState() {
        projections = new Projections(new ProjectId(projectId));
        order = new AppliedOrder(rootDirectory);
        events.clear();
        voided.clear();
        taken.clear();
        unlinedOwn = 0;
        waiting = List.of();
    }

    private void catchUpWithDisk() throws IOException, GeneralSecurityException {
        List<PredictionEvent> ownEntries;
        do {
            readLines();
            ownEntries = own.verified();
        } while (readLines() > 0);
        long ownTaken = taken.getOrDefault(AppliedOrder.SELF, 0L);
        for (int index = (int) ownTaken; index < ownEntries.size(); index++) {
            take(AppliedOrder.SELF, ownEntries.get(index));
            unlinedOwn++;
        }
    }

    /**
     * Applies the lines written since the last read.
     *
     * @return how many lines were new
     */
    private int readLines() throws IOException, GeneralSecurityException {
        List<AppliedOrder.Line> lines = order.readNew();
        for (AppliedOrder.Line line : lines) {
            if (line.log().equals(AppliedOrder.SELF) && line.sequence() <= taken.getOrDefault(AppliedOrder.SELF, 0L)) {
                // An own entry applied before its line was written; the line confirms its place.
                if (unlinedOwn == 0) {
                    throw new CoordinationException("invalid coordination event log");
                }
                unlinedOwn--;
                continue;
            }
            if (unlinedOwn > 0) {
                // Every writer gives unlined own entries their lines before any other line.
                throw new CoordinationException("invalid coordination event log");
            }
            record(line, entry(line));
        }
        return lines.size();
    }

    /**
     * Writes the lines of own entries applied without one, so that they keep their place before any line written
     * now. The caller holds the order lock and has caught up.
     */
    private void lineOwnLeftovers() throws IOException {
        if (unlinedOwn == 0) {
            return;
        }
        long ownTaken = taken.getOrDefault(AppliedOrder.SELF, 0L);
        List<AppliedOrder.Line> lines = new ArrayList<>();
        for (long sequence = ownTaken - unlinedOwn + 1; sequence <= ownTaken; sequence++) {
            lines.add(new AppliedOrder.Line(AppliedOrder.SELF, sequence, ""));
        }
        order.append(lines);
        unlinedOwn = 0;
    }

    private static void requireHeld(ProjectAppendLock lock) throws CoordinationException {
        if (!lock.isHeld()) {
            throw new CoordinationException("event append lock unavailable");
        }
    }

    /** Applies one entry of the order that took effect, or records one that counted for nothing. */
    private void record(AppliedOrder.Line line, PredictionEvent event) throws IOException {
        if (line.applied()) {
            take(line.log(), event);
        } else {
            // What the entry would have opened is ended here, as it was when the entry was first judged.
            projections.collaborationProjection().countedForNothing(event);
            voided.add(line);
            taken.put(line.log(), line.sequence());
        }
    }

    /** Applies an entry that took effect at the next local revision. */
    private void take(String log, PredictionEvent event) throws IOException {
        projections.apply(event, events.size() + 1L);
        events.add(event);
        taken.put(log, event.sequence());
    }

    /** Returns the entry a line names, which its log must hold. */
    private PredictionEvent entry(AppliedOrder.Line line) throws IOException, GeneralSecurityException {
        LogFolder folder = line.log().equals(AppliedOrder.SELF) ? own : peer(line.log());
        List<PredictionEvent> held = folder.verified();
        if (line.sequence() > held.size() || line.sequence() != taken.getOrDefault(line.log(), 0L) + 1L) {
            throw new CoordinationException("invalid coordination event log");
        }
        return held.get((int) line.sequence() - 1);
    }

    private boolean morePending() throws IOException, GeneralSecurityException {
        for (Map.Entry<String, LogFolder> peer : peers.entrySet()) {
            if (taken.getOrDefault(peer.getKey(), 0L) < peer.getValue().verified().size()
                    && waiting.stream().noneMatch(held -> held.log().equals(peer.getKey()))) {
                return true;
            }
        }
        return false;
    }

    private LogFolder peer(String node) throws CoordinationException {
        if (node.isEmpty() || node.equals(AppliedOrder.SELF) || !node.chars().allMatch(c -> Character.isLetterOrDigit(c)
                || c == '-' || c == '_')) {
            throw new CoordinationException("LOG_COPY_NODE_INVALID");
        }
        return peers.computeIfAbsent(node, name -> new LogFolder(peersDirectory().resolve(name).resolve("events"),
                projectId, name));
    }

    private LogFolder existingPeer(String node) throws IOException {
        discoverPeers();
        return peers.get(node);
    }

    private void discoverPeers() throws IOException {
        Path directory = peersDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var listing = Files.list(directory)) {
            for (Path candidate : listing.filter(Files::isDirectory).toList()) {
                try {
                    peer(candidate.getFileName().toString());
                } catch (CoordinationException notANode) {
                    // A folder that cannot be a node's copy is not one.
                }
            }
        }
    }

    private Path peersDirectory() {
        return rootDirectory.resolve("peers");
    }

    /**
     * Returns every entry that took effect, in local order.
     *
     * @return immutable event list
     */
    public synchronized List<PredictionEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the entries that took effect after a local revision, in local order.
     *
     * @param revision a local revision, such as a reader's cursor
     * @return immutable list of the entries at revisions {@code revision + 1} onward
     */
    public synchronized List<PredictionEvent> eventsAfter(long revision) {
        return List.copyOf(events.subList((int) Math.min(Math.max(revision, 0L), events.size()), events.size()));
    }

    /**
     * Returns this copy's local revision: how many entries took effect here.
     *
     * @return current head revision
     */
    public synchronized long headSequence() {
        return events.size();
    }

    /**
     * Reports whether an optimistic envelope issued at {@code revision} still describes this log.
     *
     * <p>Completion envelopes are fenced by the revision at which they were projected. Every MCP
     * call from every participant used to append a {@link PredictionEventType#PARTICIPANT_HEARTBEAT}
     * (it is now kept outside the log, but logs written before still hold them), so
     * requiring that revision to equal the head would let one participant's liveness invalidate another
     * participant's envelope. A heartbeat, a message between lanes and a mark of delivery
     * change no claim, grant, snapshot, or capability state, so they do not make the envelope
     * stale; any other event appended after {@code revision} does.</p>
     *
     * @param revision head revision observed when the envelope was projected
     * @return true when no event that changes state has been appended since that revision
     */
    public synchronized boolean unchangedSince(long revision) {
        if (revision < 0L || revision > events.size()) {
            return false;
        }
        // The event with revision n is stored at index n - 1.
        for (int index = (int) revision; index < events.size(); index++) {
            if (!events.get(index).type().recordsNoStateChange()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the project namespace served by this store.
     *
     * @return project identifier
     */
    public UUID projectId() {
        return projectId;
    }

    /**
     * Returns the project namespace with its domain identity type.
     *
     * @return typed project identity
     */
    public ProjectId projectIdentity() {
        return new ProjectId(projectId);
    }

    /**
     * Returns the project-local store root for coordinated refreshes.
     *
     * @return store root
     */
    public Path rootDirectory() {
        return rootDirectory;
    }

    /**
     * Returns the deterministic prediction projection.
     *
     * @return live projection
     */
    public PredictionProjection projection() {
        return projections.prediction();
    }

    /**
     * Returns the task and ownership projection reconstructed from the event log.
     *
     * @return coordination projection
     */
    public CoordinationProjection coordinationProjection() {
        return projections.coordination();
    }

    /**
     * Returns the capability request projection reconstructed from the event log.
     *
     * @return capability request projection
     */
    public CapabilityRequestProjection capabilityRequestProjection() {
        return projections.capabilityRequests();
    }

    /**
     * Returns the task completion and integration projection reconstructed from the event log.
     *
     * @return task completion projection
     */
    public TaskCompletionProjection taskCompletionProjection() {
        return projections.taskCompletionProjection();
    }

    /**
     * Returns the collaboration projection reconstructed from signed events.
     *
     * @return live collaboration projection
     */
    public CollaborationProjection collaborationProjection() {
        return projections.collaborationProjection();
    }

    /**
     * Returns logical work-group and lane-grant state reconstructed from events.
     *
     * @return work-group projection
     */
    public WorkGroupProjection workGroupProjection() {
        return projections.workGroupProjection();
    }

    /**
     * Returns the replayed contract projection.
     *
     * @return contract projection
     */
    public ContractProjection contractProjection() {
        return projections.contracts();
    }

    /**
     * Returns the replayed operator request projection.
     *
     * @return operator request projection
     */
    public OperatorRequestProjection operatorRequestProjection() {
        return projections.operatorRequests();
    }

    /**
     * Returns the replayed conversation projection.
     *
     * @return conversation projection
     */
    public ConversationProjection conversationProjection() {
        return projections.conversations();
    }

    /**
     * Returns who belongs to the project, as the membership entries say (ADR-0098 step 6).
     *
     * @return membership projection
     */
    public org.synesis.coordination.domain.membership.MembershipProjection membershipProjection() {
        return projections.membership();
    }

    /**
     * Returns the trash as the log says (ADR-0098 step 7).
     *
     * @return trash projection
     */
    public org.synesis.coordination.domain.trash.TrashProjection trashProjection() {
        return projections.trash();
    }

    /**
     * Counts the entries recorded under a coordination directory without replaying them: this computer's own and
     * every copy it holds. It grows whenever an entry is written or arrives, which is what its readers watch for.
     *
     * @param root coordination directory
     * @return the number of recorded entries; zero when nothing is recorded or the directory cannot be read
     */
    public static long recordedEvents(Path root) {
        Path coordination = CoordinationNullException.requireNonNull(root, "root");
        long count = new LogFolder(coordination.resolve("events"), null, null).count();
        Path copies = coordination.resolve("peers");
        if (Files.isDirectory(copies)) {
            try (var listing = Files.list(copies)) {
                for (Path node : listing.toList()) {
                    count += new LogFolder(node.resolve("events"), null, null).count();
                }
            } catch (IOException unreadable) {
                // Counted as far as it could be read.
            }
        }
        return count;
    }

    /**
     * Boundaries at which the persistence test harness may simulate an abrupt process loss.
     */
    enum AppendBoundary {
        BEFORE_DURABLE_MOVE, AFTER_DURABLE_MOVE, AFTER_PROJECTION
    }

    /**
     * Deterministic fault hook kept package-private so production callers cannot control durability.
     */
    @FunctionalInterface
    interface TestFaultInjector {

        /**
         * Simulates loss at one append boundary.
         *
         * @param boundary append boundary reached
         * @throws IOException simulated process failure
         */
        void hit(AppendBoundary boundary) throws IOException;
    }
}
