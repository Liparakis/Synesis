package org.synesis.workspace.application.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Verifies when a later owner-lane commit counts as carrying an implementation a requester
 * validated at a different commit, as happens after a lane changes hands.
 */
final class ValidatedContentCarriedTest {

    @Test
    void rebuiltLaneCarriesTheImplementationOnlyWhenEveryValidatedPathIsIdentical(@TempDir Path repository) throws Exception {
        GitProcessRunner.run(repository, "init");
        Files.writeString(repository.resolve("store.py"), "def add(): return 1\n");
        Files.writeString(repository.resolve("removed.py"), "old\n");
        String base = commit(repository, "base");

        // The commit the requester validated: store.py changed, removed.py deleted.
        Files.writeString(repository.resolve("store.py"), "def add(): return 2\n");
        Files.delete(repository.resolve("removed.py"));
        String validated = commit(repository, "implementation");
        List<String> validatedPaths = List.of("store.py", "removed.py");

        // A lane rebuilt after recovery: unrelated history, same content, plus a file of its own.
        GitProcessRunner.run(repository, "checkout", "--orphan", "rebuilt");
        Files.writeString(repository.resolve("notes.txt"), "recovered\n");
        String rebuilt = commit(repository, "rebuilt lane");
        assertTrue(IntegrationOrchestrationService.carriesValidatedContent(repository, validated, validatedPaths, rebuilt));
        assertTrue(IntegrationOrchestrationService.carriesValidatedContent(repository, validated, validatedPaths, validated));

        // The owner changed the validated file afterwards: that content was never validated.
        Files.writeString(repository.resolve("store.py"), "def add(): return 3\n");
        String edited = commit(repository, "edited after validation");
        assertFalse(IntegrationOrchestrationService.carriesValidatedContent(repository, validated, validatedPaths, edited));

        // A path the implementation removed came back.
        Files.writeString(repository.resolve("store.py"), "def add(): return 2\n");
        Files.writeString(repository.resolve("removed.py"), "back\n");
        String restored = commit(repository, "restored a removed path");
        assertFalse(IntegrationOrchestrationService.carriesValidatedContent(repository, validated, validatedPaths, restored));

        assertFalse(IntegrationOrchestrationService.carriesValidatedContent(repository, validated, validatedPaths, base), "the lane before the implementation does not carry it");
        assertFalse(IntegrationOrchestrationService.carriesValidatedContent(repository, validated, List.of(), rebuilt), "no validated paths prove nothing");
        assertFalse(IntegrationOrchestrationService.carriesValidatedContent(repository, validated, validatedPaths, "0000000000000000000000000000000000000000"), "an unknown commit is not evidence");
        assertFalse(IntegrationOrchestrationService.carriesValidatedContent(repository, "0000000000000000000000000000000000000000", validatedPaths, rebuilt));
    }

    private static String commit(Path repository, String message) throws Exception {
        GitProcessRunner.run(repository, "add", "-A");
        GitProcessRunner.run(repository, "commit", "-m", message);
        return GitProcessRunner.run(repository, "rev-parse", "HEAD");
    }
}
