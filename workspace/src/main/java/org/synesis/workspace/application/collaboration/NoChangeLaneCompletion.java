package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

import static org.synesis.workspace.application.collaboration.WorkspaceCollaborationService.participantHandle;

/**
 * Completes a lane successfully without publishing a snapshot. Every caller-supplied identifier is
 * only optimistic evidence; this class re-derives the participant, binding and workspace state and
 * rejects stale or unsafe completions. It is separate from {@link WorkspaceCollaborationService}
 * because it is the one lane-ending path that holds real admission logic rather than a plain
 * binding-to-coordination call.
 */
final class NoChangeLaneCompletion {

    private final ProjectApplicationService projectService;
    private final ProviderManualService manualService;
    private final WorkspaceReadinessService readinessService;

    NoChangeLaneCompletion(ProjectApplicationService projectService, ProviderManualService manualService, WorkspaceReadinessService readinessService) {
        this.projectService = projectService;
        this.manualService = manualService;
        this.readinessService = readinessService;
    }

    /**
     * Completes the exact bound intent successfully without publishing a snapshot.
     *
     * <p>All caller-selected identifiers are treated as optimistic evidence.
     * The participant, binding identity, authority lineage, and workspace commit are derived from the
     * verified binding and current intent.</p>
     *
     * @param projectRoot              project root
     * @param provider                 provider ID
     * @param connectionInstanceId     exact connection ID
     * @param expectedIntentId         expected active intent
     * @param expectedWorkGroupId      expected work group
     * @param expectedClaimEpoch       expected claim epoch
     * @param expectedWorkGroupVersion expected work-group version
     * @param expectedRevision         expected project event revision
     * @param expectedParticipant      expected participant handle
     * @param summary                  completion explanation
     * @return durable no-change completion evidence
     * @throws Exception when authority, workspace, or lifecycle evidence is stale
     */
    NoChangeCompletion completeNoChange(Path projectRoot,
            String provider,
            String connectionInstanceId,
            UUID expectedIntentId,
            UUID expectedWorkGroupId,
            long expectedClaimEpoch,
            long expectedWorkGroupVersion,
            long expectedRevision,
            String expectedParticipant,
            String summary) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        manualService.requireAttested(provider);
        ProviderSessionBindingService.Binding binding;
        ReadinessResult readiness = readinessService.assessNoChange(location, provider, connectionInstanceId);
        if (!readiness.ready()) {
            throw new IOException(readiness.internalReason());
        }
        binding = readiness.binding();
        ProviderSessionBindingService.Binding verifiedBinding = binding;
        String participant = participantHandle(binding.sessionId());
        if (!participant.equals(expectedParticipant)) {
            throw new IOException("NO_CHANGE_PARTICIPANT_MISMATCH");
        }
        if (expectedIntentId == null || expectedWorkGroupId == null || expectedRevision < 0) {
            throw new IOException("NO_CHANGE_COMPLETION_EVIDENCE_REQUIRED");
        }
        if (!new TaskSnapshotService().isCleanWorktree(readiness.worktree())) {
            throw new IOException("NO_CHANGE_DIRTY_WORKSPACE");
        }
        NodeIdentity identity = location.loadOrCreateIdentity();
        PredictionEventStore store = location.openCoordinationStore();
        WorkIntent intent = store.collaborationProjection().intent(expectedIntentId).orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
        WorkGroup group = store.workGroupProjection().group(expectedWorkGroupId).orElseThrow(() -> new IOException("WORK_GROUP_NOT_FOUND"));
        if (!intent.workGroupId().equals(expectedWorkGroupId) || intent.version() != expectedClaimEpoch || group.version() != expectedWorkGroupVersion || !store.unchangedSince(expectedRevision)) {
            throw new IOException("NO_CHANGE_COMPLETION_EVIDENCE_STALE");
        }
        boolean participantObligation = store.workGroupProjection().grants().stream().filter(grant -> grant.workGroupId().equals(expectedWorkGroupId)).filter(grant -> grant.targetParticipant().equals(participant))
                .filter(grant -> !store.workGroupProjection().continuationGrant(grant.grantId())).filter(grant -> !store.workGroupProjection().grantSuperseded(grant.grantId()))
                .anyMatch(grant -> store.workGroupProjection().grantAvailable(grant.grantId()) || (store.workGroupProjection().grantConsumed(grant.grantId()) && store.workGroupProjection()
                        .reviewValidationForGrant(grant.grantId()).isEmpty()));
        if (participantObligation) {
            throw new IOException("NO_CHANGE_REVIEW_OBLIGATION");
        }
        boolean pendingDependency = store.capabilityRequestProjection().findAllForRequester(identity.nodeId()).stream()
                .filter(candidate -> candidate.matchesRequester(identity.nodeId(), verifiedBinding.supervisorId(), verifiedBinding.workerId()))
                .anyMatch(candidate -> candidate.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.AWAITING_OWNER
                        || candidate.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.REVISION_REQUESTED
                        || candidate.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTING
                        || candidate.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                        || candidate.state() == org.synesis.coordination.domain.capability.CapabilityLifecycleState.VALIDATING);
        if (pendingDependency || !store.capabilityRequestProjection().allValidationContexts().isEmpty()) {
            throw new IOException("NO_CHANGE_DEPENDENCY_PENDING");
        }
        NoChangeCompletion completion = new NoChangeCompletion(expectedIntentId,
                expectedWorkGroupId,
                participant,
                provider,
                binding.sessionId(),
                intent.authorityLineageId(),
                expectedClaimEpoch,
                expectedWorkGroupVersion,
                expectedRevision,
                binding.baseCommit(),
                summary);
        return new WorkIntentService(store, identity).completeNoChange(completion);
    }
}
