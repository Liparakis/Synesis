package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies semantic staged-state comparisons.
 */
class SemanticIndexFingerprintTest {

    private static Path init(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init");
        git(root, "config", "user.name", "Test User");
        git(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("tracked.txt"), "baseline\n");
        git(root, "add", "tracked.txt");
        git(root, "commit", "-m", "baseline");
        return root;
    }

    private static void git(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }

    @Test
    void unchangedIndexIsExact(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        SemanticIndexFingerprint.Fingerprint first = SemanticIndexFingerprint.capture(root);
        SemanticIndexFingerprint.Fingerprint second = SemanticIndexFingerprint.capture(root);
        assertEquals(SemanticIndexFingerprint.Comparison.EXACT,
                SemanticIndexFingerprint.compare(first, second));
    }

    @Test
    void stagedBlobChangeIsSemantic(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        SemanticIndexFingerprint.Fingerprint before = SemanticIndexFingerprint.capture(root);
        Files.writeString(root.resolve("tracked.txt"), "changed\n");
        git(root, "add", "tracked.txt");
        SemanticIndexFingerprint.Fingerprint after = SemanticIndexFingerprint.capture(root);
        assertEquals(SemanticIndexFingerprint.Comparison.SEMANTIC_STATE_CHANGED,
                SemanticIndexFingerprint.compare(before, after));
    }

    @Test
    void intentToAddIsRecorded(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve("new.txt"), "new\n");
        git(root, "add", "-N", "new.txt");
        SemanticIndexFingerprint.Fingerprint fingerprint = SemanticIndexFingerprint.capture(root);
        assertTrue(fingerprint.intentToAddFlags()
                .contains("new.txt"));
    }

    @Test
    void unsupportedSplitIndexIsExplicit(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        SemanticIndexFingerprint.Fingerprint before = SemanticIndexFingerprint.capture(root);
        git(root, "config", "core.splitIndex", "true");
        SemanticIndexFingerprint.Fingerprint after = SemanticIndexFingerprint.capture(root);
        assertEquals(SemanticIndexFingerprint.Comparison.INDEX_EXTENSION_UNSUPPORTED,
                SemanticIndexFingerprint.compare(before, after));
    }

    @Test
    void cacheRefreshWithUnchangedStagedSemanticsIsNotAUserMutation(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        SemanticIndexFingerprint.Fingerprint before = SemanticIndexFingerprint.capture(root);
        Files.setLastModifiedTime(root.resolve("tracked.txt"),
                FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        git(root, "update-index", "--refresh");
        SemanticIndexFingerprint.Fingerprint after = SemanticIndexFingerprint.capture(root);

        assertTrue(SemanticIndexFingerprint.compare(before, after)
                == SemanticIndexFingerprint.Comparison.EXACT
                || SemanticIndexFingerprint.compare(before, after)
                == SemanticIndexFingerprint.Comparison.NONSEMANTIC_REFRESH);
    }

    @Test
    void largeIndexInspectionIsNotTruncated(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        int fileCount = 3_000;
        Path source = root.resolve("src");
        Files.createDirectories(source);
        for (int index = 0; index < fileCount; index++) {
            Files.writeString(source.resolve("fixture-" + String.format("%04d", index) + ".txt"), "fixture\n");
        }
        git(root, "add", "--", "src");

        SemanticIndexFingerprint.Fingerprint fingerprint = SemanticIndexFingerprint.capture(root);

        assertEquals(fileCount + 1,
                fingerprint.stagedEntryPaths()
                        .size());
        assertTrue(fingerprint.stagedEntryPaths()
                .contains("src/fixture-2999.txt"));
    }
}
