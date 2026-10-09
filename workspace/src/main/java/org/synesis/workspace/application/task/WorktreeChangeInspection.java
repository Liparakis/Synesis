package org.synesis.workspace.application.task;

import static org.synesis.workspace.application.task.SnapshotGitCommands.commitIsAncestor;
import static org.synesis.workspace.application.task.SnapshotGitCommands.gitRevParse;
import static org.synesis.workspace.application.task.SnapshotGitCommands.runGitOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;

/**
 * Reads what a worker worktree contains relative to a base commit: whether it is dirty, which
 * paths changed, and how a path compares with a given commit. It performs only read-only Git
 * inspection and applies no publication policy, so the snapshot-materialization and
 * publishability decisions can share one definition of "what changed".
 */
final class WorktreeChangeInspection {

    record WorktreeChanges(boolean dirty, String headCommit, String baseCommit, List<String> changedPaths) {

    }

    private WorktreeChangeInspection() {
    }

    static String deriveBaseCommit(Path workdir) {
        try {
            return runGitOutput(workdir, "rev-parse", "HEAD^");
        } catch (IOException e) {
            try {
                return runGitOutput(workdir, "hash-object", "-t", "tree", "/dev/null");
            } catch (IOException e2) {
                return "";
            }
        }
    }

    static WorktreeChanges inspectWorktreeChanges(Path worktree, String laneBaseCommit, boolean includeAllUntracked) throws IOException {
        String status = includeAllUntracked ? runGitOutput(worktree, "status", "--porcelain", "--untracked-files=all") : runGitOutput(worktree, "status", "--porcelain");
        boolean dirty = !status.isBlank();
        String headCommit = gitRevParse(worktree);
        String baseCommit = laneBaseCommit == null || laneBaseCommit.isBlank() ? dirty ? headCommit : deriveBaseCommit(worktree) : laneBaseCommit;
        return new WorktreeChanges(dirty, headCommit, baseCommit, deriveChangedPaths(worktree, baseCommit, dirty));
    }

    static List<String> deriveChangedPaths(Path workdir, String baseCommit, boolean includeWorkingTree) {
        if (baseCommit.isBlank()) {
            return List.of();
        }
        try {
            List<String> paths = new ArrayList<>();
            String committed = runGitOutput(workdir, "diff", "--name-only", baseCommit, "HEAD");
            String working = includeWorkingTree ? runGitOutput(workdir, "diff", "--name-only", "HEAD") : "";
            String untracked = includeWorkingTree ? runGitOutput(workdir, "ls-files", "--others", "--exclude-standard") : "";
            String output = committed + "\n" + working + "\n" + untracked;
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    if (!paths.contains(trimmed)) {
                        paths.add(trimmed);
                    }
                    if (paths.size() >= TaskSnapshotRecord.MAX_CHANGED_PATHS) {
                        break;
                    }
                }
            }
            return List.copyOf(paths);
        } catch (Exception ex) {
            throw new IllegalStateException("SNAPSHOT_DIFF_INSPECTION_FAILED", ex);
        }
    }

    /** Implements {@link TaskSnapshotService#snapshotBaseForBoundWorktree(Path, String, String)}. */
    static String snapshotBaseForBoundWorktree(Path workerWorktreePath, String intentBaseCommit, String bindingBaseCommit) throws IOException {
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        if (intentBaseCommit == null || intentBaseCommit.isBlank() || bindingBaseCommit == null || bindingBaseCommit.isBlank() || intentBaseCommit.equals(bindingBaseCommit)) {
            return intentBaseCommit;
        }
        String worktreeHead = gitRevParse(workerWorktreePath);
        if (commitIsAncestor(workerWorktreePath, intentBaseCommit, bindingBaseCommit) && commitIsAncestor(workerWorktreePath, bindingBaseCommit, worktreeHead)) {
            return bindingBaseCommit;
        }
        return intentBaseCommit;
    }

    static boolean differsFromCommit(Path workdir, String commit, String path) throws IOException {
        String normalized = path.replace('\\', '/');
        boolean presentInCommit;
        try {
            runGitOutput(workdir, "cat-file", "-e", commit + ":" + normalized);
            presentInCommit = true;
        } catch (IOException missingPath) {
            presentInCommit = false;
        }
        if (!presentInCommit) {
            return Files.exists(workdir.resolve(normalized));
        }
        Path currentPath = workdir.resolve(normalized);
        if (!Files.exists(currentPath)) {
            return true;
        }
        String committedHash = runGitOutput(workdir, "rev-parse", commit + ":" + normalized);
        String currentHash = runGitOutput(workdir, "hash-object", "--", normalized);
        return !committedHash.equals(currentHash);
    }

    /** Implements {@link TaskSnapshotService#isCleanWorktree(Path)}. */
    static boolean isCleanWorktree(Path workerWorktreePath) throws IOException {
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        return runGitOutput(workerWorktreePath, "status", "--porcelain", "--untracked-files=all").isBlank();
    }
}
