package org.synesis.coordination.domain.task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptPayload;
import org.synesis.coordination.domain.integration.IntegrationAttemptRecord;
import org.synesis.coordination.domain.prediction.PredictionEvent;

/**
 * Deterministic task completion and integration projection over the shared event sequence.
 */
public final class TaskCompletionProjection {

    private final Map<UUID, TaskCompletionState> taskStates = new LinkedHashMap<>();
    private final Map<UUID, List<TaskSnapshotRecord>> snapshotsByTask = new LinkedHashMap<>();
    private final Map<String, TaskSnapshotRecord> snapshotsById = new LinkedHashMap<>();
    private final Map<String, TaskCompletionState> snapshotStates = new LinkedHashMap<>();
    private final Map<String, IntegrationAttemptRecord> attempts = new LinkedHashMap<>();
    private final Map<UUID, CompletionPreparedPayload> prepared = new LinkedHashMap<>();
    private final Map<String, CompletionPreparedPayload> preparedByRevision = new LinkedHashMap<>();
    private final Map<String, TaskCompletionState> integrationPriorStates = new LinkedHashMap<>();
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

    private static boolean isIntegrationEligibleState(TaskCompletionState state) {
        return state == TaskCompletionState.INTEGRATION_PENDING
                || state == TaskCompletionState.SNAPSHOT_READY
                || state == TaskCompletionState.READY_FOR_INTEGRATION
                || state == TaskCompletionState.REVIEW_ACCEPTED;
    }

    private static String revisionKey(UUID taskId, UUID laneId, long claimEpoch) {
        return taskId + ":" + laneId + ":" + claimEpoch;
    }

    /**
     * Applies one task completion or integration event.
     *
     * @param event event to apply
     * @throws IOException malformed or invalid transition
     */
    public synchronized void apply(PredictionEvent event) throws IOException {
        Objects.requireNonNull(event, "event");
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
        List<TaskSnapshotRecord> history = snapshotsByTask.get(taskId);
        if (history != null && !history.isEmpty()) {
            TaskSnapshotRecord latest = history.getLast();
            return snapshotStates.getOrDefault(latest.snapshotId(),
                    taskStates.getOrDefault(taskId, TaskCompletionState.ACTIVE));
        }
        return taskStates.getOrDefault(taskId, TaskCompletionState.ACTIVE);
    }

