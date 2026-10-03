package org.synesis.coordination.domain.task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.IntegrationAttemptId;
import org.synesis.coordination.domain.identity.NodeId;
import org.synesis.coordination.domain.identity.SnapshotId;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.domain.identity.WorkerId;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptRecord;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Deterministic task completion and integration projection over the shared event sequence.
 */
public final class TaskCompletionProjection {

    private final Map<TaskId, TaskCompletionState> taskStates = new LinkedHashMap<>();
    private final Map<TaskId, List<TaskSnapshotRecord>> snapshotsByTask = new LinkedHashMap<>();
    private final Map<SnapshotId, TaskSnapshotRecord> snapshotsById = new LinkedHashMap<>();
    private final Map<SnapshotId, TaskCompletionState> snapshotStates = new LinkedHashMap<>();
    private final Map<IntegrationAttemptId, IntegrationAttemptRecord> attempts = new LinkedHashMap<>();
    private final Map<TaskId, CompletionPreparedPayload> prepared = new LinkedHashMap<>();
    private final Map<PreparedRevisionKey, CompletionPreparedPayload> preparedByRevision = new LinkedHashMap<>();
    private final Map<SnapshotId, TaskCompletionState> integrationPriorStates = new LinkedHashMap<>();
    private IntegrationAttemptId activeAttemptId = null;
    private CommitId lastControlHeadAdvanced = null;

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
        source.snapshotsByTask.forEach((taskId, history) -> snapshotsByTask.put(taskId, List.copyOf(history)));
        snapshotsById.putAll(source.snapshotsById);
        snapshotStates.putAll(source.snapshotStates);
        attempts.putAll(source.attempts);
        prepared.putAll(source.prepared);
        preparedByRevision.putAll(source.preparedByRevision);
        integrationPriorStates.putAll(source.integrationPriorStates);
        activeAttemptId = source.activeAttemptId;
        lastControlHeadAdvanced = source.lastControlHeadAdvanced;
    }

    /**
     * Returns whether the task completion state can enter integration.
     */
    private static boolean isIntegrationEligibleState(TaskCompletionState state) {
        return state == TaskCompletionState.INTEGRATION_PENDING || state == TaskCompletionState.SNAPSHOT_READY
                || state == TaskCompletionState.READY_FOR_INTEGRATION || state == TaskCompletionState.REVIEW_ACCEPTED;
    }

    /**
     * Builds the key for one task revision.
     */
    private static PreparedRevisionKey revisionKey(TaskId taskId, WorkIntentId laneId, ClaimEpoch claimEpoch) {
        return new PreparedRevisionKey(taskId, laneId, claimEpoch);
    }

    /**
     * Applies one task completion or integration event.
     *
     * @param event event to apply
     * @throws IOException malformed or invalid transition
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        CoordinationNullException.requireNonNull(event, "event");
        switch (event.type()) {
            case TASK_COMPLETION_REQUESTED -> processCompletionRequested(event);
            case COMPLETION_PREPARED -> processCompletionPrepared(event);
            case COMPLETION_UNWOUND -> processCompletionUnwound(event);
            case TASK_SNAPSHOT_CREATED -> processSnapshotCreated(event);
            case REVIEW_VALIDATION_RECORDED -> processReviewValidation(event);
            case TASK_WAITING_FOR_DEPENDENCIES -> processWaitingForDependencies(event);
            case INTEGRATION_ATTEMPT_STARTED -> processAttemptStarted(event);
            case INTEGRATION_ATTEMPT_FAILED -> processAttemptFailed(event);
            case INTEGRATION_CONFLICTED -> processAttemptConflicted(event);
            case INTEGRATION_BLOCKED -> processIntegrationBlocked(event);
            case REPAIR_REQUIRED -> processRepairRequired(event);
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
     * @throws IOException malformed or invalid transition
     */
    public synchronized void validate(PredictionEvent event) throws IOException {
        new TaskCompletionProjection(this).apply(event);
    }

    /**
     * Returns the current completion state for a task.
     *
     * @param taskId task identity
     * @return latest snapshot state, or the non-snapshot task state
     */
    public synchronized TaskCompletionState taskState(UUID taskId) {
        if (taskId == null) {
            return TaskCompletionState.ACTIVE;
        }
        return taskState(new TaskId(taskId));
    }

    /**
     * Returns completion state using the task's typed identity.
     *
     * @param taskId task identity to look up
     * @return completion state using the task's typed identity.
     */
    public synchronized TaskCompletionState taskState(TaskId taskId) {
        TaskId identity = CoordinationNullException.requireNonNull(taskId, "task ID");
        List<TaskSnapshotRecord> history = snapshotsByTask.get(identity);
        if (history != null && !history.isEmpty()) {
            TaskSnapshotRecord latest = history.getLast();
            return snapshotStates.getOrDefault(latest.snapshotIdentity(),
                    taskStates.getOrDefault(identity, TaskCompletionState.ACTIVE));
        }
        return taskStates.getOrDefault(identity, TaskCompletionState.ACTIVE);
    }

    /**
     * Looks up the latest immutable task snapshot by task identity.
     *
     * @param taskId task identity
     * @return latest snapshot when present
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotForTask(UUID taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        return findSnapshotForTask(new TaskId(taskId));
    }

    /**
     * Looks up a task's latest snapshot using its typed identity.
     *
     * @param taskId task identity to look up
     * @return Looks up a task's latest snapshot using its typed identity.
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotForTask(TaskId taskId) {
        List<TaskSnapshotRecord> history = snapshotsByTask.get(CoordinationNullException.requireNonNull(taskId,
                "task ID"));
        return history == null || history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    /**
     * Looks up the immutable snapshot for one exact lane revision.
     *
     * @param taskId     task identity
     * @param laneId     WorkIntent identity
     * @param claimEpoch exact WorkIntent version
     * @return matching snapshot when present
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotForTaskRevision(UUID taskId,
            UUID laneId,
            long claimEpoch) {
        if (taskId == null || laneId == null) {
            return Optional.empty();
        }
        return findSnapshotForTaskRevision(new TaskId(taskId), new WorkIntentId(laneId), new ClaimEpoch(claimEpoch));
    }

    /**
     * Looks up a snapshot using typed task, lane, and claim-epoch identities.
     *
     * @param taskId     task identity to look up
     * @param laneId     work-intent lane identity
     * @param claimEpoch claim epoch fencing stale lane actions
     * @return Looks up a snapshot using typed task, lane, and claim-epoch identities.
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotForTaskRevision(TaskId taskId,
            WorkIntentId laneId,
            ClaimEpoch claimEpoch) {
        List<TaskSnapshotRecord> history = snapshotsByTask.get(CoordinationNullException.requireNonNull(taskId,
                "task ID"));
        if (history == null) {
            return Optional.empty();
        }
        return history.stream().filter(snapshot ->
                snapshot.provenance().laneIdentity().equals(CoordinationNullException.requireNonNull(laneId, "lane ID"))
                        && snapshot.provenance().claimEpochIdentity()
                        .equals(CoordinationNullException.requireNonNull(claimEpoch, "claim epoch"))).findFirst();
    }

    /**
     * Looks up an immutable task snapshot by snapshot ID.
     *
     * @param snapshotId snapshot identity
     * @return snapshot when present
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotById(String snapshotId) {
        return snapshotId == null ? Optional.empty() : findSnapshotById(new SnapshotId(snapshotId));
    }

    /**
     * Looks up a snapshot by its typed immutable identity.
     *
     * @param snapshotId immutable snapshot identity to look up
     * @return Looks up a snapshot by its typed immutable identity.
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotById(SnapshotId snapshotId) {
        return Optional.ofNullable(snapshotsById.get(CoordinationNullException.requireNonNull(snapshotId,
                "snapshot ID")));
    }

    /**
     * Returns the latest snapshot published by a worker.
     *
     * @param nodeId   worker node ID
     * @param workerId worker ID
     * @return latest matching snapshot when present
     */
    public synchronized Optional<TaskSnapshotRecord> findLatestSnapshotForWorker(String nodeId, String workerId) {
        if (nodeId == null || workerId == null) {
            return Optional.empty();
        }
        return findLatestSnapshotForWorker(new NodeId(nodeId), new WorkerId(workerId));
    }

    /**
     * Returns the latest snapshot published by a typed worker identity.
     *
     * @param nodeId   worker node identity
     * @param workerId worker identity
     * @return latest matching snapshot when present
     */
    public synchronized Optional<TaskSnapshotRecord> findLatestSnapshotForWorker(NodeId nodeId, WorkerId workerId) {
        NodeId nodeIdentity = CoordinationNullException.requireNonNull(nodeId, "node ID");
        WorkerId workerIdentity = CoordinationNullException.requireNonNull(workerId, "worker ID");
        List<TaskSnapshotRecord> history = allSnapshots();
        for (int index = history.size() - 1; index >= 0; index--) {
            TaskSnapshotRecord snapshot = history.get(index);
            if (snapshot.nodeIdentity().equals(nodeIdentity) && snapshot.workerIdentity().equals(workerIdentity)) {
                return Optional.of(snapshot);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns all immutable task snapshots, including rejected history.
     *
     * @return immutable snapshot history
     */
    public synchronized List<TaskSnapshotRecord> allSnapshots() {
        return snapshotsByTask.values().stream().flatMap(List::stream).toList();
    }

    /**
     * Returns the latest prepared completion for a task.
     *
     * @param taskId task identity
     * @return prepared completion when present
     */
    public synchronized Optional<CompletionPreparedPayload> findPrepared(UUID taskId) {
        return taskId == null ? Optional.empty() : findPrepared(new TaskId(taskId));
    }

    /**
     * Returns prepared completion evidence for a typed task identity.
     *
     * @param taskId task identity to look up
     * @return prepared completion evidence for a typed task identity.
     */
    public synchronized Optional<CompletionPreparedPayload> findPrepared(TaskId taskId) {
        return Optional.ofNullable(prepared.get(CoordinationNullException.requireNonNull(taskId, "task ID")));
    }

    /**
     * Returns prepared completion evidence for one exact lane revision.
     *
     * @param taskId     task identity
     * @param laneId     WorkIntent identity
     * @param claimEpoch exact WorkIntent version
     * @return matching prepared completion when present
     */
    public synchronized Optional<CompletionPreparedPayload> findPrepared(UUID taskId, UUID laneId, long claimEpoch) {
        if (taskId == null || laneId == null) {
            return Optional.empty();
        }
        return findPrepared(new TaskId(taskId), new WorkIntentId(laneId), new ClaimEpoch(claimEpoch));
    }

    /**
     * Looks up prepared completion evidence by typed lane revision.
     *
     * @param taskId     task identity to look up
     * @param laneId     work-intent lane identity
     * @param claimEpoch claim epoch fencing stale lane actions
     * @return Looks up prepared completion evidence by typed lane revision.
     */
    public synchronized Optional<CompletionPreparedPayload> findPrepared(TaskId taskId,
            WorkIntentId laneId,
            ClaimEpoch claimEpoch) {
        return Optional.ofNullable(preparedByRevision.get(revisionKey(CoordinationNullException.requireNonNull(taskId,
                        "task ID"),
                CoordinationNullException.requireNonNull(laneId, "lane ID"),
                CoordinationNullException.requireNonNull(claimEpoch, "claim epoch"))));
    }

    /**
     * Returns the latest prepared completion per task.
     *
     * @return immutable prepared completions
     */
    public synchronized List<CompletionPreparedPayload> allPrepared() {
        return List.copyOf(prepared.values());
    }

    /**
     * Returns snapshots currently eligible for guarded integration.
     *
     * @return immutable eligible snapshots
     */
    public synchronized List<TaskSnapshotRecord> readySnapshots() {
        return allSnapshots().stream()
                .filter(snapshot -> isIntegrationEligibleState(snapshotStates.get(snapshot.snapshotIdentity())))
                .toList();
    }

    /**
     * Returns snapshots currently eligible for the integration pump.
     *
     * @return immutable eligible snapshots
     */
    public synchronized List<TaskSnapshotRecord> eligibleSnapshots() {
        return readySnapshots();
    }

    /**
     * Returns snapshots waiting for a durable review decision.
     *
     * @return immutable review-pending snapshots
     */
    public synchronized List<TaskSnapshotRecord> pendingReviewSnapshots() {
        return allSnapshots().stream().filter(snapshot -> snapshotStates.get(snapshot.snapshotIdentity())
                == TaskCompletionState.REVIEW_PENDING).toList();
    }

    /**
     * Returns the durable state for one immutable snapshot.
     *
     * @param snapshotId snapshot identity
     * @return snapshot state when present
     */
    public synchronized Optional<TaskCompletionState> snapshotState(String snapshotId) {
        return snapshotId == null ? Optional.empty() : snapshotState(new SnapshotId(snapshotId));
    }

    /**
     * Returns the completion state for a typed snapshot identity.
     *
     * @param snapshotId immutable snapshot identity to look up
     * @return the completion state for a typed snapshot identity.
     */
    public synchronized Optional<TaskCompletionState> snapshotState(SnapshotId snapshotId) {
        return Optional.ofNullable(snapshotStates.get(CoordinationNullException.requireNonNull(snapshotId,
                "snapshot ID")));
    }

    /**
     * Returns the active integration attempt, if any.
     *
     * @return active attempt when present
     */
    public synchronized Optional<IntegrationAttemptRecord> activeIntegrationAttempt() {
        if (activeAttemptId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(attempts.get(activeAttemptId));
    }

    /**
     * Returns the last control HEAD advanced by integration.
     *
     * @return last advanced commit or {@code null}
     */
    public synchronized String lastControlHeadAdvanced() {
        return lastControlHeadAdvanced == null ? null : lastControlHeadAdvanced.value();
    }

    /**
     * Applies the completion requested event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processCompletionRequested(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        taskStates.put(payload.taskIdentity(), TaskCompletionState.COMPLETION_REQUESTED);
    }

    /**
     * Applies the completion prepared event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processCompletionPrepared(PredictionEvent event) throws IOException {
        CompletionPreparedPayload payload = CompletionPreparedPayload.decode(event.payload());
        prepared.put(payload.taskIdentity(), payload);
        preparedByRevision.put(revisionKey(payload.taskIdentity(),
                payload.laneIdentity(),
                payload.claimEpochIdentity()), payload);
        taskStates.put(payload.taskIdentity(), TaskCompletionState.COMPLETION_PREPARED);
    }

    /**
     * Applies the completion unwound event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processCompletionUnwound(PredictionEvent event) throws IOException {
        CompletionUnwoundPayload payload = CompletionUnwoundPayload.decode(event.payload());
        CompletionPreparedPayload preparedPayload = payload.prepared();
        PreparedRevisionKey key = revisionKey(preparedPayload.taskIdentity(),
                preparedPayload.laneIdentity(),
                preparedPayload.claimEpochIdentity());
        CompletionPreparedPayload current = preparedByRevision.get(key);
        if (current == null || !current.equals(payload.prepared())) {
            throw new CoordinationException("COMPLETION_PREPARATION_MISMATCH");
        }
        if (!snapshotsByTask.getOrDefault(preparedPayload.taskIdentity(), List.of()).isEmpty()) {
            throw new CoordinationException("PUBLISHED_COMPLETION_CANNOT_UNWIND");
        }
        preparedByRevision.remove(key);
        if (prepared.get(preparedPayload.taskIdentity()).equals(preparedPayload)) {
            prepared.remove(preparedPayload.taskIdentity());
        }
        taskStates.put(preparedPayload.taskIdentity(), TaskCompletionState.ACTIVE);
    }

    /**
     * Applies the snapshot created event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processSnapshotCreated(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        if (snapshotsById.containsKey(payload.snapshotIdentity())) {
            throw new CoordinationException("SNAPSHOT_ID_EXISTS");
        }
        List<TaskSnapshotRecord> history = new ArrayList<>(snapshotsByTask.getOrDefault(payload.taskIdentity(),
                List.of()));
        if (history.stream()
                .anyMatch(existing -> existing.provenance().laneIdentity().equals(payload.provenance().laneIdentity())
                        && existing.provenance().claimEpochIdentity()
                        .equals(payload.provenance().claimEpochIdentity()))) {
            throw new CoordinationException("SNAPSHOT_REVISION_EXISTS");
        }
        TaskSnapshotRecord record = new TaskSnapshotRecord(payload.taskId(),
                payload.snapshotId(),
                payload.nodeId(),
                payload.supervisorId(),
                payload.workerId(),
                payload.providerSessionId(),
                payload.baseCommit(),
                payload.commitSha(),
                payload.changedPaths(),
                payload.capabilityDependencies(),
                payload.summary(),
                event.createdAtEpochMillis(),
                payload.provenance(),
                payload.reviewRequired());
        history.add(record);
        snapshotsByTask.put(payload.taskIdentity(), List.copyOf(history));
        snapshotsById.put(payload.snapshotIdentity(), record);
        TaskCompletionState state =
                payload.reviewRequired() ? TaskCompletionState.REVIEW_PENDING : TaskCompletionState.INTEGRATION_PENDING;
        snapshotStates.put(payload.snapshotIdentity(), state);
        taskStates.put(payload.taskIdentity(), state);
    }

    /**
     * Applies the review validation event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processReviewValidation(PredictionEvent event) throws IOException {
        ReviewValidationPayload payload = ReviewValidationPayload.decode(event.payload());
        TaskSnapshotRecord snapshot = snapshotsById.get(payload.snapshotIdentity());
        // Historical standalone review records remain replayable. New review
        // admission validates the exact snapshot before appending this event.
        if (snapshot == null) {
            return;
        }
        if (!snapshot.taskIdentity().equals(payload.taskIdentity()) || !snapshot.provenance().workGroupIdentity()
                .equals(payload.workGroupIdentity()) || !snapshot.provenance().laneIdentity()
                .equals(payload.targetIntentIdentity()) || !snapshot.provenance().claimEpochIdentity()
                .equals(payload.claimEpochIdentity())) {
            throw new CoordinationException("REVIEW_SNAPSHOT_BINDING_MISMATCH");
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotIdentity());
        if (current == TaskCompletionState.REVIEW_ACCEPTED || current == TaskCompletionState.REVIEW_REJECTED
                || current == TaskCompletionState.INTEGRATED) {
            throw new CoordinationException("REVIEW_DECISION_ALREADY_RECORDED");
        }
        TaskCompletionState next = "ACCEPTED".equals(payload.result()) ? TaskCompletionState.REVIEW_ACCEPTED
                : TaskCompletionState.REVIEW_REJECTED;
        setSnapshotState(snapshot, next);
    }

    /**
     * Applies the waiting for dependencies event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processWaitingForDependencies(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        taskStates.put(payload.taskIdentity(), TaskCompletionState.WAITING_FOR_DEPENDENCIES);
    }

    /**
     * Applies the attempt started event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processAttemptStarted(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord record = new IntegrationAttemptRecord(payload.attemptId(),
                payload.projectId(),
                payload.taskSnapshotIds(),
                payload.expectedControlHead(),
                payload.integrationCommitSha(),
                "started",
                "",
                event.createdAtEpochMillis(),
                0L);
        attempts.put(payload.identity(), record);
        activeAttemptId = payload.identity();
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(new SnapshotId(snapshotId));
            if (snapshot != null) {
                TaskCompletionState current = snapshotStates.get(snapshot.snapshotIdentity());
                if (!isIntegrationEligibleState(current)) {
                    throw new CoordinationException("REVIEW_ACCEPTANCE_REQUIRED");
                }
                integrationPriorStates.put(snapshot.snapshotIdentity(), current);
                setSnapshotState(snapshot, TaskCompletionState.INTEGRATING);
            }
        }
    }

    /**
     * Applies the attempt failed event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processAttemptFailed(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord current = attempts.get(payload.identity());
        if (current != null) {
            attempts.put(payload.identity(),
                    new IntegrationAttemptRecord(current.attemptId(),
                            current.projectId(),
                            current.taskSnapshotIds(),
                            current.expectedControlHead(),
                            current.integrationCommitSha(),
                            "pending".equalsIgnoreCase(payload.status()) ? "pending" : "failed",
                            payload.failureReason(),
                            current.startedAtMillis(),
                            event.createdAtEpochMillis()));
        }
        if (payload.identity().equals(activeAttemptId)) {
            activeAttemptId = null;
        }
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(new SnapshotId(snapshotId));
            if (snapshot != null) {
                TaskCompletionState state = "pending".equalsIgnoreCase(payload.status())
                        ? integrationPriorStates.remove(snapshot.snapshotIdentity())
                        : TaskCompletionState.INTEGRATION_FAILED;
                if (state == null) {
                    state = snapshot.reviewRequired() ? TaskCompletionState.REVIEW_ACCEPTED
                            : TaskCompletionState.INTEGRATION_PENDING;
                }
                setSnapshotState(snapshot, state);
            }
        }
    }

    /**
     * Applies the attempt conflicted event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processAttemptConflicted(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord current = attempts.get(payload.identity());
        if (current != null) {
            attempts.put(payload.identity(),
                    new IntegrationAttemptRecord(current.attemptId(),
                            current.projectId(),
                            current.taskSnapshotIds(),
                            current.expectedControlHead(),
                            current.integrationCommitSha(),
                            "conflict",
                            payload.failureReason(),
                            current.startedAtMillis(),
                            event.createdAtEpochMillis()));
        }
        if (payload.identity().equals(activeAttemptId)) {
            activeAttemptId = null;
        }
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(new SnapshotId(snapshotId));
            if (snapshot != null) {
                integrationPriorStates.remove(snapshot.snapshotIdentity());
                setSnapshotState(snapshot, TaskCompletionState.REPAIR_REQUIRED);
            }
        }
    }

    /**
     * Applies the integration blocked event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processIntegrationBlocked(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(new SnapshotId(snapshotId));
            if (snapshot != null) {
                setSnapshotState(snapshot, TaskCompletionState.INTEGRATION_BLOCKED);
            }
        }
    }

    /**
     * Applies the repair required event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processRepairRequired(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(new SnapshotId(snapshotId));
            if (snapshot != null) {
                setSnapshotState(snapshot, TaskCompletionState.REPAIR_REQUIRED);
            }
        }
    }

    /**
     * Applies the commit created event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processCommitCreated(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord current = attempts.get(payload.identity());
        if (current != null) {
            attempts.put(payload.identity(),
                    new IntegrationAttemptRecord(current.attemptId(),
                            current.projectId(),
                            current.taskSnapshotIds(),
                            current.expectedControlHead(),
                            payload.integrationCommitSha(),
                            current.status(),
                            current.failureReason(),
                            current.startedAtMillis(),
                            current.completedAtMillis()));
        }
    }

    /**
     * Applies the branch advanced event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processBranchAdvanced(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        lastControlHeadAdvanced = new CommitId(payload.integrationCommitSha());
        IntegrationAttemptRecord current = attempts.get(payload.identity());
        if (current != null) {
            attempts.put(payload.identity(),
                    new IntegrationAttemptRecord(current.attemptId(),
                            current.projectId(),
                            current.taskSnapshotIds(),
                            current.expectedControlHead(),
                            payload.integrationCommitSha(),
                            "advanced",
                            "",
                            current.startedAtMillis(),
                            event.createdAtEpochMillis()));
        }
        if (payload.identity().equals(activeAttemptId)) {
            activeAttemptId = null;
        }
    }

    /**
     * Applies the task integrated event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processTaskIntegrated(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        TaskSnapshotRecord snapshot = snapshotsById.get(payload.snapshotIdentity());
        if (snapshot == null) {
            taskStates.put(payload.taskIdentity(), TaskCompletionState.INTEGRATED);
            return;
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotIdentity());
        if (current == TaskCompletionState.REVIEW_REJECTED) {
            throw new CoordinationException("REJECTED_SNAPSHOT_CANNOT_INTEGRATE");
        }
        if (snapshot.reviewRequired() && current != TaskCompletionState.REVIEW_ACCEPTED
                && integrationPriorStates.get(snapshot.snapshotIdentity()) != TaskCompletionState.REVIEW_ACCEPTED) {
            throw new CoordinationException("REVIEW_ACCEPTANCE_REQUIRED");
        }
        setSnapshotState(snapshot, TaskCompletionState.INTEGRATED);
    }

    /**
     * Applies the session finalized event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processSessionFinalized(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload;
        try {
            payload = TaskSnapshotPayload.decode(event.payload());
        } catch (IOException legacyFinalization) {
            // Cancellation and pre-snapshot terminal events historically carry
            // a bounded diagnostic string rather than snapshot payload bytes.
            // They do not authorize integration and therefore have no
            // snapshot projection to update.
            return;
        }
        TaskSnapshotRecord snapshot = snapshotsById.get(payload.snapshotIdentity());
        if (snapshot == null) {
            taskStates.put(payload.taskIdentity(), TaskCompletionState.INTEGRATED);
            return;
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotIdentity());
        if (current == TaskCompletionState.REVIEW_REJECTED || (snapshot.reviewRequired()
                && current != TaskCompletionState.INTEGRATED && current != TaskCompletionState.REVIEW_ACCEPTED)) {
            throw new CoordinationException("REVIEW_ACCEPTANCE_REQUIRED");
        }
        setSnapshotState(snapshot, TaskCompletionState.INTEGRATED);
    }

    /**
     * Returns the newest snapshot for the requested task revision.
     */
    private Optional<TaskSnapshotRecord> latestSnapshot(TaskId taskId) {
        List<TaskSnapshotRecord> history = snapshotsByTask.get(CoordinationNullException.requireNonNull(taskId,
                "task ID"));
        return history == null || history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    /**
     * Updates the lifecycle state of the identified snapshot.
     */
    private void setSnapshotState(TaskSnapshotRecord snapshot, TaskCompletionState state) {
        snapshotStates.put(snapshot.snapshotIdentity(), state);
        if (latestSnapshot(snapshot.taskIdentity()).map(latest -> latest.snapshotIdentity()
                .equals(snapshot.snapshotIdentity())).orElse(false)) {
            taskStates.put(snapshot.taskIdentity(), state);
        }
    }

    /**
     * Composite identity for one task lane at one claim epoch.
     */
    private record PreparedRevisionKey(TaskId taskId, WorkIntentId laneId, ClaimEpoch claimEpoch) {

    }
}
