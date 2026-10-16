package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.AppendAdmission;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.ProjectionView;
import org.synesis.coordination.domain.collaboration.WorkGroupProjection;
import org.synesis.coordination.domain.contract.ContractProjection;
import org.synesis.coordination.domain.conversation.ConversationProjection;
import org.synesis.coordination.domain.operator.OperatorRequestProjection;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.PredictionId;
import org.synesis.coordination.domain.identity.ProjectId;
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
 * Crash-safe per-project event store. Each event is one immutable file; the directory is the append-only log and
 * projection replay is
 * deterministic.
 */
public final class PredictionEventStore implements ProjectionView {

    /**
     * Test-only hook; a volatile reference keeps a fault visible to the fresh store opened by a service during the same
     * JVM test.
     */
    private static volatile TestFaultInjector testFaultInjector;
    /**
     * Directory containing the immutable, sequence-numbered event files.
     */
    private final Path eventsDirectory;
    /**
     * Root used to derive sibling durable coordination state.
     */
    private final Path rootDirectory;
    /**
     * Project namespace every loaded and appended event must match.
     */
    private final UUID projectId;
    /**
     * How often one append is made again after another writer took its sequence.
     */
    private static final int MAX_APPEND_ATTEMPTS = 8;

    /**
     * Age beyond which a temporary event file can only have been abandoned by a writer that died.
     */
    private static final Duration ABANDONED_TEMPORARY_AGE = Duration.ofMinutes(1);

    /**
     * How often a proposal with no answer is sent again before its outcome is reported unknown.
     */
    private static final int MAX_PROPOSAL_SENDS = 3;

    /**
     * Largest page a member asks the leader for, in entries and in encoded bytes.
     */
    private static final int PULL_PAGE_ENTRIES = 256;
    private static final int PULL_PAGE_BYTES = 1 << 20;

    /**
     * Clock used for newly appended event timestamps.
     */
    private final Clock clock;
    /**
     * Whether this log decides its own entries or proposes them to a leader.
     */
    private final ReplicationRole role;
    /**
     * Projection for prediction-specific state.
     */
    private final PredictionProjection projection = new PredictionProjection();
    /**
     * Projection for task and coordination state.
     */
    private final CoordinationProjection coordinationProjection = new CoordinationProjection();
    /**
     * Projection for capability-request state.
     */
    private final CapabilityRequestProjection capabilityRequestProjection = new CapabilityRequestProjection();
    /**
     * Projection for completion and terminal outcome state.
     */
    private final TaskCompletionProjection taskCompletionProjection = new TaskCompletionProjection();
    /**
     * Projection for collaboration request and response state.
     */
    private final CollaborationProjection collaborationProjection = new CollaborationProjection();
    /**
     * Projection for work-group membership and handoff state.
     */
    private final WorkGroupProjection workGroupProjection = new WorkGroupProjection();
    /**
     * Projection for contract binding and publication state.
     */
    private final ContractProjection contractProjection = new ContractProjection();
    /**
     * Projection for operator requests.
     */
    private final OperatorRequestProjection operatorRequestProjection = new OperatorRequestProjection();
    /**
     * Projection for what lanes said to each other.
     */
    private final ConversationProjection conversationProjection = new ConversationProjection();
    /**
     * Every projection above, in validation and application order; the one place a new projection is registered.
     */
    private final List<Stage> stages = List.of(
            new Stage(projection::validate, projection::apply),
            new Stage(coordinationProjection::validate, coordinationProjection::apply),
            new Stage(capabilityRequestProjection::validate, capabilityRequestProjection::apply),
            new Stage(taskCompletionProjection::validate, taskCompletionProjection::apply),
            new Stage(collaborationProjection::validate, collaborationProjection::apply),
            new Stage(workGroupProjection::validate, workGroupProjection::apply),
            new Stage(contractProjection::validate, contractProjection::apply),
            new Stage(operatorRequestProjection::validate, operatorRequestProjection::apply),
            new Stage(conversationProjection::validate, conversationProjection::apply),
            // Last, so a rule of one projection answers first. It reads every projection and applies nothing.
            new Stage(event -> AppendAdmission.check(event, this), event -> {
            }));
    /**
     * Events replayed into this instance's in-memory projections.
     */
    private final List<PredictionEvent> events = new ArrayList<>();

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
        this.eventsDirectory = CoordinationNullException.requireNonNull(root, "root").resolve("events");
        this.projectId = CoordinationNullException.requireNonNull(projectId, "project ID");
        this.clock = CoordinationNullException.requireNonNull(clock, "clock");
        Files.createDirectories(eventsDirectory);
        this.role = ReplicationRole.of(root);
        cleanupTemporaryFiles();
        load();
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
     * @throws IOException when payload validation or durable I/O fails
     */
    private static void invokeFaultInjector(AppendBoundary boundary) throws IOException {
        TestFaultInjector injector = testFaultInjector;
        if (injector != null) {
            injector.hit(boundary);
        }
    }

