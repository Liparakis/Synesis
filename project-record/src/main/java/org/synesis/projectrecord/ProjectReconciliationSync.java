package org.synesis.projectrecord;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import java.nio.file.Path;
import org.synesis.link.session.PeerSession;
import org.synesis.projectrecord.ReconciliationMessage.InventoryEntry;
import org.synesis.projectrecord.DecisionStore.SaveResult;

/**
 * Handles project-wide bidirectional reconciliation (PRP1) over one authenticated session.
 */
public final class ProjectReconciliationSync {

    /** Maximum inventory entries limit (safety bound). */
    public static final int MAX_INVENTORY_ENTRIES = 1_000;
    /** Maximum revisions per record (safety bound). */
    public static final int MAX_REVISIONS = 100;
    /** Maximum session bytes transferred (safety bound). */
    public static final int MAX_SESSION_BYTES = 10 * 1024 * 1024;
    /** Entries per chunk. */
    public static final int CHUNK_SIZE = 50;

    /** Reconciled per-record action/outcome. */
    public enum RecordStatus {
        /** Revision was duplicated. */ DUPLICATE,
        /** Revision was stale. */ REMOTE_STALE,
        /** Revision had Divergence and was quarantined. */ CONFLICT,
        /** Revision failed verification. */ REJECTED,
        /** Revision was successfully applied. */ APPLIED
    }

    /** Project-level reconciliation result. */
    public static final class ReconciliationResult {
        private final boolean success;
        private final int reconciledCount;
        private final int addedLocal;
        private final int addedRemote;
        private final int duplicateCount;
        private final int corruptLocalCount;
        private final int corruptRemoteCount;
        private final List<String> outcomes;
        private final String hint;

        /**
         * Creates a reconciliation result instance.
         *
         * @param success true if reconciliation fully succeeded without corrupt records
         * @param reconciledCount total number of records reconciled
         * @param addedLocal number of records added locally
         * @param addedRemote number of records uploaded to remote
         * @param duplicateCount number of duplicate records observed
         * @param corruptLocalCount number of corrupt records observed locally
         * @param corruptRemoteCount number of corrupt records observed on remote
         * @param outcomes list of per-record outcome descriptions
         * @param hint diagnostic hint string
         */
        public ReconciliationResult(boolean success, int reconciledCount, int addedLocal, int addedRemote,
                int duplicateCount, int corruptLocalCount, int corruptRemoteCount, List<String> outcomes, String hint) {
            this.success = success;
            this.reconciledCount = reconciledCount;
            this.addedLocal = addedLocal;
            this.addedRemote = addedRemote;
            this.duplicateCount = duplicateCount;
            this.corruptLocalCount = corruptLocalCount;
            this.corruptRemoteCount = corruptRemoteCount;
            this.outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
            this.hint = hint == null ? "" : hint;
        }

        /**
         * Returns whether the inventories converged exactly with no corruptions.
         *
         * @return true if successful
         */
        public boolean success() { return success; }

        /**
         * Returns total records reconciled.
         *
         * @return reconciled count
         */
        public int reconciledCount() { return reconciledCount; }

        /**
         * Returns local additions count.
         *
         * @return local additions count
         */
        public int addedLocal() { return addedLocal; }

        /**
         * Returns remote additions count.
         *
         * @return remote additions count
         */
        public int addedRemote() { return addedRemote; }

        /**
         * Returns duplicate count.
         *
         * @return duplicate count
         */
        public int duplicateCount() { return duplicateCount; }

        /**
         * Returns corrupt local count.
         *
         * @return corrupt local count
         */
        public int corruptLocalCount() { return corruptLocalCount; }

        /**
         * Returns corrupt remote count.
         *
         * @return corrupt remote count
         */
        public int corruptRemoteCount() { return corruptRemoteCount; }

        /**
         * Returns detailed per-record outcomes.
         *
         * @return list of outcomes
         */
        public List<String> outcomes() { return outcomes; }

