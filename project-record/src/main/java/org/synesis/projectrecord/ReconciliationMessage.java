package org.synesis.projectrecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded Project Reconciliation Protocol (PRP1) message codec.
 */
public final class ReconciliationMessage {
    /**
     * Maximum application payload allowed.
     */
    public static final int MAX_BYTES = 4_096;
    /**
     * Magic prefix for PRP1.
     */
    public static final int MAGIC = 0x50525031;
    /**
     * Protocol version.
     */
    public static final int VERSION = 1;

    /**
     * Message kinds.
     */
    public enum Kind {
        /**
         * Project validation request.
         */
        VALIDATE_PROJECT,
        /**
         * Project validation acknowledgement.
         */
        VALIDATE_PROJECT_ACK,
        /**
         * Swapping validated head inventory chunk.
         */
        INVENTORY_CHUNK,
        /**
         * Acknowledgement of inventory chunk.
         */
        INVENTORY_CHUNK_ACK,
        /**
         * Uploading one decision record revision.
         */
        CLIENT_UPLOAD_REVISION,
        /**
         * Upload acknowledgement with outcome.
         */
        CLIENT_UPLOAD_ACK,
        /**
         * Requesting download of a record revision.
         */
        CLIENT_DOWNLOAD_REQUEST,
        /**
         * Host response carrying revision.
         */
        CLIENT_DOWNLOAD_RESPONSE,
        /**
         * Final inventory verification chunk.
         */
        FINAL_INVENTORY_CHUNK,
        /**
         * Final inventory verification chunk ack.
         */
        FINAL_INVENTORY_CHUNK_ACK,
        /**
         * Reconciliation protocol error.
         */
        ERROR
    }

    /**
     * Error code categories.
     */
    public enum ErrorCode {
        /**
         * Malformed message payload.
         */
        MALFORMED,
        /**
         * Peer unauthorized.
         */
        UNAUTHORIZED,
        /**
         * Project ID mismatch.
         */
        PROJECT_MISMATCH,
        /**
         * General internal error.
         */
        INTERNAL
    }

