package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
                if (Files.exists(worktreePath.resolve(".git/CHERRY_PICK_HEAD"))) {
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
     * Materializes an immutable conflicting snapshot into an already assigned
     * repair worktree. A cherry-pick conflict is an expected bounded repair
     * representation and is deliberately left unresolved for the repair
     * participant; unrelated Git failures remain fatal.
     *
     * @param repairWorktree assigned repair lane worktree
     * @param snapshotCommit immutable snapshot commit
     * @throws IOException when the worktree is invalid or materialization fails
     */
    public void materializeRepairRepresentation(Path repairWorktree, String snapshotCommit) throws IOException {
        Objects.requireNonNull(repairWorktree, "repairWorktree");
        Objects.requireNonNull(snapshotCommit, "snapshotCommit");
        if (!Files.isDirectory(repairWorktree) || !Files.exists(repairWorktree.resolve(".git"))) {
            throw new IOException("REPAIR_WORKTREE_INVALID");
        }
        try {
            runGit(repairWorktree, "cherry-pick", "--allow-empty", "--keep-redundant-commits", snapshotCommit);
        } catch (IOException conflict) {
            if (!Files.exists(repairWorktree.resolve(".git/CHERRY_PICK_HEAD"))) {
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
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", ref);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes()).trim();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("git rev-parse failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git rev-parse interrupted", e);
        }
        return output;
    }

    private static String gitDiff(Path workdir, String from, String to) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", from, to);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        String diff = new String(proc.getInputStream().readAllBytes());
        try {
            proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git diff interrupted", e);
        }
        return diff;
    }

    private static void applyPatch(Path worktreeDir, Path patchFile) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("git", "apply", "--3way", patchFile.toAbsolutePath().toString());
        pb.directory(worktreeDir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes()).trim();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("git apply failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git apply interrupted", e);
        }
    }

    private static void runGit(Path workdir, String... args) throws IOException {
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
                throw new IOException("git " + args[0] + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git " + args[0] + " interrupted", e);
        }
    }

    private static Path resolveGitTopLevel(Path path) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel");
        pb.directory(path.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes()).trim();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                return path;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return path;
        }
        return Path.of(output).toAbsolutePath().normalize();
    }
}
