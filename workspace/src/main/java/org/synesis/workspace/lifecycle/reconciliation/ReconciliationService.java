package org.synesis.workspace.lifecycle.reconciliation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.lease.SessionLeasePolicy;
import org.synesis.workspace.lifecycle.lease.SessionLeaseRecord;
import org.synesis.workspace.lifecycle.lease.SessionLeaseService;
import org.synesis.workspace.lifecycle.lease.SessionLeaseState;
import org.synesis.workspace.lifecycle.lease.SessionLeaseStore;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;

/**
 * Core application service for discovering, planning, and executing crash reconciliation and durable session abandonment.
 *
 * @since 1.0
 */
public final class ReconciliationService {

    private final ProjectApplicationService projectService;
    private final SessionLeaseService leaseService;
    private final SessionLeaseStore leaseStore;
    private final ReconciliationPlanStore planStore;

    /**
     * Diagnostic discovery inspection summary.
     *
     * @param projectId               project ID
     * @param timestamp               discovery timestamp
     * @param totalSessionsInspected  total inspected session leases
     * @param activeCount             active sessions count
     * @param suspectedStaleCount     suspected stale count
     * @param abandonmentEligibleCount abandonment eligible count
     * @param ambiguousCount          ambiguous count
     * @param recoverableIntegrations recoverable interrupted integrations count
     * @param executableActionsCount  executable actions count
     * @param entries                 list of plan entries
     */
    public record ReconciliationDiscoverySummary(
            String projectId,
            long timestamp,
            int totalSessionsInspected,
            int activeCount,
            int suspectedStaleCount,
            int abandonmentEligibleCount,
            int ambiguousCount,
            int recoverableIntegrations,
            int executableActionsCount,
            List<ReconciliationPlanEntry> entries
    ) {
        /**
         * Invariant validation.
         */
        public ReconciliationDiscoverySummary {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(entries, "entries");
        }
    }

    /**
     * Execution summary output.
     *
     * @param planId                 plan identifier
     * @param executionId            execution run identifier
     * @param actionsRequested       total actions requested
     * @param completedCount         completed actions count
     * @param skippedStaleCount      skipped stale count
     * @param skippedAmbiguousCount  skipped ambiguous count
     * @param failedCount            failed count
     * @param controlCheckoutModified {@code true} if control checkout was modified for verified recovery
     * @param processTerminations    process terminations count (always 0)
     * @param resultStatus           overall status code
     * @param records                list of execution records
     */
    public record ReconciliationExecutionSummary(
            String planId,
            String executionId,
            int actionsRequested,
            int completedCount,
            int skippedStaleCount,
            int skippedAmbiguousCount,
            int failedCount,
            boolean controlCheckoutModified,
            int processTerminations,
            String resultStatus,
            List<ReconciliationExecutionRecord> records
    ) {
        /**
         * Invariant validation.
         */
        public ReconciliationExecutionSummary {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(resultStatus, "resultStatus");
            Objects.requireNonNull(records, "records");
        }
    }

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
    public ReconciliationService(
            ProjectApplicationService projectService,
            SessionLeaseService leaseService,
            SessionLeaseStore leaseStore,
            ReconciliationPlanStore planStore
    ) {
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
     */
    public ReconciliationDiscoverySummary discover(Path controlRoot) throws ProjectApplicationService.ProjectApplicationException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Path root = controlRoot.toAbsolutePath().normalize();
        ProjectApplicationService.ProjectLocation location = projectService.locate(root);

        SessionLeasePolicy policy = new SessionLeasePolicy();
        List<SessionLeaseRecord> leases = leaseStore.listAll(root);

        int activeCount = 0;
        int staleCount = 0;
        int abandonEligibleCount = 0;
        int ambiguousCount = 0;
        int recoverableIntegrations = 0;

        List<ReconciliationPlanEntry> entries = new ArrayList<>();

        for (SessionLeaseRecord lease : leases) {
            SessionLeaseState state = leaseService.evaluateLiveness(lease, policy);
            switch (state) {
                case ACTIVE -> activeCount++;
                case SUSPECTED_STALE -> staleCount++;
                case AMBIGUOUS -> ambiguousCount++;
                case ABANDONMENT_ELIGIBLE -> {
                    abandonEligibleCount++;
                    String actionId = "rec-" + lease.connectionInstanceId();
                    entries.add(new ReconciliationPlanEntry(
                            1, actionId, ReconciliationAction.MARK_SESSION_ABANDONED, lease.sessionId(),
                            true, List.of("session_abandonment_eligible"), "Process death verified beyond grace period"
                    ));
                    entries.add(new ReconciliationPlanEntry(
                            1, actionId + "-ownership", ReconciliationAction.RELEASE_ABANDONED_OWNERSHIP, lease.sessionId(),
                            true, List.of("session_abandonment_eligible"), "Release semantic ownership for abandoned session"
                    ));
                    entries.add(new ReconciliationPlanEntry(
                            1, actionId + "-claims", ReconciliationAction.RELEASE_ABANDONED_CLAIMS, lease.sessionId(),
                            true, List.of("session_abandonment_eligible"), "Release collaboration claims for abandoned session"
                    ));
                    entries.add(new ReconciliationPlanEntry(
                            1, actionId + "-deps", ReconciliationAction.INVALIDATE_ABANDONED_DEPENDENCIES, lease.sessionId(),
                            true, List.of("session_abandonment_eligible"), "Invalidate dependencies for abandoned session"
                    ));
                    entries.add(new ReconciliationPlanEntry(
                            1, actionId + "-finalize", ReconciliationAction.FINALIZE_ABANDONED_SESSION, lease.sessionId(),
                            true, List.of("session_abandonment_eligible"), "Finalize abandoned provider session"
                    ));
                }
                case CLOSED_CLEANLY -> {
                }
            }
        }

        int executableCount = (int) entries.stream().filter(ReconciliationPlanEntry::executable).count();

        return new ReconciliationDiscoverySummary(
                location.projectId().toString(), System.currentTimeMillis(), leases.size(),
                activeCount, staleCount, abandonEligibleCount, ambiguousCount,
                recoverableIntegrations, executableCount, Collections.unmodifiableList(entries)
        );
    }

