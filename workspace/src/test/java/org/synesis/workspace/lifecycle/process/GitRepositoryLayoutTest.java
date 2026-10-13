package org.synesis.workspace.lifecycle.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.synesis.workspace.test.TestGit.run;

import java.io.IOException;
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

    private static Path symbolicLink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException cannotLink) {
            assumeTrue(false, "this machine cannot create symbolic links: " + cannotLink);
            return link;
        }
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
    void aCheckoutAndTheDirectoriesBelowItMayBeInsideARepository(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        Path inside = Files.createDirectories(main.resolve("src").resolve("deep"));

        assertTrue(GitRepositoryLayout.mayBeInsideARepository(main));
        assertTrue(GitRepositoryLayout.mayBeInsideARepository(inside), "Git finds the repository above");
    }

    @Test
    void aDirectoryWithNoGitInItOrAboveItIsKnownToBeInNoRepository(@TempDir Path temp) throws Exception {
        assumeTrue(System.getenv("GIT_DIR") == null && System.getenv("GIT_COMMON_DIR") == null, "the environment redirects Git");
        // Only where the temporary directory lies outside every repository can "no" be known here.
        for (Path above = temp.toAbsolutePath().normalize(); above != null; above = above.getParent()) {
            assumeTrue(!Files.exists(above.resolve(".git")) && !Files.exists(above.resolve("HEAD")), "a repository lies above the temporary directory: " + above);
        }
        Path outside = Files.createDirectories(temp.resolve("plain").resolve("deep"));

        assertFalse(GitRepositoryLayout.mayBeInsideARepository(outside));
    }

    @Test
    void aBareRepositoryAndTheDirectoriesInsideItMayBeInsideARepositoryButHaveNoCheckoutCommonDirectory(@TempDir Path temp) throws Exception {
        Path bare = Files.createDirectories(temp.resolve("bare.git"));
        run(bare, "init", "--bare");

        assertTrue(GitRepositoryLayout.mayBeInsideARepository(bare), "it is a Git directory itself");
        assertTrue(GitRepositoryLayout.mayBeInsideARepository(bare.resolve("refs").resolve("heads")), "and below it Git finds it");
        assertEquals(Optional.empty(), GitRepositoryLayout.commonDirectory(bare), "it has no .git to read");
    }

    @Test
    void aLinkedWorktreeWhoseGitFileNamesItsGitDirectoryRelativelyAnswersTheMainRepositoryAsARealPath(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        Path linked = temp.resolve("linked");
        run(main, "worktree", "add", "-b", "feature", linked.toString());
        Files.writeString(linked.resolve(".git"), "gitdir: ../repo/.git/worktrees/linked\n");

        assertEquals(main.resolve(".git").toRealPath(), GitRepositoryLayout.commonDirectory(linked).orElseThrow());
        assertEquals(askedOfGit(linked), readFromDisk(linked));
    }

    @Test
    void aCheckoutReachedThroughASymbolicLinkAnswersWhatGitAnswersThere(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        Path link = symbolicLink(temp.resolve("link"), main);

        assertEquals(askedOfGit(link), readFromDisk(link));
        assertEquals(readFromDisk(main), GitRepositoryLayout.commonDirectory(link).orElseThrow(), "a real path, whichever way the checkout was reached");
    }

    @Test
    void aRelativePointerBehindASymbolicLinkIsResolvedFromTheRealDirectoryAsGitDoesNotFromTheLinksParent(@TempDir Path temp) throws Exception {
        // real/sub/wt names its Git directory as ../x/.git: real/sub/x/.git. Resolved against the
        // link's own parent it would name decoy/x/.git, another repository.
        Path real = Files.createDirectories(temp.resolve("real").resolve("sub"));
        repository(real.resolve("x"));
        Path wt = Files.createDirectories(real.resolve("wt"));
        Files.writeString(wt.resolve(".git"), "gitdir: ../x/.git\n");
        Path decoy = repository(temp.resolve("x"));
        Path w = symbolicLink(temp.resolve("w"), wt);

        Path gitsAnswer = askedOfGit(w);
        assertEquals(real.resolve("x").resolve(".git").toRealPath(), gitsAnswer);
        assertNotEquals(decoy.resolve(".git").toRealPath(), gitsAnswer);
        assertEquals(gitsAnswer, readFromDisk(w));
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
