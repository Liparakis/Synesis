package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.synesis.workspace.test.TestGit.run;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Holds the one-process reading of a checkout's branch and commit to what the two separate Git
 * questions answer, and the ancestor check to Git's own.
 */
class ProviderWorktreeGitFactsTest {

    private static Path repository(Path root) throws Exception {
        Files.createDirectories(root);
        run(root, "init");
        run(root, "config", "user.name", "Test User");
        run(root, "config", "user.email", "test@example.com");
        commit(root, "README.md", "baseline\n");
        return root;
    }

    private static String commit(Path root, String file, String content) throws Exception {
        Files.writeString(root.resolve(file), content);
        run(root, "add", file);
        run(root, "commit", "-m", "change " + file);
        return GitProcessRunner.run(root, "rev-parse", "HEAD").trim();
    }

    private static void assertSameAsTheTwoQuestions(Path checkout) throws Exception {
        ProviderWorktreeGitFacts.Head head = ProviderWorktreeGitFacts.head(checkout);
        assertEquals(GitProcessRunner.run(checkout, "symbolic-ref", "--short", "HEAD").trim(), head.branch());
        assertEquals(GitProcessRunner.run(checkout, "rev-parse", "HEAD").trim(), head.commit());
    }

    @Test
    void oneQuestionAnswersWhatTheBranchAndTheCommitQuestionsAnswer(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        Path linked = temp.resolve("linked");
        run(main, "worktree", "add", "-b", "synesis/session-1", linked.toString());

        assertSameAsTheTwoQuestions(main);
        assertSameAsTheTwoQuestions(linked);
        assertEquals("synesis/session-1", ProviderWorktreeGitFacts.head(linked).branch(), "a branch name keeps its slashes");

        String advanced = commit(linked, "work.txt", "work\n");
        assertSameAsTheTwoQuestions(linked);
        assertEquals(advanced, ProviderWorktreeGitFacts.head(linked).commit());
    }

    @Test
    void aCheckoutOnNoBranchOrWithNoCommitIsNotAnswered(@TempDir Path temp) throws Exception {
        Path detached = repository(temp.resolve("detached"));
        run(detached, "checkout", "--detach");
        assertThrows(Exception.class, () -> ProviderWorktreeGitFacts.head(detached), "a detached HEAD is on no branch");

        Path unborn = Files.createDirectories(temp.resolve("unborn"));
        run(unborn, "init");
        assertThrows(Exception.class, () -> ProviderWorktreeGitFacts.head(unborn), "a branch without a commit has no commit to report");
    }

    @Test
    void theAncestorCheckAgreesWithGitWhetherOrNotGitIsAsked(@TempDir Path temp) throws Exception {
        Path main = repository(temp.resolve("repo"));
        String base = GitProcessRunner.run(main, "rev-parse", "HEAD").trim();
        Path linked = temp.resolve("linked");
        run(main, "worktree", "add", "-b", "feature", linked.toString());

        assertTrue(ProviderWorktreeGitFacts.isBaseAncestor(linked, base, base), "a commit is its own ancestor");
        assertEquals(GitProcessRunner.isAncestor(linked, base, "HEAD"), ProviderWorktreeGitFacts.isBaseAncestor(linked, base, base));

        String advanced = commit(linked, "work.txt", "work\n");
        assertTrue(ProviderWorktreeGitFacts.isBaseAncestor(linked, base, advanced), "the base stays an ancestor after the lane commits");
        assertFalse(ProviderWorktreeGitFacts.isBaseAncestor(main, advanced, base), "a commit the checkout has not reached is not its ancestor");
        assertFalse(ProviderWorktreeGitFacts.isBaseAncestor(linked, "not-a-commit", advanced));
        assertFalse(ProviderWorktreeGitFacts.isBaseAncestor(linked, "0".repeat(40), advanced));
    }
}
