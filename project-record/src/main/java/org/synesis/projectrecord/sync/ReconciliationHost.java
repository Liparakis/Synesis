package org.synesis.projectrecord.sync;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.UnaryOperator;
import org.synesis.projectrecord.domain.DecisionRecord;
import org.synesis.projectrecord.domain.ProjectConfig;
import org.synesis.projectrecord.persistence.DecisionStore;
import org.synesis.projectrecord.persistence.DecisionStore.SaveResult;
import org.synesis.projectrecord.sync.ProjectReconciliationSync.RecordStatus;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.ErrorCode;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.InventoryEntry;

/**
 * Hosting side of PRP1: answers one request per application stream.
 */
final class ReconciliationHost {

  private final String localNodeId;
  private final ProjectConfig config;
  private final DecisionStore store;

  ReconciliationHost(String localNodeId, ProjectConfig config, DecisionStore store) {
    this.localNodeId = localNodeId;
    this.config = config;
    this.store = store;
  }

  CompletionStage<byte[]> handle(String remoteNodeId, byte[] payload) {
    return CompletableFuture.completedFuture(reply(remoteNodeId, payload));
  }

  private byte[] reply(String remoteNodeId, byte[] payload) {
    try {
      if (!config.allows(remoteNodeId)) {
        return ReconciliationMessage.error(ErrorCode.UNAUTHORIZED, "peer not allowed")
            .encoded();
      }
      ReconciliationMessage message = ReconciliationMessage.decode(payload);
      ReconciliationMessage response = switch (message.kind()) {
        case VALIDATE_PROJECT -> inProject(message, this::validateProject);
        case INVENTORY_CHUNK_ACK -> inProject(message,
            m -> inventoryChunk(m, ReconciliationMessage::inventoryChunk));
        case CLIENT_UPLOAD_REVISION -> inProject(message, this::acceptUpload);
        case CLIENT_DOWNLOAD_REQUEST -> inProject(message, this::serveDownload);
        case FINAL_INVENTORY_CHUNK -> inProject(message, this::checkFinalInventory);
        case FINAL_INVENTORY_CHUNK_ACK -> inProject(message,
            m -> inventoryChunk(m, ReconciliationMessage::finalInventoryChunk));
        default -> ReconciliationMessage.error(ErrorCode.MALFORMED, "unexpected message kind");
      };
      return response.encoded();
    } catch (IOException | IllegalArgumentException failure) {
      return ReconciliationMessage.error(ErrorCode.MALFORMED, "malformed payload")
          .encoded();
    } catch (RuntimeException failure) {
      return ReconciliationMessage.error(ErrorCode.INTERNAL, "internal failure")
          .encoded();
    }
  }

  private ReconciliationMessage inProject(ReconciliationMessage message,
      UnaryOperator<ReconciliationMessage> handler) {
    if (!config.projectId()
        .equals(message.projectId())) {
      return ReconciliationMessage.error(ErrorCode.PROJECT_MISMATCH, "project mismatch");
    }
    return handler.apply(message);
  }

  private ReconciliationMessage validateProject(ReconciliationMessage message) {
    return ReconciliationMessage.validateProjectAck(config.projectId());
  }

  /**
   * Answers an inventory chunk request. A store whose heads cannot be read counts as one more
   * corrupt record on top of the ones found on disk.
   */
  private ReconciliationMessage inventoryChunk(ReconciliationMessage request, ChunkReply reply) {
    int corrupt = store.corruptRecordCount();
    InventorySnapshot snapshot = InventorySnapshot.take(store);
    if (snapshot.unreadable()) {
      corrupt++;
    }
    int chunkIndex = request.chunkIndex();
    if (!snapshot.hasChunk(chunkIndex)) {
      return ReconciliationMessage.error(ErrorCode.MALFORMED, "invalid chunk index");
    }
    return reply.of(config.projectId(), chunkIndex, snapshot.chunkCount(), corrupt,
        snapshot.chunk(chunkIndex));
  }

  private ReconciliationMessage acceptUpload(ReconciliationMessage message) {
    DecisionRecord record;
    try {
      record = DecisionRecord.decode(message.recordBytes());
      if (!trusts(record)) {
        return ReconciliationMessage.clientUploadAck(config.projectId(), record.recordId(), 0,
            RecordStatus.REJECTED.ordinal());
      }
    } catch (Exception failure) {
      return ReconciliationMessage.error(ErrorCode.MALFORMED, "invalid record payload");
    }

    try {
      SaveResult save = store.applyRemote(record);
      RecordStatus status = switch (save) {
        case APPLIED -> RecordStatus.APPLIED;
        case DUPLICATE -> RecordStatus.DUPLICATE;
        case STALE_BASE -> RecordStatus.REMOTE_STALE;
        case CONFLICT, CORRUPT -> RecordStatus.CONFLICT;
        case REVISION_LIMIT -> RecordStatus.REJECTED;
      };
      return ReconciliationMessage.clientUploadAck(config.projectId(), record.recordId(),
          record.revision(), status.ordinal());
    } catch (IOException e) {
      return ReconciliationMessage.error(ErrorCode.INTERNAL, "storage failed");
    }
  }

  private boolean trusts(DecisionRecord record) throws GeneralSecurityException {
    return config.projectId()
        .equals(record.projectId())
        && !isInvalidPeer(record.ownerNodeId())
        && !isInvalidPeer(record.authorNodeId())
        && record.verify();
  }

  private boolean isInvalidPeer(String nodeId) {
    return !nodeId.equals(localNodeId) && !config.allows(nodeId);
  }

  private ReconciliationMessage serveDownload(ReconciliationMessage message) {
    try {
      Optional<DecisionRecord> head = store.head(message.recordId());
      if (head.isPresent() && head.get()
          .revision() >= message.revision()) {
        DecisionRecord target = store.readRevision(message.recordId(), message.revision());
        return downloadResponse(message, ReconciliationMessage.DOWNLOAD_OK, target.encoded());
      }
      return downloadResponse(message, ReconciliationMessage.DOWNLOAD_NOT_FOUND, null);
    } catch (IOException e) {
      return downloadResponse(message, ReconciliationMessage.DOWNLOAD_IO_FAILURE, null);
    }
  }

  private ReconciliationMessage downloadResponse(ReconciliationMessage request, int status,
      byte[] recordBytes) {
    return ReconciliationMessage.clientDownloadResponse(config.projectId(), request.recordId(),
        request.revision(), status, recordBytes);
  }

  private ReconciliationMessage checkFinalInventory(ReconciliationMessage message) {
    int status = ReconciliationMessage.FINAL_CONVERGED;
    for (InventoryEntry entry : message.entries()) {
      try {
        Optional<DecisionStore.Head> localHead = store.headState(entry.recordId());
        if (localHead.isEmpty() || localHead.get()
            .revision() != entry.headVersion()
            || !Arrays.equals(localHead.get()
            .digest(), entry.headDigest())) {
          status = ReconciliationMessage.FINAL_MISMATCH;
        }
      } catch (IOException e) {
        status = ReconciliationMessage.FINAL_MISMATCH;
      }
    }
    return ReconciliationMessage.finalInventoryChunkAck(config.projectId(), message.chunkIndex(),
        status);
  }

  /**
   * Factory of the inventory chunk reply, which differs between the two inventory exchanges.
   */
  @FunctionalInterface
  private interface ChunkReply {

    ReconciliationMessage of(UUID projectId, int chunkIndex, int totalChunks, int corruptCount,
        List<InventoryEntry> entries);
  }
}
