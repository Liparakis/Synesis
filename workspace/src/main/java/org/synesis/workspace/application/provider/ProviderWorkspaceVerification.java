package org.synesis.workspace.application.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.WorkspaceCheck;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.WorkspaceVerificationResult;
import org.synesis.workspace.infrastructure.json.ProviderJson;

import static org.synesis.workspace.application.provider.ProviderBindingFile.bindingPath;
import static org.synesis.workspace.application.provider.ProviderBindingFile.fingerprint;
import static org.synesis.workspace.application.provider.ProviderBindingFile.requireText;
import static org.synesis.workspace.application.provider.ProviderBindingFile.sessionsDirectory;
import static org.synesis.workspace.application.provider.ProviderBindingFile.write;
import static org.synesis.workspace.application.provider.ProviderBindingFile.writeText;
import static org.synesis.workspace.application.provider.ProviderSessionCoordinationFacts.hasActiveManagedAttachment;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.git;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.isBaseAncestor;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.isWorktreeClean;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.registeredWorktree;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.sameFilesystemObject;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.sameGitCommonDirectory;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.sameOrDescendant;

/**
 * Checks that a provider event is running in exactly the worktree its binding was assigned, and
 * records workspace trust when every check passes. Each variant relaxes one named condition, such
 * as the control base having advanced, and none relaxes the control-checkout, registration, branch,
 * or ancestry checks. The public contract and its per-variant rationale are documented on the
 * matching methods of {@link ProviderSessionBindingService}; the checks live here so that the
 * order in which they fail closed is read in one place.
 */
final class ProviderWorkspaceVerification {

    private ProviderWorkspaceVerification() {
    }

    static WorkspaceCheck verifyWorkspace(ProjectLocation location, Binding binding, Path cwd) {
        return verifyWorkspace(location, binding, cwd, true, false);
    }

    static WorkspaceCheck verifyNoChangeWorkspace(ProjectLocation location, Binding binding, Path cwd) {
        return verifyWorkspace(location, binding, cwd, false, false);
    }

    static WorkspaceCheck verifyManagedContinuationWorkspace(ProjectLocation location, Binding binding, Path cwd) {
        if (!hasActiveManagedAttachment(location, binding)) {
            return new WorkspaceCheck(false, "MANAGED_ATTACHMENT_NOT_ACTIVE");
        }
        return verifyWorkspace(location, binding, cwd, false, false);
    }

    static WorkspaceCheck verifyClaimedMutationContinuationWorkspace(ProjectLocation location, Binding binding, Path cwd, String relativePath) {
        if (binding == null || binding.state() != ProviderBindingStatus.BOUND) {
            return new WorkspaceCheck(false, "SESSION_NOT_ACTIVE");
        }
        if (!new WorkspaceCollaborationService().hasActiveMutationClaim(location, binding, relativePath)) {
            return new WorkspaceCheck(false, "ACTIVE_MUTATION_CLAIM_REQUIRED");
        }
        return verifyWorkspace(location, binding, cwd, false, false);
    }

    static WorkspaceCheck verifyCompletionWorkspace(ProjectLocation location, Binding binding, Path cwd) {
        return verifyWorkspace(location, binding, cwd, false, true);
    }

    static WorkspaceCheck verifyWorkspace(ProjectLocation location, Binding binding, Path cwd, boolean requireCurrentControlBase, boolean allowCommittedGeneration) {
        if (binding == null || binding.worktreePath() == null || binding.worktreePath().isBlank()) {
            return new WorkspaceCheck(false, binding == null ? "WORKSPACE_UNVERIFIED" : binding.lastSeenState());
        }
        try {
            Path root = location.root().toAbsolutePath().normalize();
            Path assigned = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            Path actual = Objects.requireNonNull(cwd, "cwd").toAbsolutePath().normalize();
            if (sameFilesystemObject(actual, root)) {
                return new WorkspaceCheck(false, "CONTROL_CHECKOUT_MUTATION_DENIED");
            }
            if (sameOrDescendant(assigned, root) || !sameFilesystemObject(root, Path.of(binding.controlCheckoutPath()))) {
                return new WorkspaceCheck(false, "CONTROL_CHECKOUT_MUTATION_DENIED");
            }
            if (!Files.isDirectory(assigned) || !sameFilesystemObject(actual, assigned)) {
                return new WorkspaceCheck(false, "WORKSPACE_TRANSITION_REQUIRED");
            }
            if (!registeredWorktree(root, assigned, binding)) {
                return new WorkspaceCheck(false, "WORKTREE_NOT_REGISTERED");
            }
            if (!sameGitCommonDirectory(root, assigned, binding)) {
                return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
            }
            ProviderWorktreeGitFacts.Head head = ProviderWorktreeGitFacts.head(assigned);
            if (!binding.branch().equals(head.branch())) {
                return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
            }
            if (!isBaseAncestor(assigned, binding.baseCommit(), head.commit())) {
                return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
            }
            String currentWorkerHead = head.commit();
            if (!binding.baseCommit().equals(currentWorkerHead) && (!allowCommittedGeneration || !isWorktreeClean(binding))) {
                return new WorkspaceCheck(false, "WORKSPACE_GENERATION_MISMATCH");
            }
            if (requireCurrentControlBase && !binding.baseCommit().equals(git(root, "rev-parse", "HEAD"))) {
                return new WorkspaceCheck(false, "CONTROL_BASE_ADVANCED");
            }
            return new WorkspaceCheck(true, "WORKSPACE_VERIFIED");
        } catch (Exception failure) {
            return new WorkspaceCheck(false, "WORKSPACE_BINDING_MISMATCH");
        }
    }

