package org.synesis.workspace.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies that only currently supported provider administration paths receive
 * runtime-artifact treatment during snapshot classification.
 */
class SnapshotArtifactPolicyTest {

    private final SnapshotArtifactPolicy policy = new SnapshotArtifactPolicy();

    /**
     * Keeps the removed provider-era path in the ordinary source set while
     * retaining the supported Codex and Claude path classifications.
     */
    @Test
    void removedProviderPathIsNotSilentlyDroppedFromSnapshots() {
        assertEquals(SnapshotArtifactPolicy.Classification.SOURCE,
                policy.classify(".agents/hooks.json"));
        assertEquals(SnapshotArtifactPolicy.Classification.ALLOWED_RUNTIME_ARTIFACT,
                policy.classify(".codex/hooks.json"));
        assertEquals(SnapshotArtifactPolicy.Classification.ALLOWED_RUNTIME_ARTIFACT,
                policy.classify(".claude/settings.json"));
    }
}
