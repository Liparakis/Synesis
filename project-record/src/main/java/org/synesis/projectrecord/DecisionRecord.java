package org.synesis.projectrecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable canonical signed decision revision v2 (SDR2).
 *
 * <p>Supports explicit record types (DECISION vs PROJECT_CONSTRAINT) with
 * deterministic binary serialization. Instances are immutable and thread-safe.
 *
 * @since 1.0
 */
public final class DecisionRecord {
    /** Maximum complete encoded record size. */
    public static final int MAX_BYTES = 16_384;
    /** Maximum UTF-8 title size. */
    public static final int MAX_TITLE_BYTES = 512;
    /** Maximum UTF-8 rationale size. */
    public static final int MAX_RATIONALE_BYTES = 4_096;
    /** Maximum evidence references per revision. */
    public static final int MAX_EVIDENCE = 8;

    private static final int MAGIC = 0x53445232;
    private static final int VERSION = 2;
    private static final int MAX_NODE_ID_BYTES = 128;
    private static final int MAX_KEY_BYTES = 256;
    private static final int MAX_SIGNATURE_BYTES = 128;

    /** Record classification. */
    public enum RecordType {
        /** General architectural or project decision. */
        DECISION,
        /** Enforceable project constraint record. */
        PROJECT_CONSTRAINT
    }

    /**
     * Bounded typed constraint payload embedded in SDR2 PROJECT_CONSTRAINT records.
     *
     * @param effect     enforcement effect
     * @param status     lifecycle status
     * @param scopes     target path scopes
     * @param supersedes list of superseded record UUIDs
     */
    public record ConstraintPayload(
            ProjectConstraint.Effect effect,
            ProjectConstraint.ConstraintStatus status,
            List<String> scopes,
            List<UUID> supersedes
    ) {
        /**
         * Validates constraint payload fields.
         */
        public ConstraintPayload {
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(scopes, "scopes");
            Objects.requireNonNull(supersedes, "supersedes");
            if (scopes.isEmpty() || scopes.size() > 16) {
                throw new IllegalArgumentException("scopes count outside bound");
            }
            List<String> normScopes = new ArrayList<>();
            for (String s : scopes) {
                String n = ScopeMatcher.normalizePath(s);
                if (normScopes.contains(n)) {
                    throw new IllegalArgumentException("duplicate normalized scope: " + n);
                }
                normScopes.add(n);
            }
            scopes = List.copyOf(normScopes);
            if (supersedes.size() > 16) {
                throw new IllegalArgumentException("supersedes count outside bound");
            }
            supersedes = List.copyOf(supersedes.stream().distinct().toList());
        }
    }

    private final UUID projectId;
    private final UUID recordId;
    private final RecordType recordType;
    private final long revision;
    private final byte[] previousDigest;
    private final String ownerNodeId;
    private final String authorNodeId;
    private final DecisionStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String title;
    private final String rationale;
    private final List<DecisionEvidence> evidence;
    private final ConstraintPayload constraintPayload;
    private final byte[] publicKey;
    private final byte[] unsignedBytes;
    private final byte[] signature;
    private final byte[] encoded;
    private final byte[] digest;

