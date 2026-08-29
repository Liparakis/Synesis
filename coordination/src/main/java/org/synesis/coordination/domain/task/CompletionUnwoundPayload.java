package org.synesis.coordination.domain.task;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.WorkIntent;

/**
 * Signed payload for an authorized unwind of a prepared, unpublished
 * completion. The replacement intent is carried in the same event so replay
 * cannot expose a fenced task with an unowned claim epoch.
 *
 * @param prepared          original durable preparation
 * @param replacementIntent next claim epoch for the same lane
 */
public record CompletionUnwoundPayload(CompletionPreparedPayload prepared,
                                       WorkIntent replacementIntent) {

    private static final int MAGIC = 0x53555731;

    /**
     * Validates the payload fields.
     */
    public CompletionUnwoundPayload {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(replacementIntent, "replacementIntent");
        if (!prepared.laneId()
                .equals(replacementIntent.intentId())) {
            throw new IllegalArgumentException("replacement intent must retain lane identity");
        }
        if (replacementIntent.version() <= prepared.claimEpoch()) {
            throw new IllegalArgumentException("replacement intent must advance claim epoch");
        }
    }

    /**
     * Decodes and validates one payload.
     *
     * @param encoded bytes
     * @return decoded payload
     * @throws IOException malformed payload
     */
    public static CompletionUnwoundPayload decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new IOException("unsupported unwind format");
            }
            byte[] preparedBytes = readBytes(in);
            byte[] intentBytes = readBytes(in);
            if (in.available() != 0) {
                throw new IOException("trailing unwind bytes");
            }
            return new CompletionUnwoundPayload(CompletionPreparedPayload.decode(preparedBytes),
                    CollaborationCodec.decodeIntent(intentBytes));
        } catch (RuntimeException | java.io.EOFException failure) {
            throw new IOException("malformed completion unwind", failure);
        }
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 1 || length > 1_000_000) {
            throw new IOException("payload bound");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated payload");
        }
        return bytes;
    }

    /**
     * Encodes this payload deterministically.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            byte[] preparedBytes = prepared.encode();
            out.writeInt(preparedBytes.length);
            out.write(preparedBytes);
            byte[] intentBytes = CollaborationCodec.encodeIntent(replacementIntent);
            out.writeInt(intentBytes.length);
            out.write(intentBytes);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
