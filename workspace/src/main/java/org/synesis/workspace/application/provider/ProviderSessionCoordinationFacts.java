package org.synesis.workspace.application.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentRecord;
import org.synesis.workspace.application.provider.continuity.ManagedAttachmentService;
import org.synesis.workspace.application.provider.continuity.ProviderContinuityMode;

/**
 * Answers read-only questions about whether a binding's session still holds live authority in
 * durable coordination state or in a managed attachment: detached, a committed recovery
 * continuation, a no-change intent, or an active managed runtime. Both the decision to rebind a
 * stale workspace and the decision to keep verifying one rely on these answers, so they are kept
 * apart from {@link ProviderSessionBindingService} to give those two decisions one definition.
 * Unreadable state is reported as "no" or as an exception, never as a guessed "yes".
 */
final class ProviderSessionCoordinationFacts {

    private ProviderSessionCoordinationFacts() {
    }

    static boolean cleanlyDetached(ProjectLocation location, Binding binding) {
        Path coordination = location.coordinationDirectory();
        if (!Files.isDirectory(coordination.resolve("events"))) {
            return false;
        }
        try {
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            return location.openCoordinationStore().collaborationProjection().participantState(participant)
                    .map(state -> state == org.synesis.coordination.domain.collaboration.Participant.State.DETACHED).orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Determines whether a dirty binding is the exact live target of a committed recovery
     * continuation. Snapshot restoration intentionally creates provider-visible changes in the
     * target worktree; the durable continuation event is the authority that distinguishes those
     * changes from an unrelated stale workspace.
     *
     * @param location initialized project location
     * @param binding  exact provider binding
     * @return {@code true} only for an active successor intent bound to this session
     */
    static boolean authorizedRecoveryContinuation(ProjectLocation location, Binding binding) {
        if (binding == null || binding.worktreePath() == null || !Files.isDirectory(Path.of(binding.worktreePath()))) {
            return false;
        }
        try {
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            var projection = location.openCoordinationStore().collaborationProjection();
            return projection.activeIntents().stream().filter(intent -> intent.projectId().equals(location.projectId())).filter(intent -> intent.provider().equals(binding.provider()))
                    .filter(intent -> intent.participant().equals(participant)).filter(intent -> intent.status() == WorkIntent.Status.ANNOUNCED)
                    .anyMatch(intent -> projection.predecessorIntent(intent.intentId()).isPresent() && projection.participantState(participant)
                            .filter(state -> state == org.synesis.coordination.domain.collaboration.Participant.State.ACTIVE).isPresent());
        } catch (Exception failure) {
            return false;
        }
    }

    static boolean hasActiveNoChangeIntent(ProjectLocation location, Binding binding) throws Exception {
        Path events = location.root().resolve(".synesis/coordination/events");
        if (!Files.exists(events)) {
            return false;
        }
        String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
        return location.openCoordinationStore().collaborationProjection().activeIntents().stream()
                .filter(intent -> intent.participant().equals(participant)).anyMatch(intent -> declaresNoRepositoryMutation(intent.acceptance()));
    }

    private static boolean declaresNoRepositoryMutation(String acceptance) {
        String normalized = acceptance.toLowerCase(Locale.ROOT);
        return normalized.contains("without repository mutation") || normalized.contains("no repository mutation is required");
    }

    /**
     * Checks whether a managed runtime still owns this exact binding.
     *
     * <p>An active managed provider process may legitimately retain a clean
     * worktree based on the previous control checkout while a sibling snapshot is integrated.
     * Reallocating that binding would strand the live provider process on its original working
     * directory and make its next exact continuation fail the lifecycle worktree fence.</p>
     *
     * @param location initialized project location
     * @param binding  exact provider binding
     * @return {@code true} only for an active managed attachment on this binding
     */
    static boolean hasActiveManagedAttachment(ProjectLocation location, Binding binding) {
        try {
            ManagedAttachmentRecord attachment = ManagedAttachmentService.storeFor(location, binding.sessionId()).read().orElse(null);
            return attachment != null && attachment.mode() == ProviderContinuityMode.MANAGED_CONTINUITY && attachment.status() == ManagedAttachmentRecord.Status.ACTIVE && attachment.projectId()
                    .equals(binding.projectId()) && attachment.bindingSessionId().equals(binding.sessionId());
        } catch (Exception ignored) {
            return false;
        }
    }
}
