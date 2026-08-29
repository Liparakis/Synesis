package org.synesis.mcp.transport.stdio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Package-private bounded newline-delimited MCP frame reader.
 */
final class McpFrameReader {

    /**
     * Maximum raw UTF-8 frame bytes accepted before the first excess byte.
     */
    static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;

    private final InputStream input;

    McpFrameReader(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    private static String decode(byte[] raw, int length) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(raw, 0, length));
            return decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("MCP_INVALID_UTF8", failure);
        }
    }

    /**
     * Reads one strict UTF-8 LF-terminated frame.
     *
     * @return trimmed frame, or {@code null} at clean EOF between frames
     * @throws IOException on overflow, partial EOF, or invalid UTF-8
     */
    String readFrame() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (bytes.size() == 0) {
                    return null;
                }
                throw new IOException("MCP_PARTIAL_FRAME_EOF");
            }
            if (bytes.size() >= MAX_FRAME_BYTES) {
                throw new IOException("MCP_FRAME_LIMIT_EXCEEDED");
            }
            if (value == '\n') {
                byte[] raw = bytes.toByteArray();
                int length = raw.length > 0 && raw[raw.length - 1] == '\r' ? raw.length - 1 : raw.length;
                return decode(raw, length).trim();
            }
            bytes.write(value);
        }
    }
}