    static WorkspaceVerificationResult verifyWorkspaceTrust(ProviderSessionBindingService service, ProjectLocation location, String provider, String sessionId, Path cwd) {
        Objects.requireNonNull(location, "location");
        requireText(provider, "provider");
        if (cwd == null) {
            return new WorkspaceVerificationResult(false, "WORKSPACE_UNVERIFIED", null, null);
        }
        try {
            var bindings = service.list(location, provider);
            if (bindings.isEmpty()) {
                return new WorkspaceVerificationResult(false, "SESSION_UNBOUND", null, null);
            }
            if (sessionId == null || sessionId.isBlank()) {
                return new WorkspaceVerificationResult(false, "SESSION_ID_REQUIRED", null, null);
            }
            Binding binding = bindings.stream().filter(b -> sessionId.equals(b.sessionId())).findFirst().orElse(null);
            if (binding == null || binding.state() != ProviderBindingStatus.BOUND) {
                return new WorkspaceVerificationResult(false, "SESSION_UNBOUND", null, null);
            }

            if (binding.worktreePath() == null || binding.worktreePath().isBlank()) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_UNASSIGNED", null, binding);
            }

            Path root = location.root().toAbsolutePath().normalize();
            Path assigned = Path.of(binding.worktreePath()).toAbsolutePath().normalize();
            Path actual = cwd.toAbsolutePath().normalize();

            // 1. Check assigned worktree differs from control checkout
            if (sameOrDescendant(assigned, root) || sameFilesystemObject(actual, root)) {
                return new WorkspaceVerificationResult(false, "CONTROL_CHECKOUT_MUTATION_DENIED", null, binding);
            }

            // 2. Check worktree path exists on filesystem
            if (!Files.isDirectory(assigned)) {
                return new WorkspaceVerificationResult(false, "WORKTREE_DIRECTORY_MISSING", null, binding);
            }

            // 3. Check canonical path equality
            Path canonicalAssigned = assigned.toRealPath();
            if (!sameFilesystemObject(actual, assigned)) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_TRANSITION_REQUIRED", null, binding);
            }

            // 4. No other active session owns it
            for (Binding other : bindings) {
                if (!other.sessionId().equals(binding.sessionId()) && !other.state().releasesWorktree() && other.worktreePath() != null) {
                    if (sameFilesystemObject(Path.of(other.worktreePath()).toAbsolutePath().normalize(), assigned)) {
                        return new WorkspaceVerificationResult(false, "DUPLICATE_ACTIVE_WORKTREE", null, binding);
                    }
                }
            }

            // 5. Git recognizes it as a registered worktree
            if (!registeredWorktree(root, assigned, binding)) {
                return new WorkspaceVerificationResult(false, "WORKTREE_NOT_REGISTERED", null, binding);
            }

            // 6. Git common directory belongs to control repository
            if (!sameGitCommonDirectory(root, assigned, binding)) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_BINDING_MISMATCH", null, binding);
            }

            // 7. Branch matches recorded session branch
            ProviderWorktreeGitFacts.Head head = ProviderWorktreeGitFacts.head(assigned);
            if (!binding.branch().equals(head.branch())) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_BINDING_MISMATCH", null, binding);
            }

            // 8. HEAD is valid commit derived from base commit
            String headCommit = head.commit();
            if (!isBaseAncestor(assigned, binding.baseCommit(), headCommit)) {
                return new WorkspaceVerificationResult(false, "WORKSPACE_BINDING_MISMATCH", null, binding);
            }

            // All checks passed! Transition to VERIFIED and persist evidence
            long now = System.currentTimeMillis();
            String evidenceRaw = location.projectId().toString() + "|" + canonicalAssigned + "|" + binding.branch() + "|" + headCommit + "|" + now;
            String evidenceDigest = fingerprint(evidenceRaw);

            Binding verifiedBinding = binding.withVerifiedWorkspaceTrust(now);

            // Persist updated binding
            Path sessionDir = sessionsDirectory(location);
            write(bindingPath(location, provider, binding.providerInstanceFingerprint()), verifiedBinding);

            // Persist verification record
            Map<String, Object> verRecord = new LinkedHashMap<>();
            verRecord.put("schemaVersion", 1);
            verRecord.put("verificationVersion", 1);
            verRecord.put("sessionId", binding.sessionId());
            verRecord.put("projectId", location.projectId().toString());
            verRecord.put("provider", provider);
            verRecord.put("verificationTimestamp", now);
            verRecord.put("repositoryIdentity", location.projectId().toString());
            verRecord.put("canonicalWorktreePath", canonicalAssigned.toString());
            verRecord.put("branch", binding.branch());
            verRecord.put("headCommit", headCommit);
            verRecord.put("evidenceDigest", evidenceDigest);
            verRecord.put("workspaceTrust", "VERIFIED");

            Path evidenceFile = sessionDir.resolve("verification-" + binding.sessionId() + ".json");
            writeText(evidenceFile, ProviderJson.write(verRecord) + System.lineSeparator());

            return new WorkspaceVerificationResult(true, "WORKSPACE_VERIFIED", evidenceDigest, verifiedBinding);
        } catch (Exception failure) {
            return new WorkspaceVerificationResult(false, "WORKSPACE_BINDING_MISMATCH", null, null);
        }
    }
}
