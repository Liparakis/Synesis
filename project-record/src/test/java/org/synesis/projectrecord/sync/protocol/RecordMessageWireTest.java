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

/**
 * Pins the exact SRP1 bytes of every message kind.
 */
final class RecordMessageWireTest {

  private static final String REQUEST = "53525031010011111111111111111111111111111111222222222222222222222222222222220000"
      + "00000000000301000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
  private static final String REQUEST_AT_ZERO = "53525031010011111111111111111111111111111111222222222222222222222222222222220000"
      + "00000000000000";
  private static final String RECORD = "53525031010100050102030405";
  private static final String RESULT = "53525031010202222222222222222222222222222222220000000000000003010001020304050607"
      + "08090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
  private static final String RESULT_AT_ZERO = "5352503101020422222222222222222222222222222222000000000000000000";
  private static final String ERROR = "535250310103030014ceb1cf80cebfcf84cf85cf87ceafceb120e29c93";
  private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RECORD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static byte[] bytes(String hex) {
    return HexFormat.of().parseHex(hex);
  }

  private static byte[] digest() {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) index;
    }
    return value;
  }

  private static void assertPinned(String vector, RecordMessage built) throws IOException {
    assertArrayEquals(bytes(vector), built.encoded());
    assertArrayEquals(bytes(vector), RecordMessage.decode(bytes(vector)).encoded());
  }

  @Test
  void syncRequestVectors() throws Exception {
    assertPinned(REQUEST, RecordMessage.syncRequest(PROJECT, RECORD_ID, 3, digest()));
    RecordMessage decoded = RecordMessage.decode(bytes(REQUEST));
    assertEquals(RecordMessage.Kind.SYNC_REQUEST, decoded.kind());
    assertEquals(PROJECT, decoded.projectId());
    assertEquals(RECORD_ID, decoded.recordId());
    assertEquals(3, decoded.knownRevision());
    assertArrayEquals(digest(), decoded.knownDigest());

    assertPinned(REQUEST_AT_ZERO, RecordMessage.syncRequest(PROJECT, RECORD_ID, 0, null));
    assertNull(RecordMessage.decode(bytes(REQUEST_AT_ZERO)).knownDigest());
  }

  @Test
  void recordVector() throws Exception {
    assertPinned(RECORD, RecordMessage.record(new byte[]{1, 2, 3, 4, 5}));
    RecordMessage decoded = RecordMessage.decode(bytes(RECORD));
    assertEquals(RecordMessage.Kind.RECORD, decoded.kind());
    assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, decoded.recordBytes());
  }

  @Test
  void resultVectors() throws Exception {
    assertPinned(RESULT,
        RecordMessage.result(RecordMessage.ResultCode.CONFLICT, RECORD_ID, 3, digest()));
    RecordMessage decoded = RecordMessage.decode(bytes(RESULT));
    assertEquals(RecordMessage.Kind.RESULT, decoded.kind());
    assertEquals(RecordMessage.ResultCode.CONFLICT, decoded.resultCode());
    assertEquals(RECORD_ID, decoded.recordId());
    assertEquals(3, decoded.revision());
    assertArrayEquals(digest(), decoded.digest());

    assertPinned(RESULT_AT_ZERO,
        RecordMessage.result(RecordMessage.ResultCode.APPLIED, RECORD_ID, 0, null));
    assertNull(RecordMessage.decode(bytes(RESULT_AT_ZERO)).digest());
  }

  @Test
  void errorVector() throws Exception {
    assertPinned(ERROR,
        RecordMessage.error(RecordMessage.ErrorCode.PROJECT_MISMATCH, "αποτυχία ✓"));
    RecordMessage decoded = RecordMessage.decode(bytes(ERROR));
    assertEquals(RecordMessage.Kind.ERROR, decoded.kind());
    assertEquals(RecordMessage.ErrorCode.PROJECT_MISMATCH, decoded.errorCode());
    assertEquals("αποτυχία ✓", decoded.errorText());
  }

  @Test
  void enumOrdinalsOnTheWireAreFixed() {
    assertEquals(List.of("SYNC_REQUEST", "RECORD", "RESULT", "ERROR"), names(
        RecordMessage.Kind.values()));
    assertEquals(List.of("DUPLICATE", "REMOTE_STALE", "CONFLICT", "REJECTED", "APPLIED"), names(
        RecordMessage.ResultCode.values()));
    assertEquals(List.of("MALFORMED", "UNAUTHORIZED", "INVALID_RECORD", "PROJECT_MISMATCH",
        "STORAGE_FAILURE", "NOT_FOUND"), names(RecordMessage.ErrorCode.values()));
  }

  @Test
  void trailingBytesAndEveryTruncationAreRejectedAsIoException() {
    for (String vector : List.of(REQUEST, REQUEST_AT_ZERO, RECORD, RESULT, RESULT_AT_ZERO,
        ERROR)) {
      byte[] encoded = bytes(vector);
      assertThrows(IOException.class, () -> RecordMessage.decode(Arrays.copyOf(encoded,
          encoded.length + 1)));
      for (int length = 0; length < encoded.length; length++) {
        byte[] truncated = Arrays.copyOf(encoded, length);
        assertThrows(IOException.class, () -> RecordMessage.decode(truncated));
      }
    }
  }

  private static List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }
}
