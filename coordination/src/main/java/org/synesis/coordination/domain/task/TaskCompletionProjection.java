package org.synesis.coordination.domain.task;

import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptRecord;
import org.synesis.coordination.domain.prediction.PredictionEvent;


import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptRecord;
import org.synesis.coordination.domain.prediction.PredictionEvent;



import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Deterministic task completion and integration projection over the shared event sequence.
 *
 * <p>Reconstructs task completion states, immutable task snapshots, active and historic
 * integration attempts, control branch advancement status, and session finalization state
 * strictly from signed coordination events.
 *
 * @since 1.0
 */
public final class TaskCompletionProjection {

    private final Map<UUID, TaskCompletionState> taskStates = new LinkedHashMap<>();
    private final Map<UUID, TaskSnapshotRecord> snapshotsByTask = new LinkedHashMap<>();
    private final Map<String, TaskSnapshotRecord> snapshotsById = new LinkedHashMap<>();
    private final Map<String, IntegrationAttemptRecord> attempts = new LinkedHashMap<>();
    private String activeAttemptId = null;
    private String lastControlHeadAdvanced = null;

    /**
     * Creates an empty task completion projection.
     */
    public TaskCompletionProjection() {
    }

    /**
     * Copy constructor for non-mutating validation.
     *
     * @param source source projection
     */
    private TaskCompletionProjection(TaskCompletionProjection source) {
        taskStates.putAll(source.taskStates);
        snapshotsByTask.putAll(source.snapshotsByTask);
        snapshotsById.putAll(source.snapshotsById);
        attempts.putAll(source.attempts);
        activeAttemptId = source.activeAttemptId;
        lastControlHeadAdvanced = source.lastControlHeadAdvanced;
    }

    /**
     * Applies one task completion or integration event to update this projection.
     *
     * @param event event to apply
     * @throws IOException when an event payload is malformed
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
        switch (event.type()) {
            case TASK_COMPLETION_REQUESTED -> processCompletionRequested(event);
            case TASK_SNAPSHOT_CREATED -> processSnapshotCreated(event);
            case TASK_WAITING_FOR_DEPENDENCIES -> processWaitingForDependencies(event);
            case INTEGRATION_ATTEMPT_STARTED -> processAttemptStarted(event);
            case INTEGRATION_ATTEMPT_FAILED -> processAttemptFailed(event);
            case INTEGRATION_CONFLICTED -> processAttemptConflicted(event);
            case INTEGRATION_COMMIT_CREATED -> processCommitCreated(event);
            case CONTROL_BRANCH_ADVANCED -> processBranchAdvanced(event);
            case TASK_INTEGRATED -> processTaskIntegrated(event);
            case SESSION_FINALIZED -> processSessionFinalized(event);
            default -> {
            }
        }
    }

    /**
     * Validates one event without mutating this projection.
     *
     * @param event event to validate
     * @throws IOException when the event payload or state transition is invalid
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        TaskCompletionProjection candidate = new TaskCompletionProjection(this);
        candidate.apply(event);
    }

    /**
     * Returns the completion state for a given task, default ACTIVE.
     *
     * @param taskId task UUID
     * @return task completion state
     */
    public synchronized TaskCompletionState taskState(UUID taskId) {
        return taskStates.getOrDefault(taskId, TaskCompletionState.ACTIVE);
    }

