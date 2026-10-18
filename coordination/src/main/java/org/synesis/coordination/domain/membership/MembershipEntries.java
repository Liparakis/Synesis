package org.synesis.coordination.domain.membership;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.synesis.coordination.domain.identity.WireFormat;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * The signed payloads of the membership entries (ADR-0098 step 6, "Membership and keys"; designer items 7 to 12).
 * Keys are X.509-encoded Ed25519 public keys, as node keys are; an empty key means none.
 */
public final class MembershipEntries {

    private static final WireFormat KEYS = WireFormat.bounded(256, true, "key bound", "truncated payload");
    private static final WireFormat TEXT = WireFormat.bounded(4096, false, "text bound", "truncated payload");
    private static final WireFormat SIGNATURE = WireFormat.bounded(128, true, "signature bound", "truncated payload");

    private MembershipEntries() {
    }

    /** Who may answer a join request besides the owner. */
    public enum JoinPolicy {
        /** Only the owner answers. */
        OWNER_ONLY,
        /** Any member may accept; a decline stays the owner's. */
        ANY_MEMBER
    }

    /**
     * {@code PROJECT_SHARED}: the owner's computer shares the project. Its node key is the owner key.
     *
     * @param ownerPublicKey    the owner computer's node public key
     * @param recoveryPublicKey the recovery key's public half, or empty when the project has none yet
     */
    public record ProjectShared(byte[] ownerPublicKey, byte[] recoveryPublicKey) {

        private static final int MAGIC = 0x534D5331;

