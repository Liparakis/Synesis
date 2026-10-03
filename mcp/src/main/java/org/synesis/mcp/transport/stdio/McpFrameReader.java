package org.synesis.mcp.transport.stdio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Package-private bounded newline-delimited MCP frame reader.
 *
 * <p>Frames are decoded strictly as UTF-8 and are accepted only after a line terminator is
 * received. Clean EOF between frames is distinct from partial EOF, which is a transport failure
 * and must not be treated as graceful shutdown.
 *
 * <p>Reads one byte at a time, so the stream should be buffered ({@link System#in} is).
 */
final class McpFrameReader {

    /**
     * Frame size limit in raw UTF-8 bytes. A frame of this many bytes or more is rejected, even if
     * the next byte would have been its terminator.
     */
    static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;

    /** Stdio stream owned by the enclosing MCP server. */
    private final InputStream input;

    /**
     * Creates a frame reader over the caller-owned input stream.
     *
     * @param input stream from which frames are read
     */
    McpFrameReader(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /**
     * Reads one strict UTF-8 LF-terminated frame. A CR directly before the LF is dropped.
     *
     * @return trimmed frame, which may be empty, or {@code null} at clean EOF between frames
     * @throws IOException {@code MCP_FRAME_LIMIT_EXCEEDED}, {@code MCP_PARTIAL_FRAME_EOF}, or
     *                     {@code MCP_INVALID_UTF8}, or the underlying read failure
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
                boolean carriageReturn = raw.length > 0 && raw[raw.length - 1] == '\r';
                return decode(raw, carriageReturn ? raw.length - 1 : raw.length).trim();
            }
            bytes.write(value);
        }
    }

    /**
     * Decodes one complete frame using strict UTF-8 error handling.
     *
     * @param raw    byte buffer containing the frame
     * @param length number of frame bytes, excluding any CR stripped before decoding
     * @return decoded frame text
     * @throws IOException {@code MCP_INVALID_UTF8} when the bytes are not valid UTF-8
     */
    private static String decode(byte[] raw, int length) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(raw, 0, length)).toString();
        } catch (CharacterCodingException failure) {
            throw new IOException("MCP_INVALID_UTF8", failure);
        }
    }
}