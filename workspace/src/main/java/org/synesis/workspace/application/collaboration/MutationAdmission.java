package org.synesis.workspace.application.collaboration;

import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;

import static org.synesis.workspace.application.collaboration.WorkspaceCollaborationService.participantHandle;

/**
 * Decides whether a file mutation is covered by an active claim. It reads durable coordination
 * state only and holds none of its own, so the decision stays stateless and fail-closed: any
 * failure to read that state classifies the mutation as requiring a coordination intent. It is
 * separate from {@link WorkspaceCollaborationService} because the completion-fence and
 * claim-ownership rules form one authority policy that the binding delegates should not obscure.
 */
final class MutationAdmission {

    private MutationAdmission() {
    }

    /**
     * Returns a stable mutation authorization classification for an already resolved binding.
     *
     * @param location     initialized project location
     * @param binding      exact provider binding
     * @param relativePath repository-relative target
     * @return {@code allowed}, {@code overlapping_claim}, or {@code coordination_intent_required}
     */
    static String mutationReason(ProjectLocation location, ProviderSessionBindingService.Binding binding, String relativePath) {
        try {
            NodeIdentity identity = location.loadOrCreateIdentity();
            PredictionEventStore store = location.openCoordinationStore();
            WorkIntentService service = new WorkIntentService(store, identity);
            if (service.activeIntents().isEmpty() && !store.collaborationProjection().activated()) {
                return "allowed";
            }
            ResourceSelector selector = ResourceSelector.pathExact(relativePath);
            // A completion transaction fences its exact lane before the
            // prepared tree is durable.  Do not permit a caller to mutate a
            // worktree after preparation or publication, even if the claim
            // projection still contains the reserved selector.
            String participant = participantHandle(binding.sessionId());
            boolean fenced = store.taskCompletionProjection().allSnapshots().stream()
                    .filter(snapshot -> snapshot.provenance().bindingSessionIdentity().equals(new ProviderSessionId(binding.sessionId())) || snapshot.provenance().participant().equals(participant))
                    .map(snapshot -> store.taskCompletionProjection().taskState(snapshot.taskId()))
                    .anyMatch(state -> state == org.synesis.coordination.domain.task.TaskCompletionState.COMPLETION_PREPARED || state == org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATION_PENDING
                            || state == org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATING || state == org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATION_BLOCKED
                            || state == org.synesis.coordination.domain.task.TaskCompletionState.REPAIR_REQUIRED);
            fenced = fenced || store.taskCompletionProjection().allPrepared().stream().anyMatch(prepared -> service.activeIntents().stream()
                    .anyMatch(intent -> intent.intentId().equals(prepared.laneId()) && intent.participant().equals(participant) && intent.version() == prepared.claimEpoch()));
            if (fenced) {
                return "coordination_intent_required";
            }
            if (service.owns(participant, selector)) {
                return "allowed";
            }
            return service.activeIntents().stream().anyMatch(intent -> intent.selectors().stream().anyMatch(selector::overlaps)) ? "overlapping_claim" : "coordination_intent_required";
        } catch (Exception failure) {
            return "coordination_intent_required";
        }
    }

    /**
     * Returns whether an exact active claim authorizes this binding to mutate the target.
     *
     * <p>This is narrower than {@link #mutationReason(ProjectLocation,
     * ProviderSessionBindingService.Binding, String)}, which intentionally allows legacy projects
     * that have not activated collaboration. A claim is required when continuing a stale assigned
     * worktree after a sibling advances the control checkout.</p>
     *
     * @param location     initialized project location
     * @param binding      exact provider binding
     * @param relativePath repository-relative target
     * @return true only when the binding's active intent owns the target and has no completion fence
     */
    static boolean hasActiveMutationClaim(ProjectLocation location, ProviderSessionBindingService.Binding binding, String relativePath) {
        try {
            if (binding == null || relativePath == null || relativePath.isBlank()) {
                return false;
            }
            NodeIdentity identity = location.loadOrCreateIdentity();
            PredictionEventStore store = location.openCoordinationStore();
            WorkIntentService service = new WorkIntentService(store, identity);
            String participant = participantHandle(binding.sessionId());
            ResourceSelector selector = ResourceSelector.pathExact(relativePath);
            return service.owns(participant, selector) && "allowed".equals(mutationReason(location, binding, relativePath));
        } catch (Exception failure) {
            return false;
        }
    }
}
