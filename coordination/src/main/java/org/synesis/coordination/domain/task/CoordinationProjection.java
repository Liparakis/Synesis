package org.synesis.coordination.domain.task;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.identity.CapabilityId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.ownership.OwnershipClaim;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.MissingReferenceException;

/**
 * Deterministic task and ownership projection over the shared event sequence.
 */
public final class CoordinationProjection {

    private final Map<TaskId, CoordinationTaskView> tasks = new LinkedHashMap<>();
    private final Map<CapabilityId, OwnershipClaim> ownership = new LinkedHashMap<>();
    private final ApplyMode mode;

    /**
     * Creates an empty coordination projection.
     */
    public CoordinationProjection() {
        mode = ApplyMode.REPLAY;
    }

    /**
     * Validates and initializes this coordination projection.
     *
     * @param source current source intent
     */
    private CoordinationProjection(CoordinationProjection source, ApplyMode mode) {
        tasks.putAll(source.tasks);
        ownership.putAll(source.ownership);
        this.mode = mode;
    }

    /**
     * Decodes the signed command payload carried by an event.
     */
    private static byte[] commandPayload(PredictionEvent event) throws IOException {
        return CoordinationCommand.decode(event.payload()).payload();
    }

    /**
     * Applies one task or ownership event.
     *
     * @param event event to apply
     * @throws IOException when an event payload is malformed
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        CoordinationNullException.requireNonNull(event, "event");
        switch (event.type()) {
            case TASK_CREATED -> createTask(event);
            case TASK_CLAIMED -> claimTask(event);
            case TASK_RELEASED -> releaseTask(event);
            case OWNERSHIP_CLAIMED -> claimOwnership(event);
            case OWNERSHIP_RELEASED -> releaseOwnership(event);
            default -> {
            }
        }
    }

    /**
     * Validates one task or ownership event without mutating this projection.
     *
     * @param event event to validate
     * @throws IOException when the event payload or state transition is invalid
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        validate(event, ApplyMode.CHECK);
    }

    /**
     * Applies an event to a copy of this projection, judged as {@code mode} says (ADR-0100, "Every rule an arriving
     * entry can reach").
     *
     * @param event event to validate
     * @param mode  how the event is judged
     * @throws IOException when the event payload or state transition is invalid, or names a task not seen yet
     */
    public synchronized void validate(PredictionEvent event, ApplyMode mode) throws IOException {
        new CoordinationProjection(this, mode).apply(event);
    }

    /**
     * Returns the projected task state.
     *
     * @param taskId task identifier
     * @return task when known
     */
    public synchronized Optional<CoordinationTaskView> task(UUID taskId) {
        return task(new TaskId(CoordinationNullException.requireNonNull(taskId, "task ID")));
    }

    /**
     * Returns the projected task state by typed identity.
     *
     * @param taskId task identity
     * @return task when known
     */
    public synchronized Optional<CoordinationTaskView> task(TaskId taskId) {
        return Optional.ofNullable(tasks.get(CoordinationNullException.requireNonNull(taskId, "task ID")));
    }

    /**
     * Returns the projected owner for a capability.
     *
     * @param capability capability name
     * @return ownership claim when known
     */
    public synchronized Optional<OwnershipClaim> ownership(String capability) {
        return capability == null ? Optional.empty() : ownership(new CapabilityId(capability));
    }

    /**
     * Returns the projected owner for a typed capability identity.
     *
     * @param capability capability identity
     * @return ownership claim when known
     */
    public synchronized Optional<OwnershipClaim> ownership(CapabilityId capability) {
        return Optional.ofNullable(ownership.get(CoordinationNullException.requireNonNull(capability,
                "capability ID")));
    }

    /**
     * Returns a stable task snapshot.
     *
     * @return immutable task view
     */
    public synchronized Map<UUID, CoordinationTaskView> tasks() {
        Map<UUID, CoordinationTaskView> snapshot = new LinkedHashMap<>();
        tasks.forEach((id, view) -> snapshot.put(id.value(), view));
        return Map.copyOf(snapshot);
    }

