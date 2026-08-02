package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies semantic staged-state comparisons. */
class SemanticIndexFingerprintTest {

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
        assertTrue(fingerprint.intentToAddFlags().contains("new.txt"));
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
        Files.setLastModifiedTime(root.resolve("tracked.txt"), FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        git(root, "update-index", "--refresh");
        SemanticIndexFingerprint.Fingerprint after = SemanticIndexFingerprint.capture(root);

        assertTrue(SemanticIndexFingerprint.compare(before, after)
                == SemanticIndexFingerprint.Comparison.EXACT
                || SemanticIndexFingerprint.compare(before, after)
                == SemanticIndexFingerprint.Comparison.NONSEMANTIC_REFRESH);
    }

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
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }
}
