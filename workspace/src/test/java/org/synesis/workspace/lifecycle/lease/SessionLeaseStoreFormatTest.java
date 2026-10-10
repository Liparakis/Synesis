package org.synesis.workspace.lifecycle.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;

/** Pins the JSON file a session lease record is persisted as. */
class SessionLeaseStoreFormatTest {

  @TempDir Path temp;

  private Path controlRoot;
  private Path workspaceRoot;

  @BeforeEach
  void createProject() throws IOException {
    controlRoot = temp.resolve("control");
    Files.createDirectories(controlRoot.resolve(".synesis"));
    Files.writeString(
        controlRoot.resolve(".synesis/project.json"),
        "{\"projectId\":\"p6-" + UUID.randomUUID() + "\"}",
        StandardCharsets.UTF_8);
    workspaceRoot = LifecyclePathVerifier.resolveWorkspaceRoot(controlRoot);
  }

  @AfterEach
  void removeWorkspace() throws IOException {
    if (Files.exists(workspaceRoot)) {
      try (Stream<Path> files = Files.walk(workspaceRoot)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(file);
        }
      }
    }
  }

  @Test
  void leaseFileFormatAndRoundTrip() throws IOException {
    SessionLeaseRecord record =
        new SessionLeaseRecord(
            1,
            "project-a",
            "codex",
            "connection-1",
            "sl1-worker",
            "session-1",
            new SessionProcessIdentity(4242L, "codex.exe", "codex app-server", 1_700_000_000_000L, "nonce-1"),
            "1.2.3",
            1_700_000_000_000L,
            1_700_000_001_000L,
            SessionLeaseState.ACTIVE);
    SessionLeaseStore store = new SessionLeaseStore();

    store.save(controlRoot, record);

    assertEquals(
        """
        {"schemaVersion":1,"projectId":"project-a","provider":"codex","connectionInstanceId":"connection-1","workerNodeId":"sl1-worker","sessionId":"session-1","synesisVersion":"1.2.3","createdAtEpochMillis":1700000000000,"lastHeartbeatEpochMillis":1700000001000,"leaseState":"ACTIVE","processIdentity":{"pid":4242,"executableIdentity":"codex.exe","commandLine":"codex app-server","processStartTime":1700000000000,"connectionNonce":"nonce-1"}}""",
        Files.readString(
            workspaceRoot.resolve("admin/session-leases/connection-1.json"), StandardCharsets.UTF_8));
    assertEquals(Optional.of(record), store.load(controlRoot, "connection-1"));
  }
}