        /**
         * Returns diagnostic hint.
         *
         * @return diagnostic hint
         */
        public String hint() { return hint; }
    }

    private final String localNodeId;
    private final ProjectConfig config;
    private final DecisionStore store;

    /**
     * Creates a project reconciliation sync endpoint.
     *
     * @param localNodeId local node identifier
     * @param config active project configuration
     * @param store local decision store
     */
    public ProjectReconciliationSync(String localNodeId, ProjectConfig config, DecisionStore store) {
        this.localNodeId = Objects.requireNonNull(localNodeId, "localNodeId");
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Returns handler for incoming server-side application streams.
     *
     * @return stream handler callback
     */
    public PeerSession.ApplicationStreamHandler handler() {
        return this::handle;
    }

    /**
     * Executes client-side bidirectional project synchronization.
     *
     * @param session authenticated Link session
     * @return reconciliation result
     */
    public ReconciliationResult syncProject(PeerSession session) {
        Objects.requireNonNull(session, "session");
        long bytesTransferred = 0;
        List<String> outcomes = new ArrayList<>();
        int addedLocal = 0;
        int addedRemote = 0;
        int duplicateCount = 0;

        try {
            // 1. Authenticated Project Validation
            byte[] validateRequest = ReconciliationMessage.validateProject(config.projectId()).encoded();
            bytesTransferred += validateRequest.length;
            if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

            byte[] validateResponse = await(session.requestApplication(validateRequest));
            bytesTransferred += validateResponse.length;
            if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

            ReconciliationMessage valMsg = ReconciliationMessage.decode(validateResponse);
            if (valMsg.kind() == ReconciliationMessage.Kind.ERROR) {
                return fail(valMsg.errorText());
            }
            if (valMsg.kind() != ReconciliationMessage.Kind.VALIDATE_PROJECT_ACK || !config.projectId().equals(valMsg.projectId())) {
                return fail("Project ID validation failed.");
            }

            // 2. Fetch Remote Inventory
            List<InventoryEntry> remoteInventory = new ArrayList<>();
            int corruptRemoteCount = 0;
            int currentChunk = 0;
            while (true) {
                byte[] requestChunk = ReconciliationMessage.inventoryChunkAck(config.projectId(), currentChunk).encoded();
                bytesTransferred += requestChunk.length;
                if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                byte[] chunkResponse = await(session.requestApplication(requestChunk));
                bytesTransferred += chunkResponse.length;
                if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                ReconciliationMessage chunkMsg = ReconciliationMessage.decode(chunkResponse);
                if (chunkMsg.kind() == ReconciliationMessage.Kind.ERROR) {
                    return fail(chunkMsg.errorText());
                }
                if (chunkMsg.kind() != ReconciliationMessage.Kind.INVENTORY_CHUNK || chunkMsg.chunkIndex() != currentChunk) {
                    return fail("Inventory exchange out of order.");
                }
                corruptRemoteCount = chunkMsg.corruptCount();
                remoteInventory.addAll(chunkMsg.entries());
                if (remoteInventory.size() > MAX_INVENTORY_ENTRIES) return fail("Exceeded inventory limit.");

                if (chunkMsg.chunkIndex() + 1 >= chunkMsg.totalChunks()) {
                    break;
                }
                currentChunk++;
            }

            // 3. Compute Reconciliation Plan locally
            List<DecisionRecord> localHeads;
            int corruptLocalCount = countCorruptRecords();
            try {
                localHeads = store.verifiedHeads(MAX_INVENTORY_ENTRIES);
            } catch (IOException e) {
                if (corruptLocalCount == 0) {
                    corruptLocalCount = 1;
                }
                localHeads = List.of();
            }

            Map<UUID, DecisionRecord> localMap = localHeads.stream().collect(Collectors.toMap(DecisionRecord::recordId, r -> r));
            Map<UUID, InventoryEntry> remoteMap = remoteInventory.stream().collect(Collectors.toMap(InventoryEntry::recordId, r -> r));

            Set<UUID> allRecords = new HashSet<>();
            allRecords.addAll(localMap.keySet());
            allRecords.addAll(remoteMap.keySet());

            List<UUID> uploadList = new ArrayList<>();
            List<UUID> downloadList = new ArrayList<>();

            for (UUID id : allRecords) {
                DecisionRecord local = localMap.get(id);
                InventoryEntry remote = remoteMap.get(id);

                if (local != null && remote == null) {
                    uploadList.add(id);
                } else if (local == null && remote != null) {
                    downloadList.add(id);
                } else if (local != null && remote != null) {
                    if (local.revision() == remote.headVersion() && Arrays.equals(local.digest(), remote.headDigest())) {
                        duplicateCount++;
                        outcomes.add(id + ": DUPLICATE");
                    } else if (local.revision() > remote.headVersion()) {
                        // local is ahead, upload the diff
                        uploadList.add(id);
                    } else if (local.revision() < remote.headVersion()) {
                        // remote is ahead, download the diff
                        downloadList.add(id);
                    } else {
                        // Equal revision but mismatch digest -> divergence!
                        try {
                            store.quarantine(local);
                            // Upload client's conflicting head to host so host quarantines it
                            byte[] uploadReq = ReconciliationMessage.clientUploadRevision(config.projectId(), local.encoded()).encoded();
                            bytesTransferred += uploadReq.length;
                            if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");
                            byte[] uploadResp = await(session.requestApplication(uploadReq));
                            bytesTransferred += uploadResp.length;
                            if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                            // Download host's conflicting head so client quarantines it
                            byte[] downloadReq = ReconciliationMessage.clientDownloadRequest(config.projectId(), id, remote.headVersion()).encoded();
                            bytesTransferred += downloadReq.length;
                            if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");
                            byte[] downloadResp = await(session.requestApplication(downloadReq));
                            bytesTransferred += downloadResp.length;
                            if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                            ReconciliationMessage resp = ReconciliationMessage.decode(downloadResp);
                            if (resp.kind() == ReconciliationMessage.Kind.CLIENT_DOWNLOAD_RESPONSE && resp.status() == 0) {
                                DecisionRecord remoteRec = DecisionRecord.decode(resp.recordBytes());
                                store.quarantine(remoteRec);
                            }
                            outcomes.add(id + ": CONFLICT");
                        } catch (Exception e) {
                            return fail("Storage or protocol failure during quarantine exchange.");
                        }
                    }
                }
            }

            // 4. Joiner (Client) Uploads to Host
            for (UUID id : uploadList) {
                DecisionRecord local = localMap.get(id);
                InventoryEntry remote = remoteMap.get(id);
                long startRev = remote == null ? 1 : remote.headVersion() + 1;
                long endRev = local.revision();

                if (endRev - startRev + 1 > MAX_REVISIONS) return fail("Exceeded revision count limit.");

                for (long r = startRev; r <= endRev; r++) {
                    DecisionRecord recToUpload;
                    try {
                        recToUpload = store.readRevision(id, r);
                    } catch (IOException e) {
                        if (corruptLocalCount == 0) {
                            corruptLocalCount = 1;
                        }
                        break;
                    }
                    byte[] uploadRequest = ReconciliationMessage.clientUploadRevision(config.projectId(), recToUpload.encoded()).encoded();
                    bytesTransferred += uploadRequest.length;
                    if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                    byte[] uploadResponse = await(session.requestApplication(uploadRequest));
                    bytesTransferred += uploadResponse.length;
                    if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                    ReconciliationMessage ack = ReconciliationMessage.decode(uploadResponse);
                    if (ack.kind() == ReconciliationMessage.Kind.ERROR) {
                        return fail(ack.errorText());
                    }
                    if (ack.kind() != ReconciliationMessage.Kind.CLIENT_UPLOAD_ACK || !id.equals(ack.recordId()) || ack.revision() != r) {
                        return fail("Upload acknowledgement mismatch.");
                    }
                    RecordStatus status = RecordStatus.values()[ack.status()];
                    outcomes.add(id + ": " + status.name());
                    if (status == RecordStatus.APPLIED) {
                        addedRemote++;
                    }
                }
            }

            // 5. Host Downloads to Joiner (Client)
            for (UUID id : downloadList) {
                DecisionRecord local = localMap.get(id);
                InventoryEntry remote = remoteMap.get(id);
                long startRev = local == null ? 1 : local.revision() + 1;
                long endRev = remote.headVersion();

                if (endRev - startRev + 1 > MAX_REVISIONS) return fail("Exceeded revision count limit.");

                for (long r = startRev; r <= endRev; r++) {
                    byte[] downloadRequest = ReconciliationMessage.clientDownloadRequest(config.projectId(), id, r).encoded();
                    bytesTransferred += downloadRequest.length;
                    if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                    byte[] downloadResponse = await(session.requestApplication(downloadRequest));
                    bytesTransferred += downloadResponse.length;
                    if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                    ReconciliationMessage resp = ReconciliationMessage.decode(downloadResponse);
                    if (resp.kind() == ReconciliationMessage.Kind.ERROR) {
                        return fail(resp.errorText());
                    }
                    if (resp.kind() != ReconciliationMessage.Kind.CLIENT_DOWNLOAD_RESPONSE || !id.equals(resp.recordId()) || resp.revision() != r) {
                        return fail("Download response mismatch.");
                    }
                    if (resp.status() != 0) {
                        outcomes.add(id + ": REJECTED");
                        break; // sync for this record aborted
                    }
                    // Validate and apply locally
                    DecisionRecord rec;
                    try {
                        rec = DecisionRecord.decode(resp.recordBytes());
                        if (!config.projectId().equals(rec.projectId())
                                || !isValidPeer(rec.ownerNodeId())
                                || !isValidPeer(rec.authorNodeId())
                                || !rec.verify()) {
                            outcomes.add(id + ": REJECTED");
                            break;
                        }
                    } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
                        outcomes.add(id + ": REJECTED");
                        break;
                    }

                    try {
                        SaveResult save = store.applyRemote(rec);
                        if (save == SaveResult.APPLIED) {
                            addedLocal++;
                            outcomes.add(id + ": APPLIED");
                        } else if (save == SaveResult.DUPLICATE) {
                            duplicateCount++;
                            outcomes.add(id + ": DUPLICATE");
                        } else {
                            outcomes.add(id + ": CONFLICT");
                            break; // conflict stops contiguity
                        }
                    } catch (IOException e) {
                        return fail("Storage failed during download apply.");
                    }
                }
            }

            // 6. Exchange Final Inventories & Verify Convergence
            List<DecisionRecord> finalHeads;
            try {
                finalHeads = store.verifiedHeads(MAX_INVENTORY_ENTRIES);
            } catch (IOException e) {
                if (corruptLocalCount == 0) {
                    corruptLocalCount = 1;
                }
                finalHeads = List.of();
            }

            List<InventoryEntry> finalEntries = finalHeads.stream()
                    .map(h -> new InventoryEntry(h.recordId(), h.revision(), h.digest()))
                    .toList();

            int finalChunksCount = (finalEntries.size() + CHUNK_SIZE - 1) / Math.max(1, CHUNK_SIZE);
            if (finalChunksCount == 0) finalChunksCount = 1;

            boolean hostValidatedSuccess = true;
            for (int chunkIndex = 0; chunkIndex < finalChunksCount; chunkIndex++) {
                int from = chunkIndex * CHUNK_SIZE;
                int to = Math.min(finalEntries.size(), from + CHUNK_SIZE);
                List<InventoryEntry> sub = finalEntries.subList(from, to);

                byte[] finalChunkRequest = ReconciliationMessage.finalInventoryChunk(config.projectId(), chunkIndex, finalChunksCount, corruptLocalCount, sub).encoded();
                bytesTransferred += finalChunkRequest.length;
                if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                byte[] finalChunkResponse = await(session.requestApplication(finalChunkRequest));
                bytesTransferred += finalChunkResponse.length;
                if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                ReconciliationMessage finalAck = ReconciliationMessage.decode(finalChunkResponse);
                if (finalAck.kind() == ReconciliationMessage.Kind.ERROR) {
                    return fail(finalAck.errorText());
                }
                if (finalAck.kind() != ReconciliationMessage.Kind.FINAL_INVENTORY_CHUNK_ACK || finalAck.chunkIndex() != chunkIndex) {
                    return fail("Final inventory exchange mismatch.");
                }
                if (finalAck.status() != 0) {
                    hostValidatedSuccess = false;
                }
            }

            // Fetch final host inventory to ensure exact client-side validation
            List<InventoryEntry> finalHostEntries = new ArrayList<>();
            int finalHostCorruptCount = 0;
            currentChunk = 0;
            while (true) {
                byte[] requestFinalChunk = ReconciliationMessage.finalInventoryChunkAck(config.projectId(), currentChunk, 0).encoded();
                bytesTransferred += requestFinalChunk.length;
                if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                byte[] finalChunkResponse = await(session.requestApplication(requestFinalChunk));
                bytesTransferred += finalChunkResponse.length;
                if (bytesTransferred > MAX_SESSION_BYTES) return fail("Exceeded session bytes limit.");

                ReconciliationMessage chunkMsg = ReconciliationMessage.decode(finalChunkResponse);
                if (chunkMsg.kind() == ReconciliationMessage.Kind.ERROR) {
                    return fail(chunkMsg.errorText());
                }
                if (chunkMsg.kind() != ReconciliationMessage.Kind.FINAL_INVENTORY_CHUNK || chunkMsg.chunkIndex() != currentChunk) {
                    return fail("Final inventory exchange out of order.");
                }
                finalHostCorruptCount = chunkMsg.corruptCount();
                finalHostEntries.addAll(chunkMsg.entries());
                if (chunkMsg.chunkIndex() + 1 >= chunkMsg.totalChunks()) {
                    break;
                }
                currentChunk++;
            }

            boolean listsEqual = finalEntries.size() == finalHostEntries.size() && new HashSet<>(finalEntries).containsAll(finalHostEntries);
            boolean success = hostValidatedSuccess && listsEqual && (corruptLocalCount == 0) && (finalHostCorruptCount == 0);

            String hint = success ? "" : "Sync completed but inventories did not fully converge. Check for quarantined conflicts or local corruptions.";
            return new ReconciliationResult(success, outcomes.size(), addedLocal, addedRemote, duplicateCount,
                    corruptLocalCount, finalHostCorruptCount, outcomes, hint);

        } catch (Exception failure) {
            return new ReconciliationResult(false, outcomes.size(), addedLocal, addedRemote, duplicateCount,
                    countCorruptRecords(), 0, outcomes, "Sync session aborted: connection lost or protocol failure.");
        }
    }

