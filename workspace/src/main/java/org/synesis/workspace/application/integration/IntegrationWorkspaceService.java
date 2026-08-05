package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.domain.task.TaskSnapshotRecord;

/**
 * Manages dedicated Git integration worktrees for task integration.
 *
 * <p>Integration worktrees are created outside the control checkout under:
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\integration\<attempt-id>}
 * or {@code ~/.synesis/workspaces/<project-id>/integration/<attempt-id>}.
 *
 * @since 1.0
 */
public final class IntegrationWorkspaceService {

    /**
     * Creates an integration workspace service.
     */
    public IntegrationWorkspaceService() {
    }

    /**
     * Result of an integration attempt inside the dedicated integration worktree.
     *
     * @param worktreePath         absolute path to integration worktree
     * @param success              true if all task snapshots applied without merge conflict
     * @param integrationCommitSha commit SHA produced in the integration worktree
     * @param failureReason        conflict or failure description when success is false
     */
    public record IntegrationWorktreeResult(
            Path worktreePath,
            boolean success,
            String integrationCommitSha,
            String failureReason
    ) {
        /**
         * Validates non-null invariants.
         */
        public IntegrationWorktreeResult {
            Objects.requireNonNull(worktreePath, "worktreePath");
            Objects.requireNonNull(integrationCommitSha, "integrationCommitSha");
            Objects.requireNonNull(failureReason, "failureReason");
        }
    }

    /**
     * Resolves the external integration root directory for a given project.
     *
     * @param projectRoot control project root path
     * @return absolute path to external integration directory
     */
    public static Path resolveIntegrationRoot(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        String projectId = resolveProjectId(projectRoot);
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis").toString();
        }
        return Path.of(base, "Synesis", "workspaces", projectId, "integration")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Reads the verified control checkout HEAD used to seed an integration or
     * repair lane.
     *
     * @param repository control checkout
     * @return full Git commit SHA
     * @throws IOException when HEAD cannot be resolved
     */
    public String currentHead(Path repository) throws IOException {
        Objects.requireNonNull(repository, "repository");
        return gitRevParse(repository.toAbsolutePath().normalize(), "HEAD");
    }

    /**
     * Creates a dedicated Git integration worktree and applies immutable task snapshots in order.
     *
     * @param projectRoot         control project root path
     * @param attemptId           unique integration attempt ID (e.g. {@code att_...})
     * @param expectedControlHead expected control branch HEAD SHA
     * @param orderedSnapshots    task snapshots in dependency-aware topological order
     * @return integration worktree result
     */
    public IntegrationWorktreeResult prepareIntegrationWorktree(
            Path projectRoot,
            String attemptId,
            String expectedControlHead,
            List<TaskSnapshotRecord> orderedSnapshots
    ) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(expectedControlHead, "expectedControlHead");
        Objects.requireNonNull(orderedSnapshots, "orderedSnapshots");

        Path integrationRoot = resolveIntegrationRoot(projectRoot);
        Path worktreePath = integrationRoot.resolve(attemptId).toAbsolutePath().normalize();

