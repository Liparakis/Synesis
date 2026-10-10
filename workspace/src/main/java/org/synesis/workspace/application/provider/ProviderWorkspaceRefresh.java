package org.synesis.workspace.application.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.persistence.ProjectAppendLock;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.BindingException;
import org.synesis.workspace.application.task.SnapshotArtifactPolicy;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

import static org.synesis.workspace.application.provider.ProviderBindingFile.read;
import static org.synesis.workspace.application.provider.ProviderBindingFile.write;
import static org.synesis.workspace.application.provider.ProviderSessionBindingService.copy;
import static org.synesis.workspace.application.provider.ProviderSessionCoordinationFacts.hasActiveManagedAttachment;
import static org.synesis.workspace.application.provider.ProviderWorkspaceVerification.verifyWorkspace;
import static org.synesis.workspace.application.provider.ProviderWorktreeAllocation.withWorktree;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.currentCommit;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.gitPaths;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.validCommit;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.verifyBinding;

/**
 * Decides whether a stored worktree still matches the control checkout and, when the control
 * branch has advanced, replaces it without losing the session or any uncommitted claimed work.
 * The refresh copies only files covered by the session's active claims and re-verifies both
 * worktrees before the binding file is rewritten, so a failure at any step leaves the original
 * binding and worktree untouched and reports {@code WORKSPACE_STALE_DIRTY}. It is separate from
 * {@link ProviderSessionBindingService} because it is the one place that moves user changes
 * between worktrees, which warrants being read and reviewed on its own.
 */
final class ProviderWorkspaceRefresh {

    private ProviderWorkspaceRefresh() {
    }

    static boolean workspaceGenerationFresh(ProjectLocation location, Binding binding) {
        if (binding == null || binding.worktreePath() == null || !validCommit(binding.baseCommit())) {
            return false;
        }
        try {
            Path worker = Path.of(binding.worktreePath());
            return Files.isDirectory(worker) && binding.baseCommit().equals(currentCommit(worker)) && binding.baseCommit().equals(currentCommit(location.root()));
        } catch (Exception failure) {
            return false;
        }
    }

    static boolean controlAdvancedWithoutUnintegratedWorkerWork(ProjectLocation location, Binding binding) {
        if (binding == null || binding.worktreePath() == null || !validCommit(binding.baseCommit())) {
            return false;
        }
        try {
            String controlHead = currentCommit(location.root());
            String workerHead = currentCommit(Path.of(binding.worktreePath()));
            // A clean worker that already contains the control head has no
            // private committed delta to discard. Preserve its session
            // identity just as for an untouched worker; replacing the
            // binding would strand any durable collaboration intent on the
            // previous participant.
            return validCommit(controlHead) && validCommit(workerHead) && !binding.baseCommit().equals(controlHead) && (binding.baseCommit().equals(workerHead) || controlHead.equals(workerHead));
        } catch (Exception failure) {
            return false;
        }
    }

    static Binding reallocatePreservingSession(ProjectLocation location, Binding binding) {
        // Keep the derived worktree path below Windows' legacy checkout-path
        // limit even when the project contains deep private metadata paths.
        String suffix = "r-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Binding replacement = copy(binding, null, location.root().toString(), null, currentCommit(location.root()), null, "UNASSIGNED", "UNVERIFIED", "WORKSPACE_REBOUND", "WORKSPACE_UNVERIFIED");
        return withWorktree(location, replacement, suffix);
    }