    /**
     * Returns a stable ownership snapshot.
     *
     * @return immutable ownership view
     */
    public synchronized Map<String, OwnershipClaim> ownerships() {
        Map<String, OwnershipClaim> snapshot = new LinkedHashMap<>();
        ownership.forEach((capability, claim) -> snapshot.put(capability.value(), claim));
        return Map.copyOf(snapshot);
    }

    /**
     * Returns a stable ownership snapshot keyed by typed capability identity.
     *
     * @return immutable typed ownership view
     */
    public synchronized Map<CapabilityId, OwnershipClaim> ownershipsByCapability() {
        return Map.copyOf(ownership);
    }

    /**
     * Applies the create task transition to the current coordination projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void createTask(PredictionEvent event) throws IOException {
        CoordinationTask task = CoordinationTask.decode(commandPayload(event));
        if (!task.taskId().equals(event.predictionId()) || tasks.containsKey(task.taskIdentity())) {
            throw new CoordinationException("INVALID_TASK_STATE");
        }
        tasks.put(task.taskIdentity(), new CoordinationTaskView(task, null));
    }

    /**
     * Applies the claim task transition to the current coordination projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void claimTask(PredictionEvent event) throws IOException {
        TaskClaim claim = TaskClaim.decode(commandPayload(event));
        CoordinationTaskView current = task(claim.taskIdentity()).orElseThrow(() -> new MissingReferenceException(
                "TASK_NOT_FOUND"));
        if (!claim.taskId().equals(event.predictionId())) {
            throw new CoordinationException("TASK_ALREADY_CLAIMED");
        }
        if (current.ownerNodeId() != null) {
            if (mode.checking()) {
                throw new CoordinationException("TASK_ALREADY_CLAIMED");
            }
            // Two computers claimed it apart (ADR-0100 row 9): the lower node keeps it, in either order.
            if (current.ownerNodeId().compareTo(claim.ownerNodeId()) <= 0) {
                return;
            }
        }
        tasks.put(claim.taskIdentity(), new CoordinationTaskView(current.task(), claim));
    }

    /**
     * Applies the release task lifecycle transition after validating its authority.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void releaseTask(PredictionEvent event) throws IOException {
        TaskId taskId = new TaskId(event.predictionId());
        CoordinationTaskView current = task(taskId).orElseThrow(() -> new MissingReferenceException("TASK_NOT_FOUND"));
        if (!mode.checking() && current.ownerNodeId() != null && !current.ownerNodeId().equals(event.actorNodeId())) {
            // A release by a computer that lost the claim to a lower node releases nothing (ADR-0100 row 9).
            return;
        }
        tasks.put(taskId, new CoordinationTaskView(current.task(), null));
    }

    /**
     * Applies the claim ownership transition to the current coordination projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void claimOwnership(PredictionEvent event) throws IOException {
        OwnershipClaim claim = OwnershipClaim.decode(commandPayload(event));
        CoordinationTaskView task = task(claim.taskIdentity()).orElseThrow(() -> new MissingReferenceException(
                "TASK_NOT_FOUND"));
        if (!claim.taskIdentity().value().equals(event.predictionId()) || task.ownerNode()
                .filter(claim.ownerNode()::equals).isEmpty()) {
            throw new CoordinationException("OWNERSHIP_REQUIRES_TASK_CLAIM");
        }
        CapabilityId capability = claim.capabilityIdentity();
        OwnershipClaim current = ownership.get(capability);
        if (current != null && !current.ownerNodeId().equals(claim.ownerNodeId())) {
            if (mode.checking()) {
                throw new CoordinationException("OWNERSHIP_CONFLICT");
            }
            // Two computers claimed it apart: the lower node keeps it, in either order.
            if (current.ownerNodeId().compareTo(claim.ownerNodeId()) < 0) {
                return;
            }
        }
        ownership.put(capability, claim);
    }

    /**
     * Applies the release ownership lifecycle transition after validating its authority.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void releaseOwnership(PredictionEvent event) throws IOException {
        OwnershipClaim claim = OwnershipClaim.decode(commandPayload(event));
        CapabilityId capability = claim.capabilityIdentity();
        OwnershipClaim current = ownership.get(capability);
        if (current == null || !current.taskIdentity().value().equals(event.predictionId())) {
            throw new CoordinationException("OWNERSHIP_NOT_FOUND");
        }
        ownership.remove(capability);
    }

}
