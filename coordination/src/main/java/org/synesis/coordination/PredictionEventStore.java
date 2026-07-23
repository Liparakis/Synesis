package org.synesis.coordination;

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

import org.synesis.link.identity.NodeIdentity;

/**
 * Crash-safe per-project event store. Each event is one immutable file; the
 * directory is the append-only log and projection replay is deterministic.
 */
public final class PredictionEventStore {
    private final Path eventsDirectory;
    private final UUID projectId;
    private final Clock clock;
    private final PredictionProjection projection = new PredictionProjection();
    private final List<PredictionEvent> events = new ArrayList<>();

    /**
     * Opens or creates a project event store and replays its existing log.
     * @param root store root directory
     * @param projectId project identifier
     * @throws IOException when the event directory cannot be read or created
     * @throws GeneralSecurityException when an existing event signature is invalid
     */
    public PredictionEventStore(Path root, UUID projectId) throws IOException, GeneralSecurityException {
        this(root, projectId, Clock.systemUTC());
    }

    /**
     * Opens a store with a supplied clock for deterministic tests.
     * @param root store root directory
     * @param projectId project identifier
     * @param clock timestamp source
     * @throws IOException when the event directory cannot be read or created
     * @throws GeneralSecurityException when an existing event signature is invalid
     */
    public PredictionEventStore(Path root, UUID projectId, Clock clock) throws IOException, GeneralSecurityException {
        this.eventsDirectory = Objects.requireNonNull(root, "root").resolve("events");
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(eventsDirectory);
        load();
    }

    /**
     * Appends a signed event after validating sequence, hash chain, and state transition.
     * @param predictionId prediction identifier
     * @param type event type
     * @param actorNodeId actor node identifier
     * @param payload canonical event payload
     * @param signer node signing identity
     * @return the persisted event
     * @throws IOException when the event cannot be persisted
     * @throws GeneralSecurityException when signing or verification fails
     */
    public synchronized PredictionEvent append(UUID predictionId, PredictionEventType type,
            String actorNodeId, byte[] payload, NodeIdentity signer) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(predictionId, "prediction ID");
        long sequence = events.size() + 1L;
        byte[] previous = events.isEmpty() ? new byte[32] : events.get(events.size() - 1).digest();
        PredictionEvent event = PredictionEvent.create(projectId, predictionId, sequence, type, actorNodeId,
                Objects.requireNonNull(payload, "payload"), previous, signer, clock.millis());
        if (!event.verify()) throw new GeneralSecurityException("event signature verification failed");
        projection.validate(event);
        Path target = eventsDirectory.resolve(String.format("%020d.sce", sequence));
        Path temporary = eventsDirectory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, event.encoded(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target); }
        } finally { Files.deleteIfExists(temporary); }
        projection.apply(event);
        events.add(event);
        return event;
    }

    /**
     * Returns all verified events in sequence order.
     * @return immutable event list
     */
    public synchronized List<PredictionEvent> events() { return List.copyOf(events); }
    /**
     * Returns the current project sequence.
     * @return current head sequence
     */
    public synchronized long headSequence() { return events.size(); }

    /** Returns the project namespace served by this store.
     * @return project identifier
     */
    public UUID projectId() { return projectId; }
    /**
     * Returns the deterministic prediction projection.
     * @return live projection
     */
    public PredictionProjection projection() { return projection; }

    private void load() throws IOException, GeneralSecurityException {
        List<Path> files;
        try (var stream = Files.list(eventsDirectory)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".sce"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        long expected = 1;
        byte[] previous = new byte[32];
        for (Path file : files) {
            PredictionEvent event = PredictionEvent.decode(Files.readAllBytes(file));
            if (event.projectId().equals(projectId) == false || event.sequence() != expected
                    || !java.util.Arrays.equals(event.previousDigest(), previous) || !event.verify()) {
                throw new IOException("invalid coordination event log");
            }
            projection.apply(event); events.add(event); previous = event.digest(); expected++;
        }
    }
}
