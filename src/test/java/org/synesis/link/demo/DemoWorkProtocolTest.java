package org.synesis.link.demo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

final class DemoWorkProtocolTest {
    @Test
    void requestAndResultRoundTripWithCorrelation() {
        UUID id = UUID.randomUUID();
        DemoWorkRequest request = new DemoWorkRequest(id, "describe-session");
        DemoWorkResult result = new DemoWorkResult(id, DemoWorkStatus.OK, "control-ready");

        assertEquals(request, DemoWorkCodec.decodeRequest(DemoWorkCodec.encodeRequest(request)));
        assertEquals(result, DemoWorkCodec.decodeResult(DemoWorkCodec.encodeResult(result)));
        assertArrayEquals(DemoWorkCodec.encodeRequest(request), DemoWorkCodec.encodeRequest(request));
    }

    @Test
    void malformedUtf8AndOversizedPayloadsReject() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new DemoWorkResult(id, DemoWorkStatus.OK, "x".repeat(2_000)));
        byte[] malformed = DemoWorkCodec.encodeRequest(new DemoWorkRequest(id, "describe-session"));
        malformed[malformed.length - 1] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> DemoWorkCodec.decodeRequest(malformed));
        assertThrows(IllegalArgumentException.class,
                () -> DemoWorkCodec.decodeRequest(new byte[DemoWorkCodec.MAX_FRAME_BYTES + 1]));
    }
}
