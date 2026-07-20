package org.synesis.link.identity;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An Ed25519 Synesis node identity.
 *
 * <p>The object is immutable and thread-safe. It owns references to the JDK
 * key objects supplied at construction; callers must not mutate provider
 * objects outside the JDK key contract. Private key material is never included
 * in {@link #toString()} or returned as an encoded value.
 *
 * @since 1.0
 */
public final class NodeIdentity {

    private static final String ALGORITHM = "Ed25519";
    private static final String SIGNATURE_ALGORITHM = "Ed25519";
    private static final String NODE_ID_PREFIX = "sl1-";
    private static final int MAX_PUBLIC_KEY_BYTES = 16_384;
    private static final int MAX_PRIVATE_KEY_BYTES = 16_384;

    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final byte[] publicKeyEncoded;
    private final String nodeId;

    private NodeIdentity(KeyPair keyPair) {
        this.publicKey = Objects.requireNonNull(keyPair.getPublic(), "public key");
        this.privateKey = Objects.requireNonNull(keyPair.getPrivate(), "private key");
        if (!isEd25519(publicKey.getAlgorithm()) || !isEd25519(privateKey.getAlgorithm())) {
            throw new IllegalArgumentException("Only Ed25519 key pairs are supported");
        }
        this.publicKeyEncoded = publicKey.getEncoded().clone();
        if (publicKeyEncoded.length == 0 || publicKeyEncoded.length > MAX_PUBLIC_KEY_BYTES
                || privateKey.getEncoded().length == 0 || privateKey.getEncoded().length > MAX_PRIVATE_KEY_BYTES) {
            throw new IllegalArgumentException("Unsupported key encoding size");
        }
        this.nodeId = deriveNodeId(publicKeyEncoded);
    }

    /**
     * Generates a new identity using the JDK provider's secure randomness.
     *
     * @return a new identity with a fresh private key
     * @throws GeneralSecurityException if the runtime has no Ed25519 generator
     */
    public static NodeIdentity generate() throws GeneralSecurityException {
        return new NodeIdentity(new KeyPairGeneratorFactory().generate());
    }

    /**
     * Wraps an externally supplied Ed25519 key pair.
     *
     * @param keyPair key pair; neither key may be {@code null}
     * @return an immutable identity wrapper
     * @throws NullPointerException if {@code keyPair} or a key is {@code null}
     * @throws IllegalArgumentException if the pair is not Ed25519
     */
    public static NodeIdentity from(KeyPair keyPair) {
        return new NodeIdentity(Objects.requireNonNull(keyPair, "key pair"));
    }

    /**
     * Reconstructs an identity from standard encoded key material.
     *
     * @param publicKeyEncoded X.509 public-key bytes
     * @param privateKeyEncoded PKCS#8 private-key bytes
     * @return the reconstructed identity
     * @throws GeneralSecurityException if either encoding is invalid
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if an encoding exceeds the bounded size
     */
    public static NodeIdentity fromEncoded(byte[] publicKeyEncoded, byte[] privateKeyEncoded)
            throws GeneralSecurityException {
        Objects.requireNonNull(publicKeyEncoded, "public key");
        Objects.requireNonNull(privateKeyEncoded, "private key");
        if (publicKeyEncoded.length == 0 || publicKeyEncoded.length > MAX_PUBLIC_KEY_BYTES
                || privateKeyEncoded.length == 0 || privateKeyEncoded.length > MAX_PRIVATE_KEY_BYTES) {
            throw new IllegalArgumentException("Key encoding exceeds the supported bound");
        }
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(publicKeyEncoded));
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyEncoded));
        return from(new KeyPair(publicKey, privateKey));
    }

    /**
     * Returns the stable node identifier derived from the canonical public key.
     *
     * @return lowercase hexadecimal SHA-256 identifier prefixed with {@code sl1-}; safe to log
     */
    public String nodeId() {
        return nodeId;
    }

    /**
     * Returns a copy of the X.509 public-key encoding.
     *
     * @return public-key bytes; contains no private secret
     */
    public byte[] publicKeyEncoded() {
        return publicKeyEncoded.clone();
    }

    /**
     * Signs a message with the identity's private key.
     *
     * @param message exact bytes to sign; never {@code null}
     * @return a fresh Ed25519 signature
     * @throws GeneralSecurityException if signing fails
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public byte[] sign(byte[] message) throws GeneralSecurityException {
        Objects.requireNonNull(message, "message");
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    /**
     * Verifies a signature made by this identity.
     *
     * @param message exact signed bytes; never {@code null}
     * @param signatureBytes signature bytes; never {@code null}
     * @return {@code true} only when the signature is valid
     * @throws GeneralSecurityException if verification cannot be performed
     * @throws NullPointerException if an argument is {@code null}
     */
    public boolean verify(byte[] message, byte[] signatureBytes) throws GeneralSecurityException {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(signatureBytes, "signature");
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initVerify(publicKey);
        signature.update(message);
        return signature.verify(signatureBytes);
    }

    /**
     * Derives a node ID from canonical encoded public-key material.
     *
     * @param publicKeyEncoded X.509 public-key bytes
     * @return the deterministic lowercase hexadecimal node ID
     */
    public static String deriveNodeId(byte[] publicKeyEncoded) {
        Objects.requireNonNull(publicKeyEncoded, "public key");
        byte[] digest = sha256(publicKeyEncoded);
        return NODE_ID_PREFIX + HexFormat.of().withUpperCase().formatHex(digest).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Returns a redacted description containing no key material.
     *
     * @return safe diagnostic text
     */
    @Override
    public String toString() {
        return "NodeIdentity[nodeId=" + nodeId + "]";
    }

    byte[] privateKeyEncodedForStore() {
        return privateKey.getEncoded().clone();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }

    private static boolean isEd25519(String algorithm) {
        return ALGORITHM.equals(algorithm) || "EdDSA".equals(algorithm);
    }

    private static final class KeyPairGeneratorFactory {
        private KeyPair generate() throws GeneralSecurityException {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        }
    }
}