    /**
     * Looks up the latest immutable task snapshot by task identity.
     *
     * @param taskId task identity
     * @return latest snapshot when present
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotForTask(UUID taskId) {
        List<TaskSnapshotRecord> history = snapshotsByTask.get(taskId);
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
    public synchronized Optional<TaskSnapshotRecord> findSnapshotForTaskRevision(
            UUID taskId, UUID laneId, long claimEpoch) {
        List<TaskSnapshotRecord> history = snapshotsByTask.get(taskId);
        if (history == null) {
            return Optional.empty();
        }
        return history.stream()
                .filter(snapshot -> snapshot.provenance()
                        .laneId()
                        .equals(laneId)
                        && snapshot.provenance()
                        .claimEpoch() == claimEpoch)
                .findFirst();
    }

    /**
     * Looks up a snapshot by exact lane revision across task identities.
     *
     * @param laneId     WorkIntent identity
     * @param claimEpoch exact WorkIntent version
     * @return matching snapshot when present
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotForLaneRevision(UUID laneId, long claimEpoch) {
        return snapshotsById.values()
                .stream()
                .filter(snapshot -> snapshot.provenance()
                        .laneId()
                        .equals(laneId)
                        && snapshot.provenance()
                        .claimEpoch() == claimEpoch)
                .findFirst();
    }

    /**
     * Looks up an immutable task snapshot by snapshot ID.
     *
     * @param snapshotId snapshot identity
     * @return snapshot when present
     */
    public synchronized Optional<TaskSnapshotRecord> findSnapshotById(String snapshotId) {
        return Optional.ofNullable(snapshotsById.get(snapshotId));
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
        List<TaskSnapshotRecord> history = allSnapshots();
        for (int index = history.size() - 1; index >= 0; index--) {
            TaskSnapshotRecord snapshot = history.get(index);
            if (snapshot.nodeId()
                    .equals(nodeId) && snapshot.workerId()
                    .equals(workerId)) {
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
        return snapshotsByTask.values()
                .stream()
                .flatMap(List::stream)
                .toList();
    }

    /**
     * Returns the latest prepared completion for a task.
     *
     * @param taskId task identity
     * @return prepared completion when present
     */
    public synchronized Optional<CompletionPreparedPayload> findPrepared(UUID taskId) {
        return Optional.ofNullable(prepared.get(taskId));
    }

    /**
     * Returns prepared completion evidence for one exact lane revision.
     *
     * @param taskId     task identity
     * @param laneId     WorkIntent identity
     * @param claimEpoch exact WorkIntent version
     * @return matching prepared completion when present
     */
    public synchronized Optional<CompletionPreparedPayload> findPrepared(
            UUID taskId, UUID laneId, long claimEpoch) {
        return Optional.ofNullable(preparedByRevision.get(revisionKey(taskId, laneId, claimEpoch)));
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
                .filter(snapshot -> isIntegrationEligibleState(snapshotStates.get(snapshot.snapshotId())))
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
        return allSnapshots().stream()
                .filter(snapshot -> snapshotStates.get(snapshot.snapshotId()) == TaskCompletionState.REVIEW_PENDING)
                .toList();
    }

    /**
     * Returns snapshots rejected by a durable review decision.
     *
     * @return immutable rejected snapshots
     */
    public synchronized List<TaskSnapshotRecord> rejectedSnapshots() {
        return allSnapshots().stream()
                .filter(snapshot -> snapshotStates.get(snapshot.snapshotId()) == TaskCompletionState.REVIEW_REJECTED)
                .toList();
    }

    /**
     * Returns the durable state for one immutable snapshot.
     *
     * @param snapshotId snapshot identity
     * @return snapshot state when present
     */
    public synchronized Optional<TaskCompletionState> snapshotState(String snapshotId) {
        return Optional.ofNullable(snapshotStates.get(snapshotId));
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
        return lastControlHeadAdvanced;
    }

    private void processCompletionRequested(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        taskStates.put(payload.taskId(), TaskCompletionState.COMPLETION_REQUESTED);
    }

    private void processCompletionPrepared(PredictionEvent event) throws IOException {
        CompletionPreparedPayload payload = CompletionPreparedPayload.decode(event.payload());
        prepared.put(payload.taskId(), payload);
        preparedByRevision.put(revisionKey(payload.taskId(), payload.laneId(), payload.claimEpoch()), payload);
        taskStates.put(payload.taskId(), TaskCompletionState.COMPLETION_PREPARED);
    }

    private void processCompletionUnwound(PredictionEvent event) throws IOException {
        CompletionUnwoundPayload payload = CompletionUnwoundPayload.decode(event.payload());
        String key = revisionKey(payload.prepared()
                        .taskId(),
                payload.prepared()
                        .laneId(),
                payload.prepared()
                        .claimEpoch());
        CompletionPreparedPayload current = preparedByRevision.get(key);
        if (current == null || !current.equals(payload.prepared())) {
            throw new IOException("COMPLETION_PREPARATION_MISMATCH");
        }
        if (!snapshotsByTask.getOrDefault(payload.prepared()
                        .taskId(), List.of())
                .isEmpty()) {
            throw new IOException("PUBLISHED_COMPLETION_CANNOT_UNWIND");
        }
        preparedByRevision.remove(key);
        if (prepared.get(payload.prepared()
                        .taskId())
                .equals(payload.prepared())) {
            prepared.remove(payload.prepared()
                    .taskId());
        }
        taskStates.put(payload.prepared()
                .taskId(), TaskCompletionState.ACTIVE);
    }

    private void processSnapshotCreated(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        if (snapshotsById.containsKey(payload.snapshotId())) {
            throw new IOException("SNAPSHOT_ID_EXISTS");
        }
        List<TaskSnapshotRecord> history = new ArrayList<>(snapshotsByTask.getOrDefault(payload.taskId(), List.of()));
        if (history.stream()
                .anyMatch(existing -> existing.provenance()
                        .laneId()
                        .equals(payload.provenance()
                                .laneId())
                        && existing.provenance()
                        .claimEpoch() == payload.provenance()
                        .claimEpoch())) {
            throw new IOException("SNAPSHOT_REVISION_EXISTS");
        }
        TaskSnapshotRecord record = new TaskSnapshotRecord(
                payload.taskId(), payload.snapshotId(), payload.nodeId(), payload.supervisorId(),
                payload.workerId(), payload.providerSessionId(), payload.baseCommit(), payload.commitSha(),
                payload.changedPaths(), payload.capabilityDependencies(), payload.summary(),
                event.createdAtEpochMillis(), payload.provenance(), payload.reviewRequired());
        history.add(record);
        snapshotsByTask.put(payload.taskId(), List.copyOf(history));
        snapshotsById.put(payload.snapshotId(), record);
        TaskCompletionState state = payload.reviewRequired()
                ? TaskCompletionState.REVIEW_PENDING : TaskCompletionState.INTEGRATION_PENDING;
        snapshotStates.put(payload.snapshotId(), state);
        taskStates.put(payload.taskId(), state);
    }

    private void processReviewValidation(PredictionEvent event) throws IOException {
        ReviewValidationPayload payload = ReviewValidationPayload.decode(event.payload());
        TaskSnapshotRecord snapshot = snapshotsById.get(payload.snapshotId());
        // Historical standalone review records remain replayable. New review
        // admission validates the exact snapshot before appending this event.
        if (snapshot == null) {
            return;
        }
        if (!snapshot.taskId()
                .equals(payload.taskId())
                || !snapshot.provenance()
                .workGroupId()
                .equals(payload.workGroupId())
                || !snapshot.provenance()
                .laneId()
                .equals(payload.targetIntentId())
                || snapshot.provenance()
                .claimEpoch() != payload.claimEpoch()) {
            throw new IOException("REVIEW_SNAPSHOT_BINDING_MISMATCH");
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotId());
        if (current == TaskCompletionState.REVIEW_ACCEPTED
                || current == TaskCompletionState.REVIEW_REJECTED
                || current == TaskCompletionState.INTEGRATED) {
            throw new IOException("REVIEW_DECISION_ALREADY_RECORDED");
        }
        TaskCompletionState next = "ACCEPTED".equals(payload.result())
                ? TaskCompletionState.REVIEW_ACCEPTED : TaskCompletionState.REVIEW_REJECTED;
        setSnapshotState(snapshot, next);
    }

    private void processWaitingForDependencies(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        taskStates.put(payload.taskId(), TaskCompletionState.WAITING_FOR_DEPENDENCIES);
    }

    private void processAttemptStarted(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord record = new IntegrationAttemptRecord(
                payload.attemptId(), payload.projectId(), payload.taskSnapshotIds(),
                payload.expectedControlHead(), payload.integrationCommitSha(),
                "started", "", event.createdAtEpochMillis(), 0L);
        attempts.put(payload.attemptId(), record);
        activeAttemptId = payload.attemptId();
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(snapshotId);
            if (snapshot != null) {
                TaskCompletionState current = snapshotStates.get(snapshot.snapshotId());
                if (!isIntegrationEligibleState(current)) {
                    throw new IOException("REVIEW_ACCEPTANCE_REQUIRED");
                }
                integrationPriorStates.put(snapshot.snapshotId(), current);
                setSnapshotState(snapshot, TaskCompletionState.INTEGRATING);
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
                    "pending".equalsIgnoreCase(payload.status()) ? "pending" : "failed",
                    payload.failureReason(), current.startedAtMillis(), event.createdAtEpochMillis()));
        }
        if (payload.attemptId()
                .equals(activeAttemptId)) {
            activeAttemptId = null;
        }
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(snapshotId);
            if (snapshot != null) {
                TaskCompletionState state = "pending".equalsIgnoreCase(payload.status())
                        ? integrationPriorStates.remove(snapshot.snapshotId())
                        : TaskCompletionState.INTEGRATION_FAILED;
                if (state == null) {
                    state = snapshot.reviewRequired()
                            ? TaskCompletionState.REVIEW_ACCEPTED : TaskCompletionState.INTEGRATION_PENDING;
                }
                setSnapshotState(snapshot, state);
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
        if (payload.attemptId()
                .equals(activeAttemptId)) {
            activeAttemptId = null;
        }
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(snapshotId);
            if (snapshot != null) {
                integrationPriorStates.remove(snapshot.snapshotId());
                setSnapshotState(snapshot, TaskCompletionState.REPAIR_REQUIRED);
            }
        }
    }

