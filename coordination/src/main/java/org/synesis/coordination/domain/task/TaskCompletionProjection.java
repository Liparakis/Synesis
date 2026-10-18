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
import org.synesis.coordination.domain.integration.ValidationRefusalPayload;
import org.synesis.coordination.domain.prediction.ApplyMode;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.MissingReferenceException;

/**
 * Deterministic task completion and integration projection over the shared event sequence.
 */
public final class TaskCompletionProjection {

    /**
     * How many times one lane is asked to repair a snapshot the project's validation command
     * refused. A lane that still fails after them is not asked again: the operator is.
     */
    public static final int VALIDATION_REPAIRS = 2;

    private static final String REVIEW_ACCEPTANCE_REQUIRED = "REVIEW_ACCEPTANCE_REQUIRED";

    private final Map<TaskId, TaskCompletionState> taskStates = new LinkedHashMap<>();
    private final Map<TaskId, List<TaskSnapshotRecord>> snapshotsByTask = new LinkedHashMap<>();
    private final Map<SnapshotId, TaskSnapshotRecord> snapshotsById = new LinkedHashMap<>();
    private final Map<SnapshotId, TaskCompletionState> snapshotStates = new LinkedHashMap<>();
    private final IntegrationAttemptLedger attempts;
    private final Map<TaskId, CompletionPreparedPayload> prepared = new LinkedHashMap<>();
    private final Map<PreparedRevisionKey, CompletionPreparedPayload> preparedByRevision = new LinkedHashMap<>();
    private final Map<SnapshotId, TaskCompletionState> integrationPriorStates = new LinkedHashMap<>();
    /** Why each snapshot the project's validation command refused was refused, in the order recorded. */
    private final Map<SnapshotId, ValidationRefusalPayload> validationRefusals = new LinkedHashMap<>();
    /**
     * Each snapshot's verdicts, by the reviewing participant. Only the reviewers' own computers write them, so on
     * another computer two may meet in either order (ADR-0100 row 4).
     */
    private final Map<SnapshotId, Map<String, String>> verdicts = new LinkedHashMap<>();
    /** Each re-merge snapshot, by its ID (ADR-0098 step 5): reviewed, never integrated by itself. */
    private final Map<SnapshotId, RemergeSnapshotPayload> remerges = new LinkedHashMap<>();
    /** Each combine a re-merge asked for, by its ID (ADR-0098 step 5, designer item 22). */
    private final Map<String, CombinePayload> combines = new LinkedHashMap<>();
    private final ApplyMode mode;

    /**
     * Creates an empty task completion projection.
     */
    public TaskCompletionProjection() {
        attempts = new IntegrationAttemptLedger();
        mode = ApplyMode.REPLAY;
    }

    /**
     * Copy constructor for non-mutating validation.
     *
     * @param source source projection
     * @param mode   how the copy judges the event it is given
     */
    private TaskCompletionProjection(TaskCompletionProjection source, ApplyMode mode) {
        this.mode = mode;
        taskStates.putAll(source.taskStates);
        source.snapshotsByTask.forEach((taskId, history) -> snapshotsByTask.put(taskId, List.copyOf(history)));
        snapshotsById.putAll(source.snapshotsById);
        snapshotStates.putAll(source.snapshotStates);
        attempts = new IntegrationAttemptLedger(source.attempts);
        prepared.putAll(source.prepared);
        preparedByRevision.putAll(source.preparedByRevision);
        integrationPriorStates.putAll(source.integrationPriorStates);
        validationRefusals.putAll(source.validationRefusals);
        source.verdicts.forEach((snapshot, byReviewer) -> verdicts.put(snapshot, new LinkedHashMap<>(byReviewer)));
        remerges.putAll(source.remerges);
        combines.putAll(source.combines);
    }

    /**
     * Returns whether the task completion state can enter integration.
     */
    private static boolean isIntegrationEligibleState(TaskCompletionState state) {
        return state == TaskCompletionState.INTEGRATION_PENDING || state == TaskCompletionState.SNAPSHOT_READY
                || state == TaskCompletionState.READY_FOR_INTEGRATION || state == TaskCompletionState.REVIEW_ACCEPTED;
    }

