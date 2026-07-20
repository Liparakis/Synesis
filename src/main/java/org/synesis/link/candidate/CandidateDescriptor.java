package org.synesis.link.candidate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.security.spec.X509EncodedKeySpec;

import org.synesis.link.identity.NodeIdentity;

/**
 * A bounded, signed, canonical candidate descriptor.
 *
 * <p>Descriptor construction normalizes duplicate candidates and sorts them
 * deterministically. The signature covers every unsigned field, including the
 * signer's public key and validity interval. Decoding validates lengths before
 * allocating collections. Instances are immutable and thread-safe.
 *
 * @since 1.0
 */
public final class CandidateDescriptor {

    /** Maximum encoded descriptor size accepted by the parser. */
    public static final int MAX_BYTES = 8_192;
    /** Maximum candidate count accepted in one descriptor. */
    public static final int MAX_CANDIDATES = 32;
    /** Maximum allowed clock skew for validity checks. */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofMinutes(2);

    private static final int MAGIC = 0x534C4431;
    private static final int VERSION = 1;
    private static final int MAX_NODE_ID_BYTES = 128;
    private static final int MAX_PUBLIC_KEY_BYTES = 256;
    private static final int MAX_SIGNATURE_BYTES = 128;
    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparing((Candidate candidate) -> candidate.type().name())
            .thenComparing(candidate -> HexFormat.of().formatHex(candidate.addressBytes()))
            .thenComparingInt(Candidate::port)
            .thenComparingInt(Candidate::priority);

    private final String nodeId;
    private final byte[] publicKey;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final List<Candidate> candidates;
    private final byte[] signature;
    private final byte[] canonicalBytes;

    private CandidateDescriptor(String nodeId, byte[] publicKey, Instant issuedAt, Instant expiresAt,
            List<Candidate> candidates, byte[] signature) {
        this.nodeId = Objects.requireNonNull(nodeId, "node ID");
        this.publicKey = publicKey.clone();
        this.issuedAt = Objects.requireNonNull(issuedAt, "issued at");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expires at");
        this.candidates = List.copyOf(candidates);
        this.signature = signature.clone();
        this.canonicalBytes = encodeUnsigned(nodeId, this.publicKey, issuedAt, expiresAt, this.candidates);
        if (this.signature.length == 0 || this.signature.length > MAX_SIGNATURE_BYTES
                || this.canonicalBytes.length + 2 + this.signature.length > MAX_BYTES) {
            throw new IllegalArgumentException("descriptor exceeds the supported bound");
        }
    }

