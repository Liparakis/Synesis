package org.synesis.link.protocol;

import java.io.IOException;
import java.util.Objects;

import org.synesis.link.session.HandshakeException;
import org.synesis.link.session.HandshakeFailureCode;

/** Bounded wire message used to close a rejected pre-session handshake. */
public final class HandshakeFailure {

    private static final int MAGIC = 0x534C4631;
    private static final int VERSION = 1;
    private final HandshakeFailureCode code;

    private HandshakeFailure(HandshakeFailureCode code) {
        this.code = Objects.requireNonNull(code, "failure code");
    }

    /**
     * Creates a failure message.
     *
     * @param code stable category
     * @return immutable failure message
     */
    public static HandshakeFailure create(HandshakeFailureCode code) {
        return new HandshakeFailure(code);
    }

    /**
     * Decodes a failure message.
     *
     * @param encoded exactly encoded failure bytes
     * @return decoded failure
     * @throws IOException if the message is malformed
     */
    public static HandshakeFailure decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length != 6) {
            throw new IOException("malformed handshake failure");
        }
        int magic = ((encoded[0] & 255) << 24) | ((encoded[1] & 255) << 16)
                | ((encoded[2] & 255) << 8) | (encoded[3] & 255);
        if (magic != MAGIC || encoded[4] != VERSION || (encoded[5] & 255) >= HandshakeFailureCode.values().length) {
            throw new IOException("malformed handshake failure");
        }
        return create(HandshakeFailureCode.values()[encoded[5] & 255]);
    }

    /**
     * Identifies this message type without allocating parser state.
     *
     * @param encoded bounded candidate bytes
     * @return whether the bytes begin with the failure magic
     */
    public static boolean looksLike(byte[] encoded) {
        return encoded != null && encoded.length >= 4
                && (encoded[0] & 255) == (MAGIC >>> 24)
                && (encoded[1] & 255) == ((MAGIC >>> 16) & 255)
                && (encoded[2] & 255) == ((MAGIC >>> 8) & 255)
                && (encoded[3] & 255) == (MAGIC & 255);
    }

    /**
     * Returns canonical six-byte failure encoding.
     *
     * @return encoded failure
     */
    public byte[] encoded() {
        return new byte[] {(byte) (MAGIC >>> 24), (byte) (MAGIC >>> 16), (byte) (MAGIC >>> 8),
            (byte) MAGIC, VERSION, (byte) code.ordinal()};
    }

    /**
     * Returns the failure category.
     *
     * @return failure code
     */
    public HandshakeFailureCode code() { return code; }

    /**
     * Converts this wire value to the public exception type.
     *
     * @return categorized exception
     */
    public HandshakeException exception() { return new HandshakeException(code); }
}
