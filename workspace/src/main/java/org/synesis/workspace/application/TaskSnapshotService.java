package org.synesis.workspace.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;

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
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(supervisorId, "supervisorId");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(providerSessionId, "providerSessionId");
        Objects.requireNonNull(workerWorktreePath, "workerWorktreePath");
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(activeCapabilities, "activeCapabilities");

        // Inspect current worker worktree HEAD commit
        String commitSha = gitRevParse(workerWorktreePath, "HEAD");

        // Idempotency: if an existing snapshot has identical commitSha, return it
        if (existingOpt.isPresent() && existingOpt.get().commitSha().equals(commitSha)) {
            return existingOpt.get();
        }

        // If existing snapshot exists but commitSha changed after completion, reject mutation
        if (existingOpt.isPresent()) {
            throw new IllegalStateException("Task snapshot is immutable and cannot be mutated after creation");
        }

        String baseCommit = deriveBaseCommit(workerWorktreePath);
        List<String> changedPaths = deriveChangedPaths(workerWorktreePath, baseCommit);
        List<String> capabilityDependencies = new ArrayList<>();
        for (CapabilityRequestRecord cap : activeCapabilities) {
            capabilityDependencies.add(cap.handle().value());
        }

        String snapshotToken = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String snapshotId = "snap_" + snapshotToken;

        return new TaskSnapshotRecord(
                taskId, snapshotId, nodeId, supervisorId, workerId,
                providerSessionId, baseCommit, commitSha, changedPaths,
                List.copyOf(capabilityDependencies), summary, System.currentTimeMillis());
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

    private static List<String> deriveChangedPaths(Path workdir, String baseCommit) {
        if (baseCommit.isBlank()) {
            return List.of();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--name-only", baseCommit, "HEAD");
            pb.directory(workdir.toFile());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (output.isBlank()) {
                return List.of();
            }
            List<String> paths = new ArrayList<>();
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) {
                    paths.add(trimmed);
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
}
