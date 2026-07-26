package org.synesis.coordination.domain.task;

import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;


import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.PredictionContract;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;



import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.coordination.application.CoordinationService;

/**
 * Foreground supervisor facade for submitting predictions and consuming replay.
 */
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
     *
     * @param localRoot    local `.synesis` state root
     * @param projectId    project identifier
     * @param supervisorId logical supervisor identifier
     * @param workerId     logical worker identifier
     * @param identity     node identity
     * @param service      coordinator service
     * @throws IOException when the inbox directory cannot be created
     */
    public SupervisorInbox(Path localRoot, UUID projectId, String supervisorId, String workerId,
            NodeIdentity identity, CoordinationService service) throws IOException {
        this.inboxDirectory = Objects.requireNonNull(localRoot, "local root")
                .resolve("supervisor")
                .resolve("inbox");
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.supervisorId = requireText(supervisorId, "supervisor ID");
        this.workerId = requireText(workerId, "worker ID");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.service = Objects.requireNonNull(service, "service");
        Files.createDirectories(inboxDirectory);
        try (var files = Files.list(inboxDirectory)) {
            lastSequence = files.map(path -> path.getFileName()
                            .toString())
                    .filter(name -> name.endsWith(".sce"))
                    .map(name -> name.substring(0, name.length() - 4))
                    .mapToLong(value -> {
                        try {
                            return Long.parseLong(value);
                        } catch (NumberFormatException ignored) {
                            return 0;
                        }
                    })
                    .max()
                    .orElse(0);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " required");
        }
        return value;
    }

    /**
     * Submits a prediction creation and routing pair.
     *
     * @param contract prediction contract owned by this requester
     * @return creation event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing/authentication fails
     */
    public PredictionEvent submit(PredictionContract contract) throws IOException, GeneralSecurityException {
        if (!contract.projectId()
                .equals(projectId) || !contract.requesterNodeId()
                .equals(identity.nodeId())
                || !contract.requesterSupervisorId()
                .equals(supervisorId) || !contract.requesterWorkerId()
                .equals(workerId)) {
            throw new IllegalArgumentException("contract requester does not match supervisor");
        }
        PredictionEvent created = command(contract.predictionId(), PredictionEventType.PREDICTION_CREATED,
                contract.encoded());
        command(contract.predictionId(), PredictionEventType.PREDICTION_ROUTED, new byte[0]);
        return created;
    }

    /**
     * Creates a task as the current requester.
     *
     * @param task task declaration
     * @return creation event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent createTask(CoordinationTask task) throws IOException, GeneralSecurityException {
        if (!task.projectId()
                .equals(projectId) || !task.creatorNodeId()
                .equals(identity.nodeId())
                || !task.creatorSupervisorId()
                .equals(supervisorId) || !task.creatorWorkerId()
                .equals(workerId)) {
            throw new IllegalArgumentException("task creator does not match supervisor");
        }
        return command(task.taskId(), PredictionEventType.TASK_CREATED, task.encoded());
    }

    /**
     * Claims one task for the current supervisor.
     *
     * @param claim task claim
     * @return claim event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent claimTask(TaskClaim claim) throws IOException, GeneralSecurityException {
        if (!claim.ownerNodeId()
                .equals(identity.nodeId()) || !claim.ownerSupervisorId()
                .equals(supervisorId)
                || !claim.ownerWorkerId()
                .equals(workerId)) {
            throw new IllegalArgumentException("task claimant mismatch");
        }
        return command(claim.taskId(), PredictionEventType.TASK_CLAIMED, claim.encoded());
    }

    /**
     * Releases the current task claim.
     *
     * @param claim current task claim
     * @return release event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent releaseTask(TaskClaim claim) throws IOException, GeneralSecurityException {
        if (!claim.ownerNodeId()
                .equals(identity.nodeId()) || !claim.ownerSupervisorId()
                .equals(supervisorId)
                || !claim.ownerWorkerId()
                .equals(workerId)) {
            throw new IllegalArgumentException("task claimant mismatch");
        }
        return command(claim.taskId(), PredictionEventType.TASK_RELEASED, claim.encoded());
    }

    /**
     * Claims semantic ownership for the current task owner.
     *
     * @param claim ownership claim
     * @return ownership event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent claimOwnership(OwnershipClaim claim) throws IOException, GeneralSecurityException {
        if (!claim.ownerNodeId()
                .equals(identity.nodeId()) || !claim.ownerSupervisorId()
                .equals(supervisorId)) {
            throw new IllegalArgumentException("ownership claimant mismatch");
        }
        return command(claim.taskId(), PredictionEventType.OWNERSHIP_CLAIMED, claim.encoded());
    }

    /**
     * Releases semantic ownership for the current owner.
     *
     * @param claim current ownership claim
     * @return release event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent releaseOwnership(OwnershipClaim claim) throws IOException, GeneralSecurityException {
        if (!claim.ownerNodeId()
                .equals(identity.nodeId()) || !claim.ownerSupervisorId()
                .equals(supervisorId)) {
            throw new IllegalArgumentException("ownership claimant mismatch");
        }
        return command(claim.taskId(), PredictionEventType.OWNERSHIP_RELEASED, claim.encoded());
    }

    /**
     * Accepts an exact request as the owner node.
     *
     * @param predictionId prediction identifier
     * @return acceptance event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing/authentication fails
     */
    public PredictionEvent acceptExact(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.ACCEPTED_EXACT, new byte[0]);
    }

    /**
     * Marks a routed request as received by this supervisor.
     *
     * @param predictionId prediction identifier
     * @return receipt event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing/authentication fails
     */
    public PredictionEvent receive(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.REQUEST_RECEIVED, new byte[0]);
    }

    /**
     * Records owner-approved implementation start.
     *
     * @param predictionId prediction identifier
     * @return implementation event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent startImplementation(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.IMPLEMENTATION_STARTED, new byte[0]);
    }

    /**
     * Records a validated patch publication milestone.
     *
     * @param predictionId prediction identifier
     * @return patch event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent patchReady(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.PATCH_READY, new byte[0]);
    }

    /**
     * Records that the capability is available to the requester.
     *
     * @param predictionId prediction identifier
     * @return availability event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent capabilityAvailable(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.CAPABILITY_AVAILABLE, new byte[0]);
    }

    /**
     * Records requester validation start.
     *
     * @param predictionId prediction identifier
     * @return validation event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent validationStarted(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.VALIDATION_STARTED, new byte[0]);
    }

    /**
     * Retires a successfully validated prediction.
     *
     * @param predictionId prediction identifier
     * @return retirement event
     * @throws IOException              when persistence fails
     * @throws GeneralSecurityException when signing fails
     */
    public PredictionEvent retire(UUID predictionId) throws IOException, GeneralSecurityException {
        return command(predictionId, PredictionEventType.SPECULATION_RETIRED, new byte[0]);
    }

    /**
     * Replays events after the supervisor's local cursor and persists inbox copies.
     *
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

    /**
     * Returns the last consumed coordinator sequence.
     *
     * @return sequence cursor
     */
    public long lastSequence() {
        return lastSequence;
    }

    private PredictionEvent command(UUID predictionId, PredictionEventType type, byte[] payload)
            throws IOException, GeneralSecurityException {
        CoordinationCommand command = CoordinationCommand.create(UUID.randomUUID(), projectId, predictionId, type,
                identity.nodeId(), payload, identity);
        return service.submit(command);
    }
}
