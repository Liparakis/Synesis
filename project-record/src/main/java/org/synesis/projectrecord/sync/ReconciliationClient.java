package org.synesis.projectrecord.sync;

import static org.synesis.projectrecord.sync.ProjectReconciliationSync.MAX_INVENTORY_ENTRIES;
import static org.synesis.projectrecord.sync.ProjectReconciliationSync.MAX_REVISIONS;
import static org.synesis.projectrecord.sync.ProjectReconciliationSync.MAX_SESSION_BYTES;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.synesis.projectrecord.domain.DecisionRecord;
import org.synesis.projectrecord.domain.ProjectConfig;
import org.synesis.projectrecord.persistence.DecisionStore;
import org.synesis.projectrecord.persistence.DecisionStore.SaveResult;
import org.synesis.projectrecord.sync.ProjectReconciliationSync.ReconciliationResult;
import org.synesis.projectrecord.sync.ProjectReconciliationSync.RecordStatus;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.InventoryEntry;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.Kind;

/**
 * Joining side of PRP1: one single-use run of the six phases of a bidirectional reconciliation.
 * Every request goes through {@link #exchange} or {@link #exchangeLenient}, which own the session
 * byte budget and the reply deadline.
 */
final class ReconciliationClient {

  private static final long REPLY_TIMEOUT_SECONDS = 5;

  private final String localNodeId;
  private final ProjectConfig config;
  private final DecisionStore store;
  private final Function<byte[], CompletionStage<byte[]>> peer;
  private final List<String> outcomes = new ArrayList<>();
  private long bytesTransferred;
  private int addedLocal;
  private int addedRemote;
  private int duplicateCount;
  private int corruptLocalCount;

  ReconciliationClient(String localNodeId, ProjectConfig config, DecisionStore store,
      Function<byte[], CompletionStage<byte[]>> peer) {
    this.localNodeId = localNodeId;
    this.config = config;
    this.store = store;
    this.peer = peer;
  }

  ReconciliationResult run() {
    try {
      validateProject();
      List<InventoryEntry> remoteInventory = fetchRemoteInventory();
      Plan plan = plan(remoteInventory);
      upload(plan);
      download(plan);
      return verifyConvergence();
    } catch (SyncAbort abort) {
      return aborted(abort.getMessage());
    } catch (Exception failure) {
      return aborted("Sync session aborted: connection lost or protocol failure.");
    }
  }

  /**
   * Reports what was applied before the abort, so the store change is not hidden from the caller.
   */
  private ReconciliationResult aborted(String hint) {
    return new ReconciliationResult(false, outcomes.size(), addedLocal, addedRemote,
        duplicateCount, store.corruptRecordCount(), 0, outcomes, hint);
  }

  // 1. Authenticated project validation

  private void validateProject() throws Exception {
    ReconciliationMessage ack = exchange(ReconciliationMessage.validateProject(config.projectId()));
    if (ack.kind() != Kind.VALIDATE_PROJECT_ACK || !config.projectId()
        .equals(ack.projectId())) {
      throw new SyncAbort("Project ID validation failed.");
    }
  }

  // 2. Fetch the remote inventory

  private List<InventoryEntry> fetchRemoteInventory() throws Exception {
    List<InventoryEntry> remoteInventory = new ArrayList<>();
    int chunkIndex = 0;
    while (true) {
      ReconciliationMessage chunk = exchange(
          ReconciliationMessage.inventoryChunkAck(config.projectId(), chunkIndex));
      if (chunk.kind() != Kind.INVENTORY_CHUNK || chunk.chunkIndex() != chunkIndex) {
        throw new SyncAbort("Inventory exchange out of order.");
      }
      remoteInventory.addAll(chunk.entries());
      if (remoteInventory.size() > MAX_INVENTORY_ENTRIES) {
        throw new SyncAbort("Exceeded inventory limit.");
      }
      if (chunk.chunkIndex() + 1 >= chunk.totalChunks()) {
        return remoteInventory;
      }
      chunkIndex++;
    }
  }

  // 3. Compute the reconciliation plan, quarantining divergent heads on both sides

  private Plan plan(List<InventoryEntry> remoteInventory) {
    corruptLocalCount = store.corruptRecordCount();
    InventorySnapshot local = InventorySnapshot.take(store);
    noteUnreadable(local);

    Map<UUID, DecisionRecord> localMap = local.heads()
        .stream()
        .collect(Collectors.toMap(DecisionRecord::recordId, r -> r));
    Map<UUID, InventoryEntry> remoteMap = remoteInventory.stream()
        .collect(Collectors.toMap(InventoryEntry::recordId, r -> r));

    Set<UUID> allRecords = new HashSet<>();
    allRecords.addAll(localMap.keySet());
    allRecords.addAll(remoteMap.keySet());

    Plan plan = new Plan(localMap, remoteMap, new ArrayList<>(), new ArrayList<>());
    for (UUID id : allRecords) {
      DecisionRecord localHead = localMap.get(id);
      InventoryEntry remote = remoteMap.get(id);

      if (localHead != null && remote == null) {
        plan.uploads.add(id);
      } else if (localHead == null && remote != null) {
        plan.downloads.add(id);
      } else if (localHead != null) {
        if (localHead.revision() == remote.headVersion() && Arrays.equals(localHead.digest(),
            remote.headDigest())) {
          duplicateCount++;
          outcomes.add(id + ": DUPLICATE");
        } else if (localHead.revision() > remote.headVersion()) {
          plan.uploads.add(id);
        } else if (localHead.revision() < remote.headVersion()) {
          plan.downloads.add(id);
        } else {
          quarantineDivergence(id, localHead, remote);
        }
      }
    }
    return plan;
  }

