package org.synesis.projectrecord;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.synesis.link.session.PeerSession;

/** One-shot authenticated exchange for the local decision record type. */
public final class ProjectRecordSync {
    /** Deterministic result visible to a one-shot caller. */
    public enum Code {
        /** Exact revision already existed. */ DUPLICATE,
        /** Remote observed a newer local head. */ REMOTE_STALE,
        /** Valid divergent revision was quarantined. */ CONFLICT,
        /** Peer or record failed trust validation. */ REJECTED,
        /** Revision was durably applied. */ APPLIED,
        /** Connection ended before a result arrived. */ UNKNOWN
    }

    /** Immutable one-shot result. */
    public static final class SyncOutcome {
        private final Code code;
        private final UUID recordId;
        private final long revision;
        private final byte[] digest;
        private final String detail;

        private SyncOutcome(Code code, UUID recordId, long revision, byte[] digest, String detail) {
            this.code = Objects.requireNonNull(code, "outcome code");
            this.recordId = recordId;
            this.revision = revision;
            this.digest = digest == null ? null : digest.clone();
            this.detail = detail == null ? "" : detail;
        }

        /** Returns the deterministic result code.
         * @return result code
         */
        public Code code() { return code; }
        /** Returns the stable record identity, when known.
         * @return record identity
         */
        public UUID recordId() { return recordId; }
        /** Returns the observed head revision.
         * @return revision
         */
        public long revision() { return revision; }
        /** Returns the observed head digest, when known.
         * @return digest
         */
        public byte[] digest() { return digest == null ? null : digest.clone(); }
        /** Returns bounded human-readable detail.
         * @return detail
         */
        public String detail() { return detail; }
    }

    private final ProjectConfig config;
    private final DecisionStore store;

    /**
     * Creates one profile-local exchange endpoint.
     *
     * @param config project namespace and explicit peer allowlist
     * @param store immutable local decision store
     */
    public ProjectRecordSync(ProjectConfig config, DecisionStore store) {
        this.config = Objects.requireNonNull(config, "project config");
        this.store = Objects.requireNonNull(store, "decision store");
    }

    /** Returns the Link application callback for this endpoint.
     * @return application callback
     */
    public PeerSession.ApplicationStreamHandler handler() { return this::handle; }

