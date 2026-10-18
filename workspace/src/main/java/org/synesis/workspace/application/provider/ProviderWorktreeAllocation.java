package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.synesis.workspace.application.control.CodeExchange;
import org.synesis.workspace.application.control.CodeRemote;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;
import org.synesis.workspace.provider.ProviderIntegration;

import static org.synesis.workspace.application.provider.ProviderBindingFile.writeText;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.runGit;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.currentCommit;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.validCommit;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.verifyBinding;

/**
 * Allocates the isolated Git worktree, branch, and workspace marker for a binding. A binding that
 * cannot be given a safe worktree is returned in a failed or unassigned state with a diagnostic
 * instead of an exception, so the caller can persist the refusal. It is separate from
 * {@link ProviderSessionBindingService} because allocation touches the Git repository and the
 * user's local application data, while the service only decides when a binding needs a worktree.
 */
final class ProviderWorktreeAllocation {

    private static final System.Logger LOG = System.getLogger(ProviderWorktreeAllocation.class.getName());

    private ProviderWorktreeAllocation() {
    }

    static Binding withWorktree(ProjectLocation location, Binding binding) {
        return withWorktree(location, binding, null);
    }
    static Binding withWorktree(ProjectLocation location, Binding binding, String allocationSuffix) {
        String baseCommit = validCommit(binding.baseCommit()) ? binding.baseCommit() : "GIT_HEAD_UNAVAILABLE";
        String suffix = allocationSuffix == null || allocationSuffix.isBlank() ? binding.sessionId() : binding.sessionId() + "-" + allocationSuffix;
        String worktreeId = "worktree-" + suffix.substring("session-".length());
        Path path = workspaceRoot().resolve(binding.projectId()).resolve("worktrees").resolve(suffix).toAbsolutePath().normalize();
        String branch = "synesis/" + binding.provider() + "/" + suffix;
        Path control = location.root().toAbsolutePath().normalize();
        if (path.startsWith(control) || control.startsWith(path)) {
            return binding.withoutWorktree(control.toString(), baseCommit, "FAILED", "CONTROL_CHECKOUT_MUTATION_DENIED");
        }
        if ("GIT_HEAD_UNAVAILABLE".equals(baseCommit)) {
            return binding.withoutWorktree(location.root().toString(), baseCommit, "UNASSIGNED", "GIT_HEAD_UNAVAILABLE");
        }
        try {
            Files.createDirectories(path.getParent());
            String localMainBecause = null;
            if (!Files.exists(path)) {
                // A member bases a new lane on the newest main it can reach (ADR-0098, step 3 item 12).
                // Its own copy is always reachable: with no computer that holds a newer one answering,
                // the lane starts from it, and the merge of the two copies later settles the difference.
                try {
                    if (CodeExchange.followLeaderIfMember(location.coordinationDirectory(), location.projectId(), location.root())) {
                        String followed = currentCommit(location.root());
                        baseCommit = validCommit(followed) ? followed : baseCommit;
                    }
                } catch (CodeRemote.Refused unreachable) {
                    if (!unreachable.retryable()) {
                        throw unreachable;
                    }
                    localMainBecause = unreachable.detail();
                    LOG.log(System.Logger.Level.INFO, "A lane starts from this computer''s own main; no computer holding a newer one answered: {0}", localMainBecause);
                }
                try {
                    runGit(location.root(), "worktree", "add", "-b", branch, path.toString(), baseCommit);
                } catch (Exception branchExists) {
                    runGit(location.root(), "worktree", "add", path.toString(), branch);
                }
            }
            writeWorkspaceMarker(path, location, binding, branch, localMainBecause);
            RepositoryPrivateStateService.ensure(location.root());
            ProviderIntegration provider = ProviderRegistry.find(binding.provider());
            if (provider != null) {
                ProviderHookConfiguration.materializeHook(path, provider, new ProviderApplicationService().launcherPath(), location.profile());
            }
            Binding allocated = binding.withAllocatedWorktree(worktreeId, location.root().toString(), path.toString(), branch, baseCommit);
            return verifyBinding(location.root(), allocated);
        } catch (CodeRemote.Refused refused) {
            return binding.withoutWorktree(location.root().toString(), baseCommit, "FAILED", refused.code(), refused.detail());
        } catch (Exception failure) {
            String diagnostic = String.valueOf(failure.getMessage()).contains("PROVIDER_CONFIGURATION_CONFLICT") ? "PROVIDER_CONFIGURATION_CONFLICT" : "WORKSPACE_ALLOCATION_FAILED";
            return binding.withoutWorktree(location.root().toString(), baseCommit, "FAILED", diagnostic);
        }
    }

    private static Path workspaceRoot() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis").toString();
        }
        return Path.of(base, "Synesis", "workspaces").toAbsolutePath().normalize();
    }

    /**
     * Writes the marker that names the worktree's project and session. When the lane started from this computer's own
     * main because no computer holding a newer one answered, the marker says so and why, so that a later re-merge of
     * the lane is expected.
     */
    private static void writeWorkspaceMarker(Path worktree, ProjectLocation location, Binding binding, String branch, String localMainBecause) throws IOException {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", 1);
        marker.put("projectId", binding.projectId());
        marker.put("sessionId", binding.sessionId());
        marker.put("provider", binding.provider());
        marker.put("controlCheckoutPath", location.root().toString());
        marker.put("branch", branch);
        if (localMainBecause != null) {
            marker.put("baseFrom", "LOCAL_MAIN");
            marker.put("baseFromReason", localMainBecause);
        }
        writeText(worktree.resolve(".synesis/local/workspace-binding.json"), ProviderJson.write(marker) + System.lineSeparator());
    }
}
