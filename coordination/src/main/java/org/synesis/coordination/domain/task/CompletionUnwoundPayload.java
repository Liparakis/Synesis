package org.synesis.coordination.domain.task;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Signed payload for an authorized unwind of a prepared, unpublished completion. The replacement intent is carried in
 * the same event so
 * replay cannot expose a fenced task with an unowned claim epoch.
 *
 * @param prepared          original durable preparation
 * @param replacementIntent next claim epoch for the same lane
 */
public record CompletionUnwoundPayload(CompletionPreparedPayload prepared, WorkIntent replacementIntent) {

    private static final int MAGIC = 0x53555731;
    private static final WireFormat BYTES = WireFormat.bounded(1_000_000, false, "payload bound", "truncated payload");

    /**
     * Validates the payload fields.
     */
    public CompletionUnwoundPayload {
        CoordinationNullException.requireNonNull(prepared, "prepared");
        CoordinationNullException.requireNonNull(replacementIntent, "replacementIntent");
        if (!prepared.laneId().equals(replacementIntent.intentId())) {
            throw new CoordinationArgumentException("replacement intent must retain lane identity");
        }
        if (replacementIntent.version() <= prepared.claimEpoch()) {
            throw new CoordinationArgumentException("replacement intent must advance claim epoch");
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
        CoordinationNullException.requireNonNull(encoded, "encoded");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) {
                throw new CoordinationException("unsupported unwind format");
            }
            byte[] preparedBytes = BYTES.readBytes(in);
            byte[] intentBytes = BYTES.readBytes(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing unwind bytes");
            }
            return new CompletionUnwoundPayload(CompletionPreparedPayload.decode(preparedBytes),
                    CollaborationCodec.decodeIntent(intentBytes));
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed completion unwind", failure);
        }
    }

    /**
     * Encodes this payload deterministically.
     *
     * @return encoded bytes
     */
    public byte[] encode() {
        return WireFormat.encode(MAGIC, out -> {
            BYTES.writeBytes(out, prepared.encode());
            BYTES.writeBytes(out, CollaborationCodec.encodeIntent(replacementIntent));
        });
    }
}
