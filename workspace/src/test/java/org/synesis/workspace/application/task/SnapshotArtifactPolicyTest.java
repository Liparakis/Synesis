package org.synesis.workspace.application.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies that only currently supported provider administration paths receive runtime-artifact
 * treatment during snapshot classification.
 */
class SnapshotArtifactPolicyTest {

  private final SnapshotArtifactPolicy policy = new SnapshotArtifactPolicy();

  /**
   * AGENTS.md is the user's own source file: Synesis stopped managing it (ADR-0103 decision 9), so a
   * change to it travels with the lane. The project file stays the one managed contract.
   */
  @Test
  void agentsMdIsSourceAndTheProjectFileIsTheOnlyManagedContract() {
    assertEquals(SnapshotArtifactPolicy.Classification.SOURCE, policy.classify("AGENTS.md"));
    assertEquals(SnapshotArtifactPolicy.Classification.MANAGED_CONTRACT,
        policy.classify(".synesis/project.json"));
  }

  /**
   * Keeps the removed provider-era path in the ordinary source set while retaining the supported
   * Codex and Claude path classifications.
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
