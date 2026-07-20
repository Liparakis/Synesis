package org.synesis.link.transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Package-private bounded SLH1 control frame; never part of the public API. */
final class ControlFrame {

    static final int MAX_PAYLOAD = 4_096;
    static final int MAX_FRAME = 12 + MAX_PAYLOAD;
    private static final int MAGIC = 0x534C4831;
    private static final int VERSION = 1;

    final ControlMessageType type;
    final byte[] payload;

    private ControlFrame(ControlMessageType type, byte[] payload) {
        this.type = type;
        this.payload = payload.clone();
    }

    static ControlFrame of(ControlMessageType type, byte[] payload) {
        if (payload == null || payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("control payload exceeds bound");
        }
        return new ControlFrame(type, payload);
    }

    byte[] encoded() {
        ByteBuffer buffer = ByteBuffer.allocate(12 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC).put((byte) VERSION).put((byte) type.code).put((byte) 1).put((byte) 0)
                .putInt(payload.length).put(payload);
        return buffer.array();
    }

    static ControlFrame decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length < 12 || encoded.length > MAX_FRAME) {
            throw new IOException("control frame size is invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
            throw new IOException("control frame magic or version is invalid");
        }
        ControlMessageType type = ControlMessageType.fromCode(buffer.get() & 255);
        if ((buffer.get() & 255) != 1 || buffer.get() != 0) {
            throw new IOException("control frame message version or flags are invalid");
        }
        int length = buffer.getInt();
        if (length < 0 || length > MAX_PAYLOAD || length != buffer.remaining()) {
            throw new IOException("control frame payload length is invalid");
        }
        return of(type, Arrays.copyOfRange(encoded, 12, encoded.length));
    }
}
