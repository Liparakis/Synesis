package org.synesis.workspace.application.task;

import static org.synesis.workspace.application.task.SnapshotGitCommands.runGitOutput;
import static org.synesis.workspace.application.task.SnapshotGitCommands.runGitWithIndex;
import static org.synesis.workspace.application.task.SnapshotGitCommands.runGitWithIndexOutput;
import static org.synesis.workspace.application.task.WorktreeChangeInspection.inspectWorktreeChanges;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.workspace.application.task.WorktreeChangeInspection.WorktreeChanges;
import org.synesis.workspace.infrastructure.digest.Sha256;

/**
 * Builds the immutable content of a task snapshot: which changed paths are materialized, the Git
 * tree captured from a temporary index, the commit and ref that pin it, and the identity and
 * integrity values derived from it. It is separate because this is the part that decides exactly
 * which bytes are published; the temporary index guarantees HEAD, the normal index and the working
 * files are never touched.
 */
final class SnapshotTreeMaterialization {

    private SnapshotTreeMaterialization() {
    }

    /** Implements {@link TaskSnapshotService#capabilityPublicationTreeHash(Path, String, List, List)}. */
    static String capabilityPublicationTreeHash(SnapshotArtifactPolicy artifactPolicy, Path worktree, String laneBaseCommit,
            List<ResourceSelector> claims, List<ResourceSelector> foreignClaims) throws IOException {
        if (claims == null || claims.isEmpty()) {
            throw new IOException("CAPABILITY_PUBLICATION_CLAIM_REQUIRED");
        }
        WorktreeChanges changes = inspectWorktreeChanges(worktree, laneBaseCommit, true);
        List<String> paths = materializedPaths(artifactPolicy, changes.changedPaths(), claims, false, foreignClaims);
        if (paths.isEmpty()) {
            throw new IOException("NO_CHANGES_TO_PUBLISH");
        }
        return preparedTreeHash(worktree, changes.headCommit(), paths);
    }

    static List<String> materializedPaths(SnapshotArtifactPolicy artifactPolicy, List<String> changedPaths, List<ResourceSelector> claims,
            boolean scopeToClaims, List<ResourceSelector> foreignClaims) {
        SnapshotArtifactPolicy.Manifest manifest = artifactPolicy.classify(changedPaths);
        if (!manifest.valid()) {
            throw new IllegalStateException("SNAPSHOT_ARTIFACT_POLICY:" + manifest.rejectedArtifacts());
        }
        List<String> sourcePaths = changedPaths.stream().filter(path -> !manifest.allowedArtifacts().contains(path))
                .filter(path -> foreignClaims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path)))).toList();
        if (!scopeToClaims && !claims.isEmpty()
                && sourcePaths.stream().anyMatch(path -> claims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))))) {
            throw new IllegalStateException("UNCLAIMED_SNAPSHOT_PATH");
        }
        return scopeToClaims ? sourcePaths.stream()
                .filter(path -> claims.stream().anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path)))).toList() : sourcePaths;
    }

    static String preparedTreeHash(Path workdir, String parent, List<String> paths) throws IOException {
        Path index = null;
        try {
            index = Files.createTempFile("synesis-tree-", ".index");
            Files.deleteIfExists(index);
            runGitWithIndex(workdir, index, "read-tree", parent);
            stageSourceIndex(workdir, index, paths);
            return runGitWithIndexOutput(workdir, index, "write-tree");
        } finally {
            if (index != null) {
                Files.deleteIfExists(index);
            }
        }
    }

    static String stableId(String project, UUID laneId, long epoch, String base, String tree) {
        return Sha256.hex(project + "\n" + laneId + "\n" + epoch + "\n" + base + "\n" + tree).substring(0, 32);
    }

    static String projectIdentity(Path controlRoot) {
        Path metadata = controlRoot.resolve(".synesis/project.json");
        try {
            String content = Files.readString(metadata);
            int marker = content.indexOf("\"projectId\"");
            if (marker >= 0) {
                int colon = content.indexOf(':', marker);
                int first = content.indexOf('"', colon + 1);
                int second = content.indexOf('"', first + 1);
                if (first >= 0 && second > first) {
                    return content.substring(first + 1, second);
                }
            }
        } catch (IOException ignored) {
            // The canonical fallback remains deterministic for uninitialized
            // unit fixtures; production callers require project metadata.
        }
        return controlRoot.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    static String materializeSnapshot(Path workdir, String parent, String snapshotId, String tree) throws IOException {
        String ref = SnapshotRefs.snapshotRef(snapshotId);
        String existing;
        try {
            existing = runGitOutput(workdir, "rev-parse", "--verify", ref);
        } catch (IOException missing) {
            existing = null;
        }
        if (existing != null) {
            if (!tree.equals(runGitOutput(workdir, "rev-parse", existing + "^{tree}"))
                    || !parent.equals(runGitOutput(workdir, "rev-parse", existing + "^"))) {
                throw new IOException("SNAPSHOT_REF_CONTENT_MISMATCH");
            }
            return existing;
        }
        // Commit the already captured tree, never restage mutable files between
        // the optimistic tree check and immutable publication.
        String commit = runGitOutput(workdir, "commit-tree", tree, "-p", parent, "-m", "Synesis immutable lane snapshot " + snapshotId);
        runGitOutput(workdir, "update-ref", ref, commit, "");
        return commit;
    }

    private static void stageSourceIndex(Path workdir, Path index, List<String> paths) throws IOException {
        // Let Git's ignore rules omit provider/admin material first.  Reset
        // explicitly managed paths from the temporary index as well, covering
        // projects that accidentally track one of those files.
        if (paths.isEmpty()) {
            runGitWithIndex(workdir, index, "add", "-A", "--", ".");
        } else {
            List<String> add = new ArrayList<>();
            add.add("add");
            add.add("-A");
            add.add("--");
            add.addAll(paths);
            runGitWithIndex(workdir, index, add.toArray(String[]::new));
        }
        for (String managed : List.of(".synesis", ".codex", ".claude", "AGENTS.md", ".mcp.json", "__pycache__", ":(glob)**/__pycache__/**")) {
            try {
                runGitWithIndex(workdir, index, "reset", "--", managed);
            } catch (IOException ignored) {
                // The managed path may not exist or be tracked in this lane.
            }
        }
    }

    static String integrity(String commit, List<String> paths) {
        return Sha256.hex(commit + "\n" + String.join("\n", paths));
    }
}