    private void processIntegrationBlocked(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(snapshotId);
            if (snapshot != null) {
                setSnapshotState(snapshot, TaskCompletionState.INTEGRATION_BLOCKED);
            }
        }
    }

    private void processRepairRequired(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        for (String snapshotId : payload.taskSnapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(snapshotId);
            if (snapshot != null) {
                setSnapshotState(snapshot, TaskCompletionState.REPAIR_REQUIRED);
            }
        }
    }

    private void processCommitCreated(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        IntegrationAttemptRecord current = attempts.get(payload.attemptId());
        if (current != null) {
            attempts.put(payload.attemptId(), new IntegrationAttemptRecord(
                    current.attemptId(), current.projectId(), current.taskSnapshotIds(),
                    current.expectedControlHead(), payload.integrationCommitSha(), current.status(),
                    current.failureReason(), current.startedAtMillis(), current.completedAtMillis()));
        }
    }

    private void processBranchAdvanced(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        lastControlHeadAdvanced = payload.integrationCommitSha();
        IntegrationAttemptRecord current = attempts.get(payload.attemptId());
        if (current != null) {
            attempts.put(payload.attemptId(), new IntegrationAttemptRecord(
                    current.attemptId(), current.projectId(), current.taskSnapshotIds(),
                    current.expectedControlHead(), payload.integrationCommitSha(), "advanced", "",
                    current.startedAtMillis(), event.createdAtEpochMillis()));
        }
        if (payload.attemptId()
                .equals(activeAttemptId)) {
            activeAttemptId = null;
        }
    }

    private void processTaskIntegrated(PredictionEvent event) throws IOException {
        TaskSnapshotPayload payload = TaskSnapshotPayload.decode(event.payload());
        TaskSnapshotRecord snapshot = snapshotsById.get(payload.snapshotId());
        if (snapshot == null) {
            taskStates.put(payload.taskId(), TaskCompletionState.INTEGRATED);
            return;
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotId());
        if (current == TaskCompletionState.REVIEW_REJECTED) {
            throw new IOException("REJECTED_SNAPSHOT_CANNOT_INTEGRATE");
        }
        if (snapshot.reviewRequired() && current != TaskCompletionState.REVIEW_ACCEPTED
                && integrationPriorStates.get(snapshot.snapshotId()) != TaskCompletionState.REVIEW_ACCEPTED) {
            throw new IOException("REVIEW_ACCEPTANCE_REQUIRED");
        }
        setSnapshotState(snapshot, TaskCompletionState.INTEGRATED);
    }

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
        TaskSnapshotRecord snapshot = snapshotsById.get(payload.snapshotId());
        if (snapshot == null) {
            taskStates.put(payload.taskId(), TaskCompletionState.INTEGRATED);
            return;
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotId());
        if (current == TaskCompletionState.REVIEW_REJECTED
                || (snapshot.reviewRequired() && current != TaskCompletionState.INTEGRATED
                && current != TaskCompletionState.REVIEW_ACCEPTED)) {
            throw new IOException("REVIEW_ACCEPTANCE_REQUIRED");
        }
        setSnapshotState(snapshot, TaskCompletionState.INTEGRATED);
    }

    private Optional<TaskSnapshotRecord> latestSnapshot(UUID taskId) {
        List<TaskSnapshotRecord> history = snapshotsByTask.get(taskId);
        return history == null || history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    private void setSnapshotState(TaskSnapshotRecord snapshot, TaskCompletionState state) {
        snapshotStates.put(snapshot.snapshotId(), state);
        if (latestSnapshot(snapshot.taskId()).map(latest -> latest.snapshotId()
                        .equals(snapshot.snapshotId()))
                .orElse(false)) {
            taskStates.put(snapshot.taskId(), state);
        }
    }
}
