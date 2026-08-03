package org.synesis.workspace.lifecycle.codex;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Incremental bounded newline-delimited reader for Codex App Server stdout.
 *
 * <p>Raw bytes are bounded before UTF-8 decoding and before a Java String is
 * materialized. The reader is thread-confined to its caller. An oversized
 * frame retains only a bounded diagnostic prefix and never grows its buffer.
 *
 * @since 1.0
 */
public final class BoundedProtocolFrameReader {

    /** Maximum raw payload bytes before the newline terminator. */
    public static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    /** Maximum diagnostic prefix retained for an oversized frame. */
    public static final int MAX_DIAGNOSTIC_PREFIX_BYTES = 64 * 1024;

    private final InputStream input;
    private final byte[] one = new byte[1];

    /**
     * Creates a frame reader.
     *
     * @param input App Server stdout stream
     */
    public BoundedProtocolFrameReader(InputStream input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    /**
     * Reads one complete frame.
     *
     * @return decoded UTF-8 JSON payload without LF or CRLF terminator
     * @throws EOFException when EOF occurs after a non-empty unterminated frame
     * @throws CleanEofException when EOF occurs at a frame boundary
     * @throws EmptyFrameException when an empty line is encountered
     * @throws OversizedFrameException when raw bytes exceed the configured bound
     * @throws InvalidUtf8Exception when the bounded payload is not valid UTF-8
     * @throws IOException when the stream cannot be read
     */
    public String readFrame() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(Math.min(4096, MAX_FRAME_BYTES));
        byte[] diagnosticPrefix = new byte[MAX_DIAGNOSTIC_PREFIX_BYTES];
        int diagnosticPrefixLength = 0;
        int count = 0;
        boolean pendingCarriageReturn = false;
        while (true) {
            int read = input.read(one, 0, 1);
            if (read == 0) {
                continue;
            }
            if (read < 0) {
                if (pendingCarriageReturn) {
                    count = append(payload, count, '\r', diagnosticPrefix, diagnosticPrefixLength);
                    if (diagnosticPrefixLength < MAX_DIAGNOSTIC_PREFIX_BYTES) {
                        diagnosticPrefixLength++;
                    }
                }
                if (count == 0) {
                    throw new CleanEofException();
                }
                throw new EOFException("unterminated App Server frame");
            }
            int value = one[0] & 0xff;
            if (pendingCarriageReturn) {
                if (value == '\n') {
                    return finish(payload, count);
                }
                count = append(payload, count, '\r', diagnosticPrefix, diagnosticPrefixLength);
                if (diagnosticPrefixLength < MAX_DIAGNOSTIC_PREFIX_BYTES) {
                    diagnosticPrefixLength++;
                }
                pendingCarriageReturn = false;
            }
            if (value == '\r') {
                pendingCarriageReturn = true;
                continue;
            }
            if (value == '\n') {
                return finish(payload, count);
            }
            count = append(payload, count, value, diagnosticPrefix, diagnosticPrefixLength);
            if (diagnosticPrefixLength < MAX_DIAGNOSTIC_PREFIX_BYTES) {
                diagnosticPrefixLength++;
            }
        }
    }

    private static int append(ByteArrayOutputStream payload, int count, int value, byte[] diagnosticPrefix,
            int diagnosticPrefixLength) throws OversizedFrameException {
        if (count >= MAX_FRAME_BYTES) {
            throw new OversizedFrameException(java.util.Arrays.copyOf(diagnosticPrefix, diagnosticPrefixLength),
                    count + 1);
        }
        payload.write(value);
        if (diagnosticPrefixLength < MAX_DIAGNOSTIC_PREFIX_BYTES) {
            diagnosticPrefix[diagnosticPrefixLength] = (byte) value;
        }
        return count + 1;
    }

    private static String finish(ByteArrayOutputStream payload, int count)
            throws EmptyFrameException, InvalidUtf8Exception {
        if (count == 0) {
            throw new EmptyFrameException();
        }
        return decode(payload.toByteArray(), count);
    }

    private static String decode(byte[] bytes, int length) throws InvalidUtf8Exception {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes, 0, length));
            return chars.toString();
        } catch (CharacterCodingException failure) {
            throw new InvalidUtf8Exception(failure);
        }
    }

    /** Clean EOF at a frame boundary. */
    public static final class CleanEofException extends EOFException {
        private static final long serialVersionUID = 1L;

        /** Creates the clean frame-boundary EOF diagnostic. */
        public CleanEofException() {
            super("clean App Server EOF");
        }
    }

    /** Empty line encountered where a JSON frame was required. */
    public static final class EmptyFrameException extends IOException {
        private static final long serialVersionUID = 1L;

        /** Creates the stable empty-frame diagnostic. */
        public EmptyFrameException() {
            super("empty App Server frame");
        }
    }

    /** Invalid UTF-8 encountered after bounded framing. */
    public static final class InvalidUtf8Exception extends IOException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates an invalid UTF-8 diagnostic.
         *
         * @param cause decoder failure
         */
        public InvalidUtf8Exception(Throwable cause) {
            super("invalid UTF-8 App Server frame", cause);
        }
    }

    /** Oversized raw frame with bounded diagnostic prefix. */
    public static final class OversizedFrameException extends IOException {
        private static final long serialVersionUID = 1L;
        /** Bounded raw diagnostic prefix. */
        private final byte[] prefix;
        /** Number of raw bytes observed before overflow. */
        private final int observedBytes;

        /**
         * Creates an oversized-frame failure.
         *
         * @param prefix bounded raw prefix
         * @param observedBytes number of raw bytes observed before failure
         */
        public OversizedFrameException(byte[] prefix, int observedBytes) {
            super("codex_protocol_oversized");
            this.prefix = prefix.clone();
            this.observedBytes = observedBytes;
        }

        /**
         * Returns the bounded raw diagnostic prefix.
         *
         * @return prefix bytes
         */
        public byte[] prefix() {
            return prefix.clone();
        }

        /**
         * Returns raw bytes observed before overflow.
         *
         * @return observed byte count
         */
        public int observedBytes() {
            return observedBytes;
        }
    }
}
