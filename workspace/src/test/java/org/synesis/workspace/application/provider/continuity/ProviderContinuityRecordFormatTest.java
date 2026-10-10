package org.synesis.workspace.application.provider.continuity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins the JSON files of the managed attachment record and the provider thread ownership record. */
class ProviderContinuityRecordFormatTest {

  @TempDir Path temp;

  @Test
  void managedAttachmentRecordFormatAndRoundTrip() throws IOException {
    ManagedAttachmentStore store = new ManagedAttachmentStore(temp.resolve("attachment.json"));
    ManagedAttachmentRecord record =
        new ManagedAttachmentRecord(
            1,
            "project-a",
            "codex",
            ProviderContinuityMode.MANAGED_CONTINUITY,
            "session-1",
            "thread-1",
            2L,
            "b".repeat(64),
            "home-1",
            ManagedAttachmentRecord.Status.ACTIVE,
            3L,
            1_700_000_000_000L);

    store.write(record);

    assertEquals(
        """
        {"schemaVersion":1,"projectId":"project-a","provider":"codex","mode":"MANAGED_CONTINUITY","bindingSessionId":"session-1","threadId":"thread-1","generation":2,"proofHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","runtimeHomeId":"home-1","status":"ACTIVE","revision":3,"updatedAtEpochMillis":1700000000000}"""
            + System.lineSeparator(),
        Files.readString(temp.resolve("attachment.json"), StandardCharsets.UTF_8));
    assertEquals(Optional.of(record), store.read());
  }

  @Test
  void providerThreadOwnershipRecordFormatAndRoundTrip() throws IOException {
    ProviderThreadOwnershipStore store = new ProviderThreadOwnershipStore(temp.resolve("owners"));

    ProviderThreadOwnershipRecord record =
        store.acquire("project-a", "codex", "thread-1", "session-1");

    String name = "codex-4b0a5fefc328e6b9257bc535e3c5d26fec36b52f92e277618bf5b30fc3885e58.json";
    assertEquals(
        """
        {"schemaVersion":2,"projectId":"project-a","provider":"codex","providerThreadId":"thread-1","bindingSessionId":"session-1","status":"ACTIVE","persistenceReady":false,"revision":1,"acquiredAtEpochMillis":<T>,"updatedAtEpochMillis":<T>}"""
            + System.lineSeparator(),
        Files.readString(temp.resolve("owners").resolve(name), StandardCharsets.UTF_8)
            .replaceAll("(Millis\":)\\d{13}", "$1<T>"));
    assertEquals(Optional.of(record), store.find("codex", "thread-1"));
  }
}
