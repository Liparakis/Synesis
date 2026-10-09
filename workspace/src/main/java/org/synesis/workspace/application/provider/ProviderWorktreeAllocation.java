package org.synesis.workspace.application.provider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderRegistry;

import static org.synesis.workspace.application.provider.ProviderBindingFile.writeText;
import static org.synesis.workspace.application.provider.ProviderSessionBindingService.copy;
import static org.synesis.workspace.application.provider.ProviderWorktreeGitFacts.runGit;
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
            return copy(binding, null, control.toString(), null, baseCommit, null, "FAILED", "UNVERIFIED", "CONTROL_CHECKOUT_MUTATION_DENIED", "WORKSPACE_UNVERIFIED");
        }
        if ("GIT_HEAD_UNAVAILABLE".equals(baseCommit)) {
            return copy(binding, null, location.root().toString(), null, baseCommit, null, "UNASSIGNED", "UNVERIFIED", "GIT_HEAD_UNAVAILABLE", "WORKSPACE_UNVERIFIED");
        }
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                try {
                    runGit(location.root(), "worktree", "add", "-b", branch, path.toString(), baseCommit);
                } catch (Exception branchExists) {
                    runGit(location.root(), "worktree", "add", path.toString(), branch);
                }
            }
            writeWorkspaceMarker(path, location, binding, branch);
            RepositoryPrivateStateService.ensure(location.root());
            ProviderIntegration provider = ProviderRegistry.find(binding.provider());
            if (provider != null) {
                ProviderHookConfiguration.materializeHook(path, provider, new ProviderApplicationService().launcherPath(), location.profile());
            }
            Binding allocated = copy(binding, worktreeId, location.root().toString(), path.toString(), baseCommit, branch, "ALLOCATED", "UNVERIFIED", "WORKTREE_ALLOCATED", binding.providerTrustState());
            return verifyBinding(location.root(), allocated);
        } catch (Exception failure) {
            String diagnostic = String.valueOf(failure.getMessage()).contains("PROVIDER_CONFIGURATION_CONFLICT") ? "PROVIDER_CONFIGURATION_CONFLICT" : "WORKSPACE_ALLOCATION_FAILED";
            return copy(binding, null, location.root().toString(), null, baseCommit, null, "FAILED", "UNVERIFIED", diagnostic, "WORKSPACE_UNVERIFIED");
        }
    }

    private static Path workspaceRoot() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis").toString();
        }
        return Path.of(base, "Synesis", "workspaces").toAbsolutePath().normalize();
    }

    private static void writeWorkspaceMarker(Path worktree, ProjectLocation location, Binding binding, String branch) throws IOException {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("schemaVersion", 1);
        marker.put("projectId", binding.projectId());
        marker.put("sessionId", binding.sessionId());
        marker.put("provider", binding.provider());
        marker.put("controlCheckoutPath", location.root().toString());
        marker.put("branch", branch);
        writeText(worktree.resolve(".synesis/local/workspace-binding.json"), ProviderJson.write(marker) + System.lineSeparator());
    }
}
