package org.synesis.link.transport.control;

import java.util.Arrays;
import java.util.Objects;

/**
 * Internal bounded codec for one opaque Link application-stream payload.
 */
public final class ApplicationStreamCodec {

    /**
     * Maximum opaque application payload accepted by the frame codec.
     */
    public static final int MAX_PAYLOAD_BYTES = ControlFrame.MAX_PAYLOAD;
    /**
     * Maximum total encoded frame size, including magic and version bytes.
     */
    public static final int MAX_FRAME_BYTES = 5 + MAX_PAYLOAD_BYTES;
    private static final byte[] MAGIC = new byte[]{'S', 'L', 'A', '1'};
    private static final int VERSION = 1;

    private ApplicationStreamCodec() {
    }

    /**
     * Encodes one opaque application payload into the bounded Link stream frame.
     *
     * @param payload application payload bytes
     * @return framed bytes
     */
    public static byte[] encode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("application payload exceeds supported bound");
        }
        byte[] frame = new byte[5 + payload.length];
        System.arraycopy(MAGIC, 0, frame, 0, MAGIC.length);
        frame[4] = VERSION;
        System.arraycopy(payload, 0, frame, 5, payload.length);
        return frame;
    }

    /**
     * Decodes one bounded Link application-stream frame.
     *
     * @param frame framed bytes
     * @return decoded payload bytes
     */
    public static byte[] decode(byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < 5 || frame.length > MAX_FRAME_BYTES
                || !looksLike(frame) || (frame[4] & 255) != VERSION) {
            throw new IllegalArgumentException("malformed application stream frame");
        }
        return Arrays.copyOfRange(frame, 5, frame.length);
    }

    /**
     * Returns whether the supplied bytes begin with the application-stream magic prefix.
     *
     * @param frame candidate frame bytes
     * @return true when the bytes match the codec prefix
     */
    public static boolean looksLike(byte[] frame) {
        return frame != null && frame.length >= MAGIC.length
                && frame[0] == MAGIC[0] && frame[1] == MAGIC[1]
                && frame[2] == MAGIC[2] && frame[3] == MAGIC[3];
    }
}
