package org.synesis.link.overlay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.link.identity.NodeIdentity;

/**
 * A bounded, signed project membership snapshot used by the Link overlay.
 *
 * <p>The snapshot authenticates membership only. It does not authorize a
 * physical socket, select a route, or make its issuer a permanent router.
 */
public final class OverlayMembershipSnapshot {

    /** Maximum encoded membership snapshot size. */
    public static final int MAX_BYTES = 16_384;
    /** Maximum number of members in one snapshot. */
    public static final int MAX_MEMBERS = 32;
    /** Active member status. */
    public static final int STATUS_ACTIVE = 0;
    /** Revoked member status. */
    public static final int STATUS_REVOKED = 1;

    private static final int MAGIC = 0x534C4D31;
    private static final int VERSION = 1;
    private static final int SIGNATURE_BYTES = OverlayCodecSupport.SIGNATURE_BYTES;
    private final UUID projectId;
    private final String authorityNodeId;
    private final long revision;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final List<Member> members;
    private final byte[] unsignedBytes;
    private final byte[] signature;
    private final byte[] encoded;

    private OverlayMembershipSnapshot(UUID projectId, String authorityNodeId, long revision,
            Instant issuedAt, Instant expiresAt, List<Member> members, byte[] unsignedBytes, byte[] signature) {
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.authorityNodeId = Objects.requireNonNull(authorityNodeId, "authority node ID");
        OverlayCodecSupport.requireNodeId(authorityNodeId);
        if (revision <= 0) {
            throw new IllegalArgumentException("membership revision must be positive");
        }
        this.revision = revision;
        OverlayCodecSupport.requireMillis(issuedAt, "issued at");
        OverlayCodecSupport.requireMillis(expiresAt, "expires at");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("membership expiry must follow issue time");
        }
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.members = List.copyOf(Objects.requireNonNull(members, "members"));
        if (this.members.isEmpty() || this.members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("member count exceeds supported bound");
        }
        this.unsignedBytes = Objects.requireNonNull(unsignedBytes, "unsigned bytes").clone();
        this.signature = Objects.requireNonNull(signature, "signature").clone();
        if (this.signature.length != SIGNATURE_BYTES) {
            throw new IllegalArgumentException("membership signature must be Ed25519-sized");
        }
        this.encoded = encodeComplete();
        if (encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("membership snapshot exceeds supported bound");
        }
    }

    /**
     * Creates and signs a canonical membership snapshot.
     *
     * @param projectId project namespace
     * @param revision positive authority revision
     * @param issuedAt issue time with millisecond precision
     * @param expiresAt expiry time with millisecond precision
     * @param members bounded member entries
     * @param authority signing identity for the snapshot
     * @return signed snapshot
     * @throws GeneralSecurityException if signing fails
     */
    public static OverlayMembershipSnapshot create(UUID projectId, long revision, Instant issuedAt,
            Instant expiresAt, List<Member> members, NodeIdentity authority) throws GeneralSecurityException {
        Objects.requireNonNull(authority, "authority");
        List<Member> sorted = normalizeMembers(members);
        Member authorityMember = find(sorted, authority.nodeId())
                .orElseThrow(() -> new IllegalArgumentException("authority is not a member"));
        if (authorityMember.status() != STATUS_ACTIVE
                || !Arrays.equals(authority.publicKeyEncoded(), authorityMember.publicKeyEncoded())) {
            throw new IllegalArgumentException("authority member does not match signing identity");
        }
        byte[] unsigned = encodeUnsigned(projectId, authority.nodeId(), revision, issuedAt, expiresAt, sorted);
        return new OverlayMembershipSnapshot(projectId, authority.nodeId(), revision, issuedAt, expiresAt, sorted,
                unsigned, authority.sign(unsigned));
    }

    /**
     * Decodes a bounded snapshot without treating its signature as valid.
     *
     * @param bytes encoded snapshot
     * @return decoded snapshot
     * @throws IOException if framing or bounds are invalid
     */
    public static OverlayMembershipSnapshot decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("membership snapshot exceeds supported bound");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("unsupported membership snapshot");
            }
            UUID project = OverlayCodecSupport.readUuid(input);
            String authority = OverlayCodecSupport.nodeId(input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
            long revision = input.readLong();
            Instant issued = Instant.ofEpochMilli(input.readLong());
            Instant expires = Instant.ofEpochMilli(input.readLong());
            int count = input.readUnsignedByte();
            if (count == 0 || count > MAX_MEMBERS) {
                throw new IOException("invalid membership count");
            }
            java.util.ArrayList<Member> members = new java.util.ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String nodeId = OverlayCodecSupport.nodeId(input.readNBytes(OverlayCodecSupport.NODE_ID_BYTES));
                int status = input.readUnsignedByte();
                if (status != STATUS_ACTIVE && status != STATUS_REVOKED) {
                    throw new IOException("invalid membership status");
                }
                byte[] publicKey = OverlayCodecSupport.readBytes(input,
                        OverlayCodecSupport.MAX_PUBLIC_KEY_BYTES, "membership public key");
                members.add(new Member(nodeId, status, publicKey));
            }
            int signatureLength = input.readUnsignedShort();
            if (signatureLength != SIGNATURE_BYTES || signatureLength > input.available()) {
                throw new IOException("invalid membership signature");
            }
            byte[] signature = input.readNBytes(signatureLength);
            if (signature.length != signatureLength) {
                throw new IOException("truncated membership signature");
            }
            OverlayCodecSupport.requireNoTrailing(input);
            List<Member> sorted = normalizeMembers(members);
            byte[] unsigned = encodeUnsigned(project, authority, revision, issued, expires, sorted);
            OverlayMembershipSnapshot result = new OverlayMembershipSnapshot(project, authority, revision, issued,
                    expires, sorted, unsigned, signature);
            if (!Arrays.equals(result.encoded, bytes)) {
                throw new IOException("non-canonical membership snapshot");
            }
            return result;
        } catch (java.io.EOFException | IllegalArgumentException | java.time.DateTimeException failure) {
            throw new IOException("malformed membership snapshot", failure);
        }
    }

    private static List<Member> normalizeMembers(List<Member> values) {
        Objects.requireNonNull(values, "members");
        if (values.isEmpty() || values.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("member count exceeds supported bound");
        }
        List<Member> sorted = values.stream()
                .map(value -> Objects.requireNonNull(value, "member"))
                .sorted(Comparator.comparing(Member::nodeId))
                .toList();
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).nodeId().equals(sorted.get(index).nodeId())) {
                throw new IllegalArgumentException("duplicate membership node");
            }
        }
        return sorted;
    }

    private static Optional<Member> find(List<Member> members, String nodeId) {
        return members.stream().filter(member -> member.nodeId().equals(nodeId)).findFirst();
    }

    private static byte[] encodeUnsigned(UUID projectId, String authorityNodeId, long revision, Instant issuedAt,
            Instant expiresAt, List<Member> members) {
        Objects.requireNonNull(projectId, "project ID");
        OverlayCodecSupport.requireNodeId(authorityNodeId);
        if (revision <= 0) {
            throw new IllegalArgumentException("membership revision must be positive");
        }
        OverlayCodecSupport.requireMillis(issuedAt, "issued at");
        OverlayCodecSupport.requireMillis(expiresAt, "expires at");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                OverlayCodecSupport.writeUuid(output, projectId);
                output.write(OverlayCodecSupport.nodeIdBytes(authorityNodeId));
                output.writeLong(revision);
                output.writeLong(issuedAt.toEpochMilli());
                output.writeLong(expiresAt.toEpochMilli());
                output.writeByte(members.size());
                for (Member member : members) {
                    output.write(OverlayCodecSupport.nodeIdBytes(member.nodeId()));
                    output.writeByte(member.status());
                    OverlayCodecSupport.writeBytes(output, member.publicKeyEncoded(),
                            OverlayCodecSupport.MAX_PUBLIC_KEY_BYTES, "membership public key");
                }
            }
            if (bytes.size() > MAX_BYTES) {
                throw new IllegalArgumentException("membership snapshot exceeds supported bound");
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private byte[] encodeComplete() {
        byte[] result = Arrays.copyOf(unsignedBytes, unsignedBytes.length + Short.BYTES + signature.length);
        int signatureOffset = unsignedBytes.length;
        result[signatureOffset] = (byte) (signature.length >>> Byte.SIZE);
        result[signatureOffset + 1] = (byte) signature.length;
        System.arraycopy(signature, 0, result, signatureOffset + Short.BYTES, signature.length);
        return result;
    }

    /**
     * Returns whether the authority signature and all key-to-ID bindings are valid.
     *
     * @return true only for a valid signed snapshot
     */
    public boolean verify() {
        try {
            Member authority = find(members, authorityNodeId).orElse(null);
            return authority != null && authority.status() == STATUS_ACTIVE
                    && OverlayCodecSupport.verifyEd25519(authority.publicKeyEncoded(), unsignedBytes, signature);
        } catch (GeneralSecurityException failure) {
            return false;
        }
    }

    /**
     * Returns whether this verified snapshot is valid at the supplied time.
     *
     * @param now current time
     * @return true when the snapshot is within its validity interval
     */
    public boolean isUsableAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(issuedAt) && now.isBefore(expiresAt) && verify();
    }

    /**
     * Returns the project namespace.
     *
     * @return project ID
     */
    public UUID projectId() {
        return projectId;
    }

    /**
     * Returns the membership authority node ID.
     *
     * @return authority node ID
     */
    public String authorityNodeId() {
        return authorityNodeId;
    }

    /**
     * Returns the monotonic membership revision.
     *
     * @return revision
     */
    public long revision() {
        return revision;
    }

    /**
     * Returns the issue time.
     *
     * @return issue time
     */
    public Instant issuedAt() {
        return issuedAt;
    }

    /**
     * Returns the expiry time.
     *
     * @return expiry time
     */
    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Returns immutable member entries.
     *
     * @return sorted member entries
     */
    public List<Member> members() {
        return members;
    }

    /**
     * Returns one member by stable node ID.
     *
     * @param nodeId node ID
     * @return member, when present
     */
    public Optional<Member> member(String nodeId) {
        return find(members, Objects.requireNonNull(nodeId, "node ID"));
    }

    /**
     * Returns whether a node is an active member.
     *
     * @param nodeId node ID
     * @return true for an active member
     */
    public boolean allows(String nodeId) {
        return member(nodeId).map(value -> value.status() == STATUS_ACTIVE).orElse(false);
    }

    /**
     * Returns the complete canonical encoded snapshot.
     *
     * @return encoded bytes
     */
    public byte[] encoded() {
        return encoded.clone();
    }

    /**
     * One project member's durable signing descriptor.
     */
    public static final class Member {

        private final String nodeId;
        private final int status;
        private final byte[] publicKey;

        /**
         * Creates a member descriptor.
         *
         * @param nodeId node ID derived from the public key
         * @param status {@link OverlayMembershipSnapshot#STATUS_ACTIVE} or revoked
         * @param publicKey X.509 Ed25519 public key
         */
        public Member(String nodeId, int status, byte[] publicKey) {
            OverlayCodecSupport.requireNodeId(nodeId);
            if (status != STATUS_ACTIVE && status != STATUS_REVOKED) {
                throw new IllegalArgumentException("invalid membership status");
            }
            Objects.requireNonNull(publicKey, "public key");
            if (publicKey.length == 0 || publicKey.length > OverlayCodecSupport.MAX_PUBLIC_KEY_BYTES
                    || !NodeIdentity.deriveNodeId(publicKey).equals(nodeId)) {
                throw new IllegalArgumentException("membership key does not match node ID");
            }
            this.nodeId = nodeId;
            this.status = status;
            this.publicKey = publicKey.clone();
        }

        /**
         * Returns the stable node ID.
         *
         * @return node ID
         */
        public String nodeId() {
            return nodeId;
        }

        /**
         * Returns the membership status.
         *
         * @return status constant
         */
        public int status() {
            return status;
        }

        /**
         * Returns a defensive copy of the signing public key.
         *
         * @return public key bytes
         */
        public byte[] publicKeyEncoded() {
            return publicKey.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Member member && nodeId.equals(member.nodeId)
                    && status == member.status && Arrays.equals(publicKey, member.publicKey);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * nodeId.hashCode() + status) + Arrays.hashCode(publicKey);
        }
    }
}
