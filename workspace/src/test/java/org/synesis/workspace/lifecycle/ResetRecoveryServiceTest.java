package org.synesis.workspace.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies identity-reset journaling and restart recovery. */
class ResetRecoveryServiceTest {

    @Test
    void resetIsIdempotentAndNewIdentityActivatesOnlyAfterAllPhases(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        AdministrativeStateLocator locator = new AdministrativeStateLocator(temp.resolve("state"));
        seedOldNamespace(locator, root, "old-project");

        ResetRecoveryService.Result result = new ResetRecoveryService(locator)
                .reset(root, "old-project", "new-project");
        AdministrativeStateLocator.Resolution resolution = locator.resolve(root);
        Path newNamespace = resolution.resetRoot().resolve("namespaces/new-project");

        assertEquals(ResetRecoveryService.Phase.COMPLETE, result.phase());
        assertTrue(result.authoritative());
        assertFalse(Files.exists(resolution.resetRoot().resolve("namespaces/old-project")));
        assertTrue(Files.readString(newNamespace.resolve("authority.state")).startsWith("ACTIVE"));
        assertEquals(result.phase(), new ResetRecoveryService(locator)
                .recover(root, result.transactionId()).phase());
    }

    @Test
    void processLossAtEveryDurablePhaseResumesTheSingleTransaction(@TempDir Path temp) throws Exception {
        for (ResetRecoveryService.Phase crashAfter : new ResetRecoveryService.Phase[] {
                ResetRecoveryService.Phase.OLD_FENCED,
                ResetRecoveryService.Phase.NEW_ID_GENERATED,
                ResetRecoveryService.Phase.BASELINE_COMMITTED,
                ResetRecoveryService.Phase.NAMESPACE_TRANSFERRED,
                ResetRecoveryService.Phase.ACTIVATED,
                ResetRecoveryService.Phase.PROVIDERS_REFRESHED }) {
            Path root = init(Files.createTempDirectory("reset-phase-").resolve("repo"));
            Path state = Files.createTempDirectory("reset-state-");
            AdministrativeStateLocator locator = new AdministrativeStateLocator(state);
            seedOldNamespace(locator, root, "old-project");
            ResetRecoveryService crashing = new ResetRecoveryService(locator, phase -> {
                if (phase == crashAfter) {
                    throw new IOException("simulated process loss");
                }
            });

            ResetRecoveryService.ResetFailure interrupted = assertThrows(
                    ResetRecoveryService.ResetFailure.class,
                    () -> crashing.reset(root, "old-project", "new-project"));
            assertEquals("RESET_RECOVERY_FAILED", interrupted.code());
            ResetRecoveryService.Journal journal = crashing.discover(root).stream().findFirst().orElseThrow();
            assertEquals(crashAfter, journal.phase());
            ResetRecoveryService.Result recovered = new ResetRecoveryService(locator)
                    .recover(root, journal.transactionId());
            assertEquals(ResetRecoveryService.Phase.COMPLETE, recovered.phase());
            assertTrue(recovered.authoritative());
            assertEquals(ResetRecoveryService.Phase.COMPLETE,
                    new ResetRecoveryService(locator).recover(root, journal.transactionId()).phase());
        }
    }

    @Test
    void interruptedResetRemainsDiscoverableThroughTheCommonDirectoryIdentity(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        AdministrativeStateLocator locator = new AdministrativeStateLocator(temp.resolve("state"));
        seedOldNamespace(locator, root, "old-project");
        ResetRecoveryService crashing = new ResetRecoveryService(locator, phase -> {
            if (phase == ResetRecoveryService.Phase.NEW_ID_GENERATED) {
                throw new IOException("simulated process loss");
            }
        });

        assertThrows(ResetRecoveryService.ResetFailure.class,
                () -> crashing.reset(root, "old-project", "new-project"));
        assertEquals(1, new ResetRecoveryService(locator).discover(root).size());
        assertEquals(ResetRecoveryService.Phase.NEW_ID_GENERATED,
                new ResetRecoveryService(locator).discover(root).get(0).phase());
        assertTrue(Files.readString(locator.resolve(root).resetRoot()
                .resolve("namespaces/old-project/authority.state")).startsWith("FENCED"));
        assertFalse(Files.exists(locator.resolve(root).resetRoot().resolve("namespaces/new-project")));
    }

    @Test
    void aSecondResetCannotRaceAnUnresolvedJournal(@TempDir Path temp) throws Exception {
        Path root = init(temp.resolve("repo"));
        AdministrativeStateLocator locator = new AdministrativeStateLocator(temp.resolve("state"));
        new ResetRecoveryService(locator).prepare(root, "old-project", "new-project");

        ResetRecoveryService.ResetFailure failure = assertThrows(
                ResetRecoveryService.ResetFailure.class,
                () -> new ResetRecoveryService(locator).prepare(root, "old-project-2", "new-project-2"));
        assertEquals("RESET_CONFLICTING_JOURNALS", failure.code());
    }

    private static void seedOldNamespace(AdministrativeStateLocator locator, Path root, String projectId)
            throws Exception {
        Path namespace = locator.resolve(root).resetRoot().resolve("namespaces").resolve(projectId);
        Files.createDirectories(namespace);
        Files.writeString(namespace.resolve("state.txt"), "old state\n", StandardCharsets.UTF_8);
        Files.writeString(namespace.resolve("authority.state"), "ACTIVE\nprojectId=" + projectId + "\n",
                StandardCharsets.UTF_8);
    }

    private static Path init(Path root) throws Exception {
        Files.createDirectories(root);
        git(root, "init");
        git(root, "config", "user.name", "Test User");
        git(root, "config", "user.email", "test@example.com");
        Files.writeString(root.resolve("tracked.txt"), "baseline\n", StandardCharsets.UTF_8);
        git(root, "add", "tracked.txt");
        git(root, "commit", "-m", "baseline");
        return root;
    }

    private static void git(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }
}
