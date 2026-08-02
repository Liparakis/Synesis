package org.synesis.coordination.domain.collaboration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Signed payload for atomic source-scope transfer to a repair lane.
 *
 * <p>The V2 fields bind the transfer to the immutable conflicting snapshot,
 * the control head from which the repair worktree was materialized, and both
 * claim epochs.  The decoder retains a bounded V1 reader solely so historical
 * event logs remain replayable; new events are always encoded as V2.</p>
 *
 * @param sourceIntentId published source intent
 * @param targetIntent newly authorized repair intent
 * @param snapshotId immutable conflicting snapshot identifier
 * @param expectedControlHead control HEAD used to materialize the repair lane
 * @param sourceClaimEpoch source lane epoch being transferred
 * @param targetClaimEpoch target lane epoch receiving the transfer
 */
public record RepairLanePayload(UUID sourceIntentId, WorkIntent targetIntent,
        String snapshotId, String expectedControlHead,
        long sourceClaimEpoch, long targetClaimEpoch) {
    private static final int V1_MAGIC = 0x52505231;
    private static final int V2_MAGIC = 0x52505232;
    private static final int MAX_TEXT = 64 * 1024;

    /** Validates repair transfer fields. */
    public RepairLanePayload {
        Objects.requireNonNull(sourceIntentId, "source intent");
        Objects.requireNonNull(targetIntent, "target intent");
        Objects.requireNonNull(snapshotId, "snapshot ID");
        Objects.requireNonNull(expectedControlHead, "expected control HEAD");
        if (sourceClaimEpoch < 0 || targetClaimEpoch < 0) {
            throw new IllegalArgumentException("repair claim epochs cannot be negative");
        }
        if (!snapshotId.isBlank() && (sourceClaimEpoch < 1 || targetClaimEpoch < 1)) {
            throw new IllegalArgumentException("versioned repair transfer requires claim epochs");
        }
    }

    /** Creates a metadata-free payload for callers that only replay historical
     * transfer events.  New repair operations should use the full constructor.
     *
     * @param sourceIntentId source intent
     * @param targetIntent target intent
     */
    public RepairLanePayload(UUID sourceIntentId, WorkIntent targetIntent) {
        this(sourceIntentId, targetIntent, "", "", 0L, 0L);
    }

    /** Encodes the transfer payload.
     * @return encoded bytes
     */
    public byte[] encode() {
        try {
            byte[] intent = CollaborationCodec.encodeIntent(targetIntent);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(V2_MAGIC); out.writeLong(sourceIntentId.getMostSignificantBits());
            out.writeLong(sourceIntentId.getLeastSignificantBits()); out.writeInt(intent.length); out.write(intent);
            writeText(out, snapshotId); writeText(out, expectedControlHead);
            out.writeLong(sourceClaimEpoch); out.writeLong(targetClaimEpoch);
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
            int magic = in.readInt();
            if (magic != V1_MAGIC && magic != V2_MAGIC) throw new IOException("unsupported repair payload");
            UUID source = new UUID(in.readLong(), in.readLong()); int length = in.readInt();
            if (length < 1 || length > 1024 * 1024) throw new IOException("repair intent bound");
            byte[] intent = in.readNBytes(length); if (intent.length != length) throw new IOException("truncated repair payload");
            WorkIntent target = CollaborationCodec.decodeIntent(intent);
            if (magic == V1_MAGIC) {
                return new RepairLanePayload(source, target);
            }
            String snapshot = readText(in); String head = readText(in);
            long sourceEpoch = in.readLong(); long targetEpoch = in.readLong();
            return new RepairLanePayload(source, target, snapshot, head, sourceEpoch, targetEpoch);
        } catch (RuntimeException failure) { throw new IOException("malformed repair payload", failure); }
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT) throw new IOException("repair text bound");
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_TEXT) throw new IOException("repair text bound");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated repair text");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
