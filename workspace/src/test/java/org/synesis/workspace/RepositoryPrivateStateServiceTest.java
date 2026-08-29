package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;

/**
 * Verifies exact common-directory Git exclusions and preservation semantics.
 */
class RepositoryPrivateStateServiceTest {

    private static void git(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }

    @Test
    void maintainsOnlyExactRootAnchoredExclusionsAndPreservesUnrelatedLines() throws Exception {
        Path root = Files.createTempDirectory("synesis-exclude-");
        git(root, "init");
        git(root, "config", "user.name", "Synesis Test");
        git(root, "config", "user.email", "synesis-test@example.invalid");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        Path exclude = root.resolve(".git/info/exclude");
        Files.writeString(exclude, "# keep this comment\n/custom\n");

        new ProjectApplicationService().init(root, false);
        RepositoryPrivateStateService.ensure(root);
        String content = Files.readString(exclude);
        assertTrue(content.contains("# keep this comment"));
        assertTrue(content.contains("/custom"));
        for (String line : RepositoryPrivateStateService.SYNESIS_EXCLUSIONS) {
            assertEquals(1,
                    content.lines()
                            .filter(candidate -> candidate.trim()
                                    .equals(line))
                            .count());
        }
        assertFalse(content.contains("/.synesis/\n"));
        assertFalse(content.contains("/.codex/\n"));
    }

    @Test
    void linkedWorktreeUsesTheCanonicalCommonExcludeFile() throws Exception {
        Path root = Files.createTempDirectory("synesis-exclude-linked-");
        git(root, "init");
        git(root, "config", "user.name", "Synesis Test");
        git(root, "config", "user.email", "synesis-test@example.invalid");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        Path linked = Files.createTempDirectory("synesis-linked-worktree-");
        Files.delete(linked);
        git(root, "worktree", "add", "-b", "linked", linked.toString());

        Path commonExclude = root.resolve(".git/info/exclude");
        Files.writeString(commonExclude, "# shared\n/custom\n");
        RepositoryPrivateStateService.ensure(linked);
        String content = Files.readString(commonExclude);
        assertTrue(content.contains("# shared"));
        assertTrue(content.contains("/custom"));
        for (String line : RepositoryPrivateStateService.SYNESIS_EXCLUSIONS) {
            assertEquals(1,
                    content.lines()
                            .filter(candidate -> candidate.trim()
                                    .equals(line))
                            .count());
        }
    }
}
