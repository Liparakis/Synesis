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

/**
 * Service for creating and verifying immutable task snapshots from worker worktrees.
 *
 * <p>A completed task is represented by an immutable snapshot record referencing the
 * worker's verified Git commit SHA, base commit, changed-path manifest, and capability dependencies.
 *
 * @since 1.0
 */
public final class TaskSnapshotService {

    /**
     * Creates a task snapshot service.
     */
    public TaskSnapshotService() {
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
        List<String> changedPaths = deriveChangedPaths(workerWorktreePath, baseCommit, dirty);
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
        List<String> capabilityDependencies = new ArrayList<>();
        for (CapabilityRequestRecord cap : activeCapabilities) {
            capabilityDependencies.add(cap.handle().value());
        }

        SnapshotProvenance provenance = new SnapshotProvenance(workGroupId, laneId, participant,
                bindingIdentity, claimEpoch, capabilityDependencies, handoffLineage,
                claims.stream().map(selector -> selector.kind().name() + ":" + selector.value()).toList(),
                "refs/synesis/snapshots/" + snapshotId, integrity(commitSha, changedPaths));
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
                    if (!isSnapshotManagedPath(trimmed)) {
                        continue;
                    }
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

    private static boolean isSnapshotManagedPath(String path) {
        return !(path.equals(".synesis") || path.startsWith(".synesis/")
                || path.equals(".codex") || path.startsWith(".codex/")
                || path.equals(".claude") || path.startsWith(".claude/")
                || path.equals(".agents") || path.startsWith(".agents/")
                || path.equals("AGENTS.md") || path.equals(".mcp.json"));
    }

    private static void stageSourceIndex(Path workdir, Path index) throws IOException {
        // Let Git's ignore rules omit provider/admin material first.  Reset
        // explicitly managed paths from the temporary index as well, covering
        // projects that accidentally track one of those files.
        runGitWithIndex(workdir, index, "add", "-A", "--", ".");
        for (String managed : List.of(".synesis", ".codex", ".claude", ".agents", "AGENTS.md", ".mcp.json")) {
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
        return runGitProcess(workdir, index, null, args);
    }
    private static void runGitWithIndex(Path workdir, Path index, String... args) throws IOException {
        runGitProcess(workdir, index, null, args);
    }
    private static void runGitInput(Path workdir, Path index, byte[] input, String... args) throws IOException {
        runGitProcess(workdir, index, input, args);
    }
    private static String runGitProcess(Path workdir, Path index, byte[] input, String... args) throws IOException {
        List<String> command = new ArrayList<>(); command.add("git"); command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command); pb.directory(workdir.toFile());
        pb.environment().put("GIT_INDEX_FILE", index.toString()); pb.environment().put("GIT_AUTHOR_NAME", "Synesis");
        pb.environment().put("GIT_AUTHOR_EMAIL", "synesis@localhost"); pb.environment().put("GIT_COMMITTER_NAME", "Synesis");
        pb.environment().put("GIT_COMMITTER_EMAIL", "synesis@localhost"); pb.redirectErrorStream(true);
        Process process = pb.start(); if (input != null) { process.getOutputStream().write(input); process.getOutputStream().close(); }
        else process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try { if (process.waitFor() != 0) throw new IOException("git snapshot command failed: " + output); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("git snapshot interrupted", interrupted); }
        return output;
    }

    private static String runGitOutput(Path workdir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String arg : args) {
            cmd.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes()).trim();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("git " + args[0] + " failed (code=" + code + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git " + args[0] + " interrupted", e);
        }
        return output;
    }

    private static String runGitStdout(Path workdir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(); cmd.add("git"); cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd); pb.directory(workdir.toFile());
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String error = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try { if (proc.waitFor() != 0) throw new IOException("git " + args[0] + " failed: " + error); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("git interrupted", interrupted); }
        return output;
    }
}
