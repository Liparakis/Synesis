package org.synesis.workspace.application.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.Binding;

/** Pins the JSON file a provider session binding is persisted as. */
class ProviderBindingFileFormatTest {

  @TempDir Path temp;

  @Test
  void bindingFileFormatAndRoundTrip() throws IOException {
    Binding binding =
        new Binding(
            2,
            "session-1",
            "project-a",
            "sl1-node",
            "claude",
            "fingerprint-1",
            "supervisor-1",
            "worker-1",
            "worktree-1",
            "/work/tree",
            "/control",
            "synesis/branch",
            "a".repeat(40),
            "/git/common",
            "CREATED",
            "VERIFIED",
            "ISOLATED",
            "ACTIVE",
            1_700_000_000_000L,
            1_700_000_001_000L,
            12L,
            "WORKSPACE_TRUSTED",
            3,
            null);
    Path file = temp.resolve("session-1.json");

    ProviderBindingFile.write(file, binding);

    assertEquals(
        """
        {"schemaVersion":2,"sessionId":"session-1","projectId":"project-a","nodeId":"sl1-node","provider":"claude","providerInstanceFingerprint":"fingerprint-1","supervisorId":"supervisor-1","workerId":"worker-1","worktreeId":"worktree-1","worktreePath":"/work/tree","controlCheckoutPath":"/control","branch":"synesis/branch","baseCommit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","gitCommonDir":"/git/common","creationState":"CREATED","verificationState":"VERIFIED","lastSeenState":"ISOLATED","status":"ACTIVE","createdAtEpochMillis":1700000000000,"lastSeenEpochMillis":1700000001000,"lastVerifiedProjectSequence":12,"providerTrustState":"WORKSPACE_TRUSTED","bindingVersion":3,"completedAt":null}"""
            + System.lineSeparator(),
        Files.readString(file, StandardCharsets.UTF_8));
    assertEquals(binding, ProviderBindingFile.read(file));
  }

  @Test
  void theTemporaryFileOfAnAtomicReplaceIsNotABinding() {
    assertTrue(ProviderBindingFile.isBindingOf(Path.of("codex-abc.json"), "codex"));
    assertFalse(ProviderBindingFile.isBindingOf(Path.of("codex-abc.json.tmp-1f2e"), "codex"));
    assertFalse(ProviderBindingFile.isBindingOf(Path.of("claude-abc.json"), "codex"));
  }
}