    private DecisionRecord(UUID projectId, UUID recordId, RecordType recordType, long revision,
                           byte[] previousDigest, String ownerNodeId, String authorNodeId,
                           DecisionStatus status, Instant createdAt, Instant updatedAt, String title,
                           String rationale, List<DecisionEvidence> evidence,
                           ConstraintPayload constraintPayload, byte[] publicKey, byte[] signature) {
        this.projectId = Objects.requireNonNull(projectId, "project ID");
        this.recordId = Objects.requireNonNull(recordId, "record ID");
        this.recordType = Objects.requireNonNull(recordType, "record type");
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        this.revision = revision;
        if ((revision == 1) != (previousDigest == null)) {
            throw new IllegalArgumentException("revision one must have no predecessor");
        }
        if (previousDigest != null && previousDigest.length != 32) {
            throw new IllegalArgumentException("predecessor digest must be 32 bytes");
        }
        this.previousDigest = previousDigest == null ? null : previousDigest.clone();
        this.ownerNodeId = nodeId(ownerNodeId, "owner node ID");
        this.authorNodeId = nodeId(authorNodeId, "author node ID");
        if (!this.ownerNodeId.equals(this.authorNodeId)) {
            throw new IllegalArgumentException("v2 author must equal owner");
        }
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = canonicalInstant(createdAt, "created at");
        this.updatedAt = canonicalInstant(updatedAt, "updated at");
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("updated at precedes created at");
        this.title = text(title, MAX_TITLE_BYTES, "title");
        this.rationale = text(rationale, MAX_RATIONALE_BYTES, "rationale");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.isEmpty() || evidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException("evidence count is outside the supported bound");
        }
        this.evidence = evidence.stream().map(Objects::requireNonNull)
                .sorted(Comparator.comparing(DecisionEvidence::kind)
                        .thenComparing(DecisionEvidence::reference)
                        .thenComparing(DecisionEvidence::digestHex))
                .toList();

        if (recordType == RecordType.PROJECT_CONSTRAINT) {
            this.constraintPayload = Objects.requireNonNull(constraintPayload, "constraint payload required");
        } else {
            if (constraintPayload != null) {
                throw new IllegalArgumentException("non-constraint record must not carry constraint payload");
            }
            this.constraintPayload = null;
        }