    /**
     * Looks up an immutable task snapshot record by task UUID.
     *
     * @param taskId task UUID
     * @return snapshot record when present
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotForTask(UUID taskId) {
        return Optional.ofNullable(snapshotsByTask.get(taskId));
    }

    /**
     * Looks up an immutable task snapshot record by snapshot ID string.
     *
     * @param snapshotId snapshot locator string
     * @return snapshot record when present
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotById(String snapshotId) {
        return Optional.ofNullable(snapshotsById.get(snapshotId));
    }

    /**
     * Returns the latest snapshot published by a given worker ID.
     *
     * @param nodeId   worker node ID
     * @param workerId worker ID
     * @return snapshot record when found
     */
    public synchronized Optional<TaskSnapshotRecord> findLatestSnapshotForWorker(String nodeId, String workerId) {
        if (nodeId == null || workerId == null) {
            return Optional.empty();
        }
        for (TaskSnapshotRecord rec : snapshotsByTask.values()) {
            if (rec.nodeId().equals(nodeId) && rec.workerId().equals(workerId)) {
                return Optional.of(rec);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all immutable task snapshot records.
     *
     * @return list of snapshot records
     */
    public synchronized List<TaskSnapshotRecord> allSnapshots() {
        return List.copyOf(snapshotsByTask.values());
    }

    /**
     * Returns the active integration attempt record, if one is currently in progress.
     *
     * @return active integration attempt record when present
     */
    public synchronized Optional<IntegrationAttemptRecord> activeIntegrationAttempt() {
        if (activeAttemptId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(attempts.get(activeAttemptId));
    }

    /**
     * Returns the last control HEAD SHA advanced by integration, if any.
     *
     * @return last advanced commit SHA, or null
     */
    public synchronized String lastControlHeadAdvanced() {
        return lastControlHeadAdvanced;
    }

    private void processCompletionRequested(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        taskStates.put(payload.taskId(), TaskCompletionState.COMPLETION_REQUESTED);
    }

    private void processSnapshotCreated(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        TaskSnapshotRecord rec = new TaskSnapshotRecord(
                payload.taskId(), payload.snapshotId(), payload.nodeId(), payload.supervisorId(),
                payload.workerId(), payload.providerSessionId(), payload.baseCommit(), payload.commitSha(),
                payload.changedPaths(), payload.capabilityDependencies(), payload.summary(),
                event.createdAtEpochMillis());

        snapshotsByTask.put(payload.taskId(), rec);
        snapshotsById.put(payload.snapshotId(), rec);
        taskStates.put(payload.taskId(), TaskCompletionState.SNAPSHOT_READY);
    }

    private void processWaitingForDependencies(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        taskStates.put(payload.taskId(), TaskCompletionState.WAITING_FOR_DEPENDENCIES);
    }

    private void processAttemptStarted(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord rec = new IntegrationAttemptRecord(
                payload.attemptId(), payload.projectId(), payload.taskSnapshotIds(),
                payload.expectedControlHead(), payload.integrationCommitSha(),
                "started", "", event.createdAtEpochMillis(), 0L);
        attempts.put(payload.attemptId(), rec);
        activeAttemptId = payload.attemptId();

        // Update task states to INTEGRATING
        for (String snapId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snap = snapshotsById.get(snapId);
            if (snap != null) {
                taskStates.put(snap.taskId(), TaskCompletionState.INTEGRATING);
            }
        }
    }

    private void processAttemptFailed(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord current = attempts.get(payload.attemptId());
        if (current != null) {
            attempts.put(payload.attemptId(), new IntegrationAttemptRecord(
                    current.attemptId(), current.projectId(), current.taskSnapshotIds(),
                    current.expectedControlHead(), current.integrationCommitSha(),
                    "failed", payload.failureReason(), current.startedAtMillis(), event.createdAtEpochMillis()));
        }
        if (payload.attemptId().equals(activeAttemptId)) {
            activeAttemptId = null;
        }
        for (String snapId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snap = snapshotsById.get(snapId);
            if (snap != null) {
                taskStates.put(snap.taskId(), TaskCompletionState.INTEGRATION_FAILED);
            }
        }
    }

    private void processAttemptConflicted(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord current = attempts.get(payload.attemptId());
        if (current != null) {
            attempts.put(payload.attemptId(), new IntegrationAttemptRecord(
                    current.attemptId(), current.projectId(), current.taskSnapshotIds(),
                    current.expectedControlHead(), current.integrationCommitSha(),
                    "conflict", payload.failureReason(), current.startedAtMillis(), event.createdAtEpochMillis()));
        }
        if (payload.attemptId().equals(activeAttemptId)) {
            activeAttemptId = null;
        }
        for (String snapId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snap = snapshotsById.get(snapId);
            if (snap != null) {
                taskStates.put(snap.taskId(), TaskCompletionState.INTEGRATION_FAILED);
            }
        }
    }

    private void processCommitCreated(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord current = attempts.get(payload.attemptId());
        if (current != null) {
            attempts.put(payload.attemptId(), new IntegrationAttemptRecord(
                    current.attemptId(), current.projectId(), current.taskSnapshotIds(),
                    current.expectedControlHead(), payload.integrationCommitSha(),
                    current.status(), current.failureReason(), current.startedAtMillis(), current.completedAtMillis()));
        }
    }

    private void processBranchAdvanced(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        lastControlHeadAdvanced = payload.integrationCommitSha();
        IntegrationAttemptRecord current = attempts.get(payload.attemptId());
        if (current != null) {
            attempts.put(payload.attemptId(), new IntegrationAttemptRecord(
                    current.attemptId(), current.projectId(), current.taskSnapshotIds(),
                    current.expectedControlHead(), payload.integrationCommitSha(),
                    "advanced", "", current.startedAtMillis(), event.createdAtEpochMillis()));
        }
        if (payload.attemptId().equals(activeAttemptId)) {
            activeAttemptId = null;
        }
    }

    private void processTaskIntegrated(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        taskStates.put(payload.taskId(), TaskCompletionState.INTEGRATED);
    }

    private void processSessionFinalized(PredictionEvent event) {
        try {
            TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
            taskStates.put(payload.taskId(), TaskCompletionState.INTEGRATED);
        } catch (Exception ignored) {
        }
    }
}