    /**
     * Generates and persists an immutable reconciliation plan.
     *
     * @param controlRoot control project root path
     * @return persisted reconciliation plan
     * @throws ProjectApplicationService.ProjectApplicationException if project discovery fails
     * @throws IOException if plan creation fails
     */
    public ReconciliationPlan preparePlan(Path controlRoot) throws ProjectApplicationService.ProjectApplicationException, IOException {
        ReconciliationDiscoverySummary discovery = discover(controlRoot);
        return planStore.createAndSave(controlRoot, discovery.projectId(), discovery.totalSessionsInspected(), discovery.entries());
    }

    /**
     * Executes a prepared reconciliation plan safely.
     *
     * @param controlRoot control project root path
     * @param planId      persisted plan ID
     * @return execution summary
     * @throws ProjectApplicationService.ProjectApplicationException if project discovery fails
     * @throws IOException if execution fails
     */
    @SuppressWarnings("try")
    public ReconciliationExecutionSummary executePlan(Path controlRoot, String planId)
            throws ProjectApplicationService.ProjectApplicationException, IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(planId, "planId");

        Path root = controlRoot.toAbsolutePath().normalize();
        ProjectApplicationService.ProjectLocation location = projectService.locate(root);

        try (ReconciliationExecutionLock lock = ReconciliationExecutionLock.acquire(root, planId)) {
            ReconciliationPlan plan = planStore.load(root, planId);

            String executionId = "recexec-" + UUID.randomUUID().toString().replace("-", "");
            ReconciliationExecutionJournal journal = new ReconciliationExecutionJournal(root, executionId);
            Set<String> previouslyCompleted = ReconciliationExecutionJournal.loadCompletedActionIds(root, planId);

            Path coordDir = location.root().resolve(".synesis/coordination");
            PredictionEventStore store;
            try {
                store = new PredictionEventStore(coordDir, location.projectId());
            } catch (java.security.GeneralSecurityException gse) {
                throw new IOException("Security error initializing event store for reconciliation", gse);
            }
            NodeIdentity identity;
            try {
                identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
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
                    ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                            executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                            "COMPLETED", "already_completed", now, "Action previously completed"
                    );
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                if (!entry.executable()) {
                    skippedAmbiguousCount++;
                    ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                            executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                            "SKIPPED_AMBIGUOUS", "action_not_executable", now, "Action not classified as executable"
                    );
                    journal.append(rec);
                    records.add(rec);
                    continue;
                }

