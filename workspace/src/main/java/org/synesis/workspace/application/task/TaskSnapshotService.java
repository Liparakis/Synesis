package org.synesis.workspace.application.task;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.domain.task.SnapshotProvenance;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.workspace.lifecycle.GitProcessRunner;

/**
 * Service for creating and verifying immutable task snapshots from worker worktrees.
 *
 * <p>A completed task is represented by an immutable snapshot record referencing the
 * worker's verified Git commit SHA, base commit, changed-path manifest, and capability dependencies.
 *
 * @since 1.0
 */
public final class TaskSnapshotService {

    private final SnapshotArtifactPolicy artifactPolicy;
    private final org.synesis.workspace.lifecycle.RepositoryPortabilityService portabilityService;

    /**
     * Creates a task snapshot service.
     */
    public TaskSnapshotService() {
        this(new SnapshotArtifactPolicy(), new org.synesis.workspace.lifecycle.RepositoryPortabilityService());
    }

    /** Creates a service with an explicit artifact policy. *
     * @param artifactPolicy snapshot artifact policy
     */
    public TaskSnapshotService(SnapshotArtifactPolicy artifactPolicy) {
        this(artifactPolicy, new org.synesis.workspace.lifecycle.RepositoryPortabilityService());
    }

    /** Creates a service with explicit artifact and portability policies.
     * @param artifactPolicy snapshot artifact policy
     * @param portabilityService complete-tree portability validator
     */
    public TaskSnapshotService(SnapshotArtifactPolicy artifactPolicy,
                               org.synesis.workspace.lifecycle.RepositoryPortabilityService portabilityService) {
        this.artifactPolicy = Objects.requireNonNull(artifactPolicy, "artifactPolicy");
        this.portabilityService = Objects.requireNonNull(portabilityService, "portabilityService");
    }

    /**
     * Checks whether the worker worktree currently contains at least one
     * publishable source change.
     *
     * <p>This is the read-only precondition used by next-action projection
     * before recommending {@code finish_lane}. It deliberately mirrors the
     * changed-path and artifact-policy portion of {@link #createSnapshot} so
     * a projected publication action cannot be emitted for an empty or
     * rejected snapshot.
     *
     * @param workerWorktreePath absolute worker worktree path
     * @return {@code true} when snapshot creation can observe a valid source
     *         change; {@code false} for an empty, artifact-only, or rejected
     *         change set
     * @throws IOException if Git inspection fails
     */
    public boolean hasPublishableChanges(Path workerWorktreePath) throws IOException {
        return hasPublishableChanges(workerWorktreePath, List.of());
    }

