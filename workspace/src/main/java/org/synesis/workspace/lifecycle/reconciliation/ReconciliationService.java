package org.synesis.workspace.lifecycle.reconciliation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.workspace.lifecycle.recovery.RecoverySnapshotService;

/**
 * Core application service for discovering, planning, and executing crash reconciliation and fenced
 * lane recovery.
 *
 * @since 1.0
 */
public final class ReconciliationService {

    private final ProjectApplicationService projectService;
    private final SessionLeaseService leaseService;
    private final SessionLeaseStore leaseStore;
    private final ReconciliationPlanStore planStore;

    /**
     * Creates a reconciliation service with default dependencies.
     */
    public ReconciliationService() {
        this(new ProjectApplicationService(), new SessionLeaseService(), new SessionLeaseStore(), new ReconciliationPlanStore());
    }

    /**
     * Creates a reconciliation service with explicit dependencies.
     *
     * @param projectService project application service
     * @param leaseService   session lease service
     * @param leaseStore     session lease store
     * @param planStore      reconciliation plan store
     */
    public ReconciliationService(ProjectApplicationService projectService, SessionLeaseService leaseService, SessionLeaseStore leaseStore, ReconciliationPlanStore planStore) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
        this.planStore = Objects.requireNonNull(planStore, "planStore");
    }

    /**
     * Discovers session leases and interrupted integrations, returning a discovery summary.
     *
     * @param controlRoot control project root path
     * @return discovery summary
     * @throws ProjectApplicationService.ProjectApplicationException if project discovery fails
     * @throws IOException if lease or collaboration state cannot be read
     */
    public ReconciliationDiscoverySummary discover(Path controlRoot) throws ProjectApplicationService.ProjectApplicationException, IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Path root = controlRoot.toAbsolutePath().normalize();
        ProjectLocation location = projectService.locate(root);

        SessionLeasePolicy policy = new SessionLeasePolicy();
        List<SessionLeaseRecord> leases = leaseStore.listAll(root);
        Map<String, Participant.State> participantStates;
        try {
            participantStates = location.openCoordinationStore().collaborationProjection().participants().stream()
                    .collect(java.util.stream.Collectors.toMap(Participant::id, Participant::state, (first, ignored) -> first));
        } catch (Exception ignored) {
            participantStates = Map.of();
        }

        int activeCount = 0;
        int staleCount = 0;
        int recoveryEligibleCount = 0;
        int ambiguousCount = 0;
        int recoverableIntegrations = 0;

        List<ReconciliationPlanEntry> entries = new ArrayList<>();

        for (SessionLeaseRecord lease : leases) {
            SessionLeaseState state = leaseService.evaluateLiveness(lease, policy);
            switch (state) {
                case ACTIVE -> activeCount++;
                case SUSPECTED_STALE -> staleCount++;
                case AMBIGUOUS -> ambiguousCount++;
                case RECOVERY_ELIGIBLE -> {
                    recoveryEligibleCount++;
                    planSuspendAndHold(entries, lease, participantStates, "session_recovery_eligible", "Fence old authority without releasing claims");
                }
                case CLOSED_CLEANLY -> {
                    // EOF proves this exact connection has ended, so unfinished
                    // claims can be snapshotted immediately without the abrupt-loss
                    // grace period. Terminal participant states are still filtered
                    // above; clean transport closure never completes a lane.
                    recoveryEligibleCount++;
                    planSuspendAndHold(entries, lease, participantStates, "transport_closed_cleanly", "Fence closed connection authority without releasing claims");
                }
            }
        }

        int executableCount = (int) entries.stream().filter(ReconciliationPlanEntry::executable).count();

        return new ReconciliationDiscoverySummary(location.projectId().toString(),
                System.currentTimeMillis(),
                leases.size(),
                activeCount,
                staleCount,
                recoveryEligibleCount,
                ambiguousCount,
                recoverableIntegrations,
                executableCount,
                Collections.unmodifiableList(entries));
    }

    /**
     * Generates and persists an immutable reconciliation plan.
     *
     * @param controlRoot control project root path
     * @return persisted reconciliation plan
     * @throws ProjectApplicationService.ProjectApplicationException if project discovery fails
     * @throws IOException                                           if plan creation fails
     */
    public ReconciliationPlan preparePlan(Path controlRoot) throws ProjectApplicationService.ProjectApplicationException, IOException {
        ReconciliationDiscoverySummary discovery = discover(controlRoot);
        return planStore.createAndSave(controlRoot, discovery.projectId(), discovery.totalSessionsInspected(), discovery.entries());
    }

    /**
     * Reconciles verified dead lanes when a new exact MCP session is admitted.
     *
     * <p>This is deliberately a narrow ingress hook into the existing durable plan lifecycle. It
     * only executes entries already classified as recovery-eligible after process and grace-period
     * evidence; it never releases claims, selects another participant, or bypasses continuation
     * grants. An empty result means there was no executable recovery transition to apply.</p>
     *
     * @param controlRoot control project root path
     * @return the durable execution result when recovery work was found
     * @throws ProjectApplicationService.ProjectApplicationException if project discovery fails
     * @throws IOException                                           if planning or execution fails
     */
    public Optional<ReconciliationExecutionSummary> reconcileOnAdmission(Path controlRoot) throws ProjectApplicationService.ProjectApplicationException, IOException {
        return reconcileEligible(controlRoot);
    }

    /**
     * Re-evaluates leases and executes only transitions that are currently recovery-eligible.
     * Callers may invoke this from admission and inbox polling: process evidence and the durable
     * participant projection are re-read each time, while completed actions remain idempotent.
     *
     * @param controlRoot project root
     * @return durable execution result when recovery work was found
     * @throws ProjectApplicationService.ProjectApplicationException when project discovery fails
     * @throws IOException when planning or execution fails
     */
    public Optional<ReconciliationExecutionSummary> reconcileEligible(Path controlRoot) throws ProjectApplicationService.ProjectApplicationException, IOException {
        ReconciliationDiscoverySummary discovery = discover(controlRoot);
        if (discovery.entries().isEmpty()) {
            return Optional.empty();
        }
        ReconciliationPlan plan = planStore.createAndSave(controlRoot, discovery.projectId(), discovery.totalSessionsInspected(), discovery.entries());
        return Optional.of(executePlan(controlRoot, plan.planId()));
    }

    /**
     * Executes a prepared reconciliation plan safely.
     *
     * @param controlRoot control project root path
     * @param planId      persisted plan ID
     * @return execution summary
     * @throws ProjectApplicationService.ProjectApplicationException if project discovery fails
     * @throws IOException                                           if execution fails
     */
    @SuppressWarnings("try")
    public ReconciliationExecutionSummary executePlan(Path controlRoot, String planId) throws ProjectApplicationService.ProjectApplicationException, IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath().normalize();
        ProjectLocation location = projectService.locate(root);

        try (var _ = ReconciliationExecutionLock.acquire(root, planId)) {
            ReconciliationPlan plan = planStore.load(root, planId);

            String executionId = "recexec-" + UUID.randomUUID().toString().replace("-", "");
            ReconciliationExecutionJournal journal = new ReconciliationExecutionJournal(root, executionId);
            Set<String> previouslyCompleted = ReconciliationExecutionJournal.loadCompletedActionIds(root, planId);

            PredictionEventStore store;
            try {
                store = location.openCoordinationStore();
            } catch (java.security.GeneralSecurityException gse) {
                throw new IOException("Security error initializing event store for reconciliation", gse);
            }
            NodeIdentity identity;
            try {
                identity = location.loadOrCreateIdentity();
            } catch (Exception ex) {
                throw new IOException("Failed to load node identity for reconciliation", ex);
            }

            int completedCount = 0;
            int skippedStaleCount = 0;
            int skippedAmbiguousCount = 0;
            int failedCount = 0;
            boolean controlCheckoutModified = false;

            List<ReconciliationExecutionRecord> records = new ArrayList<>();

            for (ReconciliationPlanEntry entry : plan.entries()) {
                long now = System.currentTimeMillis();

                if (previouslyCompleted.contains(entry.actionId())) {
                    completedCount++;
                    ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                            planId,
                            entry.actionId(),
                            entry.action(),
                            entry.targetResourceId(),
                            ReconciliationExecutionRecord.COMPLETED,
                            "already_completed",
                            now,
                            "Action previously completed");
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                if (!entry.executable()) {
                    skippedAmbiguousCount++;
                    ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                            planId,
                            entry.actionId(),
                            entry.action(),
                            entry.targetResourceId(),
                            ReconciliationExecutionRecord.SKIPPED_AMBIGUOUS,
                            "action_not_executable",
                            now,
                            "Action not classified as executable");
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                if (entry.action() == ReconciliationAction.MARK_SESSION_SUSPENDED || entry.action() == ReconciliationAction.HOLD_SUSPENDED_RECOVERY) {
                    String participant = WorkspaceCollaborationService.participantHandle(entry.targetResourceId());
                    Participant.State participantState = store.collaborationProjection().participantState(participant).orElse(null);
                    if (participantState == null || isTerminalParticipantState(participantState)) {
                        skippedStaleCount++;
                        ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                planId,
                                entry.actionId(),
                                entry.action(),
                                entry.targetResourceId(),
                                ReconciliationExecutionRecord.SKIPPED_STALE,
                                "participant_terminal_or_missing",
                                now,
                                "Recovery plan no longer matches the participant lifecycle state");
                        journal.append(rec);
                        records.add(rec);
                        continue;
                    }
                    if (entry.action() == ReconciliationAction.MARK_SESSION_SUSPENDED && (participantState == Participant.State.SUSPENDED || participantState == Participant.State.RECOVERY_HELD)) {
                        completedCount++;
                        ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                planId,
                                entry.actionId(),
                                entry.action(),
                                entry.targetResourceId(),
                                ReconciliationExecutionRecord.COMPLETED,
                                "already_suspended",
                                now,
                                "Participant was already fenced by an earlier recovery execution");
                        journal.append(rec);
                        records.add(rec);
                        continue;
                    }
                    if (entry.action() == ReconciliationAction.HOLD_SUSPENDED_RECOVERY && participantState == Participant.State.RECOVERY_HELD) {
                        completedCount++;
                        ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                planId,
                                entry.actionId(),
                                entry.action(),
                                entry.targetResourceId(),
                                ReconciliationExecutionRecord.COMPLETED,
                                "already_recovery_held",
                                now,
                                "Recovery snapshot was already held for this participant");
                        journal.append(rec);
                        records.add(rec);
                        continue;
                    }
                }

                try {
                    switch (entry.action()) {
                        case MARK_SESSION_SUSPENDED -> {
                            String participant = WorkspaceCollaborationService.participantHandle(entry.targetResourceId());
                            // Suspension is a lifecycle authority transition, so it must use the same
                            // project append lock as provider heartbeats and collaboration mutations.  A
                            // direct append here could race a live heartbeat, compute the same sequence, and
                            // leave the durable log with the heartbeat while this journal incorrectly records
                            // the suspension as complete.
                            new WorkIntentService(store, identity).suspend(participant);
                            store = location.openCoordinationStore();
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                    planId,
                                    entry.actionId(),
                                    entry.action(),
                                    entry.targetResourceId(),
                                    ReconciliationExecutionRecord.COMPLETED,
                                    "session_suspended",
                                    now,
                                    "Old authority fenced; claims retained");
                            journal.append(rec);
                            records.add(rec);
                        }
                        case RELEASE_SUSPENDED_OWNERSHIP -> {
                            var coordProj = store.coordinationProjection();
                            for (var entryOwnership : coordProj.ownerships().entrySet()) {
                                var claim = entryOwnership.getValue();
                                if (entry.targetResourceId().equals(claim.taskId().toString())) {
                                    org.synesis.coordination.domain.command.CoordinationCommand relCmd = org.synesis.coordination.domain.command.CoordinationCommand.create(UUID.randomUUID(),
                                            store.projectId(),
                                            claim.taskId(),
                                            PredictionEventType.OWNERSHIP_RELEASED,
                                            identity.nodeId(),
                                            claim.encoded(),
                                            identity);
                                    store.append(claim.taskId(), PredictionEventType.OWNERSHIP_RELEASED, identity.nodeId(), relCmd.encoded(), identity);
                                }
                            }
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                    planId,
                                    entry.actionId(),
                                    entry.action(),
                                    entry.targetResourceId(),
                                    ReconciliationExecutionRecord.COMPLETED,
                                    "ownership_released",
                                    now,
                                    "Semantic ownership released");
                            journal.append(rec);
                            records.add(rec);
                        }
                        case HOLD_SUSPENDED_RECOVERY -> {
                            String participant = WorkspaceCollaborationService.participantHandle(entry.targetResourceId());
                            WorkIntentService intentService = new WorkIntentService(store, identity);
                            RecoverySnapshotService.Snapshot snapshot = new RecoverySnapshotService().materialize(location, entry.targetResourceId());
                            intentService.holdRecovery(participant, snapshot.root().toString() + "#" + snapshot.contentHash());
                            store = location.openCoordinationStore();
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                    planId,
                                    entry.actionId(),
                                    entry.action(),
                                    entry.targetResourceId(),
                                    ReconciliationExecutionRecord.COMPLETED,
                                    "recovery_held",
                                    now,
                                    "Recovery evidence reserved for authorized continuation");
                            journal.append(rec);
                            records.add(rec);
                        }
                        case INVALIDATE_SUSPENDED_DEPENDENCIES -> {
                            store.append(UUID.randomUUID(),
                                    PredictionEventType.DEPENDENCY_INVALIDATED,
                                    identity.nodeId(),
                                    ("dependency_invalidated:" + entry.targetResourceId()).getBytes(StandardCharsets.UTF_8),
                                    identity);
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                    planId,
                                    entry.actionId(),
                                    entry.action(),
                                    entry.targetResourceId(),
                                    ReconciliationExecutionRecord.COMPLETED,
                                    "dependency_invalidated",
                                    now,
                                    "Dependencies invalidated");
                            journal.append(rec);
                            records.add(rec);
                        }
                        case FINALIZE_SUSPENDED_SESSION -> {
                            store.append(UUID.randomUUID(), PredictionEventType.SESSION_FINALIZED, identity.nodeId(), ("session_finalized:" + entry.targetResourceId()).getBytes(StandardCharsets.UTF_8), identity);
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                    planId,
                                    entry.actionId(),
                                    entry.action(),
                                    entry.targetResourceId(),
                                    ReconciliationExecutionRecord.COMPLETED,
                                    "session_finalized",
                                    now,
                                    "Suspended session finalized");
                            journal.append(rec);
                            records.add(rec);
                        }
                        case RESUME_VERIFIED_INTEGRATION_ADVANCEMENT, FINALIZE_ALREADY_ADVANCED_INTEGRATION, CLOSE_SUSPENDED_VALIDATION_CONTEXT -> {
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                                    planId,
                                    entry.actionId(),
                                    entry.action(),
                                    entry.targetResourceId(),
                                    ReconciliationExecutionRecord.COMPLETED,
                                    "action_completed",
                                    now,
                                    "Interrupted lifecycle context reconciled");
                            journal.append(rec);
                            records.add(rec);
                        }
                    }
                } catch (Exception failure) {
                    failedCount++;
                    ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(executionId,
                            planId,
                            entry.actionId(),
                            entry.action(),
                            entry.targetResourceId(),
                            ReconciliationExecutionRecord.FAILED_REQUIRES_REVIEW,
                            "reconciliation_failed",
                            now,
                            "Failure: " + failure.getMessage());
                    journal.append(rec);
                    records.add(rec);
                }
            }

            String resultStatus = failedCount > 0 ? "FAILED_REQUIRES_REVIEW" : "SUCCESS";

            return new ReconciliationExecutionSummary(planId,
                    executionId,
                    plan.entries().size(),
                    completedCount,
                    skippedStaleCount,
                    skippedAmbiguousCount,
                    failedCount,
                    controlCheckoutModified,
                    0,
                    resultStatus,
                    Collections.unmodifiableList(records));
        }
    }

    private static void planSuspendAndHold(List<ReconciliationPlanEntry> entries,
            SessionLeaseRecord lease,
            Map<String, Participant.State> participantStates,
            String suspendReason,
            String suspendSummary) {
        String actionId = "rec-" + lease.connectionInstanceId();
        String participant = WorkspaceCollaborationService.participantHandle(lease.sessionId());
        Participant.State participantState = participantStates.get(participant);
        if (participantState == null || isTerminalParticipantState(participantState) || participantState == Participant.State.RECOVERY_HELD) {
            return;
        }
        if (participantState != Participant.State.SUSPENDED) {
            entries.add(new ReconciliationPlanEntry(1, actionId, ReconciliationAction.MARK_SESSION_SUSPENDED, lease.sessionId(), true, List.of(suspendReason), suspendSummary));
        }
        entries.add(new ReconciliationPlanEntry(1,
                actionId + "-recovery",
                ReconciliationAction.HOLD_SUSPENDED_RECOVERY,
                lease.sessionId(),
                true,
                List.of("session_suspended"),
                "Materialize and reserve immutable recovery evidence"));
    }

    private static boolean isTerminalParticipantState(Participant.State state) {
        return state == Participant.State.REVOKED || state == Participant.State.COMPLETED || state == Participant.State.CANCELLED || state == Participant.State.DETACHED;
    }

    /**
     * Diagnostic discovery inspection summary.
     *
     * @param projectId               project ID
     * @param timestamp               discovery timestamp
     * @param totalSessionsInspected  total inspected session leases
     * @param activeCount             active sessions count
     * @param suspectedStaleCount     suspected stale count
     * @param recoveryEligibleCount   recovery eligible count
     * @param ambiguousCount          ambiguous count
     * @param recoverableIntegrations recoverable interrupted integrations count
     * @param executableActionsCount  executable actions count
     * @param entries                 list of plan entries
     */
    public record ReconciliationDiscoverySummary(String projectId, long timestamp, int totalSessionsInspected, int activeCount, int suspectedStaleCount, int recoveryEligibleCount, int ambiguousCount,
                                                 int recoverableIntegrations, int executableActionsCount, List<ReconciliationPlanEntry> entries) {

        /**
         * Requires a non-null project ID and entries list.
         */
        public ReconciliationDiscoverySummary {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(entries, "entries");
        }
    }

    /**
     * Execution summary output.
     *
     * @param planId                  plan identifier
     * @param executionId             execution run identifier
     * @param actionsRequested        total actions requested
     * @param completedCount          completed actions count
     * @param skippedStaleCount       skipped stale count
     * @param skippedAmbiguousCount   skipped ambiguous count
     * @param failedCount             failed count
     * @param controlCheckoutModified {@code true} if control checkout was modified for verified
     *                                recovery
     * @param processTerminations     process terminations count (always 0)
     * @param resultStatus            overall status code
     * @param records                 list of execution records
     */
    public record ReconciliationExecutionSummary(String planId, String executionId, int actionsRequested, int completedCount, int skippedStaleCount, int skippedAmbiguousCount, int failedCount,
                                                 boolean controlCheckoutModified, int processTerminations, String resultStatus, List<ReconciliationExecutionRecord> records) {

        /**
         * Requires non-null plan ID, execution ID, result status, and records.
         */
        public ReconciliationExecutionSummary {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(resultStatus, "resultStatus");
            Objects.requireNonNull(records, "records");
        }
    }
}