    /**
     * Creates a signed descriptor from a node identity.
     *
     * @param identity signing identity
     * @param issuedAt inclusive validity start
     * @param expiresAt exclusive validity end; must be after {@code issuedAt}
     * @param candidates candidate collection; duplicates are removed
     * @return canonical signed descriptor
     * @throws GeneralSecurityException if signing fails
     * @throws IllegalArgumentException if bounds or interval are invalid
     * @throws NullPointerException if an argument is {@code null}
     */
    public static CandidateDescriptor create(NodeIdentity identity, Instant issuedAt, Instant expiresAt,
            Collection<Candidate> candidates) throws GeneralSecurityException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(issuedAt, "issued at");
        Objects.requireNonNull(expiresAt, "expires at");
        List<Candidate> normalized = normalize(candidates);
        byte[] publicKey = identity.publicKeyEncoded();
        byte[] unsigned = encodeUnsigned(identity.nodeId(), publicKey, issuedAt, expiresAt, normalized);
        return new CandidateDescriptor(identity.nodeId(), publicKey, issuedAt, expiresAt, normalized,
                identity.sign(unsigned));
    }

    /**
     * Decodes a bounded descriptor without treating its signature as trusted.
     *
     * @param encoded complete descriptor bytes
     * @return decoded descriptor requiring {@link #verify()} before use
     * @throws IOException if framing, bounds, or values are malformed
     * @throws NullPointerException if {@code encoded} is {@code null}
     */
    public static CandidateDescriptor decode(byte[] encoded) throws IOException {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_BYTES) {
            throw new IOException("descriptor exceeds the supported bound");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IOException("unsupported descriptor version");
            }
            String nodeId = readString(input, MAX_NODE_ID_BYTES);
            byte[] publicKey = readBytes(input, MAX_PUBLIC_KEY_BYTES);
            Instant issuedAt = Instant.ofEpochSecond(input.readLong());
            Instant expiresAt = Instant.ofEpochSecond(input.readLong());
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IOException("descriptor validity interval is empty");
            }
            int count = input.readUnsignedShort();
            if (count > MAX_CANDIDATES) {
                throw new IOException("too many candidates");
            }
            List<Candidate> candidates = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int typeCode = input.readUnsignedByte();
                CandidateType[] types = CandidateType.values();
                if (typeCode >= types.length) {
                    throw new IOException("unknown candidate type");
                }
                byte[] addressBytes = readBytes(input, 16);
                if (addressBytes.length != 4 && addressBytes.length != 16) {
                    throw new IOException("candidate address must be IPv4 or IPv6");
                }
                int port = input.readUnsignedShort();
                if (port == 0) {
                    throw new IOException("candidate port is zero");
                }
                int priority = input.readInt();
                if (priority < 0) {
                    throw new IOException("candidate priority is negative");
                }
                candidates.add(new Candidate(types[typeCode], InetAddress.getByAddress(addressBytes), port, priority));
            }
            byte[] signature = readBytes(input, MAX_SIGNATURE_BYTES);
            if (input.available() != 0) {
                throw new IOException("trailing descriptor bytes");
            }
            List<Candidate> normalized = normalize(candidates);
            if (normalized.size() != candidates.size()) {
                throw new IOException("duplicate candidates are not canonical");
            }
            return new CandidateDescriptor(nodeId, publicKey, issuedAt, expiresAt, normalized, signature);
        } catch (EOFException | java.time.DateTimeException exception) {
            throw new IOException("truncated or invalid descriptor", exception);
        }
    }

    /**
     * Returns the complete canonical descriptor bytes.
     *
     * @return a fresh encoded byte array
     */
    public byte[] encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(canonicalBytes.length + signature.length + 2);
            bytes.write(canonicalBytes);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeShort(signature.length);
                output.write(signature);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("byte array output cannot fail", impossible);
        }
    }

    /**
     * Verifies the descriptor signature and node-ID/public-key binding.
     *
     * @return {@code true} only when the signature and binding are valid
     * @throws GeneralSecurityException if key parsing or signature verification fails
     */
    public boolean verify() throws GeneralSecurityException {
        PublicKey signer = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey));
        if (!NodeIdentity.deriveNodeId(publicKey).equals(nodeId)) {
            return false;
        }
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(signer);
        verifier.update(canonicalBytes);
        return verifier.verify(signature);
    }

    /**
     * Checks signature, node binding, and validity interval at a bounded clock-skew policy.
     *
     * @param now local wall-clock instant used only for descriptor expiry
     * @param allowedClockSkew permitted issue-time skew; non-negative
     * @return {@code true} when the descriptor is authentic and currently valid
     * @throws GeneralSecurityException if signature verification fails
     * @throws IllegalArgumentException if the skew is negative
     */
    public boolean isValidAt(Instant now, Duration allowedClockSkew) throws GeneralSecurityException {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(allowedClockSkew, "allowed clock skew");
        if (allowedClockSkew.isNegative()) {
            throw new IllegalArgumentException("clock skew must be non-negative");
        }
        return verify() && !now.isBefore(issuedAt.minus(allowedClockSkew)) && now.isBefore(expiresAt);
    }

    /**
     * Returns the signer node ID.
     *
     * @return signer node ID; safe to log
     */
    public String nodeId() { return nodeId; }

    /**
     * Returns a copy of the signer's public key bytes.
     *
     * @return X.509 public-key bytes
     */
    public byte[] publicKeyEncoded() { return publicKey.clone(); }

    /**
     * Returns the inclusive issue instant.
     *
     * @return issue instant
     */
    public Instant issuedAt() { return issuedAt; }

    /**
     * Returns the exclusive expiry instant.
     *
     * @return expiry instant
     */
    public Instant expiresAt() { return expiresAt; }

    /**
     * Returns an immutable normalized candidate list.
     *
     * @return sorted, duplicate-free candidates
     */
    public List<Candidate> candidates() { return candidates; }

    private static List<Candidate> normalize(Collection<Candidate> values) {
        Objects.requireNonNull(values, "candidates");
        if (values.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("too many candidates");
        }
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : values) {
            Candidate checked = Objects.requireNonNull(candidate, "candidate");
            unique.putIfAbsent(candidateKey(checked), checked);
        }
        List<Candidate> result = new ArrayList<>(unique.values());
        result.sort(CANDIDATE_ORDER);
        return result;
    }

    private static String candidateKey(Candidate candidate) {
        return candidate.type().name() + ':' + HexFormat.of().formatHex(candidate.addressBytes()) + ':'
                + candidate.port() + ':' + candidate.priority();
    }

    private static byte[] encodeUnsigned(String nodeId, byte[] publicKey, Instant issuedAt, Instant expiresAt,
            List<Candidate> candidates) {
        if (nodeId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_NODE_ID_BYTES
                || publicKey.length == 0 || publicKey.length > MAX_PUBLIC_KEY_BYTES
                || !expiresAt.isAfter(issuedAt) || candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("descriptor exceeds a supported bound");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                writeBytes(output, nodeId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                writeBytes(output, publicKey);
                output.writeLong(issuedAt.getEpochSecond());
                output.writeLong(expiresAt.getEpochSecond());
                output.writeShort(candidates.size());
                for (Candidate candidate : candidates) {
                    byte[] address = candidate.addressBytes();
                    output.writeByte(candidate.type().ordinal());
                    writeBytes(output, address);
                    output.writeShort(candidate.port());
                    output.writeInt(candidate.priority());
                }
            }
            if (bytes.size() > MAX_BYTES) {
                throw new IllegalArgumentException("descriptor exceeds the supported bound");
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("byte array output cannot fail", impossible);
        }
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value.length > 65_535) {
            throw new IllegalArgumentException("field exceeds encoding bound");
        }
        output.writeShort(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input, int max) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > max || length > input.available()) {
            throw new IOException("invalid bounded field length");
        }
        return input.readNBytes(length);
    }

    private static String readString(DataInputStream input, int max) throws IOException {
        return new String(readBytes(input, max), java.nio.charset.StandardCharsets.UTF_8);
    }
}
