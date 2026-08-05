package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;

/**
 * Manages disposable Git validation worktrees for capability request validation.
 *
 * <p>A validation worktree is created by combining the requester's verified HEAD with
 * the owner's implementation snapshot (expressed as a patch against the owner's base commit).
 * The worktree is isolated from all active worker worktrees, the owner worktree, and the
 * control checkout.
 *
 * <p>Validation worktrees are stored externally outside the control checkout under:
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\validation\<request-token>-r<revision>}
 * or {@code ~/.synesis/workspaces/<project-id>/validation/<request-token>-r<revision>}.
 *
 * <p>Worktree paths are never exposed to the agent via MCP responses.
 *
 * @since 1.0
 */
public final class ValidationWorkspaceService {

    /**
     * Creates a validation workspace service.
     */
    public ValidationWorkspaceService() {
    }

    /**
     * Resolves the external validation root directory for a given project.
     *
     * @param projectRoot control project root path
     * @return absolute path to external validation directory
     */
    public static Path resolveValidationRoot(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        String projectId = resolveProjectId(projectRoot);
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis").toString();
        }
        return Path.of(base, "Synesis", "workspaces", projectId, "validation")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Creates a disposable Git validation worktree.
     *
     * <p>The worktree is bound to the request handle and revision number. It is created
     * outside the control checkout.
     *
     * @param projectRoot           absolute project root path
     * @param handle                capability request handle
     * @param revisionNumber        implementation revision number
     * @param requesterWorktreePath absolute path to the requester's assigned worktree
     * @param impl                  immutable implementation revision record
     * @return absolute path to the created validation worktree
     * @throws IOException if worktree creation fails
     */
    public Path createValidationWorktree(
            Path projectRoot,
            CapabilityRequestHandle handle,
            int revisionNumber,
            Path requesterWorktreePath,
            ImplementationRevisionRecord impl
    ) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(requesterWorktreePath, "requesterWorktreePath");
        Objects.requireNonNull(impl, "impl");

        String token = extractToken(handle);
        String worktreeName = token + "-r" + revisionNumber;
        Path validationRoot = resolveValidationRoot(projectRoot);
        Files.createDirectories(validationRoot);
        Path worktreePath = validationRoot.resolve(worktreeName);

        if (Files.exists(worktreePath)) {
            // Idempotent: already exists from prior session
            return worktreePath.toAbsolutePath().normalize();
        }

        // Determine the requester HEAD commit
        String requesterHead = gitRevParse(requesterWorktreePath, "HEAD");

        // Create the worktree at requester HEAD
        runGit(projectRoot, "worktree", "add", "--detach",
                worktreePath.toAbsolutePath().toString(),
                requesterHead);

        // Generate a diff patch from baseCommit to owner commit
        String patch = gitDiff(projectRoot, impl.baseCommit(), impl.commitSha());

        if (!patch.isBlank()) {
            // Write patch to temp file and apply in the validation worktree
            Path patchFile = Files.createTempFile("synesis-val-", ".patch");
            try {
                Files.writeString(patchFile, patch);
                applyPatch(worktreePath.toAbsolutePath().normalize(), patchFile);
            } finally {
                Files.deleteIfExists(patchFile);
            }
        }

        return worktreePath.toAbsolutePath().normalize();
    }

    /**
     * Removes a disposable validation worktree via {@code git worktree remove}.
     *
     * <p>If the worktree no longer exists on disk, this method is a no-op.
     *
     * @param worktreePath absolute path to the validation worktree
     */
    public void removeValidationWorktree(Path worktreePath) {
        if (worktreePath == null || !Files.exists(worktreePath)) {
            return;
        }
        try {
            Path topLevel = resolveGitTopLevel(worktreePath);
            runGit(topLevel, "worktree", "remove", "--force",
                    worktreePath.toAbsolutePath().toString());
        } catch (IOException ignored) {
            // Best-effort cleanup; diagnostics will surface abandoned worktrees
        }
    }

    /**
     * Finds an existing validation worktree for a given handle and revision, if one was previously created.
     *
     * @param projectRoot    absolute project root path
     * @param handle         capability request handle
     * @param revisionNumber implementation revision number
     * @return path to the existing validation worktree, or empty if not found
     */
    public Optional<Path> findExistingWorktree(
            Path projectRoot,
            CapabilityRequestHandle handle,
            int revisionNumber
    ) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(handle, "handle");
        String token = extractToken(handle);
        String worktreeName = token + "-r" + revisionNumber;
        Path candidate = resolveValidationRoot(projectRoot).resolve(worktreeName);
        if (Files.exists(candidate) && Files.isDirectory(candidate)) {
            return Optional.of(candidate.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    /**
     * Discovers all abandoned validation worktrees under the external validation root.
     * Used for diagnostics and restart cleanup.
     *
     * @param projectRoot absolute project root path
     * @return list of absolute paths to candidate abandoned validation worktrees
     */
    public List<Path> discoverAbandonedWorktrees(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path validationRoot = resolveValidationRoot(projectRoot);
        if (!Files.isDirectory(validationRoot)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (var stream = Files.list(validationRoot)) {
            stream.filter(Files::isDirectory).forEach(result::add);
        } catch (IOException ignored) {
        }
        return List.copyOf(result);
    }

    private static String resolveProjectId(Path projectRoot) {
        Path projFile = projectRoot.resolve(".synesis/project.json");
        if (Files.exists(projFile)) {
            try {
                String content = Files.readString(projFile);
                int idx = content.indexOf("\"projectId\"");
                if (idx != -1) {
                    int colon = content.indexOf(':', idx);
                    int q1 = content.indexOf('"', colon + 1);
                    int q2 = content.indexOf('"', q1 + 1);
                    if (colon != -1 && q1 != -1 && q2 != -1) {
                        return content.substring(q1 + 1, q2);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "default-project";
    }

    private static String extractToken(CapabilityRequestHandle handle) {
        String value = handle.value();
        int underscore = value.indexOf('_');
        return (underscore >= 0 ? value.substring(underscore + 1) : value).toLowerCase(java.util.Locale.ROOT);
    }

    private static String gitRevParse(Path workdir, String ref) throws IOException {
        return org.synesis.workspace.lifecycle.GitProcessRunner.run(workdir, "rev-parse", ref).trim();
    }

    private static String gitDiff(Path workdir, String from, String to) throws IOException {
        return org.synesis.workspace.lifecycle.GitProcessRunner.run(workdir, "diff", from, to);
    }

    private static void applyPatch(Path worktreeDir, Path patchFile) throws IOException {
        org.synesis.workspace.lifecycle.GitProcessRunner.run(worktreeDir, "apply", "--3way",
                patchFile.toAbsolutePath().toString());
    }

    private static void runGit(Path workdir, String... args) throws IOException {
        org.synesis.workspace.lifecycle.GitProcessRunner.run(workdir, args);
    }

    private static Path resolveGitTopLevel(Path path) throws IOException {
        try {
            return Path.of(org.synesis.workspace.lifecycle.GitProcessRunner
                    .run(path, "rev-parse", "--show-toplevel").trim()).toAbsolutePath().normalize();
        } catch (IOException failure) {
            return path;
        }
    }
}
