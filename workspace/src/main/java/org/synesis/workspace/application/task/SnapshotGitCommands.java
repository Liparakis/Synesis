package org.synesis.workspace.application.task;

import java.io.IOException;
import java.nio.file.Path;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * The Git invocations shared by every stage of task-snapshot creation, publication checks and ref
 * handling. They are kept in one place so each stage runs Git through the same
 * {@link GitProcessRunner} policy and output trimming, and so the stages can be read without the
 * process plumbing in between.
 */
final class SnapshotGitCommands {

    private SnapshotGitCommands() {
    }

    static String gitRevParse(Path workdir) throws IOException {
        return runGitOutput(workdir, "rev-parse", "HEAD");
    }

    static String runGitWithIndexOutput(Path workdir, Path index, String... args) throws IOException {
        return GitProcessRunner.runWithIndex(workdir, index, args).trim();
    }

    static void runGitWithIndex(Path workdir, Path index, String... args) throws IOException {
        GitProcessRunner.runWithIndex(workdir, index, args);
    }

    static String runGitOutput(Path workdir, String... args) throws IOException {
        return GitProcessRunner.run(workdir, args).trim();
    }

    /** Implements {@link TaskSnapshotService#treeHash(Path, String)}. */
    static String treeHash(Path worktreePath, String commit) throws IOException {
        return runGitOutput(worktreePath, "rev-parse", commit + "^{tree}");
    }
}