  /**
   * Handles equal revisions with different digests: quarantines the local head, hands it to the
   * host so the host quarantines it too, and quarantines the host's head in return. Neither reply
   * has to be well-formed for the local quarantine to stand, so the upload reply is not even
   * decoded and the download reply is only used when it carries the record.
   */
  private void quarantineDivergence(UUID id, DecisionRecord local, InventoryEntry remote) {
    try {
      store.quarantine(local);
      exchangeLenient(ReconciliationMessage.clientUploadRevision(config.projectId(),
          local.encoded()));
      ReconciliationMessage reply = ReconciliationMessage.decode(exchangeLenient(
          ReconciliationMessage.clientDownloadRequest(config.projectId(), id,
              remote.headVersion())));
      if (reply.kind() == Kind.CLIENT_DOWNLOAD_RESPONSE
          && reply.status() == ReconciliationMessage.DOWNLOAD_OK) {
        store.quarantine(DecisionRecord.decode(reply.recordBytes()));
      }
      outcomes.add(id + ": CONFLICT");
    } catch (SyncAbort abort) {
      throw abort;
    } catch (Exception e) {
      throw new SyncAbort("Storage or protocol failure during quarantine exchange.");
    }
  }

  // 4. Joiner uploads to host

  private void upload(Plan plan) throws Exception {
    for (UUID id : plan.uploads) {
      DecisionRecord local = plan.local.get(id);
      InventoryEntry remote = plan.remote.get(id);
      long startRev = remote == null ? 1 : remote.headVersion() + 1;
      long endRev = local.revision();
      requireRevisionBound(startRev, endRev);

      for (long r = startRev; r <= endRev; r++) {
        DecisionRecord toUpload;
        try {
          toUpload = store.readRevision(id, r);
        } catch (IOException e) {
          noteUnreadable();
          break;
        }
        ReconciliationMessage ack = exchange(
            ReconciliationMessage.clientUploadRevision(config.projectId(), toUpload.encoded()));
        if (ack.kind() != Kind.CLIENT_UPLOAD_ACK || !id.equals(ack.recordId())
            || ack.revision() != r) {
          throw new SyncAbort("Upload acknowledgement mismatch.");
        }
        RecordStatus status = RecordStatus.values()[ack.status()];
        outcomes.add(id + ": " + status.name());
        if (status == RecordStatus.APPLIED) {
          addedRemote++;
        }
      }
    }
  }

  // 5. Host downloads to joiner

  private void download(Plan plan) throws Exception {
    for (UUID id : plan.downloads) {
      DecisionRecord local = plan.local.get(id);
      InventoryEntry remote = plan.remote.get(id);
      long startRev = local == null ? 1 : local.revision() + 1;
      long endRev = remote.headVersion();
      requireRevisionBound(startRev, endRev);

      for (long r = startRev; r <= endRev; r++) {
        ReconciliationMessage resp = exchange(
            ReconciliationMessage.clientDownloadRequest(config.projectId(), id, r));
        if (resp.kind() != Kind.CLIENT_DOWNLOAD_RESPONSE || !id.equals(resp.recordId())
            || resp.revision() != r) {
          throw new SyncAbort("Download response mismatch.");
        }
        if (resp.status() != ReconciliationMessage.DOWNLOAD_OK) {
          outcomes.add(id + ": REJECTED");
          break;
        }
        Optional<DecisionRecord> trusted = trustedRecord(resp.recordBytes());
        if (trusted.isEmpty()) {
          outcomes.add(id + ": REJECTED");
          break;
        }
        if (!applyDownloaded(id, trusted.get())) {
          break;
        }
      }
    }
  }

  /**
   * Applies one downloaded revision and returns whether the next revision may follow.
   */
  private boolean applyDownloaded(UUID id, DecisionRecord record) {
    try {
      SaveResult save = store.applyRemote(record);
      if (save == SaveResult.APPLIED) {
        addedLocal++;
        outcomes.add(id + ": APPLIED");
        return true;
      }
      if (save == SaveResult.DUPLICATE) {
        duplicateCount++;
        outcomes.add(id + ": DUPLICATE");
        return true;
      }
      outcomes.add(id + ": CONFLICT");
      return false;
    } catch (IOException e) {
      throw new SyncAbort("Storage failed during download apply.");
    }
  }

