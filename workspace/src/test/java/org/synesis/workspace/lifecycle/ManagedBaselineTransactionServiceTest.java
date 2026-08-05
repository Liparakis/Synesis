package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies journaled managed-baseline preparation and provenance fencing. */
class ManagedBaselineTransactionServiceTest {

    @Test
    void createsAndReplaysACompleteBaselineWithoutDirtyControlState(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        ManagedBaselineTransactionService service = service(temp);

        ManagedBaselineTransactionService.Result result = service.prepare(root, files("AGENTS.md", "managed\n"));

        assertEquals(ManagedBaselineTransactionService.Phase.COMPLETE, result.phase());
        assertEquals("managed\n", Files.readString(root.resolve("AGENTS.md")));
        assertTrue(git(root, "status", "--porcelain").isBlank());
        assertNotEquals(result.originalHead(), result.commit());
        ManagedBaselineTransactionService.Journal journal = service.journal(root, result.transactionId());
        assertEquals(result.commit(), journal.commit());
        assertEquals("ABSENT", journal.managedPathStates().get("AGENTS.md"));
        assertEquals(result.phase(), service.recover(root, result.transactionId()).phase());
    }

    @Test
    void rejectsPreExistingUntrackedManagedContent(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve("AGENTS.md"), "user-owned\n");

        ManagedBaselineTransactionService.BaselineFailure failure = assertThrows(
                ManagedBaselineTransactionService.BaselineFailure.class,
                () -> service(temp).prepare(root, files("AGENTS.md", "managed\n")));

