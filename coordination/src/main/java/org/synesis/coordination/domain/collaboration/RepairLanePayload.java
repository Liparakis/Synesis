package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Signed payload for atomic source-scope transfer to a repair lane.
 * @param sourceIntentId published source intent
 * @param targetIntent newly authorized repair intent
 */
public record RepairLanePayload(UUID sourceIntentId, WorkIntent targetIntent) {
    private static final int MAGIC = 0x52505231;

    /** Validates repair transfer fields. */
    public RepairLanePayload {
        Objects.requireNonNull(sourceIntentId, "source intent");
        Objects.requireNonNull(targetIntent, "target intent");
    }

    /** Encodes the transfer payload.
     * @return encoded bytes
     */
    public byte[] encode() {
        try {
            byte[] intent = CollaborationCodec.encodeIntent(targetIntent);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeLong(sourceIntentId.getMostSignificantBits());
            out.writeLong(sourceIntentId.getLeastSignificantBits()); out.writeInt(intent.length); out.write(intent);
            out.flush(); return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Decodes the transfer payload.
     * @param encoded bytes
     * @return decoded payload
     * @throws IOException malformed payload
     */
    public static RepairLanePayload decode(byte[] encoded) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != MAGIC) throw new IOException("unsupported repair payload");
            UUID source = new UUID(in.readLong(), in.readLong()); int length = in.readInt();
            if (length < 1 || length > 1024 * 1024) throw new IOException("repair intent bound");
            byte[] intent = in.readNBytes(length); if (intent.length != length) throw new IOException("truncated repair payload");
            return new RepairLanePayload(source, CollaborationCodec.decodeIntent(intent));
        } catch (RuntimeException failure) { throw new IOException("malformed repair payload", failure); }
    }
}
