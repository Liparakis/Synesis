package org.synesis.coordination.domain;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic task and ownership projection over the shared event sequence.
 */
public final class CoordinationProjection {

    private final Map<UUID, TaskView> tasks = new LinkedHashMap<>();
    private final Map<String, OwnershipClaim> ownership = new LinkedHashMap<>();

    /**
     * Creates an empty coordination projection.
     */
    public CoordinationProjection() {
    }

    private CoordinationProjection(CoordinationProjection source) {
        tasks.putAll(source.tasks);
        ownership.putAll(source.ownership);
    }

    private static byte[] commandPayload(PredictionEvent event) throws IOException {
        return CoordinationCommand.decode(event.payload())
                .payload();
    }

    /**
     * Applies one task or ownership event.
     *
     * @param event event to apply
     * @throws IOException when an event payload is malformed
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
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
        CoordinationProjection candidate = new CoordinationProjection(this);
        candidate.apply(event);
    }

    /**
     * Returns the projected task state.
     *
     * @param taskId task identifier
     * @return task when known
     */
    public synchronized Optional<TaskView> task(UUID taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * Returns the projected owner for a capability.
     *
     * @param capability capability name
     * @return ownership claim when known
     */
    public synchronized Optional<OwnershipClaim> ownership(String capability) {
        return Optional.ofNullable(ownership.get(capability));
    }

    /**
     * Returns a stable task snapshot.
     *
     * @return immutable task view
     */
    public synchronized Map<UUID, TaskView> tasks() {
        return Map.copyOf(tasks);
    }

    /**
     * Returns a stable ownership snapshot.
     *
     * @return immutable ownership view
     */
    public synchronized Map<String, OwnershipClaim> ownerships() {
        return Map.copyOf(ownership);
    }

    private void createTask(PredictionEvent event) throws IOException {
        CoordinationTask task = CoordinationTask.decode(commandPayload(event));
        if (!task.taskId()
                .equals(event.predictionId()) || tasks.containsKey(task.taskId())) {
            throw new IOException("INVALID_TASK_STATE");
        }
        tasks.put(task.taskId(), new TaskView(task, null));
    }

    private void claimTask(PredictionEvent event) throws IOException {
        TaskClaim claim = TaskClaim.decode(commandPayload(event));
        TaskView current = task(claim.taskId()).orElseThrow(() -> new IOException("TASK_NOT_FOUND"));
        if (current.ownerNodeId() != null || !claim.taskId()
                .equals(event.predictionId())) {
            throw new IOException("TASK_ALREADY_CLAIMED");
        }
        tasks.put(claim.taskId(), new TaskView(current.task(), claim));
    }

    private void releaseTask(PredictionEvent event) throws IOException {
        TaskView current = task(event.predictionId()).orElseThrow(() -> new IOException("TASK_NOT_FOUND"));
        tasks.put(event.predictionId(), new TaskView(current.task(), null));
    }

    private void claimOwnership(PredictionEvent event) throws IOException {
        OwnershipClaim claim = OwnershipClaim.decode(commandPayload(event));
        TaskView task = task(claim.taskId()).orElseThrow(() -> new IOException("TASK_NOT_FOUND"));
        if (!claim.taskId()
                .equals(event.predictionId()) || !claim.ownerNodeId()
                .equals(task.ownerNodeId())) {
            throw new IOException("OWNERSHIP_REQUIRES_TASK_CLAIM");
        }
        OwnershipClaim current = ownership.get(claim.capability());
        if (current != null && !current.ownerNodeId()
                .equals(claim.ownerNodeId())) {
            throw new IOException("OWNERSHIP_CONFLICT");
        }
        ownership.put(claim.capability(), claim);
    }

    private void releaseOwnership(PredictionEvent event) throws IOException {
        OwnershipClaim claim = OwnershipClaim.decode(commandPayload(event));
        OwnershipClaim current = ownership.get(claim.capability());
        if (current == null || !current.taskId()
                .equals(event.predictionId())) {
            throw new IOException("OWNERSHIP_NOT_FOUND");
        }
        ownership.remove(claim.capability());
    }

    /**
     * Projected task and optional claim.
     *
     * @param task  immutable task declaration
     * @param claim current claim, when assigned
     */
    public record TaskView(CoordinationTask task, TaskClaim claim) {

        /**
         * Returns the current owner node, or null when unclaimed.
         *
         * @return owner node ID
         */
        public String ownerNodeId() {
            return claim == null ? null : claim.ownerNodeId();
        }
    }
}