    private CompletionStage<byte[]> handle(String remoteNodeId, byte[] payload) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            if (!config.allows(remoteNodeId)) {
                result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.UNAUTHORIZED, "peer not allowed").encoded());
                return result;
            }

            ReconciliationMessage message = ReconciliationMessage.decode(payload);
            switch (message.kind()) {
                case VALIDATE_PROJECT -> {
                    if (!config.projectId().equals(message.projectId())) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.PROJECT_MISMATCH, "project mismatch").encoded());
                    } else {
                        result.complete(ReconciliationMessage.validateProjectAck(config.projectId()).encoded());
                    }
                }
                case INVENTORY_CHUNK_ACK -> {
                    if (!config.projectId().equals(message.projectId())) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.PROJECT_MISMATCH, "project mismatch").encoded());
                        break;
                    }
                    List<DecisionRecord> heads;
                    int corrupt = countCorruptRecords();
                    try {
                        heads = store.verifiedHeads(MAX_INVENTORY_ENTRIES);
                    } catch (IOException e) {
                        corrupt++;
                        heads = List.of();
                    }
                    List<InventoryEntry> entries = heads.stream()
                            .map(h -> new InventoryEntry(h.recordId(), h.revision(), h.digest()))
                            .toList();

                    int chunkIndex = message.chunkIndex();
                    int totalChunks = (entries.size() + CHUNK_SIZE - 1) / Math.max(1, CHUNK_SIZE);
                    if (totalChunks == 0) totalChunks = 1;

                    if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.MALFORMED, "invalid chunk index").encoded());
                    } else {
                        int from = chunkIndex * CHUNK_SIZE;
                        int to = Math.min(entries.size(), from + CHUNK_SIZE);
                        result.complete(ReconciliationMessage.inventoryChunk(config.projectId(), chunkIndex, totalChunks, corrupt, entries.subList(from, to)).encoded());
                    }
                }
                case CLIENT_UPLOAD_REVISION -> {
                    if (!config.projectId().equals(message.projectId())) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.PROJECT_MISMATCH, "project mismatch").encoded());
                        break;
                    }
                    DecisionRecord record;
                    try {
                        record = DecisionRecord.decode(message.recordBytes());
                        if (!config.projectId().equals(record.projectId())
                                || !isValidPeer(record.ownerNodeId())
                                || !isValidPeer(record.authorNodeId())
                                || !record.verify()) {
                            result.complete(ReconciliationMessage.clientUploadAck(config.projectId(), record != null ? record.recordId() : UUID.randomUUID(), 0, RecordStatus.REJECTED.ordinal()).encoded());
                            break;
                        }
                    } catch (Exception failure) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.MALFORMED, "invalid record payload").encoded());
                        break;
                    }

                    try {
                        SaveResult save = store.applyRemote(record);
                        RecordStatus status = switch (save) {
                            case APPLIED -> RecordStatus.APPLIED;
                            case DUPLICATE -> RecordStatus.DUPLICATE;
                            case STALE_BASE -> RecordStatus.REMOTE_STALE;
                            case CONFLICT, CORRUPT -> RecordStatus.CONFLICT;
                        };
                        result.complete(ReconciliationMessage.clientUploadAck(config.projectId(), record.recordId(), record.revision(), status.ordinal()).encoded());
                    } catch (IOException e) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.INTERNAL, "storage failed").encoded());
                    }
                }
                case CLIENT_DOWNLOAD_REQUEST -> {
                    if (!config.projectId().equals(message.projectId())) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.PROJECT_MISMATCH, "project mismatch").encoded());
                        break;
                    }
                    try {
                        Optional<DecisionRecord> opt = store.head(message.recordId());
                        if (opt.isPresent()) {
                            DecisionRecord current = opt.get();
                            if (current.revision() >= message.revision()) {
                                DecisionRecord target = store.readRevision(message.recordId(), message.revision());
                                result.complete(ReconciliationMessage.clientDownloadResponse(config.projectId(), message.recordId(), message.revision(), 0, target.encoded()).encoded());
                            } else {
                                result.complete(ReconciliationMessage.clientDownloadResponse(config.projectId(), message.recordId(), message.revision(), 1, null).encoded());
                            }
                        } else {
                            result.complete(ReconciliationMessage.clientDownloadResponse(config.projectId(), message.recordId(), message.revision(), 1, null).encoded());
                        }
                    } catch (IOException e) {
                        result.complete(ReconciliationMessage.clientDownloadResponse(config.projectId(), message.recordId(), message.revision(), 2, null).encoded());
                    }
                }
                case FINAL_INVENTORY_CHUNK -> {
                    if (!config.projectId().equals(message.projectId())) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.PROJECT_MISMATCH, "project mismatch").encoded());
                        break;
                    }
                    int status = 0; // 0 = converged, 1 = mismatch
                    for (InventoryEntry entry : message.entries()) {
                        try {
                            Optional<DecisionStore.Head> localHead = store.headState(entry.recordId());
                            if (localHead.isEmpty() || localHead.get().revision() != entry.headVersion()
                                    || !Arrays.equals(localHead.get().digest(), entry.headDigest())) {
                                status = 1;
                            }
                        } catch (IOException e) {
                            status = 1;
                        }
                    }
                    result.complete(ReconciliationMessage.finalInventoryChunkAck(config.projectId(), message.chunkIndex(), status).encoded());
                }
                case FINAL_INVENTORY_CHUNK_ACK -> {
                    if (!config.projectId().equals(message.projectId())) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.PROJECT_MISMATCH, "project mismatch").encoded());
                        break;
                    }
                    List<DecisionRecord> heads;
                    int corrupt = countCorruptRecords();
                    try {
                        heads = store.verifiedHeads(MAX_INVENTORY_ENTRIES);
                    } catch (IOException e) {
                        corrupt++;
                        heads = List.of();
                    }
                    List<InventoryEntry> entries = heads.stream()
                            .map(h -> new InventoryEntry(h.recordId(), h.revision(), h.digest()))
                            .toList();

                    int chunkIndex = message.chunkIndex();
                    int totalChunks = (entries.size() + CHUNK_SIZE - 1) / Math.max(1, CHUNK_SIZE);
                    if (totalChunks == 0) totalChunks = 1;

                    if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                        result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.MALFORMED, "invalid chunk index").encoded());
                    } else {
                        int from = chunkIndex * CHUNK_SIZE;
                        int to = Math.min(entries.size(), from + CHUNK_SIZE);
                        result.complete(ReconciliationMessage.finalInventoryChunk(config.projectId(), chunkIndex, totalChunks, corrupt, entries.subList(from, to)).encoded());
                    }
                }
                default -> result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.MALFORMED, "unexpected message kind").encoded());
            }
        } catch (IOException | IllegalArgumentException failure) {
            result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.MALFORMED, "malformed payload").encoded());
        } catch (RuntimeException failure) {
            result.complete(ReconciliationMessage.error(ReconciliationMessage.ErrorCode.INTERNAL, "internal failure").encoded());
        }
        return result;
    }

    private static byte[] await(CompletionStage<byte[]> stage) throws Exception {
        return stage.toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private ReconciliationResult fail(String hint) {
        return new ReconciliationResult(false, 0, 0, 0, 0, countCorruptRecords(), 0, List.of(), hint);
    }

    private boolean isValidPeer(String nodeId) {
        return nodeId.equals(localNodeId) || config.allows(nodeId);
    }

    private int countCorruptRecords() {
        Path root = store.rootPath();
        Path decisions = root.resolve("decisions");
        if (!java.nio.file.Files.isDirectory(decisions)) return 0;
        int corruptCount = 0;
        try (var recordDirs = java.nio.file.Files.list(decisions)) {
            for (Path recordDir : recordDirs.filter(java.nio.file.Files::isDirectory).toList()) {
                try {
                    UUID recordId = UUID.fromString(recordDir.getFileName().toString());
                    Optional<DecisionStore.Head> headOpt = store.headState(recordId);
                    if (headOpt.isEmpty()) {
                        corruptCount++;
                        continue;
                    }
                    DecisionStore.Head head = headOpt.get();
                    byte[] expectedPrevDigest = null;
                    for (long r = 1; r <= head.revision(); r++) {
                        DecisionRecord rec = store.readRevision(recordId, r);
                        if (r == 1) {
                            if (rec.previousDigest() != null) throw new IOException("first revision parent digest not null");
                        } else {
                            if (!Arrays.equals(rec.previousDigest(), expectedPrevDigest)) throw new IOException("broken hash chain");
                        }
                        expectedPrevDigest = rec.digest();
                    }
                    if (!Arrays.equals(head.digest(), expectedPrevDigest)) {
                        throw new IOException("head pointer mismatch");
                    }
                } catch (Exception e) {
                    corruptCount++;
                }
            }
        } catch (IOException e) {
            corruptCount++;
        }
        return corruptCount;
    }
}
