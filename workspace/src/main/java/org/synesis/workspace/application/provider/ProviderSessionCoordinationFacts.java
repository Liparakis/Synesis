package org.synesis.workspace.application.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.application.collaboration.CombineLaneJoin;
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

    /**
     * Reports whether the durable log says this session's lane has ended: detached after a clean
     * shutdown, or completed because its last intent was released. The log is the authority, not
     * the local binding file. A lane integrated by another computer completes the participant in
     * the replicated log but never touches this computer's binding, which would stay BOUND to a
     * participant that can no longer announce.
     */
    static boolean laneEnded(ProjectLocation location, Binding binding) {
        Path coordination = location.coordinationDirectory();
        if (!Files.isDirectory(coordination.resolve("events"))) {
            return false;
        }
        try {
            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            return location.openCoordinationStore().collaborationProjection().participantState(participant)
                    .map(state -> state == org.synesis.coordination.domain.collaboration.Participant.State.DETACHED
                            || state == org.synesis.coordination.domain.collaboration.Participant.State.COMPLETED).orElse(false);
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

    /**
     * Reports whether a binding records the base its worktree is judged against: this computer's control head, or the
     * base of the combine lane its session holds open. A combine lane's worktree is moved on purpose to the other copy
     * of {@code main}, the commit the re-merge could not merge onto (ADR-0098 step 5, designer item 26); judged against
     * this computer's {@code main} it would read as stale, and its conflict markers as work to refresh. Every other
     * lane keeps the control-head rule, and unreadable coordination state answers "no". The log is read only when the
     * base is not the control head, so an ordinary lane's check costs nothing more.
     *
     * @param location    initialized project location
     * @param binding     exact provider binding
     * @param controlHead this computer's current control head
     * @return {@code true} when the binding's base is the control head or its session's open combine lane's base
     */
    static boolean atJudgedBase(ProjectLocation location, Binding binding, String controlHead) {
        if (binding.baseCommit().equals(controlHead)) {
            return true;
        }
        if (!Files.isDirectory(location.coordinationDirectory().resolve("events"))) {
            return false;
        }
        try {
            return CombineLaneJoin.heldBase(location.openCoordinationStore(), binding.sessionId()).filter(binding.baseCommit()::equals).isPresent();
        } catch (Exception unreadable) {
            return false;
        }
    }

    static boolean hasActiveNoChangeIntent(ProjectLocation location, Binding binding) throws Exception {
        Path events = location.coordinationDirectory().resolve("events");
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