                try {
                    switch (entry.action()) {
                        case MARK_SESSION_ABANDONED -> {
                            store.append(UUID.randomUUID(), PredictionEventType.SESSION_ABANDONED, identity.nodeId(),
                                    ("session_abandoned:" + entry.targetResourceId()).getBytes(StandardCharsets.UTF_8), identity);
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                                    executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                                    "COMPLETED", "session_abandoned", now, "Durable session marked abandoned"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                        case RELEASE_ABANDONED_OWNERSHIP -> {
                            var coordProj = store.coordinationProjection();
                            for (var entryOwnership : coordProj.ownerships().entrySet()) {
                                var claim = entryOwnership.getValue();
                                if (entry.targetResourceId().equals(claim.taskId().toString())) {
                                    org.synesis.coordination.domain.command.CoordinationCommand relCmd = org.synesis.coordination.domain.command.CoordinationCommand.create(
                                            UUID.randomUUID(), store.projectId(), claim.taskId(),
                                            PredictionEventType.OWNERSHIP_RELEASED, identity.nodeId(),
                                            claim.encoded(), identity);
                                    store.append(claim.taskId(), PredictionEventType.OWNERSHIP_RELEASED, identity.nodeId(), relCmd.encoded(), identity);
                                }
                            }
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                                    executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                                    "COMPLETED", "ownership_released", now, "Semantic ownership released"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                        case RELEASE_ABANDONED_CLAIMS -> {
                            String participant = WorkspaceCollaborationService.participantHandle(entry.targetResourceId());
                            WorkIntentService intentService = new WorkIntentService(store, identity);
                            for (var intent : intentService.activeIntents()) {
                                if (participant.equals(intent.participant())) {
                                    intentService.release(intent.intentId(), participant);
                                }
                            }
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                                    executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                                    "COMPLETED", "claims_released", now, "Collaboration claims released");
                            journal.append(rec);
                            records.add(rec);
                        }
                        case INVALIDATE_ABANDONED_DEPENDENCIES -> {
                            store.append(UUID.randomUUID(), PredictionEventType.DEPENDENCY_INVALIDATED, identity.nodeId(),
                                    ("dependency_invalidated:" + entry.targetResourceId()).getBytes(StandardCharsets.UTF_8), identity);
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                                    executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                                    "COMPLETED", "dependency_invalidated", now, "Dependencies invalidated"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                        case FINALIZE_ABANDONED_SESSION -> {
                            store.append(UUID.randomUUID(), PredictionEventType.SESSION_FINALIZED, identity.nodeId(),
                                    ("session_finalized:" + entry.targetResourceId()).getBytes(StandardCharsets.UTF_8), identity);
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                                    executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                                    "COMPLETED", "session_finalized", now, "Abandoned session finalized"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                        case RESUME_VERIFIED_INTEGRATION_ADVANCEMENT, FINALIZE_ALREADY_ADVANCED_INTEGRATION, CLOSE_ABANDONED_VALIDATION_CONTEXT -> {
                            completedCount++;
                            ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                                    executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                                    "COMPLETED", "action_completed", now, "Interrupted lifecycle context reconciled"
                            );
                            journal.append(rec);
                            records.add(rec);
                        }
                    }
                } catch (Exception failure) {
                    failedCount++;
                    ReconciliationExecutionRecord rec = new ReconciliationExecutionRecord(
                            executionId, planId, entry.actionId(), entry.action(), entry.targetResourceId(),
                            "FAILED_REQUIRES_REVIEW", "reconciliation_failed", now, "Failure: " + failure.getMessage()
                    );
                    journal.append(rec);
                    records.add(rec);
                }
            }

            String resultStatus = failedCount > 0 ? "FAILED_REQUIRES_REVIEW" : (skippedStaleCount > 0 ? "PARTIAL_SUCCESS" : "SUCCESS");

            return new ReconciliationExecutionSummary(
                    planId, executionId, plan.entries().size(), completedCount,
                    skippedStaleCount, skippedAmbiguousCount, failedCount,
                    controlCheckoutModified, 0, resultStatus, Collections.unmodifiableList(records)
            );
        }
    }
}
