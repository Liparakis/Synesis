package org.synesis.link.transport;

import java.util.Arrays;
import java.util.Objects;

/** Internal bounded codec for one opaque Link application-stream payload. */
final class ApplicationStreamCodec {
    static final int MAX_PAYLOAD_BYTES = ControlFrame.MAX_PAYLOAD;
    static final int MAX_FRAME_BYTES = 5 + MAX_PAYLOAD_BYTES;
    private static final byte[] MAGIC = new byte[] {'S', 'L', 'A', '1'};
    private static final int VERSION = 1;

    private ApplicationStreamCodec() { }

    static byte[] encode(byte[] payload) {
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

    static byte[] decode(byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        if (frame.length < 5 || frame.length > MAX_FRAME_BYTES
                || !looksLike(frame) || (frame[4] & 255) != VERSION) {
            throw new IllegalArgumentException("malformed application stream frame");
        }
        return Arrays.copyOfRange(frame, 5, frame.length);
    }

    static boolean looksLike(byte[] frame) {
        return frame != null && frame.length >= MAGIC.length
                && frame[0] == MAGIC[0] && frame[1] == MAGIC[1]
                && frame[2] == MAGIC[2] && frame[3] == MAGIC[3];
    }
}
