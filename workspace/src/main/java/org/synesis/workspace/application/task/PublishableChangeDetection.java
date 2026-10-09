package org.synesis.workspace.application.task;

import static org.synesis.workspace.application.task.SnapshotGitCommands.runGitOutput;
import static org.synesis.workspace.application.task.WorktreeChangeInspection.differsFromCommit;
import static org.synesis.workspace.application.task.WorktreeChangeInspection.inspectWorktreeChanges;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;

/**
 * Decides whether a worker worktree holds source changes that a snapshot could publish, given
 * the snapshot artifact policy and the lane's claims. These are the read-only predicates the
 * next-action projection uses to recommend publication; keeping them apart from snapshot
 * materialization means a projected action is judged by the same changed-path and artifact-policy
 * rules without touching the index or any ref.
 */
final class PublishableChangeDetection {

    private PublishableChangeDetection() {
    }

    static Optional<List<String>> publishableSourceChanges(SnapshotArtifactPolicy artifactPolicy, List<String> changedPaths) {
        SnapshotArtifactPolicy.Manifest manifest = artifactPolicy.classify(changedPaths);
        if (!manifest.valid()) {
            return Optional.empty();
        }
        return Optional.of(changedPaths.stream().filter(path -> !manifest.allowedArtifacts().contains(path)).toList());
    }

    /** Inspects a worktree and applies the same artifact policy used by both publication gates. */
    static Optional<List<String>> publishableWorktreeSourceChanges(SnapshotArtifactPolicy artifactPolicy, Path worktreePath, String laneBaseCommit) throws IOException {
        List<String> changedPaths = inspectWorktreeChanges(worktreePath, laneBaseCommit, true).changedPaths();
        return publishableSourceChanges(artifactPolicy, changedPaths);
    }

    /** Implements {@link TaskSnapshotService#hasPublishableChanges(Path, List, String, boolean)}. */
    static boolean hasPublishableChanges(SnapshotArtifactPolicy artifactPolicy, Path workerWorktreePath, List<ResourceSelector> claims, String laneBaseCommit, boolean allowOtherClaimedChanges) throws IOException {
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(claims, "claims");
        Optional<List<String>> publishableChanges = publishableWorktreeSourceChanges(artifactPolicy, workerWorktreePath, laneBaseCommit);
        if (publishableChanges.isEmpty()) {
            return false;
        }
        List<String> sourceChanges = publishableChanges.orElseThrow();
        if (!claims.isEmpty()) {
            boolean laneChange = sourceChanges.stream().anyMatch(path -> claims.stream().anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))));
            if (!laneChange) {
                return false;
            }
            if (!allowOtherClaimedChanges && sourceChanges.stream().anyMatch(path -> claims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))))) {
                return false;
            }
        }
        return !sourceChanges.isEmpty();
    }

    /** Implements {@link TaskSnapshotService#hasScopedPublishableChanges(Path, List, List, List, String)}. */
    static boolean hasScopedPublishableChanges(SnapshotArtifactPolicy artifactPolicy, Path workerWorktreePath, List<ResourceSelector> laneClaims, List<ResourceSelector> participantClaims, List<ResourceSelector> foreignClaims, String laneBaseCommit)
            throws IOException {
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(laneClaims, "laneClaims");
        Objects.requireNonNull(participantClaims, "participantClaims");
        Objects.requireNonNull(foreignClaims, "foreignClaims");
        if (foreignClaims.isEmpty()) {
            return hasPublishableChanges(artifactPolicy, workerWorktreePath, laneClaims, laneBaseCommit, true) && hasPublishableChanges(artifactPolicy, workerWorktreePath, participantClaims, laneBaseCommit, false);
        }
        Optional<List<String>> publishableChanges = publishableWorktreeSourceChanges(artifactPolicy, workerWorktreePath, laneBaseCommit);
        if (publishableChanges.isEmpty()) {
            return false;
        }
        List<String> sourceChanges = publishableChanges.orElseThrow();
        boolean laneChange = sourceChanges.stream().anyMatch(path -> laneClaims.stream().anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))));
        if (!laneChange) {
            return false;
        }
        return sourceChanges.stream().allMatch(path -> participantClaims.stream().anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))) || foreignClaims.stream()
                .anyMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))));
    }

    /** Implements {@link TaskSnapshotService#hasRevisionPublishableChanges(Path, List, String, TaskSnapshotRecord)}. */
    static boolean hasRevisionPublishableChanges(Path workerWorktreePath, List<ResourceSelector> laneClaims, String laneBaseCommit, TaskSnapshotRecord rejectedSnapshot) throws IOException {
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(laneClaims, "laneClaims");
        Objects.requireNonNull(rejectedSnapshot, "rejectedSnapshot");
        String rejectedCommit = rejectedSnapshot.commitSha();
        if (rejectedCommit == null || rejectedCommit.isBlank()) {
            return false;
        }
        runGitOutput(workerWorktreePath, "cat-file", "-e", rejectedCommit + "^{commit}");
        List<String> currentPaths = inspectWorktreeChanges(workerWorktreePath, laneBaseCommit, true).changedPaths();
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>(rejectedSnapshot.changedPaths());
        candidates.addAll(currentPaths);
        for (String path : candidates) {
            if (laneClaims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path)))) {
                continue;
            }
            if (differsFromCommit(workerWorktreePath, rejectedCommit, path)) {
                return true;
            }
        }
        return false;
    }
}