    /**
     * Appends a signed event after validating sequence, hash chain, and state transition.
     *
     * @param predictionId prediction identifier
     * @param type         event type
     * @param actorNodeId  actor node identifier
     * @param payload      canonical event payload
     * @param signer       node signing identity
     * @return the persisted event
     * @throws IOException              when the event cannot be persisted
     * @throws GeneralSecurityException when signing or verification fails
     */
    public synchronized PredictionEvent append(UUID predictionId, PredictionEventType type, String actorNodeId, byte[] payload, NodeIdentity signer) throws IOException, GeneralSecurityException {
        CoordinationNullException.requireNonNull(predictionId, "prediction ID");
        // A caller may hold a long-lived store while another connection appends through a fresh
        // instance. Reading the durable head brings this instance's projections up to it first, so
        // the event is validated against the log it will be placed on and not against what this
        // instance saw when it opened. Without that, a rule would be checked on stale state.
        //
        // Reading the head and placing the file are not one step across processes, and not every
        // appender holds the project append lock. A sequence another writer took in between is
        // never replaced: this append is made again on the new head, after catching up again.
        //
        // A member does not decide where its entry goes. It builds and checks the entry as above, then the
        // leader places it (invariant 1); only an entry the leader accepted is placed here, as the same bytes.
        for (int attempt = 1; ; attempt++) {
            Head durableHead = durableHead();
            long sequence = durableHead.sequence() + 1L;
            PredictionEvent event = createEvent(predictionId, type, actorNodeId, payload, signer, durableHead, sequence);
            if (!event.verify()) {
                throw new CoordinationSecurityException("event signature verification failed");
            }
            validateProjections(event);
            try {
                if (role.isMember()) {
                    if (!leaderAccepts(event)) {
                        if (attempt >= MAX_APPEND_ATTEMPTS) {
                            throw new CoordinationException("event append lost its sequence to other writers");
                        }
                        pullFromLeader();
                        continue;
                    }
                    placeCommitted(event);
                } else {
                    persistEvent(event, sequence);
                }
            } catch (FileAlreadyExistsException taken) {
                if (attempt >= MAX_APPEND_ATTEMPTS) {
                    throw new CoordinationException("event append lost its sequence to other writers");
                }
                continue;
            }
            applyProjections(event);
            return event;
        }
    }

    /**
     * Creates the next event from the durable head and caller-provided event data.
     *
     * @param predictionId prediction identifier
     * @param type         event type
     * @param actorNodeId  actor node identifier
     * @param payload      canonical event payload
     * @param signer       node signing identity
     * @param durableHead  latest durable sequence and digest
     * @param sequence     next event sequence
     * @return signed event for the next sequence
     * @throws GeneralSecurityException when event signing fails
     */
    private PredictionEvent createEvent(UUID predictionId, PredictionEventType type, String actorNodeId, byte[] payload, NodeIdentity signer, Head durableHead, long sequence) throws GeneralSecurityException {
        return PredictionEvent.create(new ProjectId(projectId),
                new PredictionId(predictionId),
                new EventSequence(sequence),
                type,
                new NodeId(actorNodeId),
                CoordinationNullException.requireNonNull(payload, "payload"),
                durableHead.digest(),
                signer,
                clock.millis());
    }

    /**
     * Checks that every event projection accepts the same transition before persistence.
     *
     * @param event signed event under append validation
     * @throws IOException when any projection rejects the transition
     */
    private void validateProjections(PredictionEvent event) throws IOException {
        for (Stage stage : stages) {
            stage.validate().accept(event);
        }
    }

