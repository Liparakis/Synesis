package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies fail-closed managed-path and ignored-output classification. */
class ManagedPathPolicyTest {

    @Test
    void ordinaryIgnoredBuildOutputDoesNotBlock(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve(".gitignore"), "node_modules/\nbuild/\n.gradle/\ntarget/\n.venv/\n");
        git(root, "add", ".gitignore");
        git(root, "commit", "-m", "ignore generated output");
        for (String directory : List.of("node_modules", "build", ".gradle", "target", ".venv")) {
            Files.createDirectories(root.resolve(directory));
            Files.writeString(root.resolve(directory).resolve("generated.txt"), "ignored\n");
        }
        assertFalse(new ManagedPathPolicy().inspect(root).blocked());
    }

    @Test
    void untrackedNonIgnoredContentBlocks(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve("notes.txt"), "user content\n");
        ManagedPathPolicy.Report report = new ManagedPathPolicy().inspect(root);
        assertTrue(report.blocked());
        assertTrue(report.findings().stream().anyMatch(f -> f.classification()
                == ManagedPathPolicy.Classification.UNTRACKED_NON_IGNORED));
    }

    @Test
    void preExistingIgnoredManagedPathBlocks(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve(".gitignore"), ".synesis/\n");
        git(root, "add", ".gitignore");
        git(root, "commit", "-m", "ignore synesis state");
        Files.createDirectories(root.resolve(".synesis"));
        Files.writeString(root.resolve(".synesis/project.json"), "canonical-looking\n");
        ManagedPathPolicy.Report report = new ManagedPathPolicy().inspect(root);
        assertTrue(report.blocked());
        assertTrue(report.findings().stream().anyMatch(f -> f.classification()
                == ManagedPathPolicy.Classification.IGNORED_MANAGED_PATH_COLLISION));
    }

    @Test
    void MatchingJournaledTransactionOwnedManagedPathIsAllowed(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve(".gitignore"), ".synesis/\n");
        git(root, "add", ".gitignore");
        git(root, "commit", "-m", "ignore synesis state");
        Files.createDirectories(root.resolve(".synesis"));
        byte[] content = "owned\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve(".synesis/project.json"), content);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        ManagedPathPolicy.TransactionOwnership ownership = new ManagedPathPolicy.TransactionOwnership(
                "repo-identity", "txn-1", List.of(".synesis/project.json"),
                Map.of(".synesis/project.json", digest));
        ManagedPathPolicy.Report report = new ManagedPathPolicy().inspect(root, Optional.of(ownership));
        assertFalse(report.blocked());
        assertTrue(report.findings().stream().anyMatch(f -> f.classification()
                == ManagedPathPolicy.Classification.TRANSACTION_OWNED_IGNORED_MANAGED_PATH));
    }

    @Test
    void ReplacedTransactionOwnedPathIsBlocked(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve(".gitignore"), ".synesis/\n");
        git(root, "add", ".gitignore");
        git(root, "commit", "-m", "ignore synesis state");
        Files.createDirectories(root.resolve(".synesis"));
        Files.writeString(root.resolve(".synesis/project.json"), "expected\n");
        ManagedPathPolicy.TransactionOwnership ownership = new ManagedPathPolicy.TransactionOwnership(
                "repo-identity", "txn-1", List.of(".synesis/project.json"),
                Map.of(".synesis/project.json", "not-the-current-digest"));
        assertTrue(new ManagedPathPolicy().inspect(root, Optional.of(ownership)).blocked());
    }

    @Test
    void TrackedFileStillBlocksEvenWhenIgnoreRuleIsAdded(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve("tracked.txt"), "changed\n");
        Files.writeString(root.resolve(".gitignore"), "tracked.txt\n");
        ManagedPathPolicy.Report report = new ManagedPathPolicy().inspect(root);
        assertTrue(report.blocked());
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
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }
}
