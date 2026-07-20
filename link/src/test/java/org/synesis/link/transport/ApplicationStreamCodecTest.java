package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

final class ApplicationStreamCodecTest {
    @Test
    void roundTripsOpaquePayload() {
        byte[] payload = new byte[] {0, 1, 2, (byte) 255};
        assertArrayEquals(payload, ApplicationStreamCodec.decode(ApplicationStreamCodec.encode(payload)));
    }

    @Test
    void rejectsOversizedAndMalformedFrames() {
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationStreamCodec.encode(new byte[ApplicationStreamCodec.MAX_PAYLOAD_BYTES + 1]));
        byte[] malformed = ApplicationStreamCodec.encode(new byte[] {1});
        malformed[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> ApplicationStreamCodec.decode(malformed));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationStreamCodec.decode(Arrays.copyOf(malformed, ApplicationStreamCodec.MAX_FRAME_BYTES + 1)));
    }
}
