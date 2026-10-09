package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Manages disposable Git validation worktrees for capability request validation.
 *
 * <p>A validation worktree is created by combining the requester's verified HEAD with
 * the owner's implementation snapshot (expressed as a patch against the owner's base commit). The
 * worktree is isolated from all active worker worktrees, the owner worktree, and the control
 * checkout.
 *
 * <p>Validation worktrees are stored externally outside the control checkout under:
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\validation\<request-token>-r<revision>} or
 * {@code ~/.synesis/workspaces/<project-id>/validation/<request-token>-r<revision>}.
 *
 * <p>Worktree paths are never exposed to the agent via MCP responses.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
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
        return Path.of(base, "Synesis", "workspaces", projectId, "validation").toAbsolutePath().normalize();
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

    private static Path expectedWorktreePath(Path projectRoot, CapabilityRequestHandle handle, int revisionNumber) {
        return resolveValidationRoot(projectRoot).resolve(extractToken(handle) + "-r" + revisionNumber).toAbsolutePath().normalize();
    }

    private static void runGit(Path workdir, String... args) throws IOException {
        GitProcessRunner.run(workdir, args);
    }

    private static Path resolveGitTopLevel(Path path) {
        try {
            return Path.of(GitProcessRunner.run(path, "rev-parse", "--show-toplevel").trim()).toAbsolutePath().normalize();
        } catch (IOException failure) {
            return path;
        }
    }

    /**
     * Creates a disposable Git validation worktree.
     *
     * <p>The worktree checks out the exact published implementation commit and is created outside
     * the control checkout. It never rebases or applies a patch to requester code.
     *
     * @param projectRoot           absolute project root path
     * @param handle                capability request handle
     * @param revisionNumber        implementation revision number
     * @param impl                  immutable implementation revision record
     * @return absolute path to the created validation worktree
     * @throws IOException if worktree creation fails
     */
    public Path createValidationWorktree(Path projectRoot, CapabilityRequestHandle handle, int revisionNumber, ImplementationRevisionRecord impl) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(impl, "impl");
        if (revisionNumber < 1 || impl.revisionNumber() != revisionNumber || impl.commitSha().isBlank()) {
            throw new IOException("VALIDATION_WORKSPACE_REVISION_INVALID");
        }

        String token = extractToken(handle);
        String worktreeName = token + "-r" + revisionNumber;
        Path validationRoot = resolveValidationRoot(projectRoot);
        Path canonicalRoot = projectRoot.toAbsolutePath().normalize();
        if (validationRoot.startsWith(canonicalRoot) || canonicalRoot.startsWith(validationRoot)) {
            throw new IOException("VALIDATION_WORKSPACE_PATH_INVALID");
        }
        Files.createDirectories(validationRoot);
        Path worktreePath = validationRoot.resolve(worktreeName);
        if (!worktreePath.startsWith(validationRoot)) {
            throw new IOException("VALIDATION_WORKSPACE_PATH_INVALID");
        }

        if (Files.exists(worktreePath)) {
            return verifyValidationWorktree(projectRoot, handle, revisionNumber, impl.commitSha(), worktreePath.toString());
        }

        try {
            runGit(projectRoot, "worktree", "add", "--detach", worktreePath.toString(), impl.commitSha());
            verifyValidationWorktree(projectRoot, handle, revisionNumber, impl.commitSha(), worktreePath.toString());
            return worktreePath;
        } catch (IOException | RuntimeException failure) {
            removeValidationWorktree(worktreePath);
            throw failure;
        }
    }

    /**
     * Verifies the durable path, Git root, and exact published commit for one validation workspace.
     *
     * @param projectRoot project control checkout
     * @param handle capability request handle
     * @param revisionNumber implementation revision number
     * @param expectedCommit exact published implementation commit
     * @param recordedPath path retained in the durable validation context
     * @return verified validation worktree path
     * @throws IOException if the context path, registered worktree, or commit is stale
     */
    public Path verifyValidationWorktree(Path projectRoot,
            CapabilityRequestHandle handle,
            int revisionNumber,
            String expectedCommit,
            String recordedPath) throws IOException {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(expectedCommit, "expectedCommit");
        Objects.requireNonNull(recordedPath, "recordedPath");
        if (revisionNumber < 1) {
            throw new IOException("VALIDATION_WORKSPACE_REVISION_INVALID");
        }
        Path expected = expectedWorktreePath(projectRoot, handle, revisionNumber);
        Path recorded;
        try {
            recorded = Path.of(recordedPath).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            throw new IOException("VALIDATION_WORKSPACE_PATH_INVALID", invalidPath);
        }
        Path validationRoot = resolveValidationRoot(projectRoot);
        if (validationRoot.startsWith(projectRoot.toAbsolutePath().normalize()) || !recorded.equals(expected) || !recorded.startsWith(validationRoot)
                || !Files.isDirectory(recorded)) {
            throw new IOException("VALIDATION_WORKSPACE_PATH_INVALID");
        }
        Path gitRoot;
        try {
            gitRoot = Path.of(GitProcessRunner.run(recorded, "rev-parse", "--show-toplevel").trim()).toAbsolutePath().normalize();
        } catch (IOException failure) {
            throw new IOException("VALIDATION_WORKSPACE_NOT_A_WORKTREE", failure);
        }
        if (!gitRoot.equals(recorded)) {
            throw new IOException("VALIDATION_WORKSPACE_ROOT_MISMATCH");
        }
        String head = GitProcessRunner.run(recorded, "rev-parse", "HEAD").trim();
        if (!expectedCommit.equals(head)) {
            throw new IOException("VALIDATION_WORKSPACE_COMMIT_MISMATCH");
        }
        return recorded;
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
            runGit(topLevel, "worktree", "remove", "--force", worktreePath.toAbsolutePath().toString());
        } catch (IOException ignored) {
            // Best-effort cleanup; diagnostics will surface abandoned worktrees
        }
    }
}