        Objects.requireNonNull(publicKey, "public key");
        if (publicKey.length == 0 || publicKey.length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("public key exceeds the supported bound");
        }
        this.publicKey = publicKey.clone();
        Objects.requireNonNull(signature, "signature");
        if (signature.length == 0 || signature.length > MAX_SIGNATURE_BYTES) {
            throw new IllegalArgumentException("signature exceeds the supported bound");
        }
        this.signature = signature.clone();
        this.unsignedBytes = encodeUnsigned();
        this.encoded = encodeComplete();
        if (encoded.length > MAX_BYTES) throw new IllegalArgumentException("record exceeds the supported bound");
        this.digest = sha256(encoded);
    }

    /**
     * Creates and signs an ordinary decision record.
     *
     * @param projectId      stable local project namespace
     * @param recordId       stable decision identity
     * @param revision       positive revision number
     * @param previousDigest predecessor digest, or null only for revision one
     * @param ownerNodeId    immutable signer node ID
     * @param authorNodeId   provenance author ID; must equal owner
     * @param status         decision status
     * @param createdAt      millisecond-precision UTC provenance timestamp
     * @param updatedAt      millisecond-precision UTC provenance timestamp
     * @param title          bounded readable title
     * @param rationale      bounded readable rationale
     * @param evidence       at least one bounded evidence reference
     * @param signer         Ed25519 signer
     * @return a canonical signed record
     * @throws GeneralSecurityException if signing fails
     */
    public static DecisionRecord create(UUID projectId, UUID recordId, long revision, byte[] previousDigest,
                                        String ownerNodeId, String authorNodeId, DecisionStatus status, Instant createdAt,
                                        Instant updatedAt, String title, String rationale, List<DecisionEvidence> evidence,
                                        Ed25519Signer signer) throws GeneralSecurityException {
        return createTyped(projectId, recordId, RecordType.DECISION, revision, previousDigest, ownerNodeId,
                authorNodeId, status, createdAt, updatedAt, title, rationale, evidence, null, signer);
    }

    /**
     * Creates and signs a typed project constraint record.
     *
     * @param projectId         stable local project namespace
     * @param recordId          stable decision identity
     * @param revision          positive revision number
     * @param previousDigest    predecessor digest, or null only for revision one
     * @param ownerNodeId       immutable signer node ID
     * @param authorNodeId      provenance author ID; must equal owner
     * @param status            decision status
     * @param createdAt         millisecond-precision UTC provenance timestamp
     * @param updatedAt         millisecond-precision UTC provenance timestamp
     * @param title             bounded readable title
     * @param rationale         bounded readable rationale
     * @param evidence          at least one bounded evidence reference
     * @param constraintPayload explicit constraint payload
     * @param signer            Ed25519 signer
     * @return a canonical signed project constraint record
     * @throws GeneralSecurityException if signing fails
     */
    public static DecisionRecord createConstraint(UUID projectId, UUID recordId, long revision, byte[] previousDigest,
                                                  String ownerNodeId, String authorNodeId, DecisionStatus status, Instant createdAt,
                                                  Instant updatedAt, String title, String rationale, List<DecisionEvidence> evidence,
                                                  ConstraintPayload constraintPayload, Ed25519Signer signer) throws GeneralSecurityException {
        return createTyped(projectId, recordId, RecordType.PROJECT_CONSTRAINT, revision, previousDigest, ownerNodeId,
                authorNodeId, status, createdAt, updatedAt, title, rationale, evidence, constraintPayload, signer);
    }

    private static DecisionRecord createTyped(UUID projectId, UUID recordId, RecordType recordType, long revision, byte[] previousDigest,
                                              String ownerNodeId, String authorNodeId, DecisionStatus status, Instant createdAt,
                                              Instant updatedAt, String title, String rationale, List<DecisionEvidence> evidence,
                                              ConstraintPayload constraintPayload, Ed25519Signer signer) throws GeneralSecurityException {
        Objects.requireNonNull(signer, "signer");
        if (!signer.nodeId().equals(ownerNodeId)) throw new IllegalArgumentException("signer does not match owner");
        DecisionRecord unsigned = new DecisionRecord(projectId, recordId, recordType, revision, previousDigest,
                ownerNodeId, authorNodeId, status, createdAt, updatedAt, title, rationale, evidence, constraintPayload,
                signer.publicKeyEncoded(), new byte[]{0});
        byte[] signature = signer.sign(unsigned.unsignedBytes);
        return new DecisionRecord(projectId, recordId, recordType, revision, previousDigest, ownerNodeId, authorNodeId,
                status, createdAt, updatedAt, title, rationale, evidence, constraintPayload, signer.publicKeyEncoded(), signature);
    }

    /**
     * Decodes a bounded record without trusting its signature.
     *
     * @param bytes complete SDR2 bytes
     * @return decoded immutable record
     * @throws IOException if framing, UTF-8, bounds, or values are invalid
     */
    public static DecisionRecord decode(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("record exceeds bound");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("unsupported decision record version: expected SDR2");
            }
            UUID projectId = readUuid(input);
            UUID recordId = readUuid(input);
            int typeOrd = input.readUnsignedByte();
            if (typeOrd >= RecordType.values().length) throw new IOException("unknown record type");
            RecordType recordType = RecordType.values()[typeOrd];
            long revision = input.readLong();
            int predecessorFlag = input.readUnsignedByte();
            if (predecessorFlag > 1) throw new IOException("invalid predecessor marker");
            byte[] previousDigest = predecessorFlag == 0 ? null : readExact(input, 32, 32);
            String owner = readText(input, MAX_NODE_ID_BYTES);
            String author = readText(input, MAX_NODE_ID_BYTES);
            int status = input.readUnsignedByte();
            if (status >= DecisionStatus.values().length) throw new IOException("unknown decision status");
            Instant createdAt = readInstant(input);
            Instant updatedAt = readInstant(input);
            String title = readText(input, MAX_TITLE_BYTES);
            String rationale = readText(input, MAX_RATIONALE_BYTES);
            int count = input.readUnsignedByte();
            if (count == 0 || count > MAX_EVIDENCE) throw new IOException("invalid evidence count");
            List<DecisionEvidence> evidence = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                evidence.add(new DecisionEvidence(readText(input, DecisionEvidence.MAX_KIND_BYTES),
                        readText(input, DecisionEvidence.MAX_REFERENCE_BYTES), readExact(input, 32, 32)));
            }

            ConstraintPayload constraintPayload = null;
            if (recordType == RecordType.PROJECT_CONSTRAINT) {
                int effOrd = input.readUnsignedByte();
                if (effOrd >= ProjectConstraint.Effect.values().length) throw new IOException("invalid effect");
                int statOrd = input.readUnsignedByte();
                if (statOrd >= ProjectConstraint.ConstraintStatus.values().length) throw new IOException("invalid status");
                int scopeCount = input.readUnsignedByte();
                if (scopeCount == 0 || scopeCount > 16) throw new IOException("invalid scope count");
                List<String> scopes = new ArrayList<>(scopeCount);
                for (int i = 0; i < scopeCount; i++) {
                    scopes.add(readText(input, 1_024));
                }
                int supCount = input.readUnsignedByte();
                if (supCount > 16) throw new IOException("invalid supersedes count");
                List<UUID> supersedes = new ArrayList<>(supCount);
                for (int i = 0; i < supCount; i++) {
                    supersedes.add(readUuid(input));
                }
                constraintPayload = new ConstraintPayload(ProjectConstraint.Effect.values()[effOrd],
                        ProjectConstraint.ConstraintStatus.values()[statOrd], scopes, supersedes);
            }

            byte[] publicKey = readBytes(input, MAX_KEY_BYTES);
            byte[] signature = readBytes(input, MAX_SIGNATURE_BYTES);
            if (input.available() != 0) throw new IOException("trailing record bytes");
            return new DecisionRecord(projectId, recordId, recordType, revision, previousDigest, owner, author,
                    DecisionStatus.values()[status], createdAt, updatedAt, title, rationale, evidence,
                    constraintPayload, publicKey, signature);
        } catch (EOFException | DateTimeException | IllegalArgumentException exception) {
            throw new IOException("malformed decision record", exception);
        }
    }

    /**
     * Returns a copy of the complete encoded bytes.
     *
     * @return encoded bytes
     */
    public byte[] encoded() { return encoded.clone(); }

    /**
     * Returns a copy of the SHA-256 digest.
     *
     * @return digest bytes
     */
    public byte[] digest() { return digest.clone(); }

    /**
     * Returns the hex-encoded digest string.
     *
     * @return hex digest string
     */
    public String digestHex() { return HexFormat.of().formatHex(digest); }

    /**
     * Verifies the cryptographic signature of this record against its owner public key.
     *
     * @return true if valid
     * @throws GeneralSecurityException if verification fails
     */
    public boolean verify() throws GeneralSecurityException {
        return Ed25519Signer.deriveNodeId(publicKey).equals(ownerNodeId)
                && ownerNodeId.equals(authorNodeId)
                && Ed25519Signer.verify(publicKey, unsignedBytes, signature);
    }

    /**
     * Returns the project ID.
     *
     * @return project ID
     */
    public UUID projectId() { return projectId; }

    /**
     * Returns the record ID.
     *
     * @return record ID
     */
    public UUID recordId() { return recordId; }

    /**
     * Returns the record type.
     *
     * @return record type
     */
    public RecordType recordType() { return recordType; }

    /**
     * Returns the revision number.
     *
     * @return revision number
     */
    public long revision() { return revision; }

    /**
     * Returns a copy of the previous revision digest.
     *
     * @return previous digest bytes or null
     */
    public byte[] previousDigest() { return previousDigest == null ? null : previousDigest.clone(); }

    /**
     * Returns the owner node ID.
     *
     * @return owner node ID
     */
    public String ownerNodeId() { return ownerNodeId; }

    /**
     * Returns the author node ID.
     *
     * @return author node ID
     */
    public String authorNodeId() { return authorNodeId; }

    /**
     * Returns the decision status.
     *
     * @return status
     */
    public DecisionStatus status() { return status; }

    /**
     * Returns the created instant.
     *
     * @return created instant
     */
    public Instant createdAt() { return createdAt; }

    /**
     * Returns the updated instant.
     *
     * @return updated instant
     */
    public Instant updatedAt() { return updatedAt; }

    /**
     * Returns the title.
     *
     * @return title
     */
    public String title() { return title; }

    /**
     * Returns the rationale.
     *
     * @return rationale
     */
    public String rationale() { return rationale; }

    /**
     * Returns the evidence list.
     *
     * @return evidence
     */
    public List<DecisionEvidence> evidence() { return evidence; }

    /**
     * Returns the constraint payload if record type is PROJECT_CONSTRAINT, or null.
     *
     * @return constraint payload or null
     */
    public ConstraintPayload constraintPayload() { return constraintPayload; }

    /**
     * Returns a copy of the owner public key bytes.
     *
     * @return public key bytes
     */
    public byte[] publicKeyEncoded() { return publicKey.clone(); }

    /**
     * Returns a copy of the Ed25519 signature bytes.
     *
     * @return signature bytes
     */
    public byte[] signature() { return signature.clone(); }

    private byte[] encodeUnsigned() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                writeUuid(output, projectId);
                writeUuid(output, recordId);
                output.writeByte(recordType.ordinal());
                output.writeLong(revision);
                output.writeByte(previousDigest == null ? 0 : 1);
                if (previousDigest != null) output.write(previousDigest);
                writeText(output, ownerNodeId);
                writeText(output, authorNodeId);
                output.writeByte(status.ordinal());
                output.writeLong(createdAt.toEpochMilli());
                output.writeLong(updatedAt.toEpochMilli());
                writeText(output, title);
                writeText(output, rationale);
                output.writeByte(evidence.size());
                for (DecisionEvidence item : evidence) {
                    writeText(output, item.kind());
                    writeText(output, item.reference());
                    output.write(item.digest());
                }
                if (recordType == RecordType.PROJECT_CONSTRAINT) {
                    output.writeByte(constraintPayload.effect().ordinal());
                    output.writeByte(constraintPayload.status().ordinal());
                    output.writeByte(constraintPayload.scopes().size());
                    for (String s : constraintPayload.scopes()) {
                        writeText(output, s);
                    }
                    output.writeByte(constraintPayload.supersedes().size());
                    for (UUID u : constraintPayload.supersedes()) {
                        writeUuid(output, u);
                    }
                }
                writeBytes(output, publicKey);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("byte array output cannot fail", impossible);
        }
    }

    private byte[] encodeComplete() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(unsignedBytes.length + signature.length + 2);
            bytes.write(unsignedBytes);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeBytes(output, signature);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("byte array output cannot fail", impossible);
        }
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        try {
            return Instant.ofEpochMilli(input.readLong());
        } catch (DateTimeException exception) {
            throw new IOException("invalid timestamp", exception);
        }
    }

    private static byte[] readExact(DataInputStream input, int length, int max) throws IOException {
        if (length <= 0 || length > max || input.available() < length) throw new IOException("invalid fixed field");
        return input.readNBytes(length);
    }

    private static byte[] readBytes(DataInputStream input, int max) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > max || input.available() < length) throw new IOException("invalid bounded field");
        return input.readNBytes(length);
    }

    private static String readText(DataInputStream input, int max) throws IOException {
        byte[] bytes = readBytes(input, max);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("invalid UTF-8", exception);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) throw new IllegalArgumentException("text field exceeds encoding bound");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 65_535) throw new IllegalArgumentException("byte field exceeds bound");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String text(String value, int maxBytes, String name) {
        Objects.requireNonNull(value, name);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maxBytes || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " exceeds its supported bound");
        }
        return value;
    }

    private static String nodeId(String value, String name) {
        String result = text(value, MAX_NODE_ID_BYTES, name);
        if (!result.matches("sl1-[0-9a-f]{64}")) throw new IllegalArgumentException("invalid " + name);
        return result;
    }

    private static Instant canonicalInstant(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException(name + " must have millisecond precision");
        return value;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the JDK", impossible);
        }
    }
}
