package org.synesis.coordination.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.capability.CapabilityRequestProjection;
import org.synesis.coordination.domain.collaboration.CollaborationProjection;
import org.synesis.coordination.domain.collaboration.WorkGroupProjection;
import org.synesis.coordination.domain.contract.ContractProjection;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.prediction.PredictionProjection;
import org.synesis.coordination.domain.task.CoordinationProjection;
import org.synesis.coordination.domain.task.TaskCompletionProjection;
import org.synesis.link.identity.NodeIdentity;

/**
 * Crash-safe per-project event store. Each event is one immutable file; the
 * directory is the append-only log and projection replay is deterministic.
 */
@SuppressWarnings("DuplicatedCode")
public final class PredictionEventStore {

    /** Directory containing the immutable, sequence-numbered event files. */
    private final Path eventsDirectory;
    /** Root used to derive sibling durable coordination state. */
    private final Path rootDirectory;
    /** Project namespace every loaded and appended event must match. */
    private final UUID projectId;
    /** Clock used for newly appended event timestamps. */
    private final Clock clock;
    /** Projection for prediction-specific state. */
    private final PredictionProjection projection = new PredictionProjection();
    /** Projection for task and coordination state. */
    private final CoordinationProjection coordinationProjection = new CoordinationProjection();
    /** Projection for capability-request state. */
    private final CapabilityRequestProjection capabilityRequestProjection = new CapabilityRequestProjection();
    /** Projection for completion and terminal outcome state. */
    private final TaskCompletionProjection taskCompletionProjection = new TaskCompletionProjection();
    /** Projection for collaboration request and response state. */
    private final CollaborationProjection collaborationProjection = new CollaborationProjection();
    /** Projection for work-group membership and handoff state. */
    private final WorkGroupProjection workGroupProjection = new WorkGroupProjection();
    /** Projection for contract binding and publication state. */
    private final ContractProjection contractProjection = new ContractProjection();
    /** Events replayed into this instance's in-memory projections. */
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
        this.rootDirectory = Objects.requireNonNull(root, "root");
        this.eventsDirectory = Objects.requireNonNull(root, "root")
                .resolve("events");
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(eventsDirectory);
        load();
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
    public synchronized PredictionEvent append(UUID predictionId, PredictionEventType type,
            String actorNodeId, byte[] payload, NodeIdentity signer) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(predictionId, "prediction ID");
        // A caller may hold a long-lived store while another connection
        // appends through a fresh instance.  Derive the append head from the
        // durable directory so the stale instance cannot overwrite the other
        // event or fork the hash chain.  Its in-memory projections remain a
        // caller concern; authority-sensitive services use fresh projections
        // at their boundaries.
        Head durableHead = durableHead();
        long sequence = durableHead.sequence() + 1L;
        byte[] previous = durableHead.digest();
        PredictionEvent event = PredictionEvent.create(projectId, predictionId, sequence, type, actorNodeId,
                Objects.requireNonNull(payload, "payload"), previous, signer, clock.millis());
        if (!event.verify()) {
            throw new GeneralSecurityException("event signature verification failed");
        }
        projection.validate(event);
        coordinationProjection.validate(event);
        capabilityRequestProjection.validate(event);
        taskCompletionProjection.validate(event);
        collaborationProjection.validate(event);
        workGroupProjection.validate(event);
        contractProjection.validate(event);
        Path target = eventsDirectory.resolve(String.format("%020d.sce", sequence));
        Path temporary = eventsDirectory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, event.encoded(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        projection.apply(event);
        coordinationProjection.apply(event);
        capabilityRequestProjection.apply(event);
        taskCompletionProjection.apply(event);
        collaborationProjection.apply(event);
        workGroupProjection.apply(event);
        contractProjection.apply(event);
        events.add(event);
        return event;
    }

    @SuppressWarnings("DataFlowIssue")
    private synchronized Head durableHead() throws IOException, GeneralSecurityException {
        long sequence = 0L;
        byte[] digest = new byte[32];
        try (var files = Files.list(eventsDirectory)) {
            for (Path file : files.filter(path -> path.getFileName()
                            .toString()
                            .endsWith(".sce"))
                    .sorted(Comparator.comparing(path -> path.getFileName()
                            .toString()))
                    .toList()) {
                PredictionEvent event = PredictionEvent.decode(Files.readAllBytes(file));
                if (!event.projectId()
                        .equals(projectId) || event.sequence() != sequence + 1L
                        || !java.util.Arrays.equals(event.previousDigest(), digest) || !event.verify()) {
                    throw new IOException("invalid coordination event log");
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
     * Returns the project namespace served by this store.
     *
     * @return project identifier
     */
    public UUID projectId() {
        return projectId;
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

    private void load() throws IOException, GeneralSecurityException {
        List<Path> files;
        try (var stream = Files.list(eventsDirectory)) {
            files = stream.filter(path -> path.getFileName()
                            .toString()
                            .endsWith(".sce"))
                    .sorted(Comparator.comparing(path -> path.getFileName()
                            .toString()))
                    .toList();
        }
        long expected = 1;
        byte[] previous = new byte[32];
        for (Path file : files) {
            PredictionEvent event = PredictionEvent.decode(Files.readAllBytes(file));
            if (!event.projectId()
                    .equals(projectId) || event.sequence() != expected
                    || !java.util.Arrays.equals(event.previousDigest(), previous) || !event.verify()) {
                throw new IOException("invalid coordination event log");
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
            expected++;
        }
    }

    /** Represents the durable event-log head used for append validation. */
    private record Head(long sequence, byte[] digest) {

    }
}