    /**
     * Gate every path into the integrated state passes. A refused snapshot, rejected by review or by the project's
     * validation command, never integrates and fails with the caller's {@code rejectedCode}; otherwise
     * {@code reviewSatisfied}, computed by the caller because each path accepts a different set of prior states,
     * must hold.
     */
    private void requireIntegrable(TaskCompletionState current, boolean reviewSatisfied, String rejectedCode)
            throws CoordinationException {
        if (!mode.checking()) {
            // The integrating computer judged it on its own view; an integration is a fact, since main only moves
            // forward (ADR-0100, "Every rule an arriving entry can reach").
            return;
        }
        if (current.refused()) {
            throw new CoordinationException(rejectedCode);
        }
        if (!reviewSatisfied) {
            throw new CoordinationException(REVIEW_ACCEPTANCE_REQUIRED);
        }
    }

    /**
     * Resolves the snapshots of an integration payload that this projection knows; unknown ids are skipped, except
     * on arrival, where a snapshot of another log that has not arrived holds the entry back.
     */
    private List<TaskSnapshotRecord> knownSnapshots(IntegrationAttemptPayload payload) throws MissingReferenceException {
        if (mode == ApplyMode.ARRIVAL && payload.taskSnapshotIds().stream()
                .anyMatch(snapshotId -> !snapshotsById.containsKey(new SnapshotId(snapshotId)))) {
            throw new MissingReferenceException("INTEGRATION_SNAPSHOT_NOT_FOUND");
        }
        return payload.taskSnapshotIds().stream()
                .map(snapshotId -> snapshotsById.get(new SnapshotId(snapshotId)))
                .filter(Objects::nonNull)
                .toList();
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
            case REMERGE_SNAPSHOT_CREATED -> processRemergeSnapshot(event);
            case COMBINE_REQUIRED -> processCombineRequired(event);
            case REVIEW_VALIDATION_RECORDED -> processReviewValidation(event);
            case TASK_WAITING_FOR_DEPENDENCIES -> processWaitingForDependencies(event);
            case INTEGRATION_ATTEMPT_STARTED -> processAttemptStarted(event);
            case INTEGRATION_ATTEMPT_FAILED -> processAttemptFailed(event);
            case INTEGRATION_VALIDATION_REFUSED -> processValidationRefused(event);
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
        validate(event, ApplyMode.CHECK);
    }

