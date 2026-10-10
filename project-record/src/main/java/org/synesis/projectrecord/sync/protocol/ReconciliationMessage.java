package org.synesis.projectrecord.sync.protocol;

import static org.synesis.projectrecord.sync.protocol.WireFields.readBytes;
import static org.synesis.projectrecord.sync.protocol.WireFields.readText;
import static org.synesis.projectrecord.sync.protocol.WireFields.readUuid;
import static org.synesis.projectrecord.sync.protocol.WireFields.writeBytes;
import static org.synesis.projectrecord.sync.protocol.WireFields.writeText;
import static org.synesis.projectrecord.sync.protocol.WireFields.writeUuid;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
  static final int MAX_BYTES = 4_096;
  /**
   * Download response status: the requested revision follows.
   */
  public static final int DOWNLOAD_OK = 0;
  /**
   * Download response status: the host has no such revision.
   */
  public static final int DOWNLOAD_NOT_FOUND = 1;
  /**
   * Download response status: the host failed to read the revision.
   */
  public static final int DOWNLOAD_IO_FAILURE = 2;
  /**
   * Final inventory ack status: every listed head matches the host.
   */
  public static final int FINAL_CONVERGED = 0;
  /**
   * Final inventory ack status: at least one listed head differs from the host.
   */
  public static final int FINAL_MISMATCH = 1;
  private static final int MAGIC = 0x50525031;
  private static final int VERSION = 1;
  private static final int MAX_ENTRIES = 100;
  private static final int MAX_TEXT_BYTES = 256;
  private static final String INVALID_BYTES = "invalid payload bytes length";
  private final Kind kind;
  private final UUID projectId;
  private final int chunkIndex;
  private final int totalChunks;
  private final int corruptCount;
  private final List<InventoryEntry> entries;
  private final byte[] recordBytes;
  private final UUID recordId;
  private final long revision;
  private final int status; // download or final-ack status, or an upload result ordinal
  private final ErrorCode errorCode;
  private final String errorText;

  private ReconciliationMessage(Kind kind, UUID projectId, int chunkIndex, int totalChunks,
      int corruptCount,
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

  /** Project id only. */
  private ReconciliationMessage(Kind kind, UUID projectId) {
    this(kind, Objects.requireNonNull(projectId), 0, 0, 0, null, null, null, 0, 0, null, null);
  }

  /** Project id, chunk index and status. */
  private ReconciliationMessage(Kind kind, UUID projectId, int chunkIndex, int status) {
    this(kind, Objects.requireNonNull(projectId), chunkIndex, 0, 0, null, null, null, 0, status,
        null, null);
  }

  /** Inventory chunk. */
  private ReconciliationMessage(Kind kind, UUID projectId, int chunkIndex, int totalChunks,
      int corruptCount, List<InventoryEntry> entries) {
    this(kind, Objects.requireNonNull(projectId), chunkIndex, totalChunks, corruptCount, entries,
        null, null, 0, 0, null, null);
  }

  /** Project id and record bytes. */
  private ReconciliationMessage(Kind kind, UUID projectId, byte[] recordBytes) {
    this(kind, Objects.requireNonNull(projectId), 0, 0, 0, null, recordBytes, null, 0, 0, null,
        null);
  }

  /** A record revision, with its status and optional record bytes. */
  private ReconciliationMessage(Kind kind, UUID projectId, UUID recordId, long revision,
      int status, byte[] recordBytes) {
    this(kind, Objects.requireNonNull(projectId), 0, 0, 0, null, recordBytes,
        Objects.requireNonNull(recordId), revision, status, null, null);
  }

  /** Protocol error. */
  private ReconciliationMessage(ErrorCode errorCode, String errorText) {
    this(Kind.ERROR, null, 0, 0, 0, null, null, null, 0, 0, Objects.requireNonNull(errorCode),
        Objects.requireNonNull(errorText));
  }

  /**
   * Creates a project validation request.
   *
   * @param projectId project identifier
   * @return a validate project message
   */
  public static ReconciliationMessage validateProject(UUID projectId) {
    return new ReconciliationMessage(Kind.VALIDATE_PROJECT, projectId);
  }

  /**
   * Creates a project validation ack.
   *
   * @param projectId project identifier
   * @return a validate project ack message
   */
  public static ReconciliationMessage validateProjectAck(UUID projectId) {
    return new ReconciliationMessage(Kind.VALIDATE_PROJECT_ACK, projectId);
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
  public static ReconciliationMessage inventoryChunk(UUID projectId,
      int chunkIndex,
      int totalChunks,
      int corruptCount,
      List<InventoryEntry> entries) {
    return new ReconciliationMessage(Kind.INVENTORY_CHUNK, projectId, chunkIndex, totalChunks,
        corruptCount, entries);
  }

  /**
   * Creates an inventory chunk ack message.
   *
   * @param projectId  project identifier
   * @param chunkIndex index of chunk acknowledged
   * @return an inventory chunk ack message
   */
  public static ReconciliationMessage inventoryChunkAck(UUID projectId, int chunkIndex) {
    return new ReconciliationMessage(Kind.INVENTORY_CHUNK_ACK, projectId, chunkIndex, 0);
  }

  /**
   * Creates a client upload revision message.
   *
   * @param projectId   project identifier
   * @param recordBytes raw decision record bytes
   * @return a client upload revision message
   */
  public static ReconciliationMessage clientUploadRevision(UUID projectId, byte[] recordBytes) {
    return new ReconciliationMessage(Kind.CLIENT_UPLOAD_REVISION, projectId, recordBytes);
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
  public static ReconciliationMessage clientUploadAck(UUID projectId, UUID recordId, long revision,
      int resultCode) {
    return new ReconciliationMessage(Kind.CLIENT_UPLOAD_ACK, projectId, recordId, revision,
        resultCode, null);
  }

  /**
   * Creates a client download request message.
   *
   * @param projectId project identifier
   * @param recordId  record identifier
   * @param revision  revision number
   * @return a client download request message
   */
  public static ReconciliationMessage clientDownloadRequest(UUID projectId, UUID recordId,
      long revision) {
    return new ReconciliationMessage(Kind.CLIENT_DOWNLOAD_REQUEST, projectId, recordId, revision,
        0, null);
  }

  /**
   * Creates a client download response message.
   *
   * @param projectId   project identifier
   * @param recordId    record identifier
   * @param revision    revision number
   * @param status      {@link #DOWNLOAD_OK}, {@link #DOWNLOAD_NOT_FOUND} or
   *                    {@link #DOWNLOAD_IO_FAILURE}
   * @param recordBytes raw decision record bytes
   * @return a client download response message
   */
  public static ReconciliationMessage clientDownloadResponse(UUID projectId,
      UUID recordId,
      long revision,
      int status,
      byte[] recordBytes) {
    return new ReconciliationMessage(Kind.CLIENT_DOWNLOAD_RESPONSE, projectId, recordId, revision,
        status, recordBytes);
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
  public static ReconciliationMessage finalInventoryChunk(UUID projectId,
      int chunkIndex,
      int totalChunks,
      int corruptCount,
      List<InventoryEntry> entries) {
    return new ReconciliationMessage(Kind.FINAL_INVENTORY_CHUNK, projectId, chunkIndex,
        totalChunks, corruptCount, entries);
  }

  /**
   * Creates a final inventory chunk ack message.
   *
   * @param projectId  project identifier
   * @param chunkIndex index of chunk acknowledged
   * @param status     {@link #FINAL_CONVERGED} or {@link #FINAL_MISMATCH}
   * @return a final inventory chunk ack message
   */
  public static ReconciliationMessage finalInventoryChunkAck(UUID projectId, int chunkIndex,
      int status) {
    return new ReconciliationMessage(Kind.FINAL_INVENTORY_CHUNK_ACK, projectId, chunkIndex,
        status);
  }

  /**
   * Creates a protocol error message.
   *
   * @param errorCode category of error
   * @param text      human-readable text
   * @return an error message
   */
  public static ReconciliationMessage error(ErrorCode errorCode, String text) {
    return new ReconciliationMessage(errorCode, text);
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
    if (bytes.length < 6 || bytes.length > MAX_BYTES) {
      throw new IOException("message exceeds bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
        throw new IOException("unsupported protocol magic or version");
      }
      int kindCode = input.readUnsignedByte();
      if (kindCode >= Kind.values().length) {
        throw new IOException("unknown kind code");
      }
      Kind kind = Kind.values()[kindCode];
      UUID project = null;
      if (kind != Kind.ERROR) {
        project = readUuid(input);
      }
      ReconciliationMessage msg = switch (kind) {
        case VALIDATE_PROJECT -> validateProject(project);
        case VALIDATE_PROJECT_ACK -> validateProjectAck(project);
        case INVENTORY_CHUNK -> readInventoryChunk(input, kind, project,
            "invalid inventory entry count");
        case INVENTORY_CHUNK_ACK -> {
          int chunk = input.readInt();
          yield inventoryChunkAck(project, chunk);
        }
        case CLIENT_UPLOAD_REVISION -> {
          byte[] recBytes = readBytes(input, INVALID_BYTES);
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
          byte[] recBytes = marker == 1 ? readBytes(input, INVALID_BYTES) : null;
          yield clientDownloadResponse(project, rec, rev, stat, recBytes);
        }
        case FINAL_INVENTORY_CHUNK -> readInventoryChunk(input, kind, project,
            "invalid final inventory entry count");
        case FINAL_INVENTORY_CHUNK_ACK -> {
          int chunk = input.readInt();
          int stat = input.readInt();
          yield finalInventoryChunkAck(project, chunk, stat);
        }
        case ERROR -> {
          int errCode = input.readUnsignedByte();
          if (errCode >= ErrorCode.values().length) {
            throw new IOException("unknown error code");
          }
          String text = readText(input, MAX_TEXT_BYTES, INVALID_BYTES);
          yield error(ErrorCode.values()[errCode], text);
        }
      };
      if (input.available() != 0) {
        throw new IOException("trailing message bytes");
      }
      return msg;
    } catch (java.io.EOFException | IllegalArgumentException exception) {
      throw new IOException("malformed message payload", exception);
    }
  }

  private static ReconciliationMessage readInventoryChunk(DataInputStream input, Kind kind,
      UUID project, String invalidCountMessage) throws IOException {
    int chunk = input.readInt();
    int total = input.readInt();
    int corrupt = input.readInt();
    int count = input.readInt();
    if (count < 0 || count > MAX_ENTRIES) {
      throw new IOException(invalidCountMessage);
    }
    List<InventoryEntry> list = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      UUID rec = readUuid(input);
      long ver = input.readLong();
      byte[] dig = input.readNBytes(32);
      list.add(new InventoryEntry(rec, ver, dig));
    }
    return new ReconciliationMessage(kind, project, chunk, total, corrupt, list);
  }

  private void writeInventoryChunk(DataOutputStream output) throws IOException {
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
   * Returns the download status, final-ack status, or upload result ordinal, by message kind.
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
          case INVENTORY_CHUNK, FINAL_INVENTORY_CHUNK -> writeInventoryChunk(output);
          case INVENTORY_CHUNK_ACK -> output.writeInt(chunkIndex);
          case FINAL_INVENTORY_CHUNK_ACK -> {
            output.writeInt(chunkIndex);
            output.writeInt(status);
          }
          case CLIENT_UPLOAD_REVISION -> writeBytes(output, recordBytes, INVALID_BYTES);
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
              writeBytes(output, recordBytes, INVALID_BYTES);
            }
          }
          case ERROR -> {
            output.writeByte(errorCode.ordinal());
            writeText(output, errorText, MAX_TEXT_BYTES, INVALID_BYTES);
          }
        }
      }
      if (bytes.size() > MAX_BYTES) {
        throw new IllegalArgumentException("message size exceeds bounds limit");
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

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
      if (headVersion <= 0) {
        throw new IllegalArgumentException("invalid version");
      }
      Objects.requireNonNull(headDigest, "headDigest");
      if (headDigest.length != 32) {
        throw new IllegalArgumentException("invalid digest");
      }
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof InventoryEntry(
          UUID otherRecordId, long otherHeadVersion,
          byte[] otherHeadDigest
      ))) {
        return false;
      }
      return headVersion == otherHeadVersion && recordId.equals(otherRecordId)
          && Arrays.equals(headDigest, otherHeadDigest);
    }

    @Override
    public int hashCode() {
      int result = Objects.hash(recordId, headVersion);
      result = 31 * result + Arrays.hashCode(headDigest);
      return result;
    }
  }
}
