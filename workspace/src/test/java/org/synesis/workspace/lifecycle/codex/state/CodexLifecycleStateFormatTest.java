package org.synesis.workspace.lifecycle.codex.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.lifecycle.codex.protocol.LifecycleControlRequestEnvelope;

/** Pins the persisted checkpoint file and the idempotency ledger file of Codex lifecycle state. */
class CodexLifecycleStateFormatTest {

  @TempDir Path temp;

  @Test
  void checkpointFileFormat() throws IOException {
    CodexLifecycleStateStore store = new CodexLifecycleStateStore(temp.resolve("runtime"));
    CodexLifecycleStateStore.Checkpoint checkpoint =
        new CodexLifecycleStateStore.Checkpoint(
            "session-1",
            "project-a",
            "codex",
            3L,
            CodexLifecycleStateStore.State.RUNNING,
            "host-1",
            2L,
            5L,
            4242L,
            1_700_000_000_000L,
            "codex.exe",
            "codex app-server",
            "thread-1",
            null,
            "diagnostic",
            true,
            1_700_000_001_000L);

    store.write(checkpoint);

    assertEquals(
        """
        {"bindingSessionId":"session-1","projectId":"project-a","provider":"codex","revision":3,"state":"RUNNING","ownerHostInstanceId":"host-1","attachmentGeneration":2,"connectionGeneration":5,"rootPid":4242,"rootStartEpochMillis":1700000000000,"rootExecutable":"codex.exe","rootCommandIdentity":"codex app-server","threadId":"thread-1","turnId":null,"terminalDiagnostic":"diagnostic","evidenceComplete":true,"updatedAtEpochMillis":1700000001000}"""
            + System.lineSeparator(),
        Files.readString(store.root().resolve("session-1.json"), StandardCharsets.UTF_8));
    assertEquals(checkpoint, store.read("session-1", "project-a"));
  }

  @Test
  void ledgerFileFormatForPreparedAndCompletedEntries() throws IOException {
    Path file = temp.resolve("ledger.json");
    LifecycleIdempotencyLedger ledger = new LifecycleIdempotencyLedger(file);
    UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
    UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
    LifecycleControlRequestEnvelope started = request(first, "START", "go");
    LifecycleControlRequestEnvelope status = request(second, "STATUS", null);

    ledger.prepare(started, 4L);
    ledger.prepare(status, 4L);
    ledger.complete(second, LifecycleIdempotencyLedger.State.COMPLETED, 4L, "{\"ok\":true}");

    String text = Files.readString(file, StandardCharsets.UTF_8);
    assertEquals(
        """
        [{"hostInstanceId":"host-1","projectId":"project-a","requestId":"00000000-0000-4000-8000-000000000001","operation":"START","classification":"STATE_CHANGING","digest":"<DIGEST-1>","bindingSessionId":"session-1","expectedRevision":4,"laneId":"intent-1","laneEpoch":2,"threadId":null,"turnId":null,"startedAtEpochMillis":<T>,"deadlineEpochMillis":<T>,"state":"ACCEPTED","revisionBefore":4,"revisionAfter":0,"result":null,"completedAtEpochMillis":0,"expiryEpochMillis":<T>},{"hostInstanceId":"host-1","projectId":"project-a","requestId":"00000000-0000-4000-8000-000000000002","operation":"STATUS","classification":"READ_ONLY","digest":"<DIGEST-2>","bindingSessionId":"session-1","expectedRevision":4,"laneId":"intent-1","laneEpoch":2,"threadId":null,"turnId":null,"startedAtEpochMillis":<T>,"deadlineEpochMillis":<T>,"state":"COMPLETED","revisionBefore":4,"revisionAfter":4,"result":"{\\"ok\\":true}","completedAtEpochMillis":<T>,"expiryEpochMillis":<T>}]"""
            + System.lineSeparator(),
        text.replace(started.digest(), "<DIGEST-1>")
            .replace(status.digest(), "<DIGEST-2>")
            .replaceAll("(Millis\":)\\d{13}", "$1<T>"));
    assertEquals(
        LifecycleIdempotencyLedger.State.ACCEPTED, ledger.find(first).orElseThrow().state());
  }

  private static LifecycleControlRequestEnvelope request(
      UUID id, String operation, String input) {
    return new LifecycleControlRequestEnvelope(
        id,
        "host-1",
        new LifecycleControlRequestEnvelope.AuthorityContext(
            "project-a",
            "codex",
            "connection-1",
            "session-1",
            "fingerprint-1",
            1,
            "agt_participant",
            "intent-1",
            2L,
            "worktree",
            "worktree",
            "git",
            "branch",
            "a".repeat(40),
            "supervisor-1",
            "worker-1"),
        LifecycleControlRequestEnvelope.Operation.valueOf(operation),
        4L,
        null,
        null,
        false,
        input,
        System.currentTimeMillis() + 60_000L,
        Map.of());
  }
}