    /**
     * Applies an event to a copy of this projection, judged as {@code mode} says (ADR-0100, "Every rule an arriving
     * entry can reach").
     *
     * @param event event to judge
     * @param mode  how the event is judged
     * @throws IOException when the event is refused, or names something not seen yet
     */
    public synchronized void validate(PredictionEvent event, ApplyMode mode) throws IOException {
        new TaskCompletionProjection(this, mode).apply(event);
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
     * Returns the authority lineages whose published work waits to be integrated: those whose
     * latest snapshot, by claim epoch within its task, is in a state that
     * {@link TaskCompletionState#awaitsIntegration() awaits integration}.
     *
     * <p>A lane continued by a successor keeps its lineage, so its work is found whichever
     * session holds it now. A snapshot left behind by a later revision of the same lane does not
     * count: only the lane's current work says whether the lane is waiting.</p>
     *
     * @return lineages waiting only for integration
     */
    public synchronized java.util.Set<UUID> lineagesAwaitingIntegration() {
        java.util.Map<String, TaskSnapshotRecord> latest = new java.util.HashMap<>();
        for (TaskSnapshotRecord snapshot : allSnapshots()) {
            latest.merge(snapshot.taskId() + "/" + snapshot.provenance().authorityLineageId(), snapshot,
                    (kept, next) -> next.provenance().claimEpoch() >= kept.provenance().claimEpoch() ? next : kept);
        }
        return latest.values().stream().filter(snapshot -> snapshotState(snapshot.snapshotId()).filter(TaskCompletionState::awaitsIntegration).isPresent())
                .map(snapshot -> snapshot.provenance().authorityLineageId()).collect(java.util.stream.Collectors.toUnmodifiableSet());
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
     * Returns why the project's validation command refused a snapshot.
     *
     * @param snapshotId snapshot identity
     * @return the recorded refusal, or empty when the command did not refuse this snapshot
     */
    public synchronized Optional<ValidationRefusalPayload> validationRefusal(String snapshotId) {
        return snapshotId == null ? Optional.empty() : Optional.ofNullable(validationRefusals.get(new SnapshotId(snapshotId)));
    }

    /**
     * Counts the snapshots of one task and authority lineage that the project's validation command
     * refused. A lane continued by a successor keeps its lineage, so its refusals are counted
     * whichever session holds it now.
     *
     * @param taskId             task identity
     * @param authorityLineageId authority lineage of the lane
     * @return the number of recorded refusals
     */
    public synchronized int validationRefusals(UUID taskId, UUID authorityLineageId) {
        return (int) validationRefusals.values().stream()
                .filter(refusal -> refusal.taskId().equals(taskId) && refusal.authorityLineageId().equals(authorityLineageId))
                .count();
    }

    /**
     * Tells how many more times the project's validation command may refuse a snapshot of one task
     * and authority lineage. Each refusal asks the lane for one repair, {@link #VALIDATION_REPAIRS}
     * in all; at zero a further failure refuses nothing and is the operator's.
     *
     * @param taskId             task identity
     * @param authorityLineageId authority lineage of the lane
     * @return the refusals still open to the lane, never negative
     */
    public synchronized int validationRepairsLeft(UUID taskId, UUID authorityLineageId) {
        return Math.max(0, VALIDATION_REPAIRS - validationRefusals(taskId, authorityLineageId));
    }

    /**
     * Returns the active integration attempt, if any.
     *
     * @return active attempt when present
     */
    public synchronized Optional<IntegrationAttemptRecord> activeIntegrationAttempt() {
        return attempts.active();
    }

    /**
     * Returns the active integration attempt of one computer's merge pass (ADR-0098 step 5: every computer merges its
     * own lanes, each with its own attempts).
     *
     * @param writer the node whose pass made the attempt
     * @return its active attempt when present
     */
    public synchronized Optional<IntegrationAttemptRecord> activeIntegrationAttempt(String writer) {
        return attempts.active(writer);
    }

    /**
     * Returns the last head one computer's merge pass advanced its control branch to.
     *
     * @param writer the node whose pass advanced it
     * @return that head, or {@code null}
     */
    public synchronized String lastControlHeadAdvanced(String writer) {
        return attempts.lastControlHeadAdvanced(writer);
    }

    /**
     * Returns the integration attempt begun just before the given one by the same computer, as it ended.
     *
     * @param attemptId attempt whose predecessor is asked for
     * @return the preceding attempt, or empty when the attempt is unknown or was the first
     */
    public synchronized Optional<IntegrationAttemptRecord> integrationAttemptBefore(String attemptId) {
        return attemptId == null ? Optional.empty() : attempts.before(new IntegrationAttemptId(attemptId));
    }

    /**
     * Returns the last control HEAD advanced by integration.
     *
     * @return last advanced commit or {@code null}
     */
    public synchronized String lastControlHeadAdvanced() {
        return attempts.lastControlHeadAdvanced();
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
        if (current == null) {
            // The preparation is the old lane owner's entry, the unwinding its replacement's.
            throw new MissingReferenceException("COMPLETION_PREPARATION_MISMATCH");
        }
        if (!current.equals(payload.prepared())) {
            if (mode.checking()) {
                throw new CoordinationException("COMPLETION_PREPARATION_MISMATCH");
            }
            return;
        }
        boolean published = !snapshotsByTask.getOrDefault(preparedPayload.taskIdentity(), List.of()).isEmpty();
        if (published && mode.checking()) {
            throw new CoordinationException("PUBLISHED_COMPLETION_CANNOT_UNWIND");
        }
        preparedByRevision.remove(key);
        if (preparedPayload.equals(prepared.get(preparedPayload.taskIdentity()))) {
            prepared.remove(preparedPayload.taskIdentity());
        }
        if (!published) {
            // A snapshot published meanwhile on another computer keeps the task's state, in either order.
            taskStates.put(preparedPayload.taskIdentity(), TaskCompletionState.ACTIVE);
        }
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
     * Applies a re-merge snapshot (ADR-0098 step 5, designer item 14). It is a snapshot for review only: it is not
     * part of its task's history, so it changes no task's state, is never a candidate of a merge pass, and is found
     * only by its ID, which is how its grant and its verdict name it.
     *
     * @param event event to apply
     * @throws IOException when the payload is malformed or, on this computer's own append, unlawful
     */
    private void processRemergeSnapshot(PredictionEvent event) throws IOException {
        RemergeSnapshotPayload remerge = RemergeSnapshotPayload.decode(event.payload());
        TaskSnapshotPayload payload = remerge.snapshot();
        TaskSnapshotRecord original = snapshotsById.get(new SnapshotId(remerge.originalSnapshotId()));
        if (original == null) {
            throw new MissingReferenceException("REMERGE_ORIGINAL_NOT_FOUND");
        }
        if (snapshotsById.containsKey(payload.snapshotIdentity())) {
            throw new CoordinationException("SNAPSHOT_ID_EXISTS");
        }
        if (mode.checking() && (snapshotStates.get(original.snapshotIdentity()) != TaskCompletionState.INTEGRATED
                || !original.provenance().laneIdentity().equals(payload.provenance().laneIdentity())
                || !original.provenance().claimEpochIdentity().equals(payload.provenance().claimEpochIdentity())
                || !original.provenance().participant().equals(payload.provenance().participant())
                || !original.taskIdentity().equals(payload.taskIdentity()) || !payload.reviewRequired())) {
            throw new CoordinationException("REMERGE_SNAPSHOT_INVALID");
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
                true);
        snapshotsById.put(payload.snapshotIdentity(), record);
        snapshotStates.put(payload.snapshotIdentity(), TaskCompletionState.REVIEW_PENDING);
        remerges.put(payload.snapshotIdentity(), remerge);
    }

    /**
     * Returns the re-merge a snapshot belongs to, when it is a re-merge snapshot.
     *
     * @param snapshotId snapshot identity
     * @return the re-merge payload, or empty for an ordinary snapshot
     */
    public synchronized Optional<RemergeSnapshotPayload> remergeOf(SnapshotId snapshotId) {
        return Optional.ofNullable(remerges.get(CoordinationNullException.requireNonNull(snapshotId, "snapshot ID")));
    }

    /**
     * Returns every snapshot of one re-merge, in the order they were recorded.
     *
     * @param remergeId the re-merge
     * @return its snapshots' payloads
     */
    public synchronized List<RemergeSnapshotPayload> remerge(String remergeId) {
        return remerges.values().stream().filter(remerge -> remerge.remergeId().equals(remergeId)).toList();
    }

    /**
     * Applies a combine a re-merge asked for (ADR-0098 step 5, designer item 22). Every snapshot it names must be known
     * (it is held back until they arrive) and, on this computer's own append, integrated: a combine re-merges work that
     * reached a copy of {@code main}.
     *
     * @param event event to apply
     * @throws IOException when the payload is malformed or, on this computer's own append, unlawful
     */
    private void processCombineRequired(PredictionEvent event) throws IOException {
        CombinePayload combine = CombinePayload.decode(event.payload());
        for (String snapshotId : combine.snapshotIds()) {
            TaskSnapshotRecord snapshot = snapshotsById.get(new SnapshotId(snapshotId));
            if (snapshot == null) {
                throw new MissingReferenceException("COMBINE_SNAPSHOT_NOT_FOUND");
            }
            if (mode.checking() && snapshotStates.get(snapshot.snapshotIdentity()) != TaskCompletionState.INTEGRATED) {
                throw new CoordinationException("COMBINE_SNAPSHOT_NOT_INTEGRATED");
            }
        }
        if (mode.checking() && combines.containsKey(combine.combineId())) {
            throw new CoordinationException("COMBINE_EXISTS");
        }
        combines.putIfAbsent(combine.combineId(), combine);
    }

    /**
     * Returns every combine a re-merge asked for, in the order they were recorded.
     *
     * @return the combines
     */
    public synchronized List<CombinePayload> combines() {
        return List.copyOf(combines.values());
    }

    /**
     * Returns the combine whose lane works in a work group, when the group is a combine's.
     *
     * @param workGroupId work group ID
     * @return the combine, or empty for any other group
     */
    public synchronized Optional<CombinePayload> combineOfGroup(UUID workGroupId) {
        CoordinationNullException.requireNonNull(workGroupId, "work group ID");
        return combines.values().stream().filter(combine -> combine.workGroupId().equals(workGroupId)).findFirst();
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
            if (mode == ApplyMode.ARRIVAL) {
                throw new MissingReferenceException("REVIEW_SNAPSHOT_NOT_FOUND");
            }
            return;
        }
        if (!snapshot.taskIdentity().equals(payload.taskIdentity()) || !snapshot.provenance().workGroupIdentity()
                .equals(payload.workGroupIdentity()) || !snapshot.provenance().laneIdentity()
                .equals(payload.targetIntentIdentity()) || !snapshot.provenance().claimEpochIdentity()
                .equals(payload.claimEpochIdentity())) {
            throw new CoordinationException("REVIEW_SNAPSHOT_BINDING_MISMATCH");
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotIdentity());
        if (mode.checking() && (current == TaskCompletionState.REVIEW_ACCEPTED || current.refused()
                || current == TaskCompletionState.INTEGRATED)) {
            throw new CoordinationException("REVIEW_DECISION_ALREADY_RECORDED");
        }
        Map<String, String> given = verdicts.computeIfAbsent(snapshot.snapshotIdentity(), id -> new LinkedHashMap<>());
        given.put(payload.targetParticipant(), payload.result());
        if (current == TaskCompletionState.INTEGRATED || current == TaskCompletionState.VALIDATION_REFUSED) {
            // An integration and a validation refusal are facts no verdict takes back.
            return;
        }
        TaskCompletionState reviewed = reviewState(given);
        if (reviewed.refused() || isReviewState(current)) {
            forceSnapshotState(snapshot, reviewed);
        }
    }

    /**
     * The review state a set of verdicts gives: accepted when every reviewer accepted, rejected when every one
     * rejected, disputed when they differ (ADR-0098 designer item 3).
     */
    private static TaskCompletionState reviewState(Map<String, String> byReviewer) {
        long accepted = byReviewer.values().stream().filter("ACCEPTED"::equals).count();
        if (accepted == byReviewer.size()) {
            return TaskCompletionState.REVIEW_ACCEPTED;
        }
        return accepted == 0 ? TaskCompletionState.REVIEW_REJECTED : TaskCompletionState.REVIEW_DISPUTED;
    }

    private static boolean isReviewState(TaskCompletionState state) {
        return state == TaskCompletionState.REVIEW_PENDING || state == TaskCompletionState.REVIEW_ACCEPTED
                || state == TaskCompletionState.REVIEW_REJECTED || state == TaskCompletionState.REVIEW_DISPUTED;
    }

    /**
     * Returns every verdict recorded on a snapshot, by reviewing participant: what a computer checks before it takes
     * another computer's copy of {@code main} (ADR-0098 step 5, the audit).
     *
     * @param snapshotId snapshot identity
     * @return each reviewer's latest verdict; empty when none was recorded
     */
    public synchronized Map<String, String> verdicts(SnapshotId snapshotId) {
        return Map.copyOf(verdicts.getOrDefault(CoordinationNullException.requireNonNull(snapshotId, "snapshot ID"),
                Map.of()));
    }

    /**
     * Returns the verdicts of a snapshot whose reviewers disagree (ADR-0100 row 4).
     *
     * @param snapshotId snapshot identity
     * @return each reviewer's verdict when they differ; empty when there is one verdict or they agree
     */
    public synchronized Map<String, String> reviewDispute(SnapshotId snapshotId) {
        Map<String, String> given = verdicts.getOrDefault(CoordinationNullException.requireNonNull(snapshotId,
                "snapshot ID"), Map.of());
        return given.values().stream().distinct().count() > 1 ? Map.copyOf(given) : Map.of();
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
                IntegrationAttemptLedger.STARTED,
                "",
                event.createdAtEpochMillis(),
                0L);
        attempts.begin(event.actorNodeId(), payload.identity(), record);
        for (TaskSnapshotRecord snapshot : knownSnapshots(payload)) {
            TaskCompletionState current = snapshotStates.get(snapshot.snapshotIdentity());
            requireIntegrable(current, isIntegrationEligibleState(current), REVIEW_ACCEPTANCE_REQUIRED);
            integrationPriorStates.put(snapshot.snapshotIdentity(), current);
            setSnapshotState(snapshot, TaskCompletionState.INTEGRATING);
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
        boolean pending = IntegrationAttemptLedger.PENDING.equalsIgnoreCase(payload.status());
        attempts.finish(payload.identity(),
                current -> current.withOutcome(pending ? IntegrationAttemptLedger.PENDING
                        : IntegrationAttemptLedger.FAILED, payload.failureReason(), event.createdAtEpochMillis()));
        for (TaskSnapshotRecord snapshot : knownSnapshots(payload)) {
            TaskCompletionState state = pending ? integrationPriorStates.remove(snapshot.snapshotIdentity())
                    : TaskCompletionState.INTEGRATION_FAILED;
            if (pending && verdicts.containsKey(snapshot.snapshotIdentity())) {
                // The verdicts held now, which may have grown while the attempt ran on another computer.
                state = reviewState(verdicts.get(snapshot.snapshotIdentity()));
            }
            if (state == null) {
                state = snapshot.reviewRequired() ? TaskCompletionState.REVIEW_ACCEPTED
                        : TaskCompletionState.INTEGRATION_PENDING;
            }
            setSnapshotState(snapshot, state);
        }
    }

    /**
     * Applies the refusal of one snapshot by the project's validation command.
     *
     * <p>The refusal is taken only for a snapshot that could still have integrated. The attempt that failed has
     * ended by then and returned its snapshots to the state they had, so an eligible state is what a lawful
     * refusal finds; a snapshot refused twice, or one that has integrated meanwhile, is not refused again.</p>
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processValidationRefused(PredictionEvent event) throws IOException {
        ValidationRefusalPayload payload = ValidationRefusalPayload.decode(event.payload());
        TaskSnapshotRecord snapshot = snapshotsById.get(payload.snapshotIdentity());
        if (snapshot == null) {
            throw new MissingReferenceException("VALIDATION_REFUSAL_SNAPSHOT_UNKNOWN");
        }
        if (!snapshot.taskId().equals(payload.taskId()) || !snapshot.provenance().workGroupId()
                .equals(payload.workGroupId()) || !snapshot.provenance().authorityLineageId()
                .equals(payload.authorityLineageId()) || snapshot.provenance().claimEpoch() != payload.claimEpoch()) {
            throw new CoordinationException("VALIDATION_REFUSAL_SNAPSHOT_BINDING_MISMATCH");
        }
        if (!mode.checking()) {
            // The integrating computer refused it on its own view. Only an integration outranks the refusal.
            if (snapshotStates.get(snapshot.snapshotIdentity()) != TaskCompletionState.INTEGRATED) {
                forceSnapshotState(snapshot, TaskCompletionState.VALIDATION_REFUSED);
            }
            validationRefusals.put(snapshot.snapshotIdentity(), payload);
            return;
        }
        if (!isIntegrationEligibleState(snapshotStates.get(snapshot.snapshotIdentity()))) {
            throw new CoordinationException("VALIDATION_REFUSAL_SNAPSHOT_NOT_ELIGIBLE");
        }
        setSnapshotState(snapshot, TaskCompletionState.VALIDATION_REFUSED);
        validationRefusals.put(snapshot.snapshotIdentity(), payload);
    }

    /**
     * Applies the attempt conflicted event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processAttemptConflicted(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        attempts.finish(payload.identity(),
                current -> current.withOutcome(IntegrationAttemptLedger.CONFLICT,
                        payload.failureReason(),
                        event.createdAtEpochMillis()));
        for (TaskSnapshotRecord snapshot : knownSnapshots(payload)) {
            integrationPriorStates.remove(snapshot.snapshotIdentity());
            setSnapshotState(snapshot, TaskCompletionState.REPAIR_REQUIRED);
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
        for (TaskSnapshotRecord snapshot : knownSnapshots(payload)) {
            setSnapshotState(snapshot, TaskCompletionState.INTEGRATION_BLOCKED);
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
        for (TaskSnapshotRecord snapshot : knownSnapshots(payload)) {
            setSnapshotState(snapshot, TaskCompletionState.REPAIR_REQUIRED);
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
        attempts.update(payload.identity(), current -> current.withCommit(payload.integrationCommitSha()));
    }

    /**
     * Applies the branch advanced event to this projection.
     *
     * @param event event to apply
     * @throws IOException when payload validation or durable I/O fails
     */
    private void processBranchAdvanced(PredictionEvent event) throws IOException {
        IntegrationAttemptPayload payload = IntegrationAttemptPayload.decode(event.payload());
        attempts.advanceHead(event.actorNodeId(), new CommitId(payload.integrationCommitSha()));
        attempts.finish(payload.identity(),
                current -> current.withCommit(payload.integrationCommitSha())
                        .withOutcome(IntegrationAttemptLedger.ADVANCED, "", event.createdAtEpochMillis()));
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
            requireArrived();
            taskStates.put(payload.taskIdentity(), TaskCompletionState.INTEGRATED);
            return;
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotIdentity());
        requireIntegrable(current,
                !snapshot.reviewRequired() || current == TaskCompletionState.REVIEW_ACCEPTED
                        || integrationPriorStates.get(snapshot.snapshotIdentity()) == TaskCompletionState.REVIEW_ACCEPTED,
                "REJECTED_SNAPSHOT_CANNOT_INTEGRATE");
        integrated(snapshot);
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
            requireArrived();
            taskStates.put(payload.taskIdentity(), TaskCompletionState.INTEGRATED);
            return;
        }
        TaskCompletionState current = snapshotStates.get(snapshot.snapshotIdentity());
        requireIntegrable(current,
                !snapshot.reviewRequired() || current == TaskCompletionState.INTEGRATED
                        || current == TaskCompletionState.REVIEW_ACCEPTED,
                REVIEW_ACCEPTANCE_REQUIRED);
        integrated(snapshot);
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
     * On arrival, holds back an integration entry whose snapshot, in its producer's log, has not arrived. On this
     * computer's own append and on replay such an entry keeps today's meaning: a task integrated before snapshots.
     */
    private void requireArrived() throws MissingReferenceException {
        if (mode == ApplyMode.ARRIVAL) {
            throw new MissingReferenceException("INTEGRATION_SNAPSHOT_NOT_FOUND");
        }
    }

    /**
     * Records that a snapshot reached the control branch. On this computer's own append the gate above has already
     * refused a refused snapshot; an integration written by another computer stands whatever this one saw first.
     */
    private void integrated(TaskSnapshotRecord snapshot) {
        if (mode.checking()) {
            setSnapshotState(snapshot, TaskCompletionState.INTEGRATED);
        } else {
            forceSnapshotState(snapshot, TaskCompletionState.INTEGRATED);
        }
    }

    /**
     * Sets a snapshot's state even over a refused one: the outcome of facts that meet from two computers, which
     * must not depend on which arrived first.
     */
    private void forceSnapshotState(TaskSnapshotRecord snapshot, TaskCompletionState state) {
        snapshotStates.put(snapshot.snapshotIdentity(), state);
        if (latestSnapshot(snapshot.taskIdentity()).map(latest -> latest.snapshotIdentity()
                .equals(snapshot.snapshotIdentity())).orElse(false)) {
            taskStates.put(snapshot.taskIdentity(), state);
        }
    }

    /**
     * Updates the lifecycle state of the identified snapshot.
     *
     * <p>A refused snapshot keeps its state whatever comes later. An attempt that ends, or a block
     * or conflict that names it, would otherwise return it to a state that integrates, and the
     * content that was refused would reach the control branch.</p>
     */
    private void setSnapshotState(TaskSnapshotRecord snapshot, TaskCompletionState state) {
        if (snapshotStates.get(snapshot.snapshotIdentity()).refused()) {
            return;
        }
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
