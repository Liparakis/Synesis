package org.synesis.projectrecord.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.projectrecord.domain.ProjectConfig;
import org.synesis.projectrecord.persistence.DecisionStore;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.ErrorCode;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.Kind;

/**
 * Characterizes the error replies of the reconciliation host handler, driven without a session.
 */
final class ProjectReconciliationHostHandlerTest {

  private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID FOREIGN = UUID.fromString("99999999-9999-9999-9999-999999999999");
  private static final String PEER = "sl1-" + "a".repeat(64);
  private static final String STRANGER = "sl1-" + "b".repeat(64);
  private static final String LOCAL = "sl1-" + "c".repeat(64);

  private ProjectReconciliationSync sync;

  @BeforeEach
  void openEmptyStore(@TempDir Path directory) throws Exception {
    DecisionStore store = new DecisionStore(directory, PROJECT);
    sync = new ProjectReconciliationSync(LOCAL, new ProjectConfig(PROJECT, Set.of(PEER)), store);
  }

  private ReconciliationMessage reply(String remote, byte[] payload) throws Exception {
    return ReconciliationMessage.decode(
        sync.handler().handle(remote, payload).toCompletableFuture().get());
  }

  private void assertError(ReconciliationMessage reply, ErrorCode code, String text) {
    assertEquals(Kind.ERROR, reply.kind());
    assertEquals(code, reply.errorCode());
    assertEquals(text, reply.errorText());
  }

  @Test
  void unauthorizedPeerIsRefusedBeforeThePayloadIsRead() throws Exception {
    assertError(reply(STRANGER, ReconciliationMessage.validateProject(PROJECT).encoded()),
        ErrorCode.UNAUTHORIZED, "peer not allowed");
    assertError(reply(STRANGER, new byte[]{1, 2, 3}), ErrorCode.UNAUTHORIZED, "peer not allowed");
  }

  @Test
  void malformedPayloadIsAnsweredWithMalformed() throws Exception {
    assertError(reply(PEER, new byte[]{1, 2, 3}), ErrorCode.MALFORMED, "malformed payload");
    byte[] trailing = ReconciliationMessage.validateProject(PROJECT).encoded();
    assertError(reply(PEER, Arrays.copyOf(trailing, trailing.length + 1)), ErrorCode.MALFORMED,
        "malformed payload");
  }

  @Test
  void messageForAForeignProjectIsAnsweredWithProjectMismatch() throws Exception {
    List<ReconciliationMessage> requests = List.of(
        ReconciliationMessage.validateProject(FOREIGN),
        ReconciliationMessage.inventoryChunkAck(FOREIGN, 0),
        ReconciliationMessage.clientUploadRevision(FOREIGN, new byte[]{1}),
        ReconciliationMessage.clientDownloadRequest(FOREIGN, UUID.randomUUID(), 1),
        ReconciliationMessage.finalInventoryChunk(FOREIGN, 0, 1, 0, List.of()),
        ReconciliationMessage.finalInventoryChunkAck(FOREIGN, 0, 0));
    for (ReconciliationMessage request : requests) {
      assertError(reply(PEER, request.encoded()), ErrorCode.PROJECT_MISMATCH, "project mismatch");
    }
  }

  @Test
  void chunkIndexOutsideTheInventoryIsAnsweredWithMalformed() throws Exception {
    assertError(reply(PEER, ReconciliationMessage.inventoryChunkAck(PROJECT, 1).encoded()),
        ErrorCode.MALFORMED, "invalid chunk index");
    assertError(reply(PEER, ReconciliationMessage.inventoryChunkAck(PROJECT, -1).encoded()),
        ErrorCode.MALFORMED, "invalid chunk index");
    assertError(reply(PEER, ReconciliationMessage.finalInventoryChunkAck(PROJECT, 5, 0).encoded()),
        ErrorCode.MALFORMED, "invalid chunk index");

    ReconciliationMessage first = reply(PEER,
        ReconciliationMessage.inventoryChunkAck(PROJECT, 0).encoded());
    assertEquals(Kind.INVENTORY_CHUNK, first.kind());
    assertEquals(0, first.chunkIndex());
    assertEquals(1, first.totalChunks());
    assertEquals(0, first.corruptCount());
    assertEquals(List.of(), first.entries());
  }

  @Test
  void undecodableUploadAndUnexpectedKindAreAnsweredWithMalformed() throws Exception {
    assertError(reply(PEER,
            ReconciliationMessage.clientUploadRevision(PROJECT, new byte[]{1, 2, 3}).encoded()),
        ErrorCode.MALFORMED, "invalid record payload");
    assertError(reply(PEER, ReconciliationMessage.validateProjectAck(PROJECT).encoded()),
        ErrorCode.MALFORMED, "unexpected message kind");
  }

  @Test
  void recordStatusOrdinalsOnTheWireAreFixed() {
    assertEquals(List.of("DUPLICATE", "REMOTE_STALE", "CONFLICT", "REJECTED", "APPLIED"),
        Arrays.stream(ProjectReconciliationSync.RecordStatus.values()).map(Enum::name).toList());
  }
}
