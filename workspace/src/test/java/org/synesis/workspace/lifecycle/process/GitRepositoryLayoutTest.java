package org.synesis.workspace.lifecycle.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.synesis.workspace.test.TestGit.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the on-disk reading of a checkout's Git common directory to what Git itself answers, and
 * to answering nothing where the layout is not a plain one.
 */
class GitRepositoryLayoutTest {

    private static Path repository(Path root) throws Exception {
        Files.createDirectories(root);
        run(root, "init");
        run(root, "config", "user.name", "Test User");
        run(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        run(root, "add", "README.md");
        run(root, "commit", "-m", "baseline");
        return root;
    }

    private static Path askedOfGit(Path root) throws Exception {
        return Path.of(GitProcessRunner.run(root, "rev-parse", "--path-format=absolute", "--git-common-dir").trim()).toRealPath();
    }

    private static Path readFromDisk(Path root) throws Exception {
        return GitRepositoryLayout.commonDirectory(root).orElseThrow().toRealPath();
    }

    @Test
    void aMainCheckoutAndItsLinkedWorktreeAnswerWhatGitAnswers(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        Path linked = temp.resolve("linked");
        run(main, "worktree", "add", "-b", "feature", linked.toString());

        assertEquals(askedOfGit(main), readFromDisk(main));
        assertEquals(askedOfGit(linked), readFromDisk(linked));
        assertEquals(readFromDisk(main), readFromDisk(linked), "a linked worktree shares the main checkout's common directory");
    }

    @Test
    void aGitDirectoryKeptOutsideTheCheckoutAnswersWhatGitAnswers(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("repo");
        Files.createDirectories(root);
        run(root, "init", "--separate-git-dir=" + temp.resolve("elsewhere.git"));
        assertTrue(Files.isRegularFile(root.resolve(".git")), "the checkout names its Git directory in a file");

        assertEquals(askedOfGit(root), readFromDisk(root));
    }

    @Test
    void aDirectoryInsideACheckoutIsLeftToGit(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        Path inside = Files.createDirectories(main.resolve("src").resolve("deep"));

        // Git finds the repository above; this reading answers for the top of a checkout only.
        assertEquals(Optional.empty(), GitRepositoryLayout.commonDirectory(inside));
        assertEquals(askedOfGit(main), askedOfGit(inside));
    }

    @Test
    void whatIsNotAPlainLayoutIsLeftToGit(@TempDir Path temp) throws Exception {
        Path empty = Files.createDirectories(temp.resolve("empty"));
        assertEquals(Optional.empty(), GitRepositoryLayout.commonDirectory(empty), "no .git at all");

        Path garbage = Files.createDirectories(temp.resolve("garbage"));
        Files.writeString(garbage.resolve(".git"), "not a pointer\n");
        assertEquals(Optional.empty(), GitRepositoryLayout.commonDirectory(garbage), "a .git file that names nothing");

        Path dangling = Files.createDirectories(temp.resolve("dangling"));
        Files.writeString(dangling.resolve(".git"), "gitdir: " + temp.resolve("missing") + "\n");
        assertEquals(Optional.empty(), GitRepositoryLayout.commonDirectory(dangling), "a .git file that names a directory that is not there");

        Path oversized = Files.createDirectories(temp.resolve("oversized"));
        Files.writeString(oversized.resolve(".git"), "gitdir: " + "x".repeat(5000));
        assertEquals(Optional.empty(), GitRepositoryLayout.commonDirectory(oversized), "a .git file too large to be a pointer");

        Path hollow = Files.createDirectories(temp.resolve("hollow"));
        Files.createDirectories(hollow.resolve(".git"));
        assertEquals(Optional.empty(), GitRepositoryLayout.commonDirectory(hollow), "a .git directory that holds no repository");
    }

    @Test
    void onlyADirectoryWithNoGitInItOrAboveItIsKnownToBeInNoRepository(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        Path inside = Files.createDirectories(main.resolve("src").resolve("deep"));
        Path outside = Files.createDirectories(temp.resolve("plain").resolve("deep"));

        assertTrue(GitRepositoryLayout.mayBeInsideARepository(main));
        assertTrue(GitRepositoryLayout.mayBeInsideARepository(inside), "Git finds the repository above");
        // The answer for the plain directory depends on what lies above the test's temporary
        // directory, so it is held to Git's own: where Git finds a repository, this must not say no.
        boolean gitFindsOne = GitProcessRunner.runResult(outside, "rev-parse", "--git-dir").exitCode() == 0;
        assertTrue(!gitFindsOne || GitRepositoryLayout.mayBeInsideARepository(outside));
    }

    @Test
    void aWorktreeWhoseSharedDirectoryIsGoneIsLeftToGit(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        Path linked = temp.resolve("linked");
        run(main, "worktree", "add", "-b", "feature", linked.toString());
        Path worktreeGitDirectory = main.resolve(".git").resolve("worktrees").resolve("linked");
        Files.writeString(worktreeGitDirectory.resolve("commondir"), "../../nowhere\n");

        assertEquals(Optional.empty(), GitRepositoryLayout.commonDirectory(linked), "a pointer to a directory without objects and refs is not answered");
    }
}
