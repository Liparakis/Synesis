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
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Strict bounded CP-R4 one-shot message: request, record, result, or error.
 */
public final class RecordMessage {

  /**
   * Maximum application payload accepted by the message codec.
   */
  static final int MAX_BYTES = 4_096;
  /**
   * Maximum safe error text size.
   */
  static final int MAX_ERROR_TEXT_BYTES = 256;
  private static final int MAGIC = 0x53525031;
  private static final int VERSION = 1;
  private static final String INVALID_BYTES = "invalid message bytes";
  private final Kind kind;
  private final UUID projectId;
  private final UUID recordId;
  private final long knownRevision;
  private final byte[] knownDigest;
  private final byte[] recordBytes;
  private final ResultCode resultCode;
  private final ErrorCode errorCode;
  private final String errorText;
  private final long revision;
  private final byte[] digest;

  private RecordMessage(Kind kind, UUID projectId, UUID recordId, long knownRevision,
      byte[] knownDigest,
      byte[] recordBytes, ResultCode resultCode, ErrorCode errorCode, String errorText,
      long revision,
      byte[] digest) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.projectId = projectId;
    this.recordId = recordId;
    this.knownRevision = knownRevision;
    this.knownDigest = knownDigest == null ? null : knownDigest.clone();
    this.recordBytes = recordBytes == null ? null : recordBytes.clone();
    this.resultCode = resultCode;
    this.errorCode = errorCode;
    this.errorText = errorText;
    this.revision = revision;
    this.digest = digest == null ? null : digest.clone();
  }

  /**
   * Creates a request for a record head newer than the supplied known state.
   *
   * @param projectId     project namespace
   * @param recordId      stable record identity
   * @param knownRevision caller's known revision
   * @param knownDigest   caller's known digest, or null at revision zero
   * @return bounded request message
   */
  public static RecordMessage syncRequest(UUID projectId, UUID recordId, long knownRevision,
      byte[] knownDigest) {
    if (knownRevision < 0 || (knownRevision == 0) != (knownDigest == null)
        || (knownDigest != null && knownDigest.length != 32)) {
      throw new IllegalArgumentException("invalid known head");
    }
    return new RecordMessage(Kind.SYNC_REQUEST,
        Objects.requireNonNull(projectId, "project ID"),
        Objects.requireNonNull(recordId, "record ID"),
        knownRevision,
        knownDigest,
        null,
        null,
        null,
        null,
        0,
        null);
  }

  /**
   * Creates a message carrying one bounded canonical signed record.
   *
   * @param recordBytes canonical record bytes
   * @return bounded record message
   */
  public static RecordMessage record(byte[] recordBytes) {
    Objects.requireNonNull(recordBytes, "record bytes");
    if (recordBytes.length == 0 || recordBytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("record message exceeds bound");
    }
    return new RecordMessage(Kind.RECORD, null, null, 0, null, recordBytes, null, null, null, 0,
        null);
  }

  /**
   * Creates a deterministic mutation result with the current local head.
   *
   * @param code     result classification
   * @param recordId stable record identity
   * @param revision current head revision
   * @param digest   current head digest, or null at revision zero
   * @return bounded result message
   */
  public static RecordMessage result(ResultCode code, UUID recordId, long revision, byte[] digest) {
    if (revision < 0 || (revision == 0) != (digest == null) || (digest != null
        && digest.length != 32)) {
      throw new IllegalArgumentException("invalid result head");
    }
    return new RecordMessage(Kind.RESULT, null, Objects.requireNonNull(recordId, "record ID"), 0,
        null,
        null, Objects.requireNonNull(code, "result code"), null, null, revision, digest);
  }

  /**
   * Creates a deterministic bounded protocol error.
   *
   * @param code error classification
   * @param text bounded error text
   * @return bounded error message
   */
  public static RecordMessage error(ErrorCode code, String text) {
    Objects.requireNonNull(code, "error code");
    Objects.requireNonNull(text, "error text");
    if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > MAX_ERROR_TEXT_BYTES) {
      throw new IllegalArgumentException("error text exceeds bound");
    }
    return new RecordMessage(Kind.ERROR, null, null, 0, null, null, null, code, text, 0, null);
  }

  /**
   * Decodes one bounded message with strict framing and UTF-8.
   *
   * @param bytes complete message bytes
   * @return decoded message
   * @throws IOException if framing, bounds, or values are invalid
   */
  public static RecordMessage decode(byte[] bytes) throws IOException {
    Objects.requireNonNull(bytes, "bytes");
    if (bytes.length < 6 || bytes.length > MAX_BYTES) {
      throw new IOException("message exceeds bound");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
        throw new IOException("unsupported message version");
      }
      int kindCode = input.readUnsignedByte();
      if (kindCode >= Kind.values().length) {
        throw new IOException("unknown message kind");
      }
      Kind kind = Kind.values()[kindCode];
      return switch (kind) {
        case SYNC_REQUEST -> decodeRequest(input);
        case RECORD -> decodeRecord(input);
        case RESULT -> decodeResult(input);
        case ERROR -> decodeError(input);
      };
    } catch (java.io.EOFException | IllegalArgumentException exception) {
      throw new IOException("malformed message", exception);
    }
  }

  private static RecordMessage decodeRequest(DataInputStream input) throws IOException {
    UUID project = readUuid(input);
    UUID record = readUuid(input);
    long revision = input.readLong();
    int marker = input.readUnsignedByte();
    if (marker > 1 || (marker == 1 && input.available() < 32)) {
      throw new IOException("invalid request head");
    }
    byte[] digest = marker == 1 ? input.readNBytes(32) : null;
    if (input.available() != 0) {
      throw new IOException("trailing request bytes");
    }
    return syncRequest(project, record, revision, digest);
  }

  private static RecordMessage decodeRecord(DataInputStream input) throws IOException {
    byte[] bytes = readBytes(input, INVALID_BYTES);
    if (input.available() != 0) {
      throw new IOException("trailing record message bytes");
    }
    return record(bytes);
  }

  private static RecordMessage decodeResult(DataInputStream input) throws IOException {
    int code = input.readUnsignedByte();
    if (code >= ResultCode.values().length) {
      throw new IOException("unknown result");
    }
    UUID record = readUuid(input);
    long revision = input.readLong();
    int marker = input.readUnsignedByte();
    if (marker > 1 || (marker == 1 && input.available() < 32)) {
      throw new IOException("invalid result head");
    }
    byte[] digest = marker == 1 ? input.readNBytes(32) : null;
    if (input.available() != 0) {
      throw new IOException("trailing result bytes");
    }
    return result(ResultCode.values()[code], record, revision, digest);
  }

  private static RecordMessage decodeError(DataInputStream input) throws IOException {
    int code = input.readUnsignedByte();
    if (code >= ErrorCode.values().length) {
      throw new IOException("unknown error");
    }
    String text = readText(input, MAX_ERROR_TEXT_BYTES, INVALID_BYTES);
    if (input.available() != 0) {
      throw new IOException("trailing error bytes");
    }
    return error(ErrorCode.values()[code], text);
  }

  /**
   * Returns the message kind.
   *
   * @return message kind
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns the project ID for a sync request, otherwise null.
   *
   * @return project ID
   */
  public UUID projectId() {
    return projectId;
  }

  /**
   * Returns the record ID where present.
   *
   * @return record ID
   */
  public UUID recordId() {
    return recordId;
  }

  /**
   * Returns the known request revision.
   *
   * @return known revision
   */
  public long knownRevision() {
    return knownRevision;
  }

  /**
   * Returns a copy of the known request digest.
   *
   * @return digest or null
   */
  public byte[] knownDigest() {
    return knownDigest == null ? null : knownDigest.clone();
  }

  /**
   * Returns a copy of the carried record bytes.
   *
   * @return record bytes or null
   */
  public byte[] recordBytes() {
    return recordBytes == null ? null : recordBytes.clone();
  }

  /**
   * Returns the result code where present.
   *
   * @return result code
   */
  public ResultCode resultCode() {
    return resultCode;
  }

  /**
   * Returns the error code where present.
   *
   * @return error code
   */
  ErrorCode errorCode() {
    return errorCode;
  }

  /**
   * Returns safe error text where present.
   *
   * @return error text
   */
  String errorText() {
    return errorText;
  }

  /**
   * Returns the result head revision.
   *
   * @return revision
   */
  public long revision() {
    return revision;
  }

  /**
   * Returns a copy of the result head digest.
   *
   * @return digest or null
   */
  public byte[] digest() {
    return digest == null ? null : digest.clone();
  }

  /**
   * Returns canonical bounded wire bytes.
   *
   * @return encoded message
   */
  public byte[] encoded() {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(MAGIC);
        output.writeByte(VERSION);
        output.writeByte(kind.ordinal());
        switch (kind) {
          case SYNC_REQUEST -> {
            writeUuid(output, projectId);
            writeUuid(output, recordId);
            output.writeLong(knownRevision);
            output.writeByte(knownDigest == null ? 0 : 1);
            if (knownDigest != null) {
              output.write(knownDigest);
            }
          }
          case RECORD -> writeBytes(output, recordBytes, INVALID_BYTES);
          case RESULT -> {
            output.writeByte(resultCode.ordinal());
            writeUuid(output, recordId);
            output.writeLong(revision);
            output.writeByte(digest == null ? 0 : 1);
            if (digest != null) {
              output.write(digest);
            }
          }
          case ERROR -> {
            output.writeByte(errorCode.ordinal());
            writeText(output, errorText, MAX_ERROR_TEXT_BYTES, INVALID_BYTES);
          }
        }
      }
      if (bytes.size() > MAX_BYTES) {
        throw new IllegalArgumentException("message exceeds application bound");
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new AssertionError("byte array output cannot fail", impossible);
    }
  }

  /**
   * Message kind in the bounded wire envelope.
   */
  public enum Kind {
    /**
     * Requests a record head.
     */
    SYNC_REQUEST,
    /**
     * Carries one canonical record.
     */
    RECORD,
    /**
     * Reports one deterministic outcome.
     */
    RESULT,
    /**
     * Reports one bounded protocol error.
     */
    ERROR
  }

  /**
   * Deterministic mutation outcome.
   */
  public enum ResultCode {
    /**
     * Exact revision already exists.
     */
    DUPLICATE,
    /**
     * Candidate is behind the local head.
     */
    REMOTE_STALE,
    /**
     * Candidate diverges from the local chain.
     */
    CONFLICT,
    /**
     * Candidate failed trust checks.
     */
    REJECTED,
    /**
     * Candidate was durably applied.
     */
    APPLIED
  }

  /**
   * Deterministic protocol error class.
   */
  public enum ErrorCode {
    /**
     * Message framing is invalid.
     */
    MALFORMED,
    /**
     * Authenticated peer is not allowlisted.
     */
    UNAUTHORIZED,
    /**
     * Record signature or fields are invalid.
     */
    INVALID_RECORD,
    /**
     * Message project does not match.
     */
    PROJECT_MISMATCH,
    /**
     * Local durable storage failed.
     */
    STORAGE_FAILURE,
    /**
     * Requested record is absent.
     */
    NOT_FOUND
  }
}
