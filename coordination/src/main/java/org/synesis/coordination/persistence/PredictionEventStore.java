package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.WorkGroupProjection;
import org.synesis.coordination.domain.contract.ContractProjection;
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
@SuppressWarnings("DuplicatedCode")
public final class PredictionEventStore {

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
     * Clock used for newly appended event timestamps.
     */
    private final Clock clock;
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
        // A caller may hold a long-lived store while another connection
        // appends through a fresh instance.  Derive the append head from the
        // durable directory so the stale instance cannot overwrite the other
        // event or fork the hash chain.  Its in-memory projections remain a
        // caller concern; authority-sensitive services use fresh projections
        // at their boundaries.
        Head durableHead = durableHead();
        long sequence = durableHead.sequence() + 1L;
        PredictionEvent event = createEvent(predictionId, type, actorNodeId, payload, signer, durableHead, sequence);
        if (!event.verify()) {
            throw new CoordinationSecurityException("event signature verification failed");
        }
        validateProjections(event);
        persistEvent(event, sequence);
        applyProjections(event);
        return event;
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
        projection.validate(event);
        coordinationProjection.validate(event);
        capabilityRequestProjection.validate(event);
        taskCompletionProjection.validate(event);
        collaborationProjection.validate(event);
        workGroupProjection.validate(event);
        contractProjection.validate(event);
    }

    /**
     * Persists one encoded event through a forced temporary file and atomic move.
     *
     * @param event    validated signed event
     * @param sequence durable event sequence
     * @throws IOException when the durable file write or move fails
     */
    private void persistEvent(PredictionEvent event, long sequence) throws IOException {
        Path target = eventsDirectory.resolve(String.format("%020d.sce", sequence));
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
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
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
        projection.apply(event);
        coordinationProjection.apply(event);
        capabilityRequestProjection.apply(event);
        taskCompletionProjection.apply(event);
        collaborationProjection.apply(event);
        workGroupProjection.apply(event);
        contractProjection.apply(event);
        events.add(event);
        invokeFaultInjector(AppendBoundary.AFTER_PROJECTION);
    }

    /**
     * Removes temporary event files left before the durable move.
     *
     * @throws IOException when payload validation or durable I/O fails
     */
    private void cleanupTemporaryFiles() throws IOException {
        try (var stream = Files.list(eventsDirectory)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().contains(".sce.tmp-")).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * Returns the verified durable event-log head.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    @SuppressWarnings("DataFlowIssue")
    private synchronized Head durableHead() throws IOException, GeneralSecurityException {
        long sequence = 0L;
        byte[] digest = new byte[32];
        try (var files = Files.list(eventsDirectory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".sce")).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                PredictionEvent event = PredictionEvent.decode(Files.readAllBytes(file));
                if (!event.projectId().equals(projectId) || event.sequence() != sequence + 1L || !Arrays.equals(event.previousDigest(), digest) || !event.verify()) {
                    throw new CoordinationException("invalid coordination event log");
                }
                sequence = event.sequence();
                digest = event.digest();
            }
        }
        return new Head(sequence, digest);
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
     * call from every participant appends a {@link PredictionEventType#PARTICIPANT_HEARTBEAT}, so
     * requiring that revision to equal the head lets one participant's liveness invalidate another
     * participant's envelope. A heartbeat records activity only and changes no claim, grant,
     * snapshot, or capability state, so it does not make the envelope stale; any other event
     * appended after {@code revision} does.</p>
     *
     * @param revision head sequence observed when the envelope was projected
     * @return true when no event other than a heartbeat has been appended since that revision
     */
    public synchronized boolean unchangedSince(long revision) {
        if (revision < 0L || revision > events.size()) {
            return false;
        }
        // The event with sequence n is stored at index n - 1.
        for (int index = (int) revision; index < events.size(); index++) {
            if (events.get(index).type() != PredictionEventType.PARTICIPANT_HEARTBEAT) {
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
     * Replays sequence-ordered durable events and rebuilds the in-memory projections.
     *
     * @throws IOException              when payload validation or durable I/O fails
     * @throws GeneralSecurityException when signing or signature verification fails
     */
    private void load() throws IOException, GeneralSecurityException {
        List<Path> files;
        try (var stream = Files.list(eventsDirectory)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".sce")).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        EventSequence expected = new EventSequence(1);
        byte[] previous = new byte[32];
        for (Path file : files) {
            PredictionEvent event = PredictionEvent.decode(Files.readAllBytes(file));
            if (!event.projectId().equals(projectId) || !event.sequenceIdentity().equals(expected) || !Arrays.equals(event.previousDigest(), previous) || !event.verify()) {
                throw new CoordinationException("invalid coordination event log");
            }
            projection.apply(event);
            coordinationProjection.apply(event);
            capabilityRequestProjection.apply(event);
            taskCompletionProjection.apply(event);
            collaborationProjection.apply(event);
            workGroupProjection.apply(event);
            contractProjection.apply(event);
            events.add(event);
            previous = event.digest();
            expected = new EventSequence(expected.value() + 1L);
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
     * Represents the durable event-log head used for append validation.
     */
    private record Head(long sequence, byte[] digest) {

    }
}
