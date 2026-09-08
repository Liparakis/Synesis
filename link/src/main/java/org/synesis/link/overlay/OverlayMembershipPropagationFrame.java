package org.synesis.link.overlay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded hop-local wrapper for forwarding one signed {@code SLM1} membership
 * snapshot through authenticated direct peers.
 *
 * <p>The snapshot remains byte-for-byte unchanged. Only the propagation budget
 * is consumed at each hop. The wrapper is limited to the existing Link
 * application-frame bound so it cannot create a larger transport message.
 */
public final class OverlayMembershipPropagationFrame {

    /** Maximum number of direct peers through which a snapshot travels. */
    public static final int MAX_HOPS = 8;
    /** Maximum encoded propagation frame size. */
    public static final int MAX_BYTES = OverlayForwardingFrame.MAX_BYTES;

    private static final int MAGIC = 0x534C5032;
    private static final int VERSION = 1;
    private static final int FIXED_BYTES = Integer.BYTES + 1 + 16 + OverlayCodecSupport.NODE_ID_BYTES
            + Long.BYTES + 1 + Short.BYTES;
    private static final int MAX_SNAPSHOT_BYTES = MAX_BYTES - FIXED_BYTES;
    private final OverlayMembershipSnapshot membership;
    private final int remainingHops;
    private final byte[] encoded;

    private OverlayMembershipPropagationFrame(OverlayMembershipSnapshot membership, int remainingHops) {
        this.membership = Objects.requireNonNull(membership, "membership");
        if (membership.encoded().length > MAX_SNAPSHOT_BYTES) {
            throw new IllegalArgumentException("membership snapshot exceeds propagation bound");
        }
        if (remainingHops < 0 || remainingHops > MAX_HOPS) {
            throw new IllegalArgumentException("membership propagation hops outside supported bound");
        }
        this.remainingHops = remainingHops;
        this.encoded = encodeComplete();
        if (encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("membership propagation frame exceeds supported bound");
        }
    }

    /**
     * Creates a new propagation frame with a positive bounded budget.
     *
     * @param membership signed membership snapshot
     * @param remainingHops propagation budget
     * @return new propagation frame
     */
    public static OverlayMembershipPropagationFrame create(OverlayMembershipSnapshot membership,
            int remainingHops) {
        if (remainingHops <= 0) {
            throw new IllegalArgumentException("new membership propagation requires a positive hop budget");
        }
        return new OverlayMembershipPropagationFrame(membership, remainingHops);
    }

    /**
     * Decodes one canonical bounded propagation frame.
     *
     * @param bytes encoded propagation frame
     * @return decoded frame
     * @throws IOException if framing, bounds, or field binding is invalid
     */
    public static OverlayMembershipPropagationFrame decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("membership propagation frame exceeds supported bound");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("unsupported membership propagation frame");
            }
            java.util.UUID projectId = OverlayCodecSupport.readUuid(input);
            String authorityNodeId = OverlayCodecSupport.nodeId(input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
            long revision = input.readLong();
            int remainingHops = input.readUnsignedByte();
            int length = input.readUnsignedShort();
            if (length <= 0 || length > MAX_SNAPSHOT_BYTES || length > input.available()) {
                throw new IOException("invalid membership snapshot length");
            }
            byte[] snapshotBytes = input.readNBytes(length);
            OverlayCodecSupport.requireNoTrailing(input);
            OverlayMembershipSnapshot membership = OverlayMembershipSnapshot.decode(snapshotBytes);
            if (!projectId.equals(membership.projectId())
                    || !authorityNodeId.equals(membership.authorityNodeId())
                    || revision != membership.revision()) {
                throw new IOException("membership propagation binding mismatch");
            }
            OverlayMembershipPropagationFrame result = new OverlayMembershipPropagationFrame(membership,
                    remainingHops);
            if (!Arrays.equals(result.encoded, bytes)) {
                throw new IOException("non-canonical membership propagation frame");
            }
            return result;
        } catch (java.io.EOFException | IllegalArgumentException failure) {
            throw new IOException("malformed membership propagation frame", failure);
        }
    }

    /**
     * Returns whether bytes begin with the membership propagation magic.
     *
     * @param bytes candidate wire bytes
     * @return true when the candidate is intended for this decoder
     */
    public static boolean hasMagic(byte[] bytes) {
        return bytes != null && bytes.length >= Integer.BYTES
                && bytes[0] == (byte) (MAGIC >>> 24)
                && bytes[1] == (byte) (MAGIC >>> 16)
                && bytes[2] == (byte) (MAGIC >>> 8)
                && bytes[3] == (byte) MAGIC;
    }

    /**
     * Returns a copy with one propagation hop consumed.
     *
     * @return decremented propagation frame
     * @throws IllegalStateException when no propagation budget remains
     */
    public OverlayMembershipPropagationFrame forwardOneHop() {
        if (remainingHops <= 0) {
            throw new IllegalStateException("membership propagation hop budget exhausted");
        }
        return new OverlayMembershipPropagationFrame(membership, remainingHops - 1);
    }

    /**
     * Returns the signed membership snapshot.
     *
     * @return membership snapshot
     */
    public OverlayMembershipSnapshot membership() {
        return membership;
    }

    /**
     * Returns the remaining propagation budget.
     *
     * @return remaining hops
     */
    public int remainingHops() {
        return remainingHops;
    }

    /**
     * Returns the canonical encoded frame.
     *
     * @return encoded frame bytes
     */
    public byte[] encoded() {
        return encoded.clone();
    }

    private byte[] encodeComplete() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                OverlayCodecSupport.writeUuid(output, membership.projectId());
                output.write(OverlayCodecSupport.nodeIdBytes(membership.authorityNodeId()));
                output.writeLong(membership.revision());
                output.writeByte(remainingHops);
                byte[] membershipBytes = membership.encoded();
                output.writeShort(membershipBytes.length);
                output.write(membershipBytes);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
