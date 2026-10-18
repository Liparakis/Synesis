package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.application.ContinuationResult;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.capability.CapabilityActorScope;
import org.synesis.coordination.domain.collaboration.LaneContinuation;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.lifecycle.recovery.RecoverySnapshotService;

import static org.synesis.workspace.application.collaboration.WorkspaceCollaborationService.participantHandle;
import static org.synesis.workspace.application.collaboration.WorkspaceCollaborationService.recoveryContinuationGrantId;
import static org.synesis.workspace.application.collaboration.WorkspaceCollaborationService.recoveryContinuationIntentId;

/**
 * Reads the recovery fence of one exact bound participant and moves a recovery-held lane to a
 * fresh binding. The fence read, the admission read and the continuation share the same rule that
 * no provider-wide or latest-session fallback may substitute for the exact binding, so they are
 * kept apart from {@link WorkspaceCollaborationService}, which stays the public entry point and
 * the owner of the deterministic recovery identity derivations.
 */
final class RecoveryLaneContinuation {

    private final ProjectApplicationService projectService;
    private final ProviderManualService manualService;
    private final SessionAuthorityResolver authorityResolver;

    RecoveryLaneContinuation(ProjectApplicationService projectService, ProviderManualService manualService, SessionAuthorityResolver authorityResolver) {
        this.projectService = projectService;
        this.manualService = manualService;
        this.authorityResolver = authorityResolver;
    }

    /**
     * Reports whether the exact bound participant has been fenced for recovery. No provider-wide or
     * latest-session fallback is permitted because several lanes may share one project.
     *
     * @param projectRoot control project root
     * @param provider provider identifier
     * @param connectionInstanceId exact provider connection
     * @return true when the bound participant is suspended or recovery-held
     * @throws Exception when the binding or the coordination state cannot be read
     */
    boolean recoveryFenced(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        PredictionEventStore store = location.openCoordinationStore();
        Participant.State state = store.collaborationProjection()
                .participantState(participantHandle(binding.sessionId()))
                .orElse(null);
        return state == Participant.State.SUSPENDED || state == Participant.State.RECOVERY_HELD;
    }

