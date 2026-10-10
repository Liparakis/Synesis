package org.synesis.link.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the demo work request and result frames.
 */
final class DemoWorkWireCompatibilityTest {

  private static final UUID REQUEST_ID = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
  private static final String ID_BYTES = "00112233445566778899aabbccddeeff";
  private static final String REQUEST =
      "53445731" + "01" + "01" + ID_BYTES + "0010" + "64657363726962652d73657373696f6e";
  private static final String RESULT_OK =
      "53445731" + "01" + "02" + ID_BYTES + "00" + "0002" + "6f6b";
  private static final String RESULT_UNSUPPORTED =
      "53445731" + "01" + "02" + ID_BYTES + "02" + "0002" + "6e6f";

  private static byte[] unhex(String value) {
    return HexFormat.of().parseHex(value);
  }

  @Test
  void requestIsSdw1MagicVersionKindIdAndLengthPrefixedOperation() {
    DemoWorkRequest request = new DemoWorkRequest(REQUEST_ID, DemoWorkRequest.DESCRIBE_SESSION);

    assertEquals(REQUEST, HexFormat.of().formatHex(DemoWorkCodec.encodeRequest(request)));
    assertEquals(request, DemoWorkCodec.decodeRequest(unhex(REQUEST)));
  }

  @Test
  void resultCarriesTheStatusOrdinalBetweenIdAndMessage() {
    assertEquals(RESULT_OK, HexFormat.of().formatHex(DemoWorkCodec.encodeResult(
        new DemoWorkResult(REQUEST_ID, DemoWorkStatus.OK, "ok"))));
    assertEquals(new DemoWorkResult(REQUEST_ID, DemoWorkStatus.OK, "ok"),
        DemoWorkCodec.decodeResult(unhex(RESULT_OK)));
    assertEquals(RESULT_UNSUPPORTED, HexFormat.of().formatHex(DemoWorkCodec.encodeResult(
        new DemoWorkResult(REQUEST_ID, DemoWorkStatus.UNSUPPORTED_OPERATION, "no"))));
    assertEquals(DemoWorkStatus.UNSUPPORTED_OPERATION,
        DemoWorkCodec.decodeResult(unhex(RESULT_UNSUPPORTED)).status());
  }

  @Test
  void decodersRejectTrailingTruncatedAndWrongKindFrames() {
    byte[] request = unhex(REQUEST);
    byte[] result = unhex(RESULT_OK);

    assertThrows(IllegalArgumentException.class,
        () -> DemoWorkCodec.decodeRequest(Arrays.copyOf(request, request.length + 1)));
    assertThrows(IllegalArgumentException.class,
        () -> DemoWorkCodec.decodeRequest(Arrays.copyOf(request, request.length - 1)));
    assertThrows(IllegalArgumentException.class,
        () -> DemoWorkCodec.decodeResult(Arrays.copyOf(result, result.length + 1)));
    assertThrows(IllegalArgumentException.class,
        () -> DemoWorkCodec.decodeResult(Arrays.copyOf(result, result.length - 1)));
    assertThrows(IllegalArgumentException.class, () -> DemoWorkCodec.decodeRequest(result));
    assertThrows(IllegalArgumentException.class, () -> DemoWorkCodec.decodeResult(request));
  }
}