    static Binding refreshClaimedDirtyWorkspace(ProviderSessionBindingService service, ProjectLocation location, Binding binding, Path bindingPath) throws Exception {
        Path coordination = location.coordinationDirectory();
        // The same lock fences claim transitions and peer integration while the new
        // worktree is prepared. The old worktree remains intact even after publication.
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(coordination)) {
            if (!lock.isHeld()) {
                throw staleDirty();
            }
            Binding current = read(bindingPath);
            if (!current.sessionId().equals(binding.sessionId()) || !"BOUND".equals(current.status()) || service.isSessionTerminal(location, current.sessionId())) {
                throw staleDirty();
            }
            if (!current.worktreePath().equals(binding.worktreePath())) {
                if (!workspaceGenerationFresh(location, current)) {
                    throw staleDirty();
                }
                return verifyBinding(location.root(), current);
            }
            Path source = Path.of(current.worktreePath());
            if (hasActiveManagedAttachment(location, current) || !verifyWorkspace(location, current, source, false, false).verified() || !controlAdvancedWithoutUnintegratedWorkerWork(location, current)
                    || !current.baseCommit().equals(currentCommit(source))) {
                throw staleDirty();
            }
            var projection = location.openCoordinationStore().collaborationProjection();
            String participant = WorkspaceCollaborationService.participantHandle(current.sessionId());
            if (projection.participantState(participant).filter(state -> state == org.synesis.coordination.domain.collaboration.Participant.State.ACTIVE).isEmpty()) {
                throw staleDirty();
            }
            List<ResourceSelector> claims = projection.activeIntents().stream()
                    .filter(intent -> intent.projectId().equals(location.projectId()) && intent.provider().equals(current.provider()) && intent.participant().equals(participant)
                            && intent.status() == WorkIntent.Status.ANNOUNCED).flatMap(intent -> intent.selectors().stream()).toList();
            String controlHead = currentCommit(location.root());
            if (GitProcessRunner.runResult(location.root(), "merge-base", "--is-ancestor", current.baseCommit(), controlHead).exitCode() != 0) {
                throw staleDirty();
            }
            List<String> peerChanges = gitPaths(location.root(), "diff", "--name-only", "--no-renames", "-z", current.baseCommit(), controlHead, "--");
            if (claims.isEmpty() || peerChanges.stream().anyMatch(path -> claims.stream().anyMatch(claim -> claim.overlaps(ResourceSelector.pathExact(path))))) {
                throw staleDirty();
            }
            Map<String, byte[]> changes = claimedDirtyContents(source, claims);
            Binding replacement = reallocatePreservingSession(location, current);
            Path target = Path.of(replacement.worktreePath());
            if (!controlHead.equals(replacement.baseCommit()) || !verifyWorkspace(location, replacement, target).verified()) {
                throw staleDirty();
            }
            for (var change : changes.entrySet()) {
                Path destination = safeRefreshPath(target, change.getKey());
                if (change.getValue() == null) {
                    Files.deleteIfExists(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(safeRefreshPath(source, change.getKey()), destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    if (!java.util.Arrays.equals(change.getValue(), Files.readAllBytes(destination))) {
                        throw staleDirty();
                    }
                }
            }
            Map<String, byte[]> rechecked = claimedDirtyContents(source, claims);
            if (!changes.keySet().equals(rechecked.keySet()) || changes.entrySet().stream().anyMatch(change -> !java.util.Arrays.equals(change.getValue(), rechecked.get(change.getKey()))) || !verifyWorkspace(location,
                    replacement,
                    target).verified() || !verifyWorkspace(location, current, source, false, false).verified()) {
                throw staleDirty();
            }
            // Before this atomic write a retry uses the untouched original; afterwards
            // it uses the fully prepared replacement, with the same session and claims.
            write(bindingPath, replacement.touch());
            return replacement;
        }
    }

    private static Map<String, byte[]> claimedDirtyContents(Path source, List<ResourceSelector> claims) throws Exception {
        // An index-only edit needs its own replay semantics; do not discard it.
        if (!gitPaths(source, "diff", "--cached", "--name-only", "-z", "--").isEmpty()) {
            throw staleDirty();
        }
        var paths = new java.util.TreeSet<>(gitPaths(source, "diff", "--name-only", "--no-renames", "-z", "HEAD", "--"));
        paths.addAll(gitPaths(source, "ls-files", "--others", "--exclude-standard", "-z"));
        var artifacts = new SnapshotArtifactPolicy().classify(List.copyOf(paths));
        if (!artifacts.valid()) {
            throw staleDirty();
        }
        Map<String, byte[]> contents = new LinkedHashMap<>();
        long bytes = 0;
        for (String path : paths) {
            if (artifacts.allowedArtifacts().contains(path)) {
                continue;
            }
            if (claims.stream().noneMatch(claim -> claim.overlaps(ResourceSelector.pathExact(path)))) {
                throw staleDirty();
            }
            Path file = safeRefreshPath(source, path);
            byte[] content = null;
            if (Files.exists(file)) {
                if (!Files.isRegularFile(file) || (bytes += Files.size(file)) > 16 * 1024 * 1024) {
                    throw staleDirty();
                }
                content = Files.readAllBytes(file);
            }
            contents.put(path, content);
        }
        return contents;
    }

    private static Path safeRefreshPath(Path root, String relative) throws Exception {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw staleDirty();
        }
        for (Path part = path; !part.equals(root); part = part.getParent()) {
            if (Files.isSymbolicLink(part) || (Files.exists(part) && !part.toRealPath().startsWith(root.toRealPath()))) {
                throw staleDirty();
            }
        }
        return path;
    }

    private static BindingException staleDirty() {
        return new BindingException("WORKSPACE_STALE_DIRTY", "Stored provider workspace cannot be refreshed without risking uncommitted work");
    }
}
