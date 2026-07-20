package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Tests the bounded heartbeat payload codec before transport integration. */
final class HeartbeatMessageTest {

    private static final UUID SESSION = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    @Test
    void heartbeatRoundTripUsesStableGoldenBytes() {
        HeartbeatMessage message = HeartbeatMessage.heartbeat(SESSION, 7, 6, 0x0102030405060708L);

        assertEquals("0100112233445566778899aabbccddeeff000000000000000700000000000000060102030405060708",
                hex(message.encoded()));
        assertEquals(message, HeartbeatMessage.decode(message.encoded(), false));
    }

    @Test
    void acknowledgementRoundTripUsesStableGoldenBytes() {
        HeartbeatMessage message = HeartbeatMessage.acknowledgement(SESSION, 7, 9, 0x0102030405060708L);

        assertEquals("0100112233445566778899aabbccddeeff000000000000000700000000000000090102030405060708",
                hex(message.encoded()));
        assertEquals(message, HeartbeatMessage.decode(message.encoded(), true));
    }

    @Test
    void malformedAndWrongSessionPayloadsAreRejected() {
        HeartbeatMessage message = HeartbeatMessage.heartbeat(SESSION, 0, -1, 0);
        byte[] encoded = message.encoded();

        assertThrows(IllegalArgumentException.class,
                () -> HeartbeatMessage.decode(new byte[encoded.length - 1], false));
        byte[] wrongVersion = encoded.clone();
        wrongVersion[0] = 2;
        assertThrows(IllegalArgumentException.class,
                () -> HeartbeatMessage.decode(wrongVersion, false));
        byte[] wrongSession = encoded.clone();
        wrongSession[1] = 9;
        assertThrows(IllegalArgumentException.class,
                () -> HeartbeatMessage.decode(wrongSession, SESSION, false));
        assertArrayEquals(encoded, HeartbeatMessage.decode(encoded, false).encoded());
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte current : value) result.append(String.format("%02x", current & 255));
        return result.toString();
    }
}
