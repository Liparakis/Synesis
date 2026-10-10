package org.synesis.projectrecord.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.projectrecord.domain.DecisionEvidence;
import org.synesis.projectrecord.domain.DecisionRecord;
import org.synesis.projectrecord.domain.DecisionStatus;
import org.synesis.projectrecord.domain.ProjectConfig;
import org.synesis.projectrecord.persistence.DecisionStore;
import org.synesis.projectrecord.security.Ed25519Signer;
import org.synesis.projectrecord.sync.ProjectReconciliationSync.ReconciliationResult;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.ErrorCode;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.InventoryEntry;

/**
 * Drives the joining side of PRP1 against a scripted host, without a session.
 */
final class ReconciliationClientTest {

  private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RECORD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant TIME = Instant.parse("2026-01-01T00:00:00Z");

  private DecisionStore store;
  private ReconciliationClient client;
  private Ed25519Signer signer;
  private Function<byte[], CompletionStage<byte[]>> host;

  @BeforeEach
  void openClient(@TempDir Path directory) throws Exception {
    NodeIdentity identity = new IdentityBootstrap(directory.resolve("link")).loadOrCreate()
        .identity();
    signer = Ed25519Signer.from(identity);
    store = new DecisionStore(directory.resolve("records"), PROJECT);
    ProjectConfig config = new ProjectConfig(PROJECT, Set.of("sl1-" + "a".repeat(64)));
    client = new ReconciliationClient(identity.nodeId(), config, store, request -> host.apply(request));
  }

  private static CompletionStage<byte[]> reply(ReconciliationMessage message) {
    return CompletableFuture.completedFuture(message.encoded());
  }

  private DecisionRecord record() throws Exception {
    return DecisionRecord.create(PROJECT, RECORD, 1, null, signer.nodeId(), signer.nodeId(),
        DecisionStatus.PROPOSED, TIME, TIME, "title", "rationale",
        List.of(new DecisionEvidence("test", "process", new byte[32])), signer);
  }

  @Test
  void errorReplyEndsTheSyncWithTheHostsErrorText() {
    host = request -> reply(ReconciliationMessage.error(ErrorCode.UNAUTHORIZED, "peer not allowed"));

    ReconciliationResult result = client.run();

    assertFalse(result.success());
    assertEquals("peer not allowed", result.hint());
    assertEquals(0, result.reconciledCount());
  }

  @Test
  void replyBeyondTheSessionByteBudgetEndsTheSync() {
    host = request -> CompletableFuture.completedFuture(
        new byte[ProjectReconciliationSync.MAX_SESSION_BYTES + 1]);

    ReconciliationResult result = client.run();

    assertFalse(result.success());
    assertEquals("Exceeded session bytes limit.", result.hint());
  }

  @Test
  void lostConnectionEndsTheSyncAsAborted() {
    host = request -> CompletableFuture.failedFuture(new IOException("connection lost"));

    ReconciliationResult result = client.run();

    assertFalse(result.success());
    assertEquals("Sync session aborted: connection lost or protocol failure.", result.hint());
  }

  @Test
  void failureAfterARecordWasAppliedReportsWhatWasApplied() throws Exception {
    DecisionRecord remote = record();
    host = request -> {
      try {
        ReconciliationMessage message = ReconciliationMessage.decode(request);
        return reply(switch (message.kind()) {
          case VALIDATE_PROJECT -> ReconciliationMessage.validateProjectAck(PROJECT);
          case INVENTORY_CHUNK_ACK -> ReconciliationMessage.inventoryChunk(PROJECT, 0, 1, 0,
              List.of(new InventoryEntry(RECORD, 1, remote.digest())));
          case CLIENT_DOWNLOAD_REQUEST -> ReconciliationMessage.clientDownloadResponse(PROJECT,
              RECORD, 1, ReconciliationMessage.DOWNLOAD_OK, remote.encoded());
          default -> ReconciliationMessage.error(ErrorCode.INTERNAL, "final refused");
        });
      } catch (IOException failure) {
        return CompletableFuture.failedFuture(failure);
      }
    };

    ReconciliationResult result = client.run();

    assertFalse(result.success());
    assertEquals("final refused", result.hint());
    assertEquals(1, result.addedLocal());
    assertEquals(1, result.reconciledCount());
    assertEquals(List.of(RECORD + ": APPLIED"), result.outcomes());
    assertTrue(store.head(RECORD).isPresent());
  }
}