  private Optional<DecisionRecord> trustedRecord(byte[] recordBytes) {
    try {
      DecisionRecord record = DecisionRecord.decode(recordBytes);
      return trusts(record) ? Optional.of(record) : Optional.empty();
    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
      return Optional.empty();
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

  private static void requireRevisionBound(long startRev, long endRev) {
    if (endRev - startRev + 1 > MAX_REVISIONS) {
      throw new SyncAbort("Exceeded revision count limit.");
    }
  }

  // 6. Exchange final inventories and verify convergence

  private ReconciliationResult verifyConvergence() throws Exception {
    InventorySnapshot finalLocal = InventorySnapshot.take(store);
    noteUnreadable(finalLocal);
    boolean hostValidated = sendFinalInventory(finalLocal);
    HostInventory finalHost = fetchFinalHostInventory();

    boolean listsEqual = finalLocal.entries()
        .size() == finalHost.entries()
        .size() && new HashSet<>(finalLocal.entries()).containsAll(finalHost.entries());
    boolean success = hostValidated && listsEqual && corruptLocalCount == 0
        && finalHost.corruptCount() == 0;

    String hint = success ? ""
        : "Sync completed but inventories did not fully converge. Check for quarantined conflicts or local corruptions.";
    return new ReconciliationResult(success, outcomes.size(), addedLocal, addedRemote,
        duplicateCount, corruptLocalCount, finalHost.corruptCount(), outcomes, hint);
  }

  /**
   * Sends the local final inventory and returns whether the host found every entry converged.
   */
  private boolean sendFinalInventory(InventorySnapshot finalLocal) throws Exception {
    boolean hostValidated = true;
    for (int chunkIndex = 0; chunkIndex < finalLocal.chunkCount(); chunkIndex++) {
      ReconciliationMessage ack = exchange(
          ReconciliationMessage.finalInventoryChunk(config.projectId(), chunkIndex,
              finalLocal.chunkCount(), corruptLocalCount, finalLocal.chunk(chunkIndex)));
      if (ack.kind() != Kind.FINAL_INVENTORY_CHUNK_ACK || ack.chunkIndex() != chunkIndex) {
        throw new SyncAbort("Final inventory exchange mismatch.");
      }
      if (ack.status() != ReconciliationMessage.FINAL_CONVERGED) {
        hostValidated = false;
      }
    }
    return hostValidated;
  }

  private HostInventory fetchFinalHostInventory() throws Exception {
    List<InventoryEntry> entries = new ArrayList<>();
    int chunkIndex = 0;
    while (true) {
      ReconciliationMessage chunk = exchange(
          ReconciliationMessage.finalInventoryChunkAck(config.projectId(), chunkIndex, 0));
      if (chunk.kind() != Kind.FINAL_INVENTORY_CHUNK || chunk.chunkIndex() != chunkIndex) {
        throw new SyncAbort("Final inventory exchange out of order.");
      }
      entries.addAll(chunk.entries());
      if (chunk.chunkIndex() + 1 >= chunk.totalChunks()) {
        return new HostInventory(entries, chunk.corruptCount());
      }
      chunkIndex++;
    }
  }

  // Local corruption accounting

  /**
   * An unreadable local store counts as one corrupt record when none was found on disk. This is
   * the joiner's rule; the host adds one to what it found.
   */
  private void noteUnreadable(InventorySnapshot snapshot) {
    if (snapshot.unreadable()) {
      noteUnreadable();
    }
  }

  private void noteUnreadable() {
    if (corruptLocalCount == 0) {
      corruptLocalCount = 1;
    }
  }

  // Session exchange

  /**
   * Sends one request and returns the decoded reply. An {@code ERROR} reply ends the sync with the
   * host's error text.
   */
  private ReconciliationMessage exchange(ReconciliationMessage request) throws Exception {
    ReconciliationMessage reply = ReconciliationMessage.decode(exchangeLenient(request));
    if (reply.kind() == Kind.ERROR) {
      throw new SyncAbort(reply.errorText());
    }
    return reply;
  }

  /**
   * Sends one request and returns the raw reply without looking at it, for the divergence
   * exchange where the reply does not decide the outcome. Still bound by the byte budget and the
   * reply deadline.
   */
  private byte[] exchangeLenient(ReconciliationMessage request) throws Exception {
    byte[] payload = request.encoded();
    spend(payload.length);
    byte[] reply = peer.apply(payload)
        .toCompletableFuture()
        .get(REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    spend(reply.length);
    return reply;
  }

  private void spend(int bytes) {
    bytesTransferred += bytes;
    if (bytesTransferred > MAX_SESSION_BYTES) {
      throw new SyncAbort("Exceeded session bytes limit.");
    }
  }

  private record Plan(Map<UUID, DecisionRecord> local, Map<UUID, InventoryEntry> remote,
                      List<UUID> uploads, List<UUID> downloads) {

  }

  private record HostInventory(List<InventoryEntry> entries, int corruptCount) {

  }

  /**
   * Ends the sync with a failure hint; the phases let it propagate to {@link #run}.
   */
  private static final class SyncAbort extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SyncAbort(String hint) {
      super(hint, null, false, false);
    }
  }
}