    /**
     * Reports whether a fresh authenticated session may defer an overlapping claim long enough to
     * discover an explicit recovery-continuation action.
     *
     * <p>This is an admission read only. It never creates a participant, releases a claim, issues a
     * grant, selects a continuation, or transfers authority. A caller still has to observe
     * {@code get_next_action} and submit the exact server-projected continuation request before it
     * receives the held lane's authority. Exact selectors are required when the caller supplied
     * ownership claims; an empty selector list is accepted only for a role-matching recovery lane,
     * which lets a blind replacement discover its server-projected claim before declaring a new
     * intent.</p>
     *
     * @param projectRoot          project root
     * @param connectionInstanceId fresh connection instance
     * @param role                 requested semantic role
     * @param selectors            requested ownership selectors
     * @param workGroupId          optional group restriction
     * @return {@code true} only when a matching active recovery-held lane exists
     */
    boolean recoveryContinuationAdmission(Path projectRoot, String connectionInstanceId, WorkIntent.Role role, List<ResourceSelector> selectors, UUID workGroupId) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        WorkIntent.Role requestedRole = role == null ? WorkIntent.Role.PRODUCER : role;
        List<ResourceSelector> requestedSelectors = selectors == null ? List.of() : List.copyOf(selectors);
        try {
            ProjectLocation location = projectService.locate(projectRoot);
            PredictionEventStore store = location.openCoordinationStore();
            String currentParticipant = participantHandle(connectionInstanceId);
            return store.collaborationProjection().activeIntents().stream().filter(intent -> intent.role() == requestedRole).filter(intent -> !intent.participant().equals(currentParticipant))
                    .filter(intent -> workGroupId == null || intent.workGroupId().equals(workGroupId))
                    .filter(intent -> store.collaborationProjection().participantState(intent.participant()).orElse(null) == Participant.State.RECOVERY_HELD)
                    .filter(intent -> store.collaborationProjection().recoverySnapshotReference(intent.participant()).filter(reference -> !reference.isBlank()).isPresent())
                    .filter(intent -> store.workGroupProjection().group(intent.workGroupId()).map(group -> group.status() == WorkGroup.Status.ACTIVE).orElse(false))
                    .anyMatch(intent -> requestedSelectors.isEmpty() || requestedSelectors.equals(intent.selectors()));
        } catch (Exception ignored) {
            // A failed read must never turn an overlapping claim into an admission bypass. The normal
            // claim path remains authoritative and will fail closed on the same state.
            return false;
        }
    }

    /**
     * Continues a held recovery lane in the exact caller's new worktree.
     *
     * @param projectRoot          project root
     * @param provider             target provider
     * @param connectionInstanceId target connection
     * @param grantId              optional pre-issued single-use continuation grant
     * @param sourceIntentId       suspended source intent
     * @param claimEpoch           expected source epoch
     * @return durable continuation result, including idempotent replay state
     * @throws Exception when authority, snapshot, or grant validation fails
     */
    ContinuationResult continueLane(Path projectRoot, String provider, String connectionInstanceId, UUID grantId, UUID sourceIntentId, long claimEpoch) throws Exception {
        manualService.requireAttested(provider);
        ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        if (binding.worktreePath() == null) {
            throw new IOException("CONTINUATION_TARGET_WORKTREE_REQUIRED");
        }
        NodeIdentity identity = location.loadOrCreateIdentity();
        PredictionEventStore store = location.openCoordinationStore();
        String targetParticipant = participantHandle(binding.sessionId());
        Optional<UUID> existingSuccessor = store.collaborationProjection().successorIntent(sourceIntentId);
        if (existingSuccessor.isPresent()) {
            WorkIntent successor = store.collaborationProjection().intent(existingSuccessor.get()).orElseThrow(() -> new IOException("CONTINUATION_SUCCESSOR_NOT_FOUND"));
            boolean callerOwnsSuccessor = successor.participant().equals(targetParticipant);
            return ContinuationResult.observed(successor, callerOwnsSuccessor);
        }
        WorkIntent source = store.collaborationProjection().intent(sourceIntentId).orElseThrow(() -> new IOException("CONTINUATION_SOURCE_NOT_FOUND"));
        if (AwayLanes.anotherComputers(store, source, identity.nodeId())) {
            return takeOver(location, store, identity, binding, provider, grantId, source, claimEpoch);
        }
        List<ProviderSessionBindingService.Binding> sourceBindings = new ProviderSessionBindingService().list(location, provider)
                .stream()
                .filter(candidate -> participantHandle(candidate.sessionId()).equals(source.participant()))
                .toList();
        if (sourceBindings.size() != 1) {
            throw new IOException(sourceBindings.isEmpty() ? "RECOVERY_SOURCE_BINDING_NOT_FOUND" : "RECOVERY_SOURCE_BINDING_AMBIGUOUS");
        }
        ProviderSessionBindingService.Binding sourceBinding = sourceBindings.getFirst();
        UUID preIssuedGrantId = grantId;
        boolean preIssuedGrant = preIssuedGrantId != null;
        UUID targetIntentId = preIssuedGrantId == null ? recoveryContinuationIntentId(sourceIntentId, targetParticipant, claimEpoch)
                : store.workGroupProjection().grants().stream().filter(candidate -> candidate.grantId().equals(preIssuedGrantId)).findFirst().map(LaneGrant::targetIntentId)
                  .orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
        if (preIssuedGrantId != null) {
            LaneGrant grant = store.workGroupProjection().grants().stream().filter(candidate -> candidate.grantId().equals(preIssuedGrantId)).findFirst().orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
            if (!grant.targetParticipant().equals(targetParticipant)) {
                throw new IOException("LANE_GRANT_TARGET_MISMATCH");
            }
        } else {
            grantId = recoveryContinuationGrantId(sourceIntentId, targetParticipant, claimEpoch);
        }
        String snapshotReference = store.collaborationProjection().recoverySnapshotReference(source.participant()).orElseThrow(() -> new IOException("RECOVERY_SNAPSHOT_REQUIRED"));
        WorkIntent target = new WorkIntent(targetIntentId,
                source.projectId(),
                targetParticipant,
                provider,
                source.taskId(),
                source.goal(),
                source.acceptance(),
                binding.baseCommit(),
                source.selectors(),
                source.version() + 1,
                source.workGroupId(),
                source.authorityLineageId(),
                WorkIntent.Status.ANNOUNCED,
                source.role(),
                source.reviewTargetSelectors(),
                source.knownDependencies());
        LaneContinuation continuation = new LaneContinuation(grantId,
                sourceIntentId,
                target,
                source.participant(),
                targetParticipant,
                claimEpoch,
                snapshotReference,
                actorScope(sourceBinding),
                actorScope(binding));
        WorkIntentService service = new WorkIntentService(store, identity);
        new RecoverySnapshotService().restoreToLane(snapshotReference, Path.of(binding.worktreePath()));
        if (preIssuedGrant) {
            return service.continueFromRecovery(continuation);
        } else {
            return service.continueEligibleFromRecovery(continuation);
        }
    }

    /**
     * Takes over another computer's lane (ADR-0098 step 7, designer items 9 to 11): judged here, now, that the lane's
     * computer is away and this one stalled, then continued from the lane's last snapshot that reached this computer,
     * or from its base, with no actor scopes, since no session of that computer is here. The worktree gets the
     * snapshot's work before the entry is written, as a combine lane's does.
     */
    private static ContinuationResult takeOver(ProjectLocation location, PredictionEventStore store, NodeIdentity identity, ProviderSessionBindingService.Binding binding,
            String provider, UUID grantId, WorkIntent source, long claimEpoch) throws Exception {
        if (!AwayLanes.offered(store, location.coordinationDirectory(), identity.nodeId()).contains(source)) {
            throw new IOException("TAKEOVER_NOT_OFFERED");
        }
        String targetParticipant = participantHandle(binding.sessionId());
        if (grantId != null) {
            throw new IOException("TAKEOVER_GRANT_NOT_ACCEPTED");
        }
        UUID targetIntentId = recoveryContinuationIntentId(source.intentId(), targetParticipant, claimEpoch);
        Optional<org.synesis.coordination.domain.task.TaskSnapshotRecord> snapshot = AwayLanes.lastSnapshotHere(store, location.root(), source);
        WorkIntent target = new WorkIntent(targetIntentId, source.projectId(), targetParticipant, provider, source.taskId(), source.goal(), source.acceptance(),
                binding.baseCommit(), source.selectors(), source.version() + 1, source.workGroupId(), source.authorityLineageId(), WorkIntent.Status.ANNOUNCED,
                source.role(), source.reviewTargetSelectors(), source.knownDependencies());
        LaneContinuation continuation = new LaneContinuation(recoveryContinuationGrantId(source.intentId(), targetParticipant, claimEpoch), source.intentId(), target,
                source.participant(), targetParticipant, claimEpoch, AwayLanes.reference(snapshot, source));
        var worktrees = new org.synesis.workspace.application.integration.IntegrationWorkspaceService();
        if (snapshot.isPresent()) {
            worktrees.materializeTakeover(Path.of(binding.worktreePath()), snapshot.get());
        }
        try {
            return new WorkIntentService(store, identity).continueEligibleFromRecovery(continuation);
        } catch (Exception refused) {
            // Another taker won, or the lane moved: the worktree goes back to how it was made.
            if (snapshot.isPresent()) {
                worktrees.undoTakeover(Path.of(binding.worktreePath()), snapshot.get());
            }
            throw refused;
        }
    }

    private static CapabilityActorScope actorScope(ProviderSessionBindingService.Binding binding) {
        return new CapabilityActorScope(binding.nodeId(), binding.supervisorId(), binding.workerId(), binding.sessionId());
    }

    private ProviderSessionBindingService.Binding binding(ProjectLocation location, String provider, String connectionId) throws Exception {
        return authorityResolver.resolve(location, provider, connectionId);
    }
}
