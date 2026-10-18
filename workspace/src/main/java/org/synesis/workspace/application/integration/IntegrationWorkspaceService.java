package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.task.SnapshotRefs;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Manages dedicated Git integration worktrees for task integration.
 *
 * <p>Integration worktrees are created outside the control checkout under:
 * {@code %LOCALAPPDATA%\Synesis\workspaces\<project-id>\integration\<attempt-id>} or
 * {@code ~/.synesis/workspaces/<project-id>/integration/<attempt-id>}.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class IntegrationWorkspaceService {

    /**
     * Creates an integration workspace service.
     */
    public IntegrationWorkspaceService() {
    }

    /**
     * Resolves the external integration root directory for a given project.
     *
     * @param projectRoot control project root path
     * @return absolute path to external integration directory
     */
    static Path resolveIntegrationRoot(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        return ExternalWorktrees.root(projectRoot, "integration");
    }

    private static Path gitPath(Path workdir) throws IOException {
        String raw = GitProcessRunner.run(workdir, "rev-parse", "--git-path", "CHERRY_PICK_HEAD");
        Path path = Path.of(raw);
        return (path.isAbsolute() ? path : workdir.resolve(path)).toAbsolutePath().normalize();
    }

    private static boolean managedWorkspaceMetadata(String statusLine) {
        if (statusLine.length() < 4) {
            return false;
        }
        String path = statusLine.substring(3).trim().replace('\\', '/');
        return path.equals(".codex/hooks.json") || path.equals(".claude/settings.json") || path.equals(".mcp.json") || path.equals(".synesis/local/workspace-binding.json") || path.equals(
                ".synesis/local/repair-materialization.txt") || path.startsWith(".synesis/local/");
    }

    private static boolean materializedCommitHasExpectedParent(Path worktree, String expectedParent) throws IOException {
        String[] fields = GitProcessRunner.run(worktree, "rev-list", "--parents", "-n", "1", "HEAD").split("\\s+");
        String subject = GitProcessRunner.run(worktree, "show", "-s", "--format=%s", "HEAD");
        return fields.length >= 2 && expectedParent.equals(fields[1]) && "Synesis immutable lane snapshot".equals(subject);
    }

    private static void writeRepairMarker(Path marker, String content) throws IOException {
        Files.createDirectories(marker.getParent());
        Path temporary = marker.resolveSibling(marker.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, content, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            try {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, marker, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Reads the verified control checkout HEAD used to seed an integration or repair lane.
     *
     * @param repository control checkout
     * @return full Git commit SHA
     * @throws IOException when HEAD cannot be resolved
     */
    public String currentHead(Path repository) throws IOException {
        Objects.requireNonNull(repository, "repository");
        return GitProcessRunner.run(repository.toAbsolutePath().normalize(), "rev-parse", "HEAD");
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
    public IntegrationWorktreeResult prepareIntegrationWorktree(Path projectRoot, String attemptId, String expectedControlHead, List<TaskSnapshotRecord> orderedSnapshots) {
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
                String currentHead = GitProcessRunner.run(worktreePath, "rev-parse", "HEAD");
                if (Files.exists(gitPath(worktreePath))) {
                    return new IntegrationWorktreeResult(worktreePath, false, "", "Integration worktree has an unresolved cherry-pick conflict");
                }
                if (!expectedControlHead.equals(currentHead)) {
                    return new IntegrationWorktreeResult(worktreePath, false, "", "Integration worktree head does not match expected control head");
                }
                return new IntegrationWorktreeResult(worktreePath, true, currentHead, "");
            }

            // 1. Create Git worktree at expectedControlHead
            GitProcessRunner.run(projectRoot, "worktree", "add", "--detach", worktreePath.toString(), expectedControlHead);
            try {
                GitProcessRunner.run(worktreePath, "config", "user.name", "Synesis Integrator");
                GitProcessRunner.run(worktreePath, "config", "user.email", "integrator@synesis.org");
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
                    GitProcessRunner.run(worktreePath, "cherry-pick", "--allow-empty", "--keep-redundant-commits", snap.commitSha());
                } catch (IOException cherryPickFailure) {
                    // Preserve the conflicted index and worktree.  The repair
                    // lane consumes this exact representation; resetting or
                    // aborting here would discard the conflicting work.
                    return new IntegrationWorktreeResult(worktreePath, false, "", "Merge conflict applying task snapshot " + snap.snapshotId() + ": " + cherryPickFailure.getMessage());
                }
            }

            String finalCommit = GitProcessRunner.run(worktreePath, "rev-parse", "HEAD");
            return new IntegrationWorktreeResult(worktreePath, true, finalCommit, "");

        } catch (Exception ex) {
            return new IntegrationWorktreeResult(worktreePath, false, "", "Integration worktree setup failed: " + ex.getMessage());
        }
    }

    /**
     * Removes an integration worktree via {@code git worktree remove --force}.
     *
     * @param worktreePath path to integration worktree
     */
    public void removeIntegrationWorktree(Path worktreePath) {
        ExternalWorktrees.removeBestEffort(worktreePath);
    }

    /**
     * Materializes an immutable conflicting snapshot into a newly assigned repair worktree rooted at
     * the current control HEAD.
     *
     * <p>The target must already be a clean, isolated worktree at the exact
     * expected control head.  Synesis never resets a non-pristine target or silently adopts a stale
     * branch.  A cherry-pick conflict is an expected, bounded repair representation and is
     * deliberately left unresolved for the repair participant; unrelated Git failures remain fatal.
     * The immutable snapshot ref and commit are verified before any target write.
     *
     * @param controlRoot         control checkout
     * @param repairWorktree      assigned repair lane worktree
     * @param expectedControlHead control HEAD that must seed the repair lane
     * @param snapshot            immutable conflicting snapshot
     * @throws IOException when the control head, target, immutable ref, or materialization cannot be
     *                     verified
     */
    public void materializeRepairRepresentation(Path controlRoot, Path repairWorktree, String expectedControlHead, TaskSnapshotRecord snapshot) throws IOException {
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
        if (!expectedControlHead.equals(GitProcessRunner.run(control, "rev-parse", "HEAD"))) {
            throw new IOException("STALE_CONTROL_HEAD");
        }
        if (!expectedControlHead.equals(GitProcessRunner.run(target, "rev-parse", "HEAD"))) {
            throw new IOException("REPAIR_TARGET_NOT_AT_CONTROL_HEAD");
        }
        String snapshotRef = snapshot.provenance().snapshotRef();
        if (!SnapshotRefs.isSnapshotRef(snapshotRef)) {
            throw new IOException("REPAIR_SNAPSHOT_REF_INVALID");
        }
        String resolvedSnapshot = GitProcessRunner.run(target, "rev-parse", snapshotRef + "^{commit}");
        if (!snapshot.commitSha().equals(resolvedSnapshot)) {
            throw new IOException("REPAIR_SNAPSHOT_OBJECT_MISMATCH");
        }

        Path cherryPickHead = gitPath(target);
        if (Files.exists(cherryPickHead)) {
            String inProgress = GitProcessRunner.run(target, "rev-parse", "CHERRY_PICK_HEAD");
            if (!snapshot.commitSha().equals(inProgress)) {
                throw new IOException("REPAIR_DIFFERENT_CHERRY_PICK_IN_PROGRESS");
            }
            return;
        }
        Path marker = target.resolve(".synesis/local/repair-materialization.txt");
        String expectedMarker = snapshot.snapshotId() + "\n" + expectedControlHead + "\n" + snapshot.commitSha() + "\n";
        if (Files.exists(marker)) {
            String actualMarker = Files.readString(marker);
            if (!expectedMarker.equals(actualMarker)) {
                throw new IOException("REPAIR_MATERIALIZATION_MARKER_MISMATCH");
            }
            if (!expectedControlHead.equals(GitProcessRunner.run(target, "rev-parse", "HEAD")) && materializedCommitHasExpectedParent(target, expectedControlHead)) {
                return;
            }
        }
        String status = GitProcessRunner.run(target, "status", "--porcelain", "--untracked-files=all");
        List<String> unmanagedChanges = status.lines().filter(line -> !line.isBlank()).filter(line -> !managedWorkspaceMetadata(line)).toList();
        if (!unmanagedChanges.isEmpty()) {
            throw new IOException("REPAIR_TARGET_DIRTY:" + unmanagedChanges.getFirst());
        }
        if (!Files.exists(marker)) {
            writeRepairMarker(marker, expectedMarker);
        }
        try {
            GitProcessRunner.run(target, "cherry-pick", "--allow-empty", "--keep-redundant-commits", snapshot.commitSha());
        } catch (IOException conflict) {
            if (!Files.exists(gitPath(target)) || !snapshot.commitSha().equals(GitProcessRunner.run(target, "rev-parse", "CHERRY_PICK_HEAD"))) {
                throw new IOException("REPAIR_SNAPSHOT_MATERIALIZATION_FAILED", conflict);
            }
            // Preserve conflict markers and the unresolved index as the
            // explicit repair representation.
        }
    }

    /**
     * Gives a combine lane's worktree both versions to combine (ADR-0098 step 5, designer items 22 and 26): the
     * session's branch is moved to the commit the advance could not be merged onto, the advance is merged in without
     * committing, Git's conflict markers stay in the files, and the index is reset to the base so the agent's change is
     * everything it finishes with. The branch is moved, not HEAD detached, so the worktree stays the session's by
     * every check its binding is verified with (s5-run8). Only a worktree still at the commit it was made at is moved,
     * so no commit of its own is left behind. A marker records the combine, so a repeated call after a crash leaves the
     * worktree as it is.
     *
     * @param controlRoot the control checkout, whose repository holds both versions
     * @param worktree    the combine lane's worktree, clean apart from Synesis's own files
     * @param branch      the session's branch, which the worktree must be on
     * @param head        the commit the worktree was made at, which it must still be at
     * @param combine     the combine
     * @throws IOException when the worktree is not clean, not on {@code branch} at {@code head}, belongs to another
     *                     combine, or Git fails
     */
    public void materializeCombineRepresentation(Path controlRoot, Path worktree, String branch, String head,
            org.synesis.coordination.domain.task.CombinePayload combine) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(combine, "combine");
        Path target = worktree.toAbsolutePath().normalize();
        if (!Files.isDirectory(target) || !Files.exists(target.resolve(".git")) || target.equals(controlRoot.toAbsolutePath().normalize())) {
            throw new IOException("COMBINE_WORKTREE_INVALID");
        }
        Path marker = target.resolve(".synesis/local/combine-materialization.txt");
        String expectedMarker = combine.combineId() + "\n" + combine.base() + "\n" + combine.theirs() + "\n";
        if (Files.exists(marker)) {
            if (!expectedMarker.equals(Files.readString(marker))) {
                throw new IOException("COMBINE_MATERIALIZATION_MARKER_MISMATCH");
            }
            return;
        }
        String status = GitProcessRunner.run(target, "status", "--porcelain", "--untracked-files=all");
        List<String> unmanagedChanges = status.lines().filter(line -> !line.isBlank()).filter(line -> !managedWorkspaceMetadata(line)).toList();
        if (!unmanagedChanges.isEmpty()) {
            throw new IOException("COMBINE_TARGET_DIRTY:" + unmanagedChanges.getFirst());
        }
        if (!branch.equals(GitProcessRunner.runOptional(target, "symbolic-ref", "-q", "--short", "HEAD").trim())) {
            throw new IOException("COMBINE_WORKTREE_INVALID");
        }
        if (!head.equals(GitProcessRunner.run(target, "rev-parse", "HEAD").trim())) {
            throw new IOException("COMBINE_WORKTREE_HAS_COMMITS");
        }
        GitProcessRunner.run(target, "checkout", "-q", "-B", branch, combine.base());
        writeRepairMarker(marker, expectedMarker);
        try {
            GitProcessRunner.run(target, "merge", "--no-ff", "--no-commit", combine.theirs());
        } catch (IOException conflict) {
            // Expected: the re-merge could not make this merge. The markers in the files are the agent's to resolve.
        }
        GitProcessRunner.runOptional(target, "merge", "--quit");
        GitProcessRunner.run(target, "reset", "-q");
    }

    /**
     * Gives a takeover's worktree the work of the lane it takes over (ADR-0098 step 7, designer item 11): everything
     * the snapshot changed against its lane's base, applied without committing onto the worktree as it was made. A
     * snapshot's commit sits on whatever its lane had committed, so it is first restated as one commit on the base.
     * Conflicts stay in the files as Git's markers, for the taker to resolve.
     *
     * @param worktree the taker's worktree, clean apart from Synesis's own files
     * @param snapshot the lane's last snapshot that reached this computer
     * @throws IOException when the worktree is not clean, belongs to another takeover, or Git fails
     */
    public void materializeTakeover(Path worktree, TaskSnapshotRecord snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        applyUncommitted(worktree, "TAKEOVER", snapshot.snapshotId(), snapshot.baseCommit(), snapshot.commitSha());
    }

    /**
     * Gives a lane of a restore's work group the trashed work it brings back (ADR-0098 step 7, designer item 15):
     * everything the trashed commit holds against the trashed lane's base, applied without committing, as a
     * takeover's worktree gets its snapshot.
     *
     * @param worktree the new lane's worktree, clean apart from Synesis's own files
     * @param item     what names the trashed work, for the marker
     * @param base     the trashed lane's base
     * @param commit   the trashed commit, present in the repository
     * @throws IOException when the worktree is not clean, belongs to another restore, or Git fails
     */
    public void materializeRestore(Path worktree, String item, String base, String commit) throws IOException {
        applyUncommitted(worktree, "RESTORE", item, base, commit);
    }

    /**
     * Takes back what {@link #materializeTakeover} applied, when the takeover's entry was refused: every path the
     * snapshot changed is put back as the worktree's commit has it, and the marker is removed, so the worktree is as
     * it was made and may take over another lane.
     *
     * @param worktree the taker's worktree
     * @param snapshot the snapshot that was applied
     * @throws IOException when Git fails
     */
    public void undoTakeover(Path worktree, TaskSnapshotRecord snapshot) throws IOException {
        undoUncommitted(worktree, "TAKEOVER", snapshot.baseCommit(), snapshot.commitSha());
    }

    /**
     * Takes back what {@link #materializeRestore} applied, when the lane's announcement was refused.
     *
     * @param worktree the lane's worktree
     * @param base     the trashed lane's base
     * @param commit   the trashed commit
     * @throws IOException when Git fails
     */
    public void undoRestore(Path worktree, String base, String commit) throws IOException {
        undoUncommitted(worktree, "RESTORE", base, commit);
    }

    private void undoUncommitted(Path worktree, String kind, String base, String commit) throws IOException {
        Path target = worktree.toAbsolutePath().normalize();
        for (String path : GitProcessRunner.run(target, "diff", "--name-only", "--no-renames", base, commit).lines().filter(line -> !line.isBlank()).toList()) {
            if (GitProcessRunner.runResult(target, "cat-file", "-e", "HEAD:" + path).exitCode() == 0) {
                GitProcessRunner.run(target, "checkout", "-q", "HEAD", "--", path);
            } else {
                Files.deleteIfExists(target.resolve(path));
            }
        }
        Files.deleteIfExists(target.resolve(".synesis/local/" + kind.toLowerCase(java.util.Locale.ROOT) + "-materialization.txt"));
    }

    /**
     * Applies everything {@code commit} holds against {@code base} to a worktree, without committing; conflicts stay
     * as Git's markers, and a marker file makes a repeated call after a crash leave the worktree as it is.
     */
    private void applyUncommitted(Path worktree, String kind, String item, String base, String commit) throws IOException {
        Objects.requireNonNull(worktree, "worktree");
        Path target = worktree.toAbsolutePath().normalize();
        if (!Files.isDirectory(target) || !Files.exists(target.resolve(".git"))) {
            throw new IOException(kind + "_WORKTREE_INVALID");
        }
        Path marker = target.resolve(".synesis/local/" + kind.toLowerCase(java.util.Locale.ROOT) + "-materialization.txt");
        String expectedMarker = item + "\n" + base + "\n" + commit + "\n";
        if (Files.exists(marker)) {
            if (!expectedMarker.equals(Files.readString(marker))) {
                throw new IOException(kind + "_MATERIALIZATION_MARKER_MISMATCH");
            }
            return;
        }
        String status = GitProcessRunner.run(target, "status", "--porcelain", "--untracked-files=all");
        List<String> unmanagedChanges = status.lines().filter(line -> !line.isBlank()).filter(line -> !managedWorkspaceMetadata(line)).toList();
        if (!unmanagedChanges.isEmpty()) {
            throw new IOException(kind + "_TARGET_DIRTY:" + unmanagedChanges.getFirst());
        }
        String onBase = GitProcessRunner.run(target, "commit-tree", commit + "^{tree}", "-p", base, "-m", "Synesis " + kind.toLowerCase(java.util.Locale.ROOT) + " of " + item);
        writeRepairMarker(marker, expectedMarker);
        try {
            GitProcessRunner.run(target, "cherry-pick", "--no-commit", onBase);
        } catch (IOException conflict) {
            // The markers in the files are the taker's to resolve.
        }
        GitProcessRunner.runOptional(target, "cherry-pick", "--quit");
        GitProcessRunner.run(target, "reset", "-q");
    }

    /**
     * Result of an integration attempt inside the dedicated integration worktree.
     *
     * @param worktreePath         absolute path to integration worktree
     * @param success              true if all task snapshots applied without merge conflict
     * @param integrationCommitSha commit SHA produced in the integration worktree
     * @param failureReason        conflict or failure description when success is false
     */
    public record IntegrationWorktreeResult(Path worktreePath, boolean success, String integrationCommitSha, String failureReason) {

        /**
         * Validates non-null invariants.
         */
        public IntegrationWorktreeResult {
            Objects.requireNonNull(worktreePath, "worktreePath");
            Objects.requireNonNull(integrationCommitSha, "integrationCommitSha");
            Objects.requireNonNull(failureReason, "failureReason");
        }
    }
}
