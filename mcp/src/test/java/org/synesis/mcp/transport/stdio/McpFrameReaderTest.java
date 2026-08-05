package org.synesis.mcp.transport.stdio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Verifies bounded strict MCP framing independently of the protocol handler. */
class McpFrameReaderTest {

    @Test
    void acceptsLfAndCrLfAndCleanEof() throws Exception {
        McpFrameReader reader = new McpFrameReader(new ByteArrayInputStream(
                " {\"id\":1}\r\n{\"id\":2}\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("{\"id\":1}", reader.readFrame());
        assertEquals("{\"id\":2}", reader.readFrame());
        assertNull(reader.readFrame());
    }

    @Test
    void rejectsInvalidUtf8AndPartialEof() {
        assertThrows(IOException.class, () -> new McpFrameReader(
                new ByteArrayInputStream(new byte[] {(byte) 0xc3, '\n'})).readFrame());
        assertThrows(IOException.class, () -> new McpFrameReader(
                new ByteArrayInputStream("partial".getBytes(StandardCharsets.UTF_8))).readFrame());
    }

    @Test
    void failsAtFirstByteBeyondFrameLimit() {
        InputStream oversized = new InputStream() {
            private int remaining = McpFrameReader.MAX_FRAME_BYTES + 1;

            @Override
            public int read() {
                return remaining-- > 0 ? 'x' : -1;
            }
        };
        assertThrows(IOException.class, () -> new McpFrameReader(oversized).readFrame());
    }
}
