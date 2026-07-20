package org.synesis.projectrecord;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable bounded evidence reference included in a decision signature. */
public final class DecisionEvidence {
    static final int MAX_KIND_BYTES = 64;
    static final int MAX_REFERENCE_BYTES = 1_024;

    private final String kind;
    private final String reference;
    private final byte[] digest;

    /**
     * Creates one evidence reference.
     *
     * @param kind short evidence kind
     * @param reference bounded evidence reference
     * @param digest exactly 32 SHA-256 bytes
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if a bound or digest length is invalid
     */
    public DecisionEvidence(String kind, String reference, byte[] digest) {
        this.kind = boundedText(kind, MAX_KIND_BYTES, "kind");
        this.reference = boundedText(reference, MAX_REFERENCE_BYTES, "reference");
        Objects.requireNonNull(digest, "digest");
        if (digest.length != 32) {
            throw new IllegalArgumentException("evidence digest must be 32 bytes");
        }
        this.digest = digest.clone();
    }

    /** Returns the evidence kind, safe to display after newline escaping.
     * @return evidence kind
     */
    public String kind() { return kind; }

    /** Returns the evidence reference, safe to display after newline escaping.
     * @return evidence reference
     */
    public String reference() { return reference; }

    /** Returns a copy of the SHA-256 evidence digest.
     * @return digest bytes
     */
    public byte[] digest() { return digest.clone(); }

    /** Returns the lowercase hexadecimal evidence digest.
     * @return digest text
     */
    public String digestHex() { return HexFormat.of().formatHex(digest); }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DecisionEvidence value)) return false;
        return kind.equals(value.kind) && reference.equals(value.reference) && Arrays.equals(digest, value.digest);
    }

    @Override
    public int hashCode() { return Objects.hash(kind, reference, Arrays.hashCode(digest)); }

    private static String boundedText(String value, int maxBytes, String name) {
        Objects.requireNonNull(value, name);
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > maxBytes || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + " exceeds its supported bound");
        }
        return value;
    }
}
