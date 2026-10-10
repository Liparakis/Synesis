package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProjectApplicationService.ProjectApplicationException;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.BindingException;
import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope;
import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope.AuthorityContext;
import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope.Operation;

/**
 * Decides whether a signed lifecycle request is still authorised by the current provider binding,
 * participant, worktree, and WorkIntent. It only reads; every ambiguous or mismatching fact fails
 * closed, and the order of the checks is part of the failure contract.
 */
final class LifecycleAuthorityVerifier {

    private final ProjectLocation location;

    LifecycleAuthorityVerifier(ProjectLocation location) {
        this.location = location;
    }

    void verify(LifecycleControlRequestEnvelope request) throws IOException, GeneralSecurityException, ProjectApplicationException, BindingException {
        ProjectLocation current = new ProjectApplicationService().locate(location.root());
        Binding binding = resolveBinding(current, request);
        verifyParticipantAndWorktreeAuthority(binding, request.authority());
    }

    /**
     * Checks that the verified binding still owns the exact participant, worktree, and intent.
     */
    private void verifyParticipantAndWorktreeAuthority(Binding binding, AuthorityContext expected) throws IOException, GeneralSecurityException {
        if (!expected.participant().equals(WorkspaceCollaborationService.participantHandle(binding.sessionId()))) {
            throw new IOException("lifecycle_participant_mismatch");
        }
        Path assigned;
        try {
            assigned = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            if (!assigned.toString().equals(expected.canonicalWorktree()) || !assigned.toRealPath().toString().equals(expected.realWorktree())) {
                throw new IOException("lifecycle_worktree_mismatch");
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IOException io && "lifecycle_worktree_mismatch".equals(io.getMessage())) {
                throw io;
            }
            throw new IOException("lifecycle_worktree_mismatch", failure);
        }
        if (binding.gitCommonDir() == null || binding.branch() == null || binding.baseCommit() == null || !binding.gitCommonDir().equals(expected.gitCommonDirectory()) || !binding.branch().equals(expected.branch())
                || !binding.baseCommit().equals(expected.baseCommit())) {
            throw new IOException("lifecycle_worktree_identity_mismatch");
        }
        PredictionEventStore store = location.openCoordinationStore();
        Participant participant = store.collaborationProjection().participants().stream().filter(item -> item.id().equals(expected.participant())).findFirst().orElse(null);
        if (participant == null || participant.state() != Participant.State.ACTIVE) {
            throw new IOException("lifecycle_participant_inactive");
        }
        WorkIntent intent;
        try {
            intent = store.collaborationProjection().intent(UUID.fromString(expected.workIntentId())).orElse(null);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("lifecycle_claim_not_acquired", malformed);
        }
        if (intent == null || intent.status() != WorkIntent.Status.ANNOUNCED || !intent.projectId().toString().equals(expected.projectId()) || !"codex".equals(intent.provider()) || !intent.participant()
                .equals(expected.participant()) || !intent.baseCommit().equals(expected.baseCommit()) || intent.version() != expected.laneEpoch()) {
            throw new IOException("lifecycle_claim_not_acquired");
        }
        new ProviderManualService().requireAttested("codex");
    }

    /**
     * Resolves and validates the provider binding named by a signed lifecycle request.
     */
    private Binding resolveBinding(ProjectLocation current, LifecycleControlRequestEnvelope request) throws IOException, BindingException {
        ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
        String connectionIdentity = request.authority().connectionInstanceId();
        Optional<Binding> resolved = connectionIdentity.startsWith(CodexWakeAdmissionService.FINGERPRINT_CONNECTION_PREFIX) ? bindingService.findByFingerprint(current,
                "codex",
                connectionIdentity.substring(CodexWakeAdmissionService.FINGERPRINT_CONNECTION_PREFIX.length())) : bindingService.find(current, "codex", connectionIdentity);
        Binding binding = resolved.orElseThrow(() -> new IOException("lifecycle_binding_missing"));
        validateResolvedBinding(current, request, binding);
        return binding;
    }

    /**
     * Verifies that a resolved binding still matches the signed request's project and trust state.
     */
    private void validateResolvedBinding(ProjectLocation current, LifecycleControlRequestEnvelope request, Binding binding) throws IOException {
        AuthorityContext expected = request.authority();
        if (!current.root().toAbsolutePath().normalize().toString().equals(expected.controlProjectRoot())) {
            throw new IOException("lifecycle_project_root_mismatch");
        }
        boolean completedStatusRead = request.operation() == Operation.STATUS && "COMPLETED".equals(binding.status());
        boolean admissibleBindingState = "BOUND".equals(binding.status()) || completedStatusRead;
        if (!binding.projectId().equals(expected.projectId()) || !"codex".equals(binding.provider()) || !binding.sessionId().equals(expected.bindingSessionId()) || !binding.providerInstanceFingerprint()
                .equals(expected.bindingFingerprint()) || binding.bindingVersion() != expected.bindingVersion() || !admissibleBindingState || !"VERIFIED".equals(binding.verificationState())
                || !"VERIFIED".equals(binding.providerTrustState()) || binding.worktreePath() == null) {
            throw new IOException("lifecycle_binding_stale");
        }
        if (binding.controlCheckoutPath() == null || !Path.of(binding.controlCheckoutPath()).toAbsolutePath().normalize().equals(current.root().toAbsolutePath().normalize())) {
            throw new IOException("lifecycle_project_root_mismatch");
        }
    }
}