    /**
     * Publishes one local signed decision over one authenticated stream.
     *
     * @param session authenticated, control-ready Link session
     * @param record local signed decision
     * @return completion with a deterministic result or {@link Code#UNKNOWN}
     */
    public SyncOutcome publish(PeerSession session, DecisionRecord record) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(record, "record");
        if (!config.projectId().equals(record.projectId()) || !session.localNodeId().equals(record.ownerNodeId())
                || !session.localNodeId().equals(record.authorNodeId())) {
            return outcome(Code.REJECTED, record, "project mismatch");
        }
        try {
            if (!record.verify()) return outcome(Code.REJECTED, record, "invalid signature");
            DecisionStore.Head base = store.headState(record.recordId()).orElse(null);
            DecisionStore.SaveResult local = store.save(record, base);
            CompletionStage<byte[]> response = session.requestApplication(RecordMessage.record(record.encoded()).encoded());
            return await(response, record.recordId(), session.remoteNodeId());
        } catch (GeneralSecurityException failure) {
            return outcome(Code.REJECTED, record, "invalid signature");
        } catch (Exception failure) {
            return new SyncOutcome(Code.UNKNOWN, record.recordId(), 0, null, "connection closed before result");
        }
    }

    /**
     * Requests one record head from a peer and applies at most one successor.
     *
     * @param session authenticated, control-ready Link session
     * @param recordId stable record identity
     * @return completion with a deterministic result or {@link Code#UNKNOWN}
     */
    public SyncOutcome sync(PeerSession session, UUID recordId) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(recordId, "record ID");
        try {
            DecisionStore.Head head = store.headState(recordId).orElse(null);
            RecordMessage request = RecordMessage.syncRequest(config.projectId(), recordId,
                    head == null ? 0 : head.revision(), head == null ? null : head.digest());
            return await(session.requestApplication(request.encoded()), recordId, session.remoteNodeId());
        } catch (Exception failure) {
            return new SyncOutcome(Code.UNKNOWN, recordId, 0, null, "connection closed before result");
        }
    }

    private CompletionStage<byte[]> handle(String remoteNodeId, byte[] payload) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        try {
            if (!config.allows(remoteNodeId)) {
                result.complete(RecordMessage.error(RecordMessage.ErrorCode.UNAUTHORIZED, "peer not allowed").encoded());
                return result;
            }
            RecordMessage message = RecordMessage.decode(payload);
            switch (message.kind()) {
                case SYNC_REQUEST -> result.complete(handleRequest(message).encoded());
                case RECORD -> result.complete(handleRecord(remoteNodeId, message).encoded());
                case RESULT, ERROR -> result.complete(RecordMessage.error(RecordMessage.ErrorCode.MALFORMED,
                        "unexpected response message").encoded());
            }
        } catch (IOException | IllegalArgumentException failure) {
            result.complete(RecordMessage.error(RecordMessage.ErrorCode.MALFORMED, "malformed message").encoded());
        } catch (RuntimeException failure) {
            result.complete(RecordMessage.error(RecordMessage.ErrorCode.STORAGE_FAILURE, "storage failure").encoded());
        }
        return result;
    }

    private RecordMessage handleRequest(RecordMessage request) throws IOException {
        if (!config.projectId().equals(request.projectId())) {
            return RecordMessage.error(RecordMessage.ErrorCode.PROJECT_MISMATCH, "project mismatch");
        }
        DecisionRecord current = store.head(request.recordId()).orElse(null);
        if (current == null) return RecordMessage.error(RecordMessage.ErrorCode.NOT_FOUND, "record not found");
        if (request.knownRevision() == current.revision()
                && Arrays.equals(request.knownDigest(), current.digest())) {
            return RecordMessage.result(RecordMessage.ResultCode.DUPLICATE, current.recordId(), current.revision(), current.digest());
        }
        if (current.revision() > request.knownRevision()) return RecordMessage.record(current.encoded());
        if (current.revision() == request.knownRevision()) {
            return RecordMessage.result(RecordMessage.ResultCode.CONFLICT, current.recordId(), current.revision(), current.digest());
        }
        return RecordMessage.result(RecordMessage.ResultCode.REMOTE_STALE, current.recordId(), current.revision(), current.digest());
    }

    private RecordMessage handleRecord(String remoteNodeId, RecordMessage message) throws IOException {
        DecisionRecord record;
        try {
            record = DecisionRecord.decode(message.recordBytes());
            if (!config.projectId().equals(record.projectId())
                    || !remoteNodeId.equals(record.ownerNodeId())
                    || !remoteNodeId.equals(record.authorNodeId())
                    || !record.verify()) {
                return RecordMessage.result(RecordMessage.ResultCode.REJECTED, record.recordId(), 0, null);
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException failure) {
            return RecordMessage.error(RecordMessage.ErrorCode.INVALID_RECORD, "invalid record");
        }
        DecisionStore.SaveResult applied = store.applyRemote(record);
        DecisionStore.Head head = store.headState(record.recordId()).orElse(null);
        return RecordMessage.result(mapResult(applied), record.recordId(), head == null ? 0 : head.revision(),
                head == null ? null : head.digest());
    }

    private SyncOutcome await(CompletionStage<byte[]> stage, UUID recordId, String remoteNodeId) {
        try {
            RecordMessage response = RecordMessage.decode(stage.toCompletableFuture().get());
            if (response.kind() == RecordMessage.Kind.ERROR) {
                return new SyncOutcome(Code.REJECTED, recordId, 0, null, response.errorText());
            }
            if (response.kind() == RecordMessage.Kind.RECORD) {
                DecisionRecord record = DecisionRecord.decode(response.recordBytes());
                if (!config.projectId().equals(record.projectId())
                        || !remoteNodeId.equals(record.ownerNodeId())
                        || !remoteNodeId.equals(record.authorNodeId())
                        || !record.verify()) {
                    return new SyncOutcome(Code.REJECTED, recordId, 0, null, "invalid remote record");
                }
                DecisionStore.SaveResult saved = store.applyRemote(record);
                DecisionStore.Head head = store.headState(record.recordId()).orElse(null);
                return new SyncOutcome(map(saved), record.recordId(), head == null ? 0 : head.revision(),
                        head == null ? null : head.digest(), "");
            }
            if (response.kind() != RecordMessage.Kind.RESULT) return new SyncOutcome(Code.REJECTED, recordId, 0, null, "unexpected response");
            return new SyncOutcome(response.resultCode() == null ? Code.REJECTED : Code.valueOf(response.resultCode().name()),
                    response.recordId(), response.revision(), response.digest(), "");
        } catch (GeneralSecurityException failure) {
            return new SyncOutcome(Code.REJECTED, recordId, 0, null, "invalid remote record");
        } catch (Exception failure) {
            return new SyncOutcome(Code.UNKNOWN, recordId, 0, null, "connection closed before result");
        }
    }

    private static SyncOutcome outcome(Code code, DecisionRecord record, String detail) {
        return new SyncOutcome(code, record.recordId(), record.revision(), record.digest(), detail);
    }

    private static Code map(DecisionStore.SaveResult result) {
        return switch (result) {
            case APPLIED -> Code.APPLIED;
            case DUPLICATE -> Code.DUPLICATE;
            case STALE_BASE -> Code.REMOTE_STALE;
            case CONFLICT -> Code.CONFLICT;
            case CORRUPT -> Code.REJECTED;
        };
    }

    private static RecordMessage.ResultCode mapResult(DecisionStore.SaveResult result) {
        return switch (result) {
            case APPLIED -> RecordMessage.ResultCode.APPLIED;
            case DUPLICATE -> RecordMessage.ResultCode.DUPLICATE;
            case STALE_BASE -> RecordMessage.ResultCode.REMOTE_STALE;
            case CONFLICT -> RecordMessage.ResultCode.CONFLICT;
            case CORRUPT -> RecordMessage.ResultCode.REJECTED;
        };
    }
}
