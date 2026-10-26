package org.synesis.workspace.lifecycle.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.synesis.workspace.test.TestGit;

/**
 * ADR-0104 adversarial item 10: a lane's {@code .git} file is rewritten to point at a directory holding a hostile
 * {@code config}; Synesis's own Git on that lane must ignore the file and run no program from it.
 */
class GitWorktreeBindingTest {

    private static void rawGit(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), command + " -> " + output);
    }

    @Test
    void aRedirectedGitFileDoesNotMakeSynesisRunAProgramFromItsConfig() throws Exception {
        assumeFalse(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"), "the hostile filter is a POSIX shell command");
        Path base = Files.createTempDirectory("synesis-binding-").toRealPath();
        Path control = base.resolve("control");
        Files.createDirectories(control);
        TestGit.run(control, "init", "-q", "-b", "main");
        Files.writeString(control.resolve("a.txt"), "one\n");
        TestGit.run(control, "add", "a.txt");
        TestGit.run(control, "commit", "-q", "-m", "first");
        Path lane = base.resolve("lane");
        TestGit.run(control, "worktree", "add", "-q", "--detach", lane.toString());
        String laneHead = TestGit.output(lane, "rev-parse", "HEAD");
        GitProcessRunner.trustControlRoot(control);

        // The attack: a repository in the lane whose config names a clean filter that runs a program, a .gitattributes
        // that applies it, and a .git file that points at it.
        Path marker = base.resolve("pwned");
        Path hostile = base.resolve("hostile");
        Files.createDirectories(hostile);
        rawGit(hostile, "init", "-q");
        rawGit(hostile, "config", "filter.x.clean", "touch " + marker + "; cat");
        Files.writeString(lane.resolve(".gitattributes"), "* filter=x\n");
        Files.writeString(lane.resolve("a.txt"), "two\n");
        Files.writeString(lane.resolve(".git"), "gitdir: " + hostile.resolve(".git") + "\n");

        // Control experiment: plain Git believes the file and runs the program.
        rawGit(lane, "add", "a.txt");
        assertTrue(Files.exists(marker), "plain Git must run the hostile filter, or this test proves nothing");
        Files.delete(marker);

        // Synesis's Git names the Git directory from the control side and ignores the file.
        TestGit.run(lane, "add", "a.txt");
        assertFalse(Files.exists(marker), "Synesis ran a program from the redirected configuration");
        assertEquals(laneHead, TestGit.output(lane, "rev-parse", "HEAD"));
        assertTrue(TestGit.output(lane, "status", "--porcelain").contains("a.txt"));
    }

    @Test
    void administrativeDirectoryComesFromTheControlSide() throws Exception {
        Path base = Files.createTempDirectory("synesis-binding-admin-").toRealPath();
        Path control = base.resolve("control");
        Files.createDirectories(control);
        TestGit.run(control, "init", "-q", "-b", "main");
        Files.writeString(control.resolve("a.txt"), "one\n");
        TestGit.run(control, "add", "a.txt");
        TestGit.run(control, "commit", "-q", "-m", "first");
        Path lane = base.resolve("lane");
        TestGit.run(control, "worktree", "add", "-q", "--detach", lane.toString());
        Path stranger = Files.createDirectories(base.resolve("stranger"));

        assertEquals(control.resolve(".git/worktrees/lane"), GitWorktreeBinding.administrativeDirectory(control.resolve(".git"), lane).orElseThrow());
        assertTrue(GitWorktreeBinding.administrativeDirectory(control.resolve(".git"), stranger).isEmpty());
    }

    @Test
    void aControlCheckoutUnderLaneStorageIsNeverTrusted() throws Exception {
        Path base = Files.createTempDirectory("synesis-binding-lanes-").toRealPath();
        Path imitation = Files.createDirectories(base.resolve("Synesis").resolve("workspaces").resolve("p").resolve("worktrees").resolve("lane"));
        TestGit.run(imitation, "init", "-q", "-b", "main");
        Path claimed = base.resolve("claimed");
        Files.createDirectories(claimed);
        Files.createDirectories(imitation.resolve(".git/worktrees/x"));
        Files.writeString(imitation.resolve(".git/worktrees/x/gitdir"), claimed.resolve(".git") + "\n");
        Files.writeString(claimed.resolve(".git"), "gitdir: " + imitation.resolve(".git/worktrees/x") + "\n");

        GitProcessRunner.trustControlRoot(imitation);

        assertTrue(GitWorktreeBinding.of(claimed) == null);
    }
}