    /**
     * Persists one encoded event through a forced temporary file that is then given its durable
     * name. The name is only ever given to a sequence nobody holds: a hard link fails when the name
     * exists, where a move would replace another writer's event.
     *
     * @param event    validated signed event
     * @param sequence durable event sequence
     * @throws FileAlreadyExistsException when another writer already holds the sequence
     * @throws IOException                when the durable file write or placement fails
     */
    private void persistEvent(PredictionEvent event, long sequence) throws IOException {
        Path target = eventFile(sequence);
        Path temporary = eventsDirectory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer encoded = ByteBuffer.wrap(event.encoded());
                while (encoded.hasRemaining()) {
                    if (channel.write(encoded) <= 0) {
                        throw new CoordinationException("event append write made no progress");
                    }
                }
                channel.force(true);
            }
            invokeFaultInjector(AppendBoundary.BEFORE_DURABLE_MOVE);
            try {
                Files.createLink(target, temporary);
            } catch (FileAlreadyExistsException taken) {
                throw taken;
            } catch (UnsupportedOperationException | IOException noHardLinks) {
                // A file system without hard links. A move without options fails as well when
                // the name exists.
                Files.move(temporary, target);
            }
            invokeFaultInjector(AppendBoundary.AFTER_DURABLE_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Applies a durably persisted event to every in-memory projection.
     *
     * @param event durably persisted signed event
     * @throws IOException when replaying the event into a projection fails
     */
    private void applyProjections(PredictionEvent event) throws IOException {
        replay(event);
        invokeFaultInjector(AppendBoundary.AFTER_PROJECTION);
    }

    /**
     * Applies one verified event to every projection and records it in the in-memory log.
     *
     * @throws IOException when a projection rejects the event
     */
    private void replay(PredictionEvent event) throws IOException {
        for (Stage stage : stages) {
            stage.apply().accept(event);
        }
        events.add(event);
    }

    private Path eventFile(long sequence) {
        return eventsDirectory.resolve(String.format("%020d.sce", sequence));
    }

    /**
     * Sends a member's entry to the leader and reads the answer.
     *
     * <p>A proposal with no answer is sent again as the same bytes, which the leader recognises, up to
     * {@link #MAX_PROPOSAL_SENDS} times. After that the entry may or may not be on the leader's log, nothing is
     * placed here, and the next pull settles it.</p>
     *
     * @return true when the leader holds the entry; false when its head moved and the entry must be built again
     * @throws IOException with the leader's refusal code, {@code LEADER_UNREACHABLE} or {@code LEADER_OUTCOME_UNKNOWN}
     */
    private boolean leaderAccepts(PredictionEvent event) throws IOException {
        byte[] encoded = event.encoded();
        for (int sends = 1; ; sends++) {
            switch (role.channel().propose(encoded)) {
                case ProposalOutcome.Accepted accepted -> {
                    if (accepted.sequence() != event.sequence()) {
                        throw new CoordinationException("LEADER_LOG_DIVERGED");
                    }
                    return true;
                }
                case ProposalOutcome.HeadMoved moved -> {
                    return false;
                }
                case ProposalOutcome.Refused refused -> throw new CoordinationException(refused.code());
                case ProposalOutcome.Unreachable unreachable -> throw new CoordinationException("LEADER_UNREACHABLE");
                case ProposalOutcome.NoAnswer noAnswer -> {
                    if (sends >= MAX_PROPOSAL_SENDS) {
                        throw new CoordinationException("LEADER_OUTCOME_UNKNOWN");
                    }
                }
            }
        }
    }

    /**
     * Places an entry the leader holds. A file already there must be the same bytes, as a pull that ran meanwhile
     * would have left; anything else means the two logs differ.
     *
     * @throws IOException with {@code LEADER_LOG_DIVERGED} when another entry holds the sequence
     */
    private void placeCommitted(PredictionEvent event) throws IOException {
        try {
            persistEvent(event, event.sequence());
        } catch (FileAlreadyExistsException taken) {
            if (!Arrays.equals(Files.readAllBytes(eventFile(event.sequence())), event.encoded())) {
                throw new CoordinationException("LEADER_LOG_DIVERGED");
            }
        }
    }

    /**
     * Decides a member's proposal on the leader's log: the leader's side of {@link LeaderEndpoint#accept}. The
     * caller holds the project append lock, opened this store after taking it, and has checked who sent the entry.
     *
     * @param event the member's signed entry, signature already verified
     * @return the outcome; the entry is placed only for {@link ProposalOutcome.Accepted} after a first send
     * @throws IOException              when durable I/O fails
     * @throws GeneralSecurityException when signature verification of the existing log fails
     */
    synchronized ProposalOutcome admit(PredictionEvent event) throws IOException, GeneralSecurityException {
        if (role.isMember()) {
            throw new CoordinationException("MEMBER_CANNOT_DECIDE");
        }
        if (!event.projectId().equals(projectId)) {
            return new ProposalOutcome.Refused("PROPOSAL_PROJECT_MISMATCH");
        }
        Head head = durableHead();
        if (event.sequence() <= head.sequence()) {
            // A resend of an entry the leader already holds is answered like the first send.
            boolean identical = Arrays.equals(events.get((int) event.sequence() - 1).encoded(), event.encoded());
            return identical ? new ProposalOutcome.Accepted(event.sequence())
                    : new ProposalOutcome.HeadMoved(head.sequence());
        }
        if (event.sequence() != head.sequence() + 1L || !Arrays.equals(event.previousDigest(), head.digest())) {
            return new ProposalOutcome.HeadMoved(head.sequence());
        }
        try {
            validateProjections(event);
        } catch (CoordinationException refusal) {
            return new ProposalOutcome.Refused(refusal.code());
        } catch (RuntimeException malformed) {
            return new ProposalOutcome.Refused("PROPOSAL_PAYLOAD_INVALID");
        }
        try {
            persistEvent(event, event.sequence());
        } catch (FileAlreadyExistsException taken) {
            return new ProposalOutcome.HeadMoved(head.sequence());
        }
        applyProjections(event);
        return new ProposalOutcome.Accepted(event.sequence());
    }

    /**
     * Returns the encoded entries after a sequence, for a member catching up.
     *
     * @param sequence   the caller's head sequence
     * @param maxEntries most entries to return, at least one
     * @param maxBytes   soft bound on the encoded size; one entry is returned even if it is larger
     * @return entries from {@code sequence + 1} in order; empty when the caller is current
     * @throws IOException              when the log cannot be read
     * @throws GeneralSecurityException when a signature cannot be checked
     */
    public synchronized List<byte[]> entriesAfter(long sequence, int maxEntries, int maxBytes)
            throws IOException, GeneralSecurityException {
        durableHead();
        List<byte[]> page = new ArrayList<>();
        int bytes = 0;
        for (int index = (int) Math.max(sequence, 0L); index < events.size() && page.size() < Math.max(maxEntries, 1);
                index++) {
            byte[] encoded = events.get(index).encoded();
            if (!page.isEmpty() && bytes + encoded.length > maxBytes) {
                break;
            }
            page.add(encoded);
            bytes += encoded.length;
        }
        return page;
    }

    /**
     * Places and applies the leader's entries that this log does not hold yet.
     *
     * <p>The whole batch is checked first: each new entry must be this project's, the next sequence, chained to
     * its predecessor's digest and correctly signed, and an entry already held must be byte-identical. If any is
     * not, nothing is placed and the log is reported as diverged; a member never repairs its copy by guessing.
     * Entries are placed exactly as received, so both logs hold the same files.</p>
     *
     * @param entries encoded entries in sequence order
     * @return how many entries were new
     * @throws IOException              with {@code LEADER_LOG_DIVERGED} on a gap, a digest mismatch, a bad
     *                                  signature or a differing entry
     * @throws GeneralSecurityException when signature verification fails
     */
    public synchronized int applyCommitted(List<byte[]> entries) throws IOException, GeneralSecurityException {
        Head head = durableHead();
        long sequence = head.sequence();
        byte[] digest = head.digest();
        List<PredictionEvent> fresh = new ArrayList<>();
        for (byte[] encoded : entries) {
            PredictionEvent event;
            try {
                event = PredictionEvent.decode(encoded);
            } catch (CoordinationException malformed) {
                throw new CoordinationException("LEADER_LOG_DIVERGED", malformed);
            }
            if (event.sequence() <= head.sequence()) {
                if (!Arrays.equals(events.get((int) event.sequence() - 1).encoded(), encoded)) {
                    throw new CoordinationException("LEADER_LOG_DIVERGED");
                }
                continue;
            }
            if (!event.projectId().equals(projectId) || event.sequence() != sequence + 1L
                    || !Arrays.equals(event.previousDigest(), digest) || !event.verify()) {
                throw new CoordinationException("LEADER_LOG_DIVERGED");
            }
            fresh.add(event);
            sequence = event.sequence();
            digest = event.digest();
        }
        for (PredictionEvent event : fresh) {
            placeCommitted(event);
            applyProjections(event);
        }
        return fresh.size();
    }

    /**
     * Brings a member's copy up to the leader's log, a page at a time.
     *
     * @return how many entries were new
     * @throws IOException              with {@code LEADER_UNREACHABLE} or {@code LEADER_LOG_DIVERGED}
     * @throws GeneralSecurityException when signature verification fails
     */
    public synchronized int pullFromLeader() throws IOException, GeneralSecurityException {
        if (!role.isMember()) {
            throw new CoordinationException("NOT_A_MEMBER");
        }
        int applied = 0;
        while (true) {
            List<byte[]> page = role.channel().pull(durableHead().sequence(), PULL_PAGE_ENTRIES, PULL_PAGE_BYTES);
            int fresh = page.isEmpty() ? 0 : applyCommitted(page);
            if (fresh == 0) {
                return applied;
            }
            applied += fresh;
        }
    }

    /**
     * Removes temporary event files that a writer left behind before its durable move.
     *
     * <p>Every open runs this, readers included, and holds no lock, so a temporary file may belong
     * to another process that is appending right now. Such a file exists for milliseconds. Only
     * one older than {@link #ABANDONED_TEMPORARY_AGE} is removed, and one that cannot be inspected
     * or deleted is left alone: it is being written or has just been moved, and reading the log
     * never looks at it.</p>
     *
     * @throws IOException when the events directory cannot be listed
     */
    private void cleanupTemporaryFiles() throws IOException {
        FileTime abandonedBefore = FileTime.from(Instant.now().minus(ABANDONED_TEMPORARY_AGE));
        try (var stream = Files.list(eventsDirectory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().contains(".sce.tmp-")).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).compareTo(abandonedBefore) < 0) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException inFlight) {
                    // Not ours to remove.
                }
            }
        }
    }

    /**
     * Returns the verified durable event-log head, after applying to this instance's projections every verified event
     * it has not applied yet.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private synchronized Head durableHead() throws IOException, GeneralSecurityException {
        return scan(event -> {
            if (event.sequence() > events.size()) {
                replay(event);
            }
        });
    }

    /**
     * Returns all verified events in sequence order.
     *
     * @return immutable event list
     */
    public synchronized List<PredictionEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Returns the current project sequence.
     *
     * @return current head sequence
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
     * @param revision head sequence observed when the envelope was projected
     * @return true when no event that changes state has been appended since that revision
     */
    public synchronized boolean unchangedSince(long revision) {
        if (revision < 0L || revision > events.size()) {
            return false;
        }
        // The event with sequence n is stored at index n - 1.
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
        return projection;
    }

    /**
     * Returns the task and ownership projection reconstructed from the event log.
     *
     * @return coordination projection
     */
    public CoordinationProjection coordinationProjection() {
        return coordinationProjection;
    }

    /**
     * Returns the capability request projection reconstructed from the event log.
     *
     * @return capability request projection
     */
    public CapabilityRequestProjection capabilityRequestProjection() {
        return capabilityRequestProjection;
    }

    /**
     * Returns the task completion and integration projection reconstructed from the event log.
     *
     * @return task completion projection
     */
    public TaskCompletionProjection taskCompletionProjection() {
        return taskCompletionProjection;
    }

    /**
     * Returns the collaboration projection reconstructed from signed events.
     *
     * @return live collaboration projection
     */
    public CollaborationProjection collaborationProjection() {
        return collaborationProjection;
    }

    /**
     * Returns logical work-group and lane-grant state reconstructed from events.
     *
     * @return work-group projection
     */
    public WorkGroupProjection workGroupProjection() {
        return workGroupProjection;
    }

    /**
     * Returns the replayed contract projection.
     *
     * @return contract projection
     */
    public ContractProjection contractProjection() {
        return contractProjection;
    }

    /**
     * Returns the replayed operator request projection.
     *
     * @return operator request projection
     */
    public OperatorRequestProjection operatorRequestProjection() {
        return operatorRequestProjection;
    }

    /**
     * Counts the events recorded under a coordination directory without replaying them. It is the
     * head a store opened there would report.
     *
     * @param root coordination directory
     * @return the number of recorded events; zero when nothing is recorded or the directory cannot be read
     */
    public static long recordedEvents(Path root) {
        Path events = CoordinationNullException.requireNonNull(root, "root").resolve("events");
        if (!Files.isDirectory(events)) {
            return 0L;
        }
        try (java.util.stream.Stream<Path> files = Files.list(events)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sce")).count();
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    /**
     * Returns the replayed conversation projection.
     *
     * @return conversation projection
     */
    public ConversationProjection conversationProjection() {
        return conversationProjection;
    }

    /**
     * Replays sequence-ordered durable events and rebuilds the in-memory projections.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void load() throws IOException, GeneralSecurityException {
        durableHead();
    }

    /**
     * Walks the durable log in file-name order, requiring each event to belong to this project, follow its
     * predecessor's sequence and digest, and carry a valid signature, and hands each such event to {@code visitor}.
     *
     * @param visitor receives every verified event, in order
     * @return sequence and digest of the last event, or zero and an all-zero digest for an empty log
     * @throws IOException              when an event is undecodable, out of chain, or the visitor fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private Head scan(Transition visitor) throws IOException, GeneralSecurityException {
        List<Path> files;
        try (var stream = Files.list(eventsDirectory)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".sce")).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        Path real = eventsDirectory.toRealPath();
        List<PredictionEvent> verified = new ArrayList<>(VerifiedEventPrefixes.recall(real, projectId));
        if (!stillHolds(verified, files)) {
            VerifiedEventPrefixes.forget(real, projectId);
            verified.clear();
        }
        long sequence = 0L;
        byte[] digest = new byte[32];
        for (PredictionEvent known : verified) {
            visitor.accept(known);
            sequence = known.sequence();
            digest = known.digest();
        }
        try {
            for (Path file : files.subList(verified.size(), files.size())) {
                PredictionEvent event = PredictionEvent.decode(Files.readAllBytes(file));
                if (!event.projectId().equals(projectId) || event.sequence() != sequence + 1L || !Arrays.equals(event.previousDigest(), digest) || !event.verify()) {
                    throw new CoordinationException("invalid coordination event log");
                }
                verified.add(event);
                visitor.accept(event);
                sequence = event.sequence();
                digest = event.digest();
            }
        } finally {
            VerifiedEventPrefixes.remember(real, projectId, verified);
        }
        return new Head(sequence, digest);
    }

    /**
     * Checks that the directory still holds the files of a remembered verified prefix: the first files are
     * exactly sequences one to the prefix length, and the last of them still has the bytes that were verified.
     * Anything else (a file missing, renamed, added in the middle, or replaced at the head) makes the prefix
     * untrusted and the caller verifies the whole log again.
     */
    private static boolean stillHolds(List<PredictionEvent> prefix, List<Path> files) throws IOException {
        if (prefix.isEmpty()) {
            return true;
        }
        if (files.size() < prefix.size()) {
            return false;
        }
        for (int index = 0; index < prefix.size(); index++) {
            if (!files.get(index).getFileName().toString().equals(String.format("%020d.sce", index + 1L))) {
                return false;
            }
        }
        PredictionEvent head = prefix.get(prefix.size() - 1);
        try {
            return Arrays.equals(Files.readAllBytes(files.get(prefix.size() - 1)), head.encoded());
        } catch (java.nio.file.NoSuchFileException vanished) {
            return false;
        }
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

    /**
     * One event action a projection performs, either validating a transition or applying it.
     */
    @FunctionalInterface
    private interface Transition {

        void accept(PredictionEvent event) throws IOException;
    }

    /**
     * The validation and application entry points of one projection.
     */
    private record Stage(Transition validate, Transition apply) {

    }

    /**
     * Represents the durable event-log head used for append validation.
     */
    private record Head(long sequence, byte[] digest) {

    }
}