        /** Validates the keys. */
        public ProjectShared {
            ownerPublicKey = requireKey(ownerPublicKey, false);
            recoveryPublicKey = requireKey(recoveryPublicKey, true);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                KEYS.writeBytes(out, ownerPublicKey);
                KEYS.writeBytes(out, recoveryPublicKey);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static ProjectShared decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new ProjectShared(KEYS.readBytes(in), KEYS.readBytes(in)));
        }
    }

    /**
     * {@code JOIN_REQUESTED}: a member holds a joiner's session and asks for it to be let in. The joiner is named by
     * its node ID, which its key derives to: the session that holds it was authenticated with that key.
     *
     * @param requestId    the request
     * @param joinerNodeId the joining computer
     */
    public record JoinRequested(UUID requestId, String joinerNodeId) {

        private static final int MAGIC = 0x534A5231;

        /** Validates the fields. */
        public JoinRequested {
            CoordinationNullException.requireNonNull(requestId, "requestId");
            requireText(joinerNodeId);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                writeUuid(out, requestId);
                TEXT.writeText(out, joinerNodeId);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static JoinRequested decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new JoinRequested(readUuid(in), TEXT.readText(in)));
        }
    }

    /**
     * {@code JOIN_ANSWERED}: a join request accepted or declined.
     *
     * @param requestId    the request answered
     * @param joinerNodeId the joining computer the request names
     * @param accepted     whether it is let in
     * @param revision     the membership revision its writer held
     */
    public record JoinAnswered(UUID requestId, String joinerNodeId, boolean accepted, long revision) {

        private static final int MAGIC = 0x534A4131;

        /** Validates the fields. */
        public JoinAnswered {
            CoordinationNullException.requireNonNull(requestId, "requestId");
            requireText(joinerNodeId);
            requireRevision(revision);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                writeUuid(out, requestId);
                TEXT.writeText(out, joinerNodeId);
                out.writeBoolean(accepted);
                out.writeLong(revision);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static JoinAnswered decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new JoinAnswered(readUuid(in), TEXT.readText(in), in.readBoolean(), in.readLong()));
        }
    }

    /**
     * {@code JOIN_POLICY_SET}: the owner says who may answer a join.
     *
     * @param policy   the policy
     * @param revision the membership revision the owner held
     */
    public record JoinPolicySet(JoinPolicy policy, long revision) {

        private static final int MAGIC = 0x534A5031;

        /** Validates the fields. */
        public JoinPolicySet {
            CoordinationNullException.requireNonNull(policy, "policy");
            requireRevision(revision);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                TEXT.writeText(out, policy.name());
                out.writeLong(revision);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed, or the policy is unknown
         */
        public static JoinPolicySet decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new JoinPolicySet(JoinPolicy.valueOf(TEXT.readText(in)), in.readLong()));
        }
    }

    /**
     * {@code MEMBER_REMOVED}: the owner removes a computer.
     *
     * @param nodeId   the removed computer
     * @param head     the number of entries of its log the owner had seen; later ones count for nothing
     * @param revision the membership revision the owner held
     */
    public record MemberRemoved(String nodeId, long head, long revision) {

        private static final int MAGIC = 0x534D5231;

        /** Validates the fields. */
        public MemberRemoved {
            requireText(nodeId);
            if (head < 0) {
                throw new CoordinationArgumentException("head must not be negative");
            }
            requireRevision(revision);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                TEXT.writeText(out, nodeId);
                out.writeLong(head);
                out.writeLong(revision);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static MemberRemoved decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new MemberRemoved(TEXT.readText(in), in.readLong(), in.readLong()));
        }
    }

    /**
     * {@code MEMBER_CARD}: a member's retained invitation of its own listener, which other members dial.
     *
     * @param invitation the invitation link
     */
    public record MemberCard(String invitation) {

        private static final int MAGIC = 0x534D4331;

        /** Validates the fields. */
        public MemberCard {
            requireText(invitation);
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> TEXT.writeText(out, invitation));
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static MemberCard decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new MemberCard(TEXT.readText(in)));
        }
    }

    /**
     * {@code OWNER_CHANGED}: ownership moves to another node's key, the recovery key is replaced, or both.
     *
     * @param ownerPublicKey    the new owner's node public key
     * @param recoveryPublicKey the new recovery key's public half, or empty to keep none
     * @param revision          the membership revision its writer held
     * @param recoverySignature the recovery key's signature over {@link #recoveryStatement}, or empty when the
     *                          current owner signs the entry itself
     */
    public record OwnerChanged(byte[] ownerPublicKey, byte[] recoveryPublicKey, long revision, byte[] recoverySignature) {

        private static final int MAGIC = 0x534F4331;

        /** Validates the fields. */
        public OwnerChanged {
            ownerPublicKey = requireKey(ownerPublicKey, false);
            recoveryPublicKey = requireKey(recoveryPublicKey, true);
            requireRevision(revision);
            recoverySignature = CoordinationNullException.requireNonNull(recoverySignature, "recoverySignature").clone();
            if (recoverySignature.length > 128) {
                throw new CoordinationArgumentException("recovery signature out of bounds");
            }
        }

        /**
         * The bytes the recovery key signs: the project, the action, the new keys and the revision, so a recovery
         * statement cannot be replayed in another project or at another point.
         *
         * @param projectId the project
         * @return the statement
         */
        public byte[] recoveryStatement(UUID projectId) {
            return statement(projectId, ownerPublicKey, recoveryPublicKey, revision);
        }

        /**
         * The bytes a recovery key signs for an owner change.
         *
         * @param projectId         the project
         * @param ownerPublicKey    the new owner's key
         * @param recoveryPublicKey the new recovery key, or empty
         * @param revision          the revision
         * @return the statement
         */
        public static byte[] statement(UUID projectId, byte[] ownerPublicKey, byte[] recoveryPublicKey, long revision) {
            byte[] domain = "synesis/OWNER_CHANGED/v1".getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(domain.length + 16 + 4 + ownerPublicKey.length + 4 + recoveryPublicKey.length + 8);
            buffer.put(domain).putLong(projectId.getMostSignificantBits()).putLong(projectId.getLeastSignificantBits())
                    .putInt(ownerPublicKey.length).put(ownerPublicKey).putInt(recoveryPublicKey.length).put(recoveryPublicKey).putLong(revision);
            return buffer.array();
        }

        /**
         * Returns the encoded payload.
         *
         * @return the encoded payload
         */
        public byte[] encode() {
            return WireFormat.encode(MAGIC, out -> {
                KEYS.writeBytes(out, ownerPublicKey);
                KEYS.writeBytes(out, recoveryPublicKey);
                out.writeLong(revision);
                SIGNATURE.writeBytes(out, recoverySignature);
            });
        }

        /**
         * Decodes and validates one payload.
         *
         * @param encoded bytes
         * @return the payload
         * @throws IOException when malformed
         */
        public static OwnerChanged decode(byte[] encoded) throws IOException {
            return read(encoded, MAGIC, in -> new OwnerChanged(KEYS.readBytes(in), KEYS.readBytes(in), in.readLong(), SIGNATURE.readBytes(in)));
        }
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static <T> T read(byte[] encoded, int magic, Reader<T> reader) throws IOException {
        CoordinationNullException.requireNonNull(encoded, "encoded");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            if (in.readInt() != magic) {
                throw new CoordinationException("unsupported membership payload");
            }
            T value = reader.read(in);
            if (in.available() != 0) {
                throw new CoordinationException("trailing membership bytes");
            }
            return value;
        } catch (RuntimeException | EOFException failure) {
            throw new CoordinationException("malformed membership payload", failure);
        }
    }

    private static byte[] requireKey(byte[] key, boolean emptyAllowed) {
        CoordinationNullException.requireNonNull(key, "key");
        if (key.length > 256 || key.length == 0 && !emptyAllowed) {
            throw new CoordinationArgumentException("key out of bounds");
        }
        return key.clone();
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank() || text.length() > 4096) {
            throw new CoordinationArgumentException("membership text out of bounds");
        }
    }

    private static void requireRevision(long revision) {
        if (revision < 0) {
            throw new CoordinationArgumentException("revision must not be negative");
        }
    }

    private static void writeUuid(java.io.DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
