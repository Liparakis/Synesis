package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.task.TaskCompletionState;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.integration.IntegrationWorkspaceService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;

import static org.synesis.workspace.application.collaboration.WorkspaceCollaborationService.participantHandle;

import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService.ScopeExtensionRequiredException;
import org.synesis.workspace.application.task.SnapshotRefs;

/**
 * Admits a verified provider session's lane: first announcement, scope extension of the session's
 * existing lane, and joining an immutable conflict-repair lane. These are the paths that decide
 * which intent identity, work group and claim epoch a binding may hold, so they are kept apart
 * from the thin binding-to-coordination delegates of {@link WorkspaceCollaborationService}, which
 * stays the public entry point.
 */
final class WorkIntentAnnouncement {

    private final ProjectApplicationService projectService;
    private final ProviderManualService manualService;
    private final SessionAuthorityResolver authorityResolver;

    WorkIntentAnnouncement(ProjectApplicationService projectService, ProviderManualService manualService, SessionAuthorityResolver authorityResolver) {
        this.projectService = projectService;
        this.manualService = manualService;
        this.authorityResolver = authorityResolver;
    }

    /**
     * Announces a new lane or extends this binding's existing lane under exact scope preconditions.
     *
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact bound connection
     * @param goal optional task description
     * @param acceptance optional acceptance description
     * @param selectors requested ownership selectors
     * @param workGroupId optional existing group for a new lane
     * @param role lane role
     * @param reviewTargetSelectors non-ownership review targets
     * @param knownDependencies requested capability dependencies
     * @param expectedIntentId server-projected current intent, or {@code null} for ordinary admission
     * @param expectedClaimEpoch server-projected current epoch, or {@code null} for ordinary admission
     * @return complete acquisition result
     * @throws Exception resolution or append failure
     */
    ClaimResult announce(Path projectRoot,
            String provider,
            String connectionInstanceId,
            String goal,
            String acceptance,
            List<ResourceSelector> selectors,
            UUID workGroupId,
            WorkIntent.Role role,
            List<ResourceSelector> reviewTargetSelectors,
            List<String> knownDependencies,
            UUID expectedIntentId,
            Long expectedClaimEpoch) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        manualService.requireAttested(provider);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = location.loadOrCreateIdentity();
        PredictionEventStore store = location.openCoordinationStore();
        String participant = participantHandle(binding.sessionId());
        var existing = store.collaborationProjection().participants().stream().filter(candidate -> candidate.id().equals(participant)).findFirst();
        if (existing.isPresent() && existing.get().state() != Participant.State.ACTIVE) {
            throw new IOException("SESSION_EPOCH_FENCED");
        }
        WorkIntentService service = new WorkIntentService(store, identity);
        UUID taskId = LaneIdentities.connectionUuid(connectionInstanceId);
        UUID intentId = LaneIdentities.sessionIntentId(provider, binding.sessionId());
        WorkIntent existingIntent = WorkspaceCollaborationService.heldIntent(store, provider, binding.sessionId()).orElse(null);
        if (existingIntent != null) {
            boolean hasScopeChange = selectors.stream().anyMatch(selector -> !existingIntent.selectors().contains(selector))
                    || knownDependencies != null && knownDependencies.stream()
                            .anyMatch(dependency -> !existingIntent.knownDependencies().contains(dependency))
                    || goal != null && !goal.equals(existingIntent.goal())
                    || acceptance != null && !acceptance.equals(existingIntent.acceptance());
            if (role != null && role != existingIntent.role()) {
                throw new IOException("SCOPE_EXTENSION_ROLE_CHANGE_REQUIRES_LIFECYCLE");
            }
            if (workGroupId != null && !workGroupId.equals(existingIntent.workGroupId())) {
                throw new IOException("SCOPE_EXTENSION_GROUP_CHANGE_REQUIRES_LIFECYCLE");
            }
            if (reviewTargetSelectors != null && !reviewTargetSelectors.isEmpty()
                    && !reviewTargetSelectors.equals(existingIntent.reviewTargetSelectors())) {
                throw new IOException("SCOPE_EXTENSION_REVIEW_TARGET_CHANGE_REQUIRES_LIFECYCLE");
            }
            if (!hasScopeChange) {
                // Naming the lane the session already holds changes nothing, whatever identifier or
                // epoch it quotes.
                return new ClaimResult(true, existingIntent, List.of());
            }
            // A change has to quote the lane's exact identifier and epoch. A caller that quotes
            // neither, only one, or a stale pair is answered with the pair to use: it holds the
            // lane, and an unexplained refusal sent agents looking for human help.
            if (expectedIntentId == null || expectedClaimEpoch == null || !expectedIntentId.equals(existingIntent.intentId())
                    || expectedClaimEpoch.longValue() != existingIntent.version()) {
                throw new ScopeExtensionRequiredException(existingIntent);
            }
            return service.extendScope(existingIntent.identity(),
                    new ParticipantId(participant),
                    new ClaimEpoch(expectedClaimEpoch),
                    selectors,
                    goal,
                    acceptance,
                    knownDependencies == null ? List.of() : knownDependencies);
        }
        if (expectedIntentId != null || expectedClaimEpoch != null) {
            throw new IOException("SCOPE_EXTENSION_INTENT_NOT_FOUND");
        }
        UUID defaultGroup = defaultWorkGroupId(location.projectId());
        UUID group = workGroupId == null ? store.workGroupProjection().groups().stream().filter(candidate -> candidate.projectId().equals(location.projectId()))
                                           .filter(candidate -> candidate.status() == WorkGroup.Status.ACTIVE).map(WorkGroup::workGroupId).findFirst()
                                           .orElseGet(() -> store.workGroupProjection().group(defaultGroup).isEmpty() ? defaultGroup : UUID.randomUUID()) : workGroupId;
        WorkIntent intent = new WorkIntent(intentId,
                location.projectId(),
                participant,
                provider,
                taskId,
                goal == null ? "Unspecified work" : goal,
                acceptance == null ? "Unspecified acceptance" : acceptance,
                binding.baseCommit(),
                selectors,
                1,
                group,
                WorkIntent.defaultAuthorityLineage(intentId),
                WorkIntent.Status.ANNOUNCED,
                role == null ? WorkIntent.Role.PRODUCER : role,
                reviewTargetSelectors == null ? List.of() : reviewTargetSelectors,
                knownDependencies == null ? List.of() : knownDependencies);
        boolean restoring = intent.role() == WorkIntent.Role.PRODUCER && binding.worktreePath() != null
                && WorkTrash.applyRestored(location, store, group, Path.of(binding.worktreePath()));
        try {
            return service.announce(intent, location.review());
        } catch (Exception refused) {
            // The restored work goes with the refused lane, not into whatever this worktree announces next.
            if (restoring) {
                WorkTrash.undoRestored(store, group, Path.of(binding.worktreePath()));
            }
            throw refused;
        }
    }

    static UUID defaultWorkGroupId(UUID projectId) {
        return UUID.nameUUIDFromBytes(("default-work-group:" + projectId).getBytes(StandardCharsets.UTF_8));
    }

    static UUID repairJoinIntentId(UUID repairIntentId, String sessionId) {
        return UUID.nameUUIDFromBytes(("repair-join|" + repairIntentId + "|" + sessionId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Joins an immutable conflict repair lane from a newly authenticated isolated binding. The local
     * project identity is the recovery authority; the old lane and snapshot remain immutable while
     * the new binding receives a fresh intent ID and claim epoch.
     *
     * @param projectRoot          control project root
     * @param provider             provider ID
     * @param connectionInstanceId exact new connection
     * @param repairIntentId       currently reserved repair intent
     * @param snapshotId           immutable conflicting snapshot
     * @return acquired claim result
     * @throws Exception resolution, materialization, or append failure
     */
    ClaimResult joinRepair(Path projectRoot, String provider, String connectionInstanceId, UUID repairIntentId, String snapshotId) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        manualService.requireAttested(provider);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        if (binding.worktreePath() == null) {
            throw new IOException("REPAIR_BINDING_NOT_READY");
        }
        NodeIdentity identity = location.loadOrCreateIdentity();
        PredictionEventStore store = location.openCoordinationStore();
        // A snapshot whose re-merge Git could not make is combined in a combine lane, taken with this same call
        // (ADR-0098 step 5, designer item 22).
        java.util.Optional<org.synesis.coordination.domain.task.CombinePayload> combine = CombineLaneJoin.openFor(store, snapshotId, binding.sessionId());
        if (combine.isPresent()) {
            return CombineLaneJoin.join(store, identity, location.projectId(), location.root(), Path.of(binding.worktreePath()),
                    participantHandle(binding.sessionId()), binding.sessionId(), provider, combine.get());
        }
        TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotById(snapshotId).orElseThrow(() -> new IOException("REPAIR_SNAPSHOT_NOT_FOUND"));
        if (!snapshot.provenance().snapshotRef().equals(SnapshotRefs.snapshotRef(snapshot.snapshotId()))) {
            throw new IOException("REPAIR_SNAPSHOT_REF_MISMATCH");
        }
        UUID targetId = repairJoinIntentId(repairIntentId, binding.sessionId());
        WorkIntent existingTarget = store.collaborationProjection().intent(targetId).orElse(null);
        if (existingTarget != null) {
            List<String> expectedSelectors = snapshot.provenance().claimSelectors();
            if (!existingTarget.participant().equals(participantHandle(binding.sessionId())) || !existingTarget.workGroupId().equals(snapshot.provenance().workGroupId()) || !existingTarget.authorityLineageId()
                    .equals(snapshot.provenance().authorityLineageId()) || existingTarget.version() != snapshot.provenance().claimEpoch() + 1L || !existingTarget.selectors().stream()
                    .map(ResourceSelector::encode).toList().equals(expectedSelectors)) {
                throw new IOException("REPAIR_TARGET_MISMATCH");
            }
            return new ClaimResult(true, existingTarget, List.of());
        }
        WorkIntent source = store.collaborationProjection().intent(repairIntentId).orElseThrow(() -> new IOException("REPAIR_INTENT_NOT_FOUND"));
        if (store.taskCompletionProjection().taskState(snapshot.taskId()) != TaskCompletionState.REPAIR_REQUIRED) {
            throw new IOException("REPAIR_SNAPSHOT_NOT_CONFLICTED");
        }
        if (!snapshot.provenance().laneId().equals(source.intentId()) || !snapshot.provenance().workGroupId().equals(source.workGroupId()) || !snapshot.provenance().authorityLineageId()
                .equals(source.authorityLineageId()) || snapshot.provenance().claimEpoch() != source.version() || !snapshot.provenance().participant().equals(source.participant()) || !snapshot.provenance()
                .claimSelectors().equals(source.selectors().stream().map(ResourceSelector::encode).toList())) {
            throw new IOException("REPAIR_LINEAGE_OR_SCOPE_MISMATCH");
        }
        IntegrationWorkspaceService integration = new IntegrationWorkspaceService();
        CodeExchange.ensureCommit(location.coordinationDirectory(), location.projectId(), location.root(), snapshot.commitSha());
        try (ProjectAppendLock appendLock = ProjectAppendLock.acquire(store.rootDirectory())) {
            if (!appendLock.isHeld()) {
                throw new IOException("event append lock unavailable");
            }
            String expectedControlHead = integration.currentHead(location.root());
            WorkIntent target = new WorkIntent(targetId,
                    location.projectId(),
                    participantHandle(binding.sessionId()),
                    provider,
                    snapshot.taskId(),
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
            integration.materializeRepairRepresentation(location.root(), Path.of(binding.worktreePath()), expectedControlHead, snapshot);
            new WorkIntentService(store, identity).createRepairLane(appendLock, repairIntentId, snapshot.snapshotId(), expectedControlHead, target);
            return new ClaimResult(true, target, List.of());
        }
    }

    private ProviderSessionBindingService.Binding binding(ProjectLocation location, String provider, String connectionId) throws Exception {
        return authorityResolver.resolve(location, provider, connectionId);
    }
}
