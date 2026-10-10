package org.synesis.projectrecord.sync.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.InventoryEntry;
import org.synesis.projectrecord.sync.protocol.ReconciliationMessage.Kind;

/**
 * Pins the exact PRP1 bytes of every message kind.
 */
final class ReconciliationMessageWireTest {

  private static final String VALIDATE = "50525031010011111111111111111111111111111111";
  private static final String VALIDATE_ACK = "50525031010111111111111111111111111111111111";
  private static final String INVENTORY = "50525031010211111111111111111111111111111111000000010000000300000002000000022222"
      + "22222222222222222222222222220000000000000005000102030405060708090a0b0c0d0e0f1011"
      + "12131415161718191a1b1c1d1e1f333333333333333333333333333333330000000000000007a0a1"
      + "a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf";
  private static final String INVENTORY_ACK = "5052503101031111111111111111111111111111111100000002";
  private static final String UPLOAD = "5052503101041111111111111111111111111111111100050102030405";
  private static final String UPLOAD_ACK = "50525031010511111111111111111111111111111111222222222222222222222222222222220000"
      + "00000000000500000004";
  private static final String DOWNLOAD_REQUEST = "50525031010611111111111111111111111111111111222222222222222222222222222222220000"
      + "000000000005";
  private static final String DOWNLOAD_RESPONSE = "50525031010711111111111111111111111111111111222222222222222222222222222222220000"
      + "00000000000500000000010003040506";
  private static final String DOWNLOAD_RESPONSE_ABSENT = "50525031010711111111111111111111111111111111222222222222222222222222222222220000"
      + "0000000000050000000100";
  private static final String FINAL_INVENTORY = "50525031010811111111111111111111111111111111000000000000000200000001000000022222"
      + "22222222222222222222222222220000000000000005000102030405060708090a0b0c0d0e0f1011"
      + "12131415161718191a1b1c1d1e1f333333333333333333333333333333330000000000000007a0a1"
      + "a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf";
  private static final String FINAL_INVENTORY_ACK =
      "505250310109111111111111111111111111111111110000000100000001";
  private static final String ERROR = "50525031010a030014ceb1cf80cebfcf84cf85cf87ceafceb120e29c93";
  private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RECORD = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OTHER = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private static byte[] bytes(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  private static byte[] counting(int start) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (start + index);
    }
    return value;
  }

  private static List<InventoryEntry> twoEntries() {
    return List.of(new InventoryEntry(RECORD, 5, counting(0)),
        new InventoryEntry(OTHER, 7, counting(0xA0)));
  }

  private static ReconciliationMessage assertPinned(String vector, ReconciliationMessage built,
      Kind kind) throws IOException {
    assertArrayEquals(bytes(vector), built.encoded());
    ReconciliationMessage decoded = ReconciliationMessage.decode(bytes(vector));
    assertArrayEquals(bytes(vector), decoded.encoded());
    assertEquals(kind, decoded.kind());
    return decoded;
  }

  @Test
  void validateProjectAndAck() throws Exception {
    ReconciliationMessage request = assertPinned(VALIDATE,
        ReconciliationMessage.validateProject(PROJECT), Kind.VALIDATE_PROJECT);
    assertEquals(PROJECT, request.projectId());
    ReconciliationMessage ack = assertPinned(VALIDATE_ACK,
        ReconciliationMessage.validateProjectAck(PROJECT), Kind.VALIDATE_PROJECT_ACK);
    assertEquals(PROJECT, ack.projectId());
  }

  @Test
  void inventoryChunkAndAck() throws Exception {
    ReconciliationMessage chunk = assertPinned(INVENTORY,
        ReconciliationMessage.inventoryChunk(PROJECT, 1, 3, 2, twoEntries()),
        Kind.INVENTORY_CHUNK);
    assertEquals(1, chunk.chunkIndex());
    assertEquals(3, chunk.totalChunks());
    assertEquals(2, chunk.corruptCount());
    assertEquals(twoEntries(), chunk.entries());
    ReconciliationMessage ack = assertPinned(INVENTORY_ACK,
        ReconciliationMessage.inventoryChunkAck(PROJECT, 2), Kind.INVENTORY_CHUNK_ACK);
    assertEquals(2, ack.chunkIndex());
  }

  @Test
  void uploadAndAck() throws Exception {
    ReconciliationMessage upload = assertPinned(UPLOAD,
        ReconciliationMessage.clientUploadRevision(PROJECT, new byte[]{1, 2, 3, 4, 5}),
        Kind.CLIENT_UPLOAD_REVISION);
    assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, upload.recordBytes());
    ReconciliationMessage ack = assertPinned(UPLOAD_ACK,
        ReconciliationMessage.clientUploadAck(PROJECT, RECORD, 5, 4), Kind.CLIENT_UPLOAD_ACK);
    assertEquals(RECORD, ack.recordId());
    assertEquals(5, ack.revision());
    assertEquals(4, ack.status());
  }

  @Test
  void downloadRequestAndResponses() throws Exception {
    ReconciliationMessage request = assertPinned(DOWNLOAD_REQUEST,
        ReconciliationMessage.clientDownloadRequest(PROJECT, RECORD, 5),
        Kind.CLIENT_DOWNLOAD_REQUEST);
    assertEquals(RECORD, request.recordId());
    assertEquals(5, request.revision());
    ReconciliationMessage found = assertPinned(DOWNLOAD_RESPONSE,
        ReconciliationMessage.clientDownloadResponse(PROJECT, RECORD, 5, 0, new byte[]{4, 5, 6}),
        Kind.CLIENT_DOWNLOAD_RESPONSE);
    assertEquals(0, found.status());
    assertArrayEquals(new byte[]{4, 5, 6}, found.recordBytes());
    ReconciliationMessage absent = assertPinned(DOWNLOAD_RESPONSE_ABSENT,
        ReconciliationMessage.clientDownloadResponse(PROJECT, RECORD, 5, 1, null),
        Kind.CLIENT_DOWNLOAD_RESPONSE);
    assertEquals(1, absent.status());
    assertNull(absent.recordBytes());
  }

  @Test
  void finalInventoryChunkAndAck() throws Exception {
    ReconciliationMessage chunk = assertPinned(FINAL_INVENTORY,
        ReconciliationMessage.finalInventoryChunk(PROJECT, 0, 2, 1, twoEntries()),
        Kind.FINAL_INVENTORY_CHUNK);
    assertEquals(0, chunk.chunkIndex());
    assertEquals(2, chunk.totalChunks());
    assertEquals(1, chunk.corruptCount());
    assertEquals(twoEntries(), chunk.entries());
    ReconciliationMessage ack = assertPinned(FINAL_INVENTORY_ACK,
        ReconciliationMessage.finalInventoryChunkAck(PROJECT, 1, 1),
        Kind.FINAL_INVENTORY_CHUNK_ACK);
    assertEquals(1, ack.chunkIndex());
    assertEquals(1, ack.status());
  }

  @Test
  void error() throws Exception {
    ReconciliationMessage message = assertPinned(ERROR,
        ReconciliationMessage.error(ReconciliationMessage.ErrorCode.INTERNAL, "αποτυχία ✓"),
        Kind.ERROR);
    assertEquals(ReconciliationMessage.ErrorCode.INTERNAL, message.errorCode());
    assertEquals("αποτυχία ✓", message.errorText());
    assertNull(message.projectId());
  }

  @Test
  void enumOrdinalsOnTheWireAreFixed() {
    assertEquals(List.of("VALIDATE_PROJECT", "VALIDATE_PROJECT_ACK", "INVENTORY_CHUNK",
        "INVENTORY_CHUNK_ACK", "CLIENT_UPLOAD_REVISION", "CLIENT_UPLOAD_ACK",
        "CLIENT_DOWNLOAD_REQUEST", "CLIENT_DOWNLOAD_RESPONSE", "FINAL_INVENTORY_CHUNK",
        "FINAL_INVENTORY_CHUNK_ACK", "ERROR"),
        Arrays.stream(Kind.values()).map(Enum::name).toList());
    assertEquals(List.of("MALFORMED", "UNAUTHORIZED", "PROJECT_MISMATCH", "INTERNAL"),
        Arrays.stream(ReconciliationMessage.ErrorCode.values()).map(Enum::name).toList());
  }

  @Test
  void trailingBytesAndEveryTruncationAreRejectedAsIoException() {
    for (String vector : List.of(VALIDATE, VALIDATE_ACK, INVENTORY, INVENTORY_ACK, UPLOAD,
        UPLOAD_ACK, DOWNLOAD_REQUEST, DOWNLOAD_RESPONSE, DOWNLOAD_RESPONSE_ABSENT,
        FINAL_INVENTORY, FINAL_INVENTORY_ACK, ERROR)) {
      byte[] encoded = bytes(vector);
      assertThrows(IOException.class, () -> ReconciliationMessage.decode(Arrays.copyOf(encoded,
          encoded.length + 1)));
      for (int length = 0; length < encoded.length; length++) {
        byte[] truncated = Arrays.copyOf(encoded, length);
        assertThrows(IOException.class, () -> ReconciliationMessage.decode(truncated));
      }
    }
  }
}