    /**
     * Single validated head entry.
     *
     * @param recordId    unique decision record identifier
     * @param headVersion latest head version number
     * @param headDigest  SHA-256 digest of head revision
     */
    public record InventoryEntry(UUID recordId, long headVersion, byte[] headDigest) {
        /**
         * Creates a validated inventory entry.
         */
        public InventoryEntry {
            Objects.requireNonNull(recordId, "recordId");
            if (headVersion <= 0) throw new IllegalArgumentException("invalid version");
            Objects.requireNonNull(headDigest, "headDigest");
            if (headDigest.length != 32) throw new IllegalArgumentException("invalid digest");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InventoryEntry other)) return false;
            return headVersion == other.headVersion && recordId.equals(other.recordId) && Arrays.equals(headDigest, other.headDigest);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(recordId, headVersion);
            result = 31 * result + Arrays.hashCode(headDigest);
            return result;
        }
    }

    private final Kind kind;
    private final UUID projectId;
    private final int chunkIndex;
    private final int totalChunks;
    private final int corruptCount;
    private final List<InventoryEntry> entries;
    private final byte[] recordBytes;
    private final UUID recordId;
    private final long revision;
    private final int status; // status code or result code ordinal
    private final ErrorCode errorCode;
    private final String errorText;

    private ReconciliationMessage(Kind kind, UUID projectId, int chunkIndex, int totalChunks, int corruptCount,
                                  List<InventoryEntry> entries, byte[] recordBytes, UUID recordId, long revision, int status,
                                  ErrorCode errorCode, String errorText) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.projectId = projectId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.corruptCount = corruptCount;
        this.entries = entries == null ? null : List.copyOf(entries);
        this.recordBytes = recordBytes == null ? null : recordBytes.clone();
        this.recordId = recordId;
        this.revision = revision;
        this.status = status;
        this.errorCode = errorCode;
        this.errorText = errorText;
    }

    /**
     * Creates a project validation request.
     *
     * @param projectId project identifier
     * @return a validate project message
     */
    public static ReconciliationMessage validateProject(UUID projectId) {
        return new ReconciliationMessage(Kind.VALIDATE_PROJECT, Objects.requireNonNull(projectId), 0, 0, 0, null, null, null, 0, 0, null, null);
    }

    /**
     * Creates a project validation ack.
     *
     * @param projectId project identifier
     * @return a validate project ack message
     */
    public static ReconciliationMessage validateProjectAck(UUID projectId) {
        return new ReconciliationMessage(Kind.VALIDATE_PROJECT_ACK, Objects.requireNonNull(projectId), 0, 0, 0, null, null, null, 0, 0, null, null);
    }

    /**
     * Creates an inventory chunk message.
     *
     * @param projectId    project identifier
     * @param chunkIndex   index of current chunk
     * @param totalChunks  total number of chunks
     * @param corruptCount count of corrupt records
     * @param entries      list of inventory entries
     * @return an inventory chunk message
     */
    public static ReconciliationMessage inventoryChunk(UUID projectId, int chunkIndex, int totalChunks, int corruptCount, List<InventoryEntry> entries) {
        return new ReconciliationMessage(Kind.INVENTORY_CHUNK, Objects.requireNonNull(projectId), chunkIndex, totalChunks, corruptCount, entries, null, null, 0, 0, null, null);
    }

    /**
     * Creates an inventory chunk ack message.
     *
     * @param projectId  project identifier
     * @param chunkIndex index of chunk acknowledged
     * @return an inventory chunk ack message
     */
    public static ReconciliationMessage inventoryChunkAck(UUID projectId, int chunkIndex) {
        return new ReconciliationMessage(Kind.INVENTORY_CHUNK_ACK, Objects.requireNonNull(projectId), chunkIndex, 0, 0, null, null, null, 0, 0, null, null);
    }

    /**
     * Creates a client upload revision message.
     *
     * @param projectId   project identifier
     * @param recordBytes raw decision record bytes
     * @return a client upload revision message
     */
    public static ReconciliationMessage clientUploadRevision(UUID projectId, byte[] recordBytes) {
        return new ReconciliationMessage(Kind.CLIENT_UPLOAD_REVISION, Objects.requireNonNull(projectId), 0, 0, 0, null, recordBytes, null, 0, 0, null, null);
    }

    /**
     * Creates a client upload ack message.
     *
     * @param projectId  project identifier
     * @param recordId   record identifier
     * @param revision   revision number
     * @param resultCode upload result code
     * @return a client upload ack message
     */
    public static ReconciliationMessage clientUploadAck(UUID projectId, UUID recordId, long revision, int resultCode) {
        return new ReconciliationMessage(Kind.CLIENT_UPLOAD_ACK, Objects.requireNonNull(projectId), 0, 0, 0, null, null, Objects.requireNonNull(recordId), revision, resultCode, null, null);
    }

    /**
     * Creates a client download request message.
     *
     * @param projectId project identifier
     * @param recordId  record identifier
     * @param revision  revision number
     * @return a client download request message
     */
    public static ReconciliationMessage clientDownloadRequest(UUID projectId, UUID recordId, long revision) {
        return new ReconciliationMessage(Kind.CLIENT_DOWNLOAD_REQUEST, Objects.requireNonNull(projectId), 0, 0, 0, null, null, Objects.requireNonNull(recordId), revision, 0, null, null);
    }

    /**
     * Creates a client download response message.
     *
     * @param projectId   project identifier
     * @param recordId    record identifier
     * @param revision    revision number
     * @param status      status code
     * @param recordBytes raw decision record bytes
     * @return a client download response message
     */
    public static ReconciliationMessage clientDownloadResponse(UUID projectId, UUID recordId, long revision, int status, byte[] recordBytes) {
        return new ReconciliationMessage(Kind.CLIENT_DOWNLOAD_RESPONSE, Objects.requireNonNull(projectId), 0, 0, 0, null, recordBytes, Objects.requireNonNull(recordId), revision, status, null, null);
    }

    /**
     * Creates a final inventory chunk message.
     *
     * @param projectId    project identifier
     * @param chunkIndex   index of current chunk
     * @param totalChunks  total number of chunks
     * @param corruptCount count of corrupt records
     * @param entries      list of inventory entries
     * @return a final inventory chunk message
     */
    public static ReconciliationMessage finalInventoryChunk(UUID projectId, int chunkIndex, int totalChunks, int corruptCount, List<InventoryEntry> entries) {
        return new ReconciliationMessage(Kind.FINAL_INVENTORY_CHUNK, Objects.requireNonNull(projectId), chunkIndex, totalChunks, corruptCount, entries, null, null, 0, 0, null, null);
    }

    /**
     * Creates a final inventory chunk ack message.
     *
     * @param projectId  project identifier
     * @param chunkIndex index of chunk acknowledged
     * @param status     status code
     * @return a final inventory chunk ack message
     */
    public static ReconciliationMessage finalInventoryChunkAck(UUID projectId, int chunkIndex, int status) {
        return new ReconciliationMessage(Kind.FINAL_INVENTORY_CHUNK_ACK, Objects.requireNonNull(projectId), chunkIndex, 0, 0, null, null, null, 0, status, null, null);
    }

    /**
     * Creates a protocol error message.
     *
     * @param errorCode category of error
     * @param text      human-readable text
     * @return an error message
     */
    public static ReconciliationMessage error(ErrorCode errorCode, String text) {
        return new ReconciliationMessage(Kind.ERROR, null, 0, 0, 0, null, null, null, 0, 0, Objects.requireNonNull(errorCode), Objects.requireNonNull(text));
    }

    /**
     * Returns kind.
     *
     * @return message kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns project ID.
     *
     * @return project identifier
     */
    public UUID projectId() {
        return projectId;
    }

    /**
     * Returns chunk index.
     *
     * @return chunk index
     */
    public int chunkIndex() {
        return chunkIndex;
    }

    /**
     * Returns total chunks.
     *
     * @return total chunks
     */
    public int totalChunks() {
        return totalChunks;
    }

    /**
     * Returns corrupt records count.
     *
     * @return corrupt count
     */
    public int corruptCount() {
        return corruptCount;
    }

    /**
     * Returns inventory entries.
     *
     * @return list of inventory entries
     */
    public List<InventoryEntry> entries() {
        return entries;
    }

    /**
     * Returns record bytes.
     *
     * @return raw record bytes
     */
    public byte[] recordBytes() {
        return recordBytes == null ? null : recordBytes.clone();
    }

    /**
     * Returns record ID.
     *
     * @return record identifier
     */
    public UUID recordId() {
        return recordId;
    }

    /**
     * Returns revision.
     *
     * @return revision number
     */
    public long revision() {
        return revision;
    }

    /**
     * Returns status/result code ordinal.
     *
     * @return status or result code
     */
    public int status() {
        return status;
    }

    /**
     * Returns error code.
     *
     * @return error category
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Returns human-readable error text.
     *
     * @return error text
     */
    public String errorText() {
        return errorText;
    }

    /**
     * Encodes this message into bounded binary form.
     *
     * @return encoded binary payload
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                output.writeByte(kind.ordinal());
                if (kind != Kind.ERROR) {
                    writeUuid(output, projectId);
                }
                switch (kind) {
                    case VALIDATE_PROJECT, VALIDATE_PROJECT_ACK -> {
                    }
                    case INVENTORY_CHUNK, FINAL_INVENTORY_CHUNK -> {
                        output.writeInt(chunkIndex);
                        output.writeInt(totalChunks);
                        output.writeInt(corruptCount);
                        output.writeInt(entries.size());
                        for (InventoryEntry entry : entries) {
                            writeUuid(output, entry.recordId());
                            output.writeLong(entry.headVersion());
                            output.write(entry.headDigest());
                        }
                    }
                    case INVENTORY_CHUNK_ACK -> {
                        output.writeInt(chunkIndex);
                    }
                    case FINAL_INVENTORY_CHUNK_ACK -> {
                        output.writeInt(chunkIndex);
                        output.writeInt(status);
                    }
                    case CLIENT_UPLOAD_REVISION -> {
                        writeBytes(output, recordBytes);
                    }
                    case CLIENT_UPLOAD_ACK -> {
                        writeUuid(output, recordId);
                        output.writeLong(revision);
                        output.writeInt(status);
                    }
                    case CLIENT_DOWNLOAD_REQUEST -> {
                        writeUuid(output, recordId);
                        output.writeLong(revision);
                    }
                    case CLIENT_DOWNLOAD_RESPONSE -> {
                        writeUuid(output, recordId);
                        output.writeLong(revision);
                        output.writeInt(status);
                        output.writeByte(recordBytes == null ? 0 : 1);
                        if (recordBytes != null) {
                            writeBytes(output, recordBytes);
                        }
                    }
                    case ERROR -> {
                        output.writeByte(errorCode.ordinal());
                        writeText(output, errorText, 256);
                    }
                }
            }
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("message size exceeds bounds limit");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Decodes a message from binary form.
     *
     * @param bytes raw binary payload
     * @return decoded message
     * @throws IOException if binary payload is invalid or malformed
     */
    public static ReconciliationMessage decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < 6 || bytes.length > MAX_BYTES) throw new IOException("message exceeds bound");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("unsupported protocol magic or version");
            }
            int kindCode = input.readUnsignedByte();
            if (kindCode >= Kind.values().length) throw new IOException("unknown kind code");
            Kind kind = Kind.values()[kindCode];
            UUID project = null;
            if (kind != Kind.ERROR) {
                project = readUuid(input);
            }
            ReconciliationMessage msg = switch (kind) {
                case VALIDATE_PROJECT -> validateProject(project);
                case VALIDATE_PROJECT_ACK -> validateProjectAck(project);
                case INVENTORY_CHUNK -> {
                    int chunk = input.readInt();
                    int total = input.readInt();
                    int corrupt = input.readInt();
                    int count = input.readInt();
                    if (count < 0 || count > 100) throw new IOException("invalid inventory entry count");
                    List<InventoryEntry> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        UUID rec = readUuid(input);
                        long ver = input.readLong();
                        byte[] dig = input.readNBytes(32);
                        list.add(new InventoryEntry(rec, ver, dig));
                    }
                    yield inventoryChunk(project, chunk, total, corrupt, list);
                }
                case INVENTORY_CHUNK_ACK -> {
                    int chunk = input.readInt();
                    yield inventoryChunkAck(project, chunk);
                }
                case CLIENT_UPLOAD_REVISION -> {
                    byte[] recBytes = readBytes(input);
                    yield clientUploadRevision(project, recBytes);
                }
                case CLIENT_UPLOAD_ACK -> {
                    UUID rec = readUuid(input);
                    long rev = input.readLong();
                    int res = input.readInt();
                    yield clientUploadAck(project, rec, rev, res);
                }
                case CLIENT_DOWNLOAD_REQUEST -> {
                    UUID rec = readUuid(input);
                    long rev = input.readLong();
                    yield clientDownloadRequest(project, rec, rev);
                }
                case CLIENT_DOWNLOAD_RESPONSE -> {
                    UUID rec = readUuid(input);
                    long rev = input.readLong();
                    int stat = input.readInt();
                    int marker = input.readUnsignedByte();
                    byte[] recBytes = marker == 1 ? readBytes(input) : null;
                    yield clientDownloadResponse(project, rec, rev, stat, recBytes);
                }
                case FINAL_INVENTORY_CHUNK -> {
                    int chunk = input.readInt();
                    int total = input.readInt();
                    int corrupt = input.readInt();
                    int count = input.readInt();
                    if (count < 0 || count > 100) throw new IOException("invalid final inventory entry count");
                    List<InventoryEntry> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        UUID rec = readUuid(input);
                        long ver = input.readLong();
                        byte[] dig = input.readNBytes(32);
                        list.add(new InventoryEntry(rec, ver, dig));
                    }
                    yield finalInventoryChunk(project, chunk, total, corrupt, list);
                }
                case FINAL_INVENTORY_CHUNK_ACK -> {
                    int chunk = input.readInt();
                    int stat = input.readInt();
                    yield finalInventoryChunkAck(project, chunk, stat);
                }
                case ERROR -> {
                    int errCode = input.readUnsignedByte();
                    if (errCode >= ErrorCode.values().length) throw new IOException("unknown error code");
                    String text = readText(input, 256);
                    yield error(ErrorCode.values()[errCode], text);
                }
            };
            if (input.available() != 0) throw new IOException("trailing message bytes");
            return msg;
        } catch (java.io.EOFException | IllegalArgumentException exception) {
            throw new IOException("malformed message payload", exception);
        }
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > input.available()) throw new IOException("invalid payload bytes length");
        return input.readNBytes(length);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value == null || value.length == 0 || value.length > 65_535) {
            throw new IllegalArgumentException("invalid payload bytes length");
        }
        output.writeShort(value.length);
        output.write(value);
    }

    private static String readText(DataInputStream input, int max) throws IOException {
        byte[] bytes = readBytes(input);
        if (bytes.length > max) throw new IOException("text exceeds bound");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("invalid UTF-8", exception);
        }
    }

    private static void writeText(DataOutputStream output, String value, int max) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) throw new IllegalArgumentException("text exceeds bound");
        writeBytes(output, bytes);
    }
}