        try {
            Files.createDirectories(integrationRoot);

            if (Files.exists(worktreePath)) {
                // Reuse only a verified attempt workspace. A conflicted or
                // mismatched workspace must remain visible for repair rather
                // than being mistaken for a successful integration.
                String currentHead = gitRevParse(worktreePath, "HEAD");
                if (Files.exists(gitPath(worktreePath, "CHERRY_PICK_HEAD"))) {
                    return new IntegrationWorktreeResult(worktreePath, false, "",
                            "Integration worktree has an unresolved cherry-pick conflict");
                }
                if (!expectedControlHead.equals(currentHead)) {
                    return new IntegrationWorktreeResult(worktreePath, false, "",
                            "Integration worktree head does not match expected control head");
                }
                return new IntegrationWorktreeResult(worktreePath, true, currentHead, "");
            }

            // 1. Create Git worktree at expectedControlHead
            runGit(projectRoot, "worktree", "add", "--detach",
                    worktreePath.toString(), expectedControlHead);
            try {
                runGit(worktreePath, "config", "user.name", "Synesis Integrator");
                runGit(worktreePath, "config", "user.email", "integrator@synesis.org");
            } catch (IOException ignored) {
            }

            // 2. Apply task snapshots in topological order via cherry-pick or patch
            for (TaskSnapshotRecord snap : orderedSnapshots) {
                if (snap.commitSha().equals(expectedControlHead)) {
                    // Worktree is already at expectedControlHead; no new commits to apply
                    continue;
                }
                try {
                    // Try cherry-pick first
                    runGit(worktreePath, "cherry-pick", "--allow-empty", "--keep-redundant-commits", snap.commitSha());
                } catch (IOException cherryPickFailure) {
                    // Preserve the conflicted index and worktree.  The repair
                    // lane consumes this exact representation; resetting or
                    // aborting here would discard the conflicting work.
                    return new IntegrationWorktreeResult(worktreePath, false, "",
                            "Merge conflict applying task snapshot " + snap.snapshotId() + ": " + cherryPickFailure.getMessage());
                }
            }

            String finalCommit = gitRevParse(worktreePath, "HEAD");
            return new IntegrationWorktreeResult(worktreePath, true, finalCommit, "");

        } catch (Exception ex) {
            return new IntegrationWorktreeResult(worktreePath, false, "",
                    "Integration worktree setup failed: " + ex.getMessage());
        }
    }

    /**
     * Removes an integration worktree via {@code git worktree remove --force}.
     *
     * @param worktreePath path to integration worktree
     */
    public void removeIntegrationWorktree(Path worktreePath) {
        if (worktreePath == null || !Files.exists(worktreePath)) {
            return;
        }
        try {
            Path topLevel = resolveGitTopLevel(worktreePath);
            runGit(topLevel, "worktree", "remove", "--force", worktreePath.toString());
        } catch (IOException ignored) {
        }
    }

    /**
     * Materializes an immutable conflicting snapshot into a newly assigned
     * repair worktree rooted at the current control HEAD.
     *
     * <p>The target must already be a clean, isolated worktree at the exact
     * expected control head.  Synesis never resets a non-pristine target or
     * silently adopts a stale branch.  A cherry-pick conflict is an expected,
     * bounded repair representation and is deliberately left unresolved for
     * the repair participant; unrelated Git failures remain fatal.  The
     * immutable snapshot ref and commit are verified before any target write.
     *
     * @param controlRoot control checkout
     * @param repairWorktree assigned repair lane worktree
     * @param expectedControlHead control HEAD that must seed the repair lane
     * @param snapshot immutable conflicting snapshot
     * @throws IOException when the control head, target, immutable ref, or
     *         materialization cannot be verified
     */
    public void materializeRepairRepresentation(Path controlRoot, Path repairWorktree,
            String expectedControlHead, TaskSnapshotRecord snapshot) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(repairWorktree, "repairWorktree");
        Objects.requireNonNull(expectedControlHead, "expectedControlHead");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!Files.isDirectory(repairWorktree) || !Files.exists(repairWorktree.resolve(".git"))) {
            throw new IOException("REPAIR_WORKTREE_INVALID");
        }
        Path control = controlRoot.toAbsolutePath().normalize();
        Path target = repairWorktree.toAbsolutePath().normalize();
        if (control.equals(target)) {
            throw new IOException("REPAIR_WORKTREE_IS_CONTROL_CHECKOUT");
        }
        if (!expectedControlHead.equals(gitRevParse(control, "HEAD"))) {
            throw new IOException("STALE_CONTROL_HEAD");
        }
        if (!expectedControlHead.equals(gitRevParse(target, "HEAD"))) {
            throw new IOException("REPAIR_TARGET_NOT_AT_CONTROL_HEAD");
        }
        String snapshotRef = snapshot.provenance().snapshotRef();
        if (!snapshotRef.startsWith("refs/synesis/snapshots/")) {
            throw new IOException("REPAIR_SNAPSHOT_REF_INVALID");
        }
        String resolvedSnapshot = gitRevParse(target, snapshotRef + "^{commit}");
        if (!snapshot.commitSha().equals(resolvedSnapshot)) {
            throw new IOException("REPAIR_SNAPSHOT_OBJECT_MISMATCH");
        }

        Path cherryPickHead = gitPath(target, "CHERRY_PICK_HEAD");
        if (Files.exists(cherryPickHead)) {
            String inProgress = gitRevParse(target, "CHERRY_PICK_HEAD");
            if (!snapshot.commitSha().equals(inProgress)) {
                throw new IOException("REPAIR_DIFFERENT_CHERRY_PICK_IN_PROGRESS");
            }
            return;
        }
        Path marker = target.resolve(".synesis/local/repair-materialization.txt");
        String expectedMarker = snapshot.snapshotId() + "\n" + expectedControlHead + "\n"
                + snapshot.commitSha() + "\n";
        if (Files.exists(marker)) {
            String actualMarker = Files.readString(marker);
            if (!expectedMarker.equals(actualMarker)) {
                throw new IOException("REPAIR_MATERIALIZATION_MARKER_MISMATCH");
            }
            if (!expectedControlHead.equals(gitRevParse(target, "HEAD"))
                    && materializedCommitHasExpectedParent(target, expectedControlHead)) {
                return;
            }
        }
        String status = runGitOutput(target, "status", "--porcelain", "--untracked-files=all");
        List<String> unmanagedChanges = status.lines()
                .filter(line -> !line.isBlank())
                .filter(line -> !managedWorkspaceMetadata(line))
                .toList();
        if (!unmanagedChanges.isEmpty()) {
            throw new IOException("REPAIR_TARGET_DIRTY:" + unmanagedChanges.getFirst());
        }
        if (!Files.exists(marker)) {
            writeRepairMarker(marker, expectedMarker);
        }
        try {
            runGit(target, "cherry-pick", "--allow-empty", "--keep-redundant-commits", snapshot.commitSha());
        } catch (IOException conflict) {
            if (!Files.exists(gitPath(target, "CHERRY_PICK_HEAD"))
                    || !snapshot.commitSha().equals(gitRevParse(target, "CHERRY_PICK_HEAD"))) {
                throw new IOException("REPAIR_SNAPSHOT_MATERIALIZATION_FAILED", conflict);
            }
            // Preserve conflict markers and the unresolved index as the
            // explicit repair representation.
        }
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

    private static String gitRevParse(Path workdir, String ref) throws IOException {
        return org.synesis.workspace.lifecycle.GitProcessRunner.run(workdir, "rev-parse", ref).trim();
    }

    private static Path gitPath(Path workdir, String name) throws IOException {
        String raw = runGitOutput(workdir, "rev-parse", "--git-path", name);
        Path path = Path.of(raw);
        return (path.isAbsolute() ? path : workdir.resolve(path)).toAbsolutePath().normalize();
    }

    private static String runGitOutput(Path workdir, String... args) throws IOException {
        return org.synesis.workspace.lifecycle.GitProcessRunner.run(workdir, args).trim();
    }

    private static List<String> buildGitCommand(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        java.util.Collections.addAll(command, args);
        return command;
    }

    private static boolean managedWorkspaceMetadata(String statusLine) {
        if (statusLine.length() < 4) return false;
        String path = statusLine.substring(3).trim().replace('\\', '/');
        return path.equals(".codex/hooks.json")
                || path.equals(".agents/hooks.json")
                || path.equals(".claude/settings.json")
                || path.equals(".mcp.json")
                || path.equals(".synesis/local/workspace-binding.json")
                || path.equals(".synesis/local/repair-materialization.txt")
                || path.startsWith(".synesis/local/");
    }

    private static boolean materializedCommitHasExpectedParent(Path worktree, String expectedParent)
            throws IOException {
        String[] fields = runGitOutput(worktree, "rev-list", "--parents", "-n", "1", "HEAD").split("\\s+");
        String subject = runGitOutput(worktree, "show", "-s", "--format=%s", "HEAD");
        return fields.length >= 2 && expectedParent.equals(fields[1])
                && "Synesis immutable lane snapshot".equals(subject);
    }

    private static void writeRepairMarker(Path marker, String content) throws IOException {
        Files.createDirectories(marker.getParent());
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            try {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
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
