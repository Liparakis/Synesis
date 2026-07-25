package org.synesis.workspace.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;

/** Verifies durable-state replay comparison is semantic and mutation-free. */
final class PostMigrationReplayVerifierTest {

    @Test
    void currentProjectReplaysEquivalentState() throws Exception {
        var root = Files.createTempDirectory("replay-verifier-");
        var location = new ProjectApplicationService().init(root).location();
        var verifier = new PostMigrationReplayVerifier();
        var before = verifier.capture(location);
        var after = verifier.capture(location);
        assertTrue(verifier.compare(before, after).successful());
        assertTrue(before.eventHashChainValid());
        assertTrue(before.snapshotReferencesValid());
    }

    @Test
    void semanticMismatchIsRejectedWithoutExposingPayloads() {
        var verifier = new PostMigrationReplayVerifier();
        var before = new PostMigrationReplayVerifier.MigrationSemanticSnapshot("p", "n", 1, "e", "s", "a", true, true);
        var after = new PostMigrationReplayVerifier.MigrationSemanticSnapshot("p", "n", 1, "e", "s", "b", true, true);
        var result = verifier.compare(before, after);
        assertFalse(result.successful());
        assertTrue(result.reason().equals("post_migration_replay_mismatch"));
    }
}
