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
     * @param taskId task ID @param nodeId node ID @param supervisorId supervisor ID @param workerId worker ID
     * @param providerSessionId binding ID @param workerWorktreePath lane worktree @param controlRoot project root
     * @param summary summary @param existingOpt existing snapshot @param activeCapabilities capability dependencies
     * @param claims current exact-path/subtree claims @return immutable snapshot @throws IOException Git failure */
    public TaskSnapshotRecord createSnapshot(
            UUID taskId, String nodeId, String supervisorId, String workerId, String providerSessionId,
            Path workerWorktreePath, Path controlRoot, String summary,
            Optional<TaskSnapshotRecord> existingOpt, List<CapabilityRequestRecord> activeCapabilities,
            List<ResourceSelector> claims
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
        boolean dirty = !runGitOutput(workerWorktreePath, "status", "--porcelain").isBlank();
        String headCommit = gitRevParse(workerWorktreePath, "HEAD");
        String snapshotToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String snapshotId = "snap_" + snapshotToken;
        String baseCommit = dirty ? headCommit : deriveBaseCommit(workerWorktreePath);
        String commitSha = dirty ? materializeSnapshot(workerWorktreePath, headCommit, snapshotId) : headCommit;
        if (!dirty) {
            runGitOutput(workerWorktreePath, "update-ref", "refs/synesis/snapshots/" + snapshotId, commitSha);
        }

        // Idempotency: if an existing snapshot has identical commitSha, return it
        if (existingOpt.isPresent() && existingOpt.get().commitSha().equals(commitSha)) {
            return existingOpt.get();
        }

        // If existing snapshot exists but commitSha changed after completion, reject mutation
        if (existingOpt.isPresent()) {
            throw new IllegalStateException("Task snapshot is immutable and cannot be mutated after creation");
        }

        List<String> changedPaths = deriveChangedPaths(workerWorktreePath, baseCommit, dirty);
        List<String> capabilityDependencies = new ArrayList<>();
        for (CapabilityRequestRecord cap : activeCapabilities) {
            capabilityDependencies.add(cap.handle().value());
        }

        SnapshotProvenance provenance = new SnapshotProvenance(taskId, taskId, nodeId,
                providerSessionId, 1, capabilityDependencies, List.of(),
                claims.stream().map(selector -> selector.kind().name() + ":" + selector.value()).toList(),
                "refs/synesis/snapshots/" + snapshotId, integrity(commitSha, changedPaths));
        return new TaskSnapshotRecord(
                taskId, snapshotId, nodeId, supervisorId, workerId,
                providerSessionId, baseCommit, commitSha, changedPaths,
                List.copyOf(capabilityDependencies), summary, System.currentTimeMillis(), provenance);
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
            return List.of();
        }
    }

    private static String materializeSnapshot(Path workdir, String parent, String snapshotId) throws IOException {
        Path index = Files.createTempFile("synesis-snapshot-", ".index");
        Files.deleteIfExists(index);
        try {
            runGitWithIndex(workdir, index, "read-tree", parent);
            runGitWithIndex(workdir, index, "add", "-A");
            String tree = runGitWithIndexOutput(workdir, index, "write-tree");
            String commit = runGitWithIndexOutput(workdir, index, "commit-tree", tree, "-p", parent, "-m", "Synesis immutable lane snapshot");
            runGitOutput(workdir, "update-ref", "refs/synesis/snapshots/" + snapshotId, commit);
            return commit;
        } finally {
            Files.deleteIfExists(index);
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