        assertEquals("CONTROL_CHECKOUT_DIRTY", failure.code());
        assertEquals("user-owned\n", Files.readString(root.resolve("AGENTS.md")));
        assertTrue(git(root, "status", "--porcelain").contains("AGENTS.md"));
    }

    @Test
    void rejectsPreExistingIgnoredManagedContentWithoutJournalOwnership(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve(".gitignore"), ".synesis/\n");
        git(root, "add", ".gitignore");
        git(root, "commit", "-m", "ignore synesis state");
        Files.createDirectories(root.resolve(".synesis"));
        Files.writeString(root.resolve(".synesis/project.json"), "user-owned\n");

        ManagedBaselineTransactionService.BaselineFailure failure = assertThrows(
                ManagedBaselineTransactionService.BaselineFailure.class,
                () -> service(temp).prepare(root, files(".synesis/project.json", "managed\n")));

        assertEquals("CONTROL_CHECKOUT_DIRTY", failure.code());
        assertEquals("user-owned\n", Files.readString(root.resolve(".synesis/project.json")));
    }

    @Test
    void allowsATrackedCleanManagedFileToBeUpdated(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        Files.writeString(root.resolve("AGENTS.md"), "old managed\n");
        git(root, "add", "AGENTS.md");
        git(root, "commit", "-m", "tracked managed file");

        ManagedBaselineTransactionService.Result result = service(temp).prepare(root,
                files("AGENTS.md", "new managed\n"));

        assertEquals(ManagedBaselineTransactionService.Phase.COMPLETE, result.phase());
        assertEquals("new managed\n", Files.readString(root.resolve("AGENTS.md")));
        assertTrue(git(root, "status", "--porcelain").isBlank());
    }

    @Test
    void crashBeforeRefAdvanceRollsBackTransactionOwnedFiles(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        ManagedBaselineTransactionService service = crashingService(temp,
                ManagedBaselineTransactionService.Phase.FILES_WRITTEN);

        assertThrows(ManagedBaselineTransactionService.BaselineFailure.class,
                () -> service.prepare(root, files("AGENTS.md", "managed\n")));
        ManagedBaselineTransactionService.Journal journal = latestJournal(service, root);
        assertEquals(ManagedBaselineTransactionService.Phase.FILES_WRITTEN, journal.phase());

        ManagedBaselineTransactionService.Result recovered = service.recover(root, journal.transactionId());
        assertEquals(ManagedBaselineTransactionService.Phase.ROLLED_BACK, recovered.phase());
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
        assertTrue(git(root, "status", "--porcelain").isBlank());
    }

    @Test
    void crashAfterRefAdvanceRecoversWithoutDuplicateCommit(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        ManagedBaselineTransactionService service = crashingService(temp,
                ManagedBaselineTransactionService.Phase.REF_ADVANCED);

        assertThrows(ManagedBaselineTransactionService.BaselineFailure.class,
                () -> service.prepare(root, files("AGENTS.md", "managed\n")));
        ManagedBaselineTransactionService.Journal journal = latestJournal(service, root);
        String headBeforeRecovery = git(root, "rev-parse", "HEAD");
        ManagedBaselineTransactionService.Result recovered = service.recover(root, journal.transactionId());

        assertEquals(ManagedBaselineTransactionService.Phase.COMPLETE, recovered.phase());
        assertEquals(headBeforeRecovery, git(root, "rev-parse", "HEAD"));
        assertTrue(git(root, "status", "--porcelain").isBlank());
    }

    @Test
    void crashAfterIndexSynchronizationFinalizesFromThePreparedCommit(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        ManagedBaselineTransactionService service = crashingService(temp,
                ManagedBaselineTransactionService.Phase.CONTROL_INDEX_SYNCHRONIZED);

        assertThrows(ManagedBaselineTransactionService.BaselineFailure.class,
                () -> service.prepare(root, files("AGENTS.md", "managed\n")));
        ManagedBaselineTransactionService.Journal journal = latestJournal(service, root);
        assertEquals(ManagedBaselineTransactionService.Phase.CONTROL_INDEX_SYNCHRONIZED, journal.phase());
        assertEquals(ManagedBaselineTransactionService.Phase.COMPLETE,
                service.recover(root, journal.transactionId()).phase());
        assertTrue(git(root, "status", "--porcelain").isBlank());
    }

    @Test
    void activeJournalReservesItsManagedPathsAfterProcessLoss(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        ManagedBaselineTransactionService crashed = crashingService(temp,
                ManagedBaselineTransactionService.Phase.PREPARED);
        assertThrows(ManagedBaselineTransactionService.BaselineFailure.class,
                () -> crashed.prepare(root, files("AGENTS.md", "first\n")));

        ManagedBaselineTransactionService.BaselineFailure failure = assertThrows(
                ManagedBaselineTransactionService.BaselineFailure.class,
                () -> service(temp).prepare(root, files("AGENTS.md", "second\n")));
        assertEquals("BASELINE_TRANSACTION_CONFLICT", failure.code());
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
    }

    @Test
    void completedManagedContentMutationIsDetectedByThePolicy(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        ManagedBaselineTransactionService service = service(temp);
        ManagedBaselineTransactionService.Result result = service.prepare(root, files("AGENTS.md", "managed\n"));
        Files.writeString(root.resolve("AGENTS.md"), "replacement\n");

        assertTrue(new ManagedPathPolicy().inspect(root).blocked());
        assertEquals(ManagedBaselineTransactionService.Phase.COMPLETE,
                service.recover(root, result.transactionId()).phase());
    }

    private static ManagedBaselineTransactionService service(Path temp) {
        return new ManagedBaselineTransactionService(
                new AdministrativeStateLocator(temp.resolve("admin-state")), new ManagedPathPolicy());
    }

    private static ManagedBaselineTransactionService crashingService(Path temp,
            ManagedBaselineTransactionService.Phase crashAfter) {
        return new ManagedBaselineTransactionService(new AdministrativeStateLocator(temp.resolve("admin-state")),
                new ManagedPathPolicy(), phase -> {
                    if (phase == crashAfter) {
                        throw new IOException("injected process loss");
                    }
                });
    }

    private static ManagedBaselineTransactionService.Journal latestJournal(
            ManagedBaselineTransactionService service, Path root) throws Exception {
        AdministrativeStateLocator.Resolution resolution = new AdministrativeStateLocator(
                root.getParent().resolve("admin-state")).resolve(root);
        Path journalFile;
        try (var files = Files.list(resolution.baselineRoot())) {
            journalFile = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .findFirst().orElseThrow();
        }
        String fileName = journalFile.getFileName().toString();
        return service.journal(root, fileName.substring(0, fileName.length() - ".json".length()));
    }

    private static Map<String, byte[]> files(String path, String content) {
        return Map.of(path, content.getBytes(StandardCharsets.UTF_8));
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

    private static String git(Path root, String... args) throws Exception {
        return GitProcessRunner.run(root, args).trim();
    }
}
