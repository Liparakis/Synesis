package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/** Verifies bounded SL-006 framing without a network dependency. */
final class ControlFrameTest {

    @Test
    void roundTripsAndRejectsBoundaries() throws Exception {
        byte[] payload = new byte[] {1, 2, 3};
        ControlFrame frame = ControlFrame.of(ControlMessageType.CONTROL_READY, payload);
        ControlFrame decoded = ControlFrame.decode(frame.encoded());
        assertEquals(ControlMessageType.CONTROL_READY, decoded.type);
        assertArrayEquals(payload, decoded.payload);
        assertEquals(ControlFrame.MAX_FRAME,
                ControlFrame.of(ControlMessageType.PROTOCOL_ERROR, new byte[ControlFrame.MAX_PAYLOAD])
                        .encoded().length);
        assertThrows(IOException.class, () -> ControlFrame.decode(
                java.util.Arrays.copyOf(frame.encoded(), frame.encoded().length - 1)));
    }

    @Test
    void rejectsMagicFlagsOverflowAndOversizedPayload() {
        ControlFrame frame = ControlFrame.of(ControlMessageType.GOODBYE, new byte[0]);
        byte[] invalidMagic = frame.encoded();
        invalidMagic[0] = 0;
        assertThrows(IOException.class, () -> ControlFrame.decode(invalidMagic));

        byte[] invalidFlags = frame.encoded();
        invalidFlags[7] = 1;
        assertThrows(IOException.class, () -> ControlFrame.decode(invalidFlags));

        byte[] overflow = frame.encoded();
        ByteBuffer.wrap(overflow).order(ByteOrder.BIG_ENDIAN).putInt(8, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> ControlFrame.decode(overflow));
        assertThrows(IllegalArgumentException.class,
                () -> ControlFrame.of(ControlMessageType.GOODBYE, new byte[ControlFrame.MAX_PAYLOAD + 1]));
    }

    @Test
    void rejectsTruncationAndUnknownMessage() {
        byte[] encoded = ControlFrame.of(ControlMessageType.GOODBYE, new byte[0]).encoded();
        assertThrows(IOException.class, () -> ControlFrame.decode(java.util.Arrays.copyOf(encoded, 11)));
        encoded[5] = (byte) 99;
        assertThrows(IOException.class, () -> ControlFrame.decode(encoded));
    }
}
