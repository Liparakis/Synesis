package org.synesis.coordination;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.synesis.link.identity.NodeIdentity;

/** Foreground supervisor facade for submitting predictions and consuming replay. */
public final class SupervisorInbox {
    private final Path inboxDirectory;
    private final UUID projectId;
    private final String supervisorId;
    private final String workerId;
    private final NodeIdentity identity;
    private final CoordinationService service;
    private long lastSequence;

    /**
     * Creates a local supervisor inbox.
     * @param localRoot local `.synesis` state root
     * @param projectId project identifier
     * @param supervisorId logical supervisor identifier
     * @param workerId logical worker identifier
     * @param identity node identity
     * @param service coordinator service
     * @throws IOException when the inbox directory cannot be created
     */
    public SupervisorInbox(Path localRoot, UUID projectId, String supervisorId, String workerId,
            NodeIdentity identity, CoordinationService service) throws IOException {
        this.inboxDirectory = Objects.requireNonNull(localRoot, "local root").resolve("supervisor").resolve("inbox");
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.supervisorId = requireText(supervisorId, "supervisor ID");
        this.workerId = requireText(workerId, "worker ID");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.service = Objects.requireNonNull(service, "service");
        Files.createDirectories(inboxDirectory);
    }

    /** Submits a prediction creation and routing pair.
     * @param contract prediction contract owned by this requester
     * @return creation event
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when signing/authentication fails
     */
    public PredictionEvent submit(PredictionContract contract) throws IOException, GeneralSecurityException {
        if (!contract.projectId().equals(projectId) || !contract.requesterNodeId().equals(identity.nodeId())
                || !contract.requesterSupervisorId().equals(supervisorId) || !contract.requesterWorkerId().equals(workerId)) {
            throw new IllegalArgumentException("contract requester does not match supervisor");
        }
        PredictionEvent created = command(contract.predictionId(), PredictionEventType.PREDICTION_CREATED,
                contract.encoded());
        command(contract.predictionId(), PredictionEventType.PREDICTION_ROUTED, new byte[0]);
        return created;
    }

    /** Accepts an exact request as the owner node.
     * @param predictionId prediction identifier
     * @return acceptance event
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when signing/authentication fails
     */
    public PredictionEvent acceptExact(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.ACCEPTED_EXACT, new byte[0]);
    }

    /** Marks a routed request as received by this supervisor.
     * @param predictionId prediction identifier
     * @return receipt event
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when signing/authentication fails
     */
    public PredictionEvent receive(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.REQUEST_RECEIVED, new byte[0]);
    }

    /** Records owner-approved implementation start.
     * @param predictionId prediction identifier
     * @return implementation event
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent startImplementation(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.IMPLEMENTATION_STARTED, new byte[0]);
    }

    /** Records a validated patch publication milestone.
     * @param predictionId prediction identifier
     * @return patch event
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent patchReady(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.PATCH_READY, new byte[0]);
    }

    /** Records that the capability is available to the requester.
     * @param predictionId prediction identifier
     * @return availability event
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent capabilityAvailable(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.CAPABILITY_AVAILABLE, new byte[0]);
    }

    /** Records requester validation start.
     * @param predictionId prediction identifier
     * @return validation event
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent validationStarted(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.VALIDATION_STARTED, new byte[0]);
    }

    /** Retires a successfully validated prediction.
     * @param predictionId prediction identifier
     * @return retirement event
     * @throws IOException when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent retire(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.SPECULATION_RETIRED, new byte[0]);
    }

    /** Replays events after the supervisor's local cursor and persists inbox copies.
     * @return newly observed events
     * @throws IOException when an inbox copy cannot be written
     */
    public List<PredictionEvent> drain() throws IOException {
        List<PredictionEvent> events = service.replayAfter(lastSequence);
        for (PredictionEvent event : events) {
            Files.write(inboxDirectory.resolve(String.format("%020d.sce", event.sequence())), event.encoded());
            lastSequence = event.sequence();
        }
        return events;
    }

    /** Returns the last consumed coordinator sequence.
     * @return sequence cursor
     */
    public long lastSequence() { return lastSequence; }

    private PredictionEvent command(UUID predictionId, PredictionEventType type, byte[] payload)
            throws IOException, GeneralSecurityException {
        CoordinationCommand command = CoordinationCommand.create(UUID.randomUUID(), projectId, predictionId, type,
                identity.nodeId(), payload, identity);
        return service.submit(command);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " required");
        return value;
    }
}