    /**
     * Checks whether the worker worktree contains source changes covered by
     * the current lane claims.
     *
     * <p>A recovered worktree may be based on a sibling's integrated commit.
     * Those inherited source changes are not publication authority for this
     * lane.  Applying the same claim boundary used by snapshot creation keeps
     * a projected {@code finish_lane} executable without expanding ownership.
     *
     * @param workerWorktreePath absolute worker worktree path
     * @param claims             current lane resource claims
     * @return {@code true} when at least one source change is covered and no
     *         source change falls outside the supplied claims
     * @throws IOException if Git inspection fails
     */
    public boolean hasPublishableChanges(Path workerWorktreePath,
                                         List<ResourceSelector> claims) throws IOException {
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(claims, "claims");
        boolean dirty = !runGitOutput(workerWorktreePath,
                "status", "--porcelain", "--untracked-files=all").isBlank();
        String headCommit = gitRevParse(workerWorktreePath, "HEAD");
        String baseCommit = dirty ? headCommit : deriveBaseCommit(workerWorktreePath);
        List<String> allChangedPaths = deriveChangedPaths(workerWorktreePath, baseCommit, dirty);
        SnapshotArtifactPolicy.Manifest manifest = artifactPolicy.classify(allChangedPaths);
        if (!manifest.valid()) {
            return false;
        }
        List<String> sourceChanges = allChangedPaths.stream()
                .filter(path -> !manifest.allowedArtifacts().contains(path))
                .toList();
        if (!claims.isEmpty() && sourceChanges.stream().anyMatch(path -> claims.stream()
                .noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))))) {
            return false;
        }
        return !sourceChanges.isEmpty();
    }

    /**
     * Creates or recovers an immutable task snapshot record for a worker's task.
     *
     * @param taskId               task UUID
     * @param nodeId               worker node ID
     * @param supervisorId         worker supervisor ID
     * @param workerId             worker ID
     * @param providerSessionId    provider session ID
     * @param workerWorktreePath   absolute path to worker worktree
     * @param controlRoot          absolute path to control project root
     * @param summary              task completion summary
     * @param existingOpt          existing snapshot record for this task, if any
     * @param activeCapabilities   list of capability request records associated with this worker
     * @return immutable task snapshot record
     * @throws IOException if Git inspection fails
     */
    public TaskSnapshotRecord createSnapshot(
            UUID taskId,
            String nodeId,
            String supervisorId,
            String workerId,
            String providerSessionId,
            Path workerWorktreePath,
            Path controlRoot,
            String summary,
            Optional<TaskSnapshotRecord> existingOpt,
            List<CapabilityRequestRecord> activeCapabilities
    ) throws IOException {
        return createSnapshot(taskId, nodeId, supervisorId, workerId, providerSessionId,
                workerWorktreePath, controlRoot, summary, existingOpt, activeCapabilities, List.of());
    }

    /** Creates a snapshot while recording the lane's current resource claims.
     * @param taskId task ID
     * @param nodeId node ID
     * @param supervisorId supervisor ID
     * @param workerId worker ID
     * @param providerSessionId binding ID
     * @param workerWorktreePath lane worktree
     * @param controlRoot project root
     * @param summary summary
     * @param existingOpt existing snapshot
     * @param activeCapabilities capability dependencies
     * @param claims current exact-path/subtree claims
     * @return immutable snapshot
     * @throws IOException Git failure
     */
    public TaskSnapshotRecord createSnapshot(
            UUID taskId, String nodeId, String supervisorId, String workerId, String providerSessionId,
            Path workerWorktreePath, Path controlRoot, String summary,
            Optional<TaskSnapshotRecord> existingOpt, List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims
    ) throws IOException {
        return createSnapshot(taskId, nodeId, supervisorId, workerId, providerSessionId,
                workerWorktreePath, controlRoot, summary, existingOpt, activeCapabilities, claims,
                taskId, taskId, nodeId, providerSessionId, 1, List.of());
    }

    /** Creates a snapshot while recording explicit logical-lane provenance.
     * @param taskId task ID
     * @param nodeId node ID
     * @param supervisorId supervisor ID
     * @param workerId worker ID
     * @param providerSessionId binding ID
     * @param workerWorktreePath lane worktree
     * @param controlRoot project root
     * @param summary summary
     * @param existingOpt existing snapshot
     * @param activeCapabilities capability dependencies
     * @param claims current exact-path/subtree claims
     * @param workGroupId logical work-group ID
     * @param laneId mutation-lane intent ID
     * @param participant opaque participant handle
     * @param bindingIdentity exact binding identity
     * @param claimEpoch current claim epoch
     * @param handoffLineage handoff references
     * @return immutable snapshot
     * @throws IOException Git failure
     */
    public TaskSnapshotRecord createSnapshot(
            UUID taskId, String nodeId, String supervisorId, String workerId, String providerSessionId,
            Path workerWorktreePath, Path controlRoot, String summary,
            Optional<TaskSnapshotRecord> existingOpt, List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims, UUID workGroupId, UUID laneId, String participant,
            String bindingIdentity, long claimEpoch, List<String> handoffLineage
    ) throws IOException {
        return createSnapshot(taskId, nodeId, supervisorId, workerId, providerSessionId,
                workerWorktreePath, controlRoot, summary, existingOpt, activeCapabilities,
                claims, workGroupId, laneId, participant, bindingIdentity, claimEpoch,
                WorkIntent.defaultAuthorityLineage(laneId), handoffLineage);
    }

    /** Creates a snapshot while recording an explicit authority lineage.
     * @param taskId task ID
     * @param nodeId node ID
     * @param supervisorId supervisor ID
     * @param workerId worker ID
     * @param providerSessionId provider session ID
     * @param workerWorktreePath lane worktree
     * @param controlRoot control root
     * @param summary completion summary
     * @param existingOpt existing immutable snapshot
     * @param activeCapabilities capability records
     * @param claims lane claims
     * @param workGroupId work-group ID
     * @param laneId lane ID
     * @param participant participant
     * @param bindingIdentity binding identity
     * @param claimEpoch claim epoch
     * @param authorityLineageId authority lineage
     * @param handoffLineage handoff lineage
     * @return immutable snapshot
     * @throws IOException Git failure
     */
    public TaskSnapshotRecord createSnapshot(
            UUID taskId, String nodeId, String supervisorId, String workerId, String providerSessionId,
            Path workerWorktreePath, Path controlRoot, String summary,
            Optional<TaskSnapshotRecord> existingOpt, List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims, UUID workGroupId, UUID laneId, String participant,
            String bindingIdentity, long claimEpoch, UUID authorityLineageId,
            List<String> handoffLineage
    ) throws IOException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(supervisorId, "supervisorId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(providerSessionId, "providerSessionId");
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(activeCapabilities, "activeCapabilities");
        Objects.requireNonNull(claims, "claims");

        // Inspect the lane without requiring the harness to create a commit.
        if (existingOpt.isPresent()) {
            // A previously published snapshot is authoritative.  Do not inspect
            // or resnapshot a mutable lane during an idempotent retry.
            return existingOpt.get();
        }
        boolean dirty = !runGitOutput(workerWorktreePath, "status", "--porcelain").isBlank();
        String headCommit = gitRevParse(workerWorktreePath, "HEAD");
        String baseCommit = dirty ? headCommit : deriveBaseCommit(workerWorktreePath);
        List<String> allChangedPaths = deriveChangedPaths(workerWorktreePath, baseCommit, dirty);
        SnapshotArtifactPolicy.Manifest artifactManifest = artifactPolicy.classify(allChangedPaths);
        if (!artifactManifest.valid()) {
            throw new IllegalStateException("SNAPSHOT_ARTIFACT_POLICY:" + artifactManifest.rejectedArtifacts());
        }
        List<String> changedPaths = allChangedPaths.stream()
                .filter(path -> !artifactManifest.allowedArtifacts().contains(path)).toList();
        if (!claims.isEmpty() && changedPaths.stream().anyMatch(path -> claims.stream()
                .noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))))) {
            throw new IllegalStateException("UNCLAIMED_SNAPSHOT_PATH:" + changedPaths.stream()
                    .filter(path -> claims.stream().noneMatch(selector -> selector.overlaps(ResourceSelector.pathExact(path))))
                    .findFirst().orElse("unknown"));
        }
        String treeHash = preparedTreeHash(workerWorktreePath, headCommit);
        String snapshotId = "snap_" + stableId(projectIdentity(controlRoot), laneId, claimEpoch, baseCommit, treeHash);
        String commitSha;
        if (dirty) {
            commitSha = materializeSnapshot(workerWorktreePath, headCommit, snapshotId);
        } else {
            commitSha = headCommit;
            runGitOutput(workerWorktreePath, "update-ref", "refs/synesis/snapshots/" + snapshotId, commitSha);
        }
        org.synesis.workspace.lifecycle.RepositoryPortabilityService.Report portability =
                portabilityService.validateTree(workerWorktreePath, commitSha);
        if (!portability.portable()) {
            try {
                runGitOutput(workerWorktreePath, "update-ref", "-d", "refs/synesis/snapshots/" + snapshotId);
            } catch (IOException ignored) {
                // The portability failure remains the authoritative result.
            }
            throw new IllegalStateException("REPOSITORY_NOT_PORTABLE:" + portability.findings());
        }
        List<String> capabilityDependencies = new ArrayList<>();
        for (CapabilityRequestRecord cap : activeCapabilities) {
            capabilityDependencies.add(cap.handle().value());
        }

        SnapshotProvenance provenance = new SnapshotProvenance(workGroupId, laneId, authorityLineageId, participant,
                bindingIdentity, claimEpoch, capabilityDependencies, handoffLineage,
                claims.stream().map(selector -> selector.kind().name() + ":" + selector.value()).toList(),
                "refs/synesis/snapshots/" + snapshotId, integrity(commitSha, changedPaths),
                artifactManifest.digest());
        return new TaskSnapshotRecord(
                taskId, snapshotId, nodeId, supervisorId, workerId,
                providerSessionId, baseCommit, commitSha, changedPaths,
                List.copyOf(capabilityDependencies), summary, System.currentTimeMillis(), provenance);
    }

    /** Pins an already materialized snapshot commit under a transaction-owned
     * prepared ref and verifies that the ref resolves to the expected commit.
     * @param worktreePath lane worktree
     * @param snapshot snapshot record
     * @param completionId durable completion transaction ID
     * @return prepared ref name
     * @throws IOException if the ref cannot be created or verified
     */
    public String pinPreparedRef(Path worktreePath, TaskSnapshotRecord snapshot, String completionId)
            throws IOException {
        Objects.requireNonNull(worktreePath, "worktreePath");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(completionId, "completionId");
        String ref = "refs/synesis/prepared/" + completionId;
        runGitOutput(worktreePath, "update-ref", ref, snapshot.commitSha());
        String resolved = runGitOutput(worktreePath, "rev-parse", ref);
        if (!resolved.equals(snapshot.commitSha())) {
            throw new IOException("PREPARED_OBJECT_MISMATCH");
        }
        return ref;
    }

    /** Removes a provisional final snapshot ref before durable preparation.
     * @param worktreePath lane worktree
     * @param snapshot snapshot record
     * @throws IOException if Git cannot remove the ref
     */
    public void removeSnapshotRef(Path worktreePath, TaskSnapshotRecord snapshot) throws IOException {
        runGitOutput(worktreePath, "update-ref", "-d", snapshot.provenance().snapshotRef());
    }

    /** Removes a transaction-owned prepared reference during an authorized
     * completion unwind.
     * @param worktreePath lane worktree
     * @param preparedRef prepared reference
     * @throws IOException if Git cannot remove the reference
     */
    public void removePreparedRef(Path worktreePath, String preparedRef) throws IOException {
        Objects.requireNonNull(worktreePath, "worktreePath");
        Objects.requireNonNull(preparedRef, "preparedRef");
        if (!preparedRef.startsWith("refs/synesis/prepared/")) {
            throw new IOException("INVALID_PREPARED_REF");
        }
        runGitOutput(worktreePath, "update-ref", "-d", preparedRef);
    }

    /** Promotes a verified prepared ref to the immutable public snapshot ref.
     * @param worktreePath lane worktree
     * @param preparedRef prepared ref
     * @param finalRef final immutable ref
     * @param expectedCommit expected commit
     * @throws IOException if the object cannot be verified or promoted
     */
    public void promotePreparedRef(Path worktreePath, String preparedRef, String finalRef,
            String expectedCommit) throws IOException {
        verifyPreparedRef(worktreePath, preparedRef, expectedCommit);
        runGitOutput(worktreePath, "update-ref", finalRef, expectedCommit);
        verifyPreparedRef(worktreePath, finalRef, expectedCommit);
    }

    /** Verifies a prepared ref still resolves to the expected immutable commit.
     * @param worktreePath repository worktree
     * @param preparedRef prepared ref
     * @param expectedCommit expected commit
     * @throws IOException if verification fails
     */
    public void verifyPreparedRef(Path worktreePath, String preparedRef, String expectedCommit)
            throws IOException {
        String resolved = runGitOutput(worktreePath, "rev-parse", preparedRef);
        if (!resolved.equals(expectedCommit)) throw new IOException("PREPARED_OBJECT_MISMATCH");
    }

    /** Returns the immutable tree object reached by a commit.
     * @param worktreePath repository worktree
     * @param commit commit object
     * @return tree SHA
     * @throws IOException if Git cannot resolve the tree
     */
    public String treeHash(Path worktreePath, String commit) throws IOException {
        return runGitOutput(worktreePath, "rev-parse", commit + "^{tree}");
    }

    private static String gitRevParse(Path workdir, String ref) throws IOException {
        return runGitOutput(workdir, "rev-parse", ref);
    }

    private static String deriveBaseCommit(Path workdir) {
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

    private static List<String> deriveChangedPaths(Path workdir, String baseCommit, boolean includeWorkingTree) {
        if (baseCommit.isBlank()) {
            return List.of();
        }
        try {
            List<String> paths = new ArrayList<>();
            String committed = runGitStdout(workdir, "diff", "--name-only", baseCommit, "HEAD");
            String working = includeWorkingTree ? runGitStdout(workdir, "diff", "--name-only", "HEAD") : "";
            String untracked = includeWorkingTree ? runGitStdout(workdir, "ls-files", "--others", "--exclude-standard") : "";
            String output = committed + "\n" + working + "\n" + untracked;
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    if (!paths.contains(trimmed)) paths.add(trimmed);
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

    private static String preparedTreeHash(Path workdir, String parent) throws IOException {
        Path index = null;
        try {
            index = Files.createTempFile("synesis-tree-", ".index");
            Files.deleteIfExists(index);
            runGitWithIndex(workdir, index, "read-tree", parent);
            stageSourceIndex(workdir, index);
            return runGitWithIndexOutput(workdir, index, "write-tree");
        } finally {
            if (index != null) Files.deleteIfExists(index);
        }
    }

    private static String stableId(String project, UUID laneId, long epoch, String base, String tree) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (project + "\n" + laneId + "\n" + epoch + "\n" + base + "\n" + tree)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception failure) {
            throw new IllegalStateException("SNAPSHOT_ID_DERIVATION_FAILED", failure);
        }
    }

    private static String projectIdentity(Path controlRoot) {
        Path metadata = controlRoot.resolve(".synesis/project.json");
        try {
            String content = Files.readString(metadata);
            int marker = content.indexOf("\"projectId\"");
            if (marker >= 0) {
                int colon = content.indexOf(':', marker);
                int first = content.indexOf('"', colon + 1);
                int second = content.indexOf('"', first + 1);
                if (first >= 0 && second > first) return content.substring(first + 1, second);
            }
        } catch (IOException ignored) {
            // The canonical fallback remains deterministic for uninitialized
            // unit fixtures; production callers require project metadata.
        }
        return controlRoot.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static String materializeSnapshot(Path workdir, String parent, String snapshotId) throws IOException {
        Path index = Files.createTempFile("synesis-snapshot-", ".index");
        Files.deleteIfExists(index);
        try {
            runGitWithIndex(workdir, index, "read-tree", parent);
            // Provider/session metadata belongs to the lane runtime, never to a
            // published source snapshot.  Keep it at the parent tree value.
            stageSourceIndex(workdir, index);
            String tree = runGitWithIndexOutput(workdir, index, "write-tree");
            String commit = runGitWithIndexOutput(workdir, index, "commit-tree", tree, "-p", parent, "-m", "Synesis immutable lane snapshot");
            runGitOutput(workdir, "update-ref", "refs/synesis/snapshots/" + snapshotId, commit);
            return commit;
        } finally {
            Files.deleteIfExists(index);
        }
    }

    private static void stageSourceIndex(Path workdir, Path index) throws IOException {
        // Let Git's ignore rules omit provider/admin material first.  Reset
        // explicitly managed paths from the temporary index as well, covering
        // projects that accidentally track one of those files.
        runGitWithIndex(workdir, index, "add", "-A", "--", ".");
        for (String managed : List.of(".synesis", ".codex", ".claude", ".agents", "AGENTS.md", ".mcp.json",
                "__pycache__", ":(glob)**/__pycache__/**")) {
            try {
                runGitWithIndex(workdir, index, "reset", "--", managed);
            } catch (IOException ignored) {
                // The managed path may not exist or be tracked in this lane.
            }
        }
    }

    private static String integrity(String commit, List<String> paths) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((commit + "\n" + String.join("\n", paths)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { return commit; }
    }

    private static String runGitWithIndexOutput(Path workdir, Path index, String... args) throws IOException {
        return GitProcessRunner.runWithIndex(workdir, index, args).trim();
    }
    private static void runGitWithIndex(Path workdir, Path index, String... args) throws IOException {
        GitProcessRunner.runWithIndex(workdir, index, args);
    }
    private static String runGitOutput(Path workdir, String... args) throws IOException {
        return GitProcessRunner.run(workdir, args).trim();
    }

    private static String runGitStdout(Path workdir, String... args) throws IOException {
        return GitProcessRunner.run(workdir, args).trim();
    }
}
