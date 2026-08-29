package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests raw-byte frame limits before UTF-8 materialization.
 */
class BoundedProtocolFrameReaderTest {

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    @Test
    void acceptsExactBoundAndCrLf() throws Exception {
        byte[] payload = new byte[BoundedProtocolFrameReader.MAX_FRAME_BYTES];
        java.util.Arrays.fill(payload, (byte) 'a');
        String frame = new BoundedProtocolFrameReader(new ByteArrayInputStream(
                concat(payload, "\r\n".getBytes(StandardCharsets.UTF_8)))).readFrame();
        assertEquals(BoundedProtocolFrameReader.MAX_FRAME_BYTES, frame.length());
    }

    @Test
    void rejectsOneByteAboveBoundWithoutFullString() {
        byte[] payload = new byte[BoundedProtocolFrameReader.MAX_FRAME_BYTES + 1];
        java.util.Arrays.fill(payload, (byte) 'a');
        assertThrows(BoundedProtocolFrameReader.OversizedFrameException.class,
                () -> new BoundedProtocolFrameReader(new ByteArrayInputStream(
                        concat(payload, new byte[]{'\n'}))).readFrame());
    }

    @Test
    void distinguishesPartialEofInvalidUtf8AndEmptyFrame() {
        assertThrows(EOFException.class, () -> new BoundedProtocolFrameReader(
                new ByteArrayInputStream("partial".getBytes(StandardCharsets.UTF_8))).readFrame());
        assertThrows(BoundedProtocolFrameReader.InvalidUtf8Exception.class, () -> new BoundedProtocolFrameReader(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, '\n'})).readFrame());
        assertThrows(BoundedProtocolFrameReader.EmptyFrameException.class, () -> new BoundedProtocolFrameReader(
                new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8))).readFrame());
    }
}
