package org.synesis.projectrecord;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/** JDK-only Ed25519 signer used to create local decision revisions. */
public final class Ed25519Signer {
    private static final String ALGORITHM = "Ed25519";
    private static final int MAX_KEY_BYTES = 256;

    private final PrivateKey privateKey;
    private final byte[] publicKey;
    private final String nodeId;

    private Ed25519Signer(KeyPair pair) {
        this.privateKey = Objects.requireNonNull(pair.getPrivate(), "private key");
        PublicKey key = Objects.requireNonNull(pair.getPublic(), "public key");
        if (!isEd25519(privateKey.getAlgorithm()) || !isEd25519(key.getAlgorithm())) {
            throw new IllegalArgumentException("only Ed25519 keys are supported");
        }
        this.publicKey = key.getEncoded().clone();
        if (publicKey.length == 0 || publicKey.length > MAX_KEY_BYTES
                || privateKey.getEncoded().length == 0 || privateKey.getEncoded().length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("key encoding exceeds the supported bound");
        }
        this.nodeId = deriveNodeId(publicKey);
    }

    /** Generates a signer using the JDK secure provider.
     * @return a new signer
     * @throws GeneralSecurityException if Ed25519 is unavailable
     */
    public static Ed25519Signer generate() throws GeneralSecurityException {
        return new Ed25519Signer(KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair());
    }

    /**
     * Reconstructs a signer from standard JDK key encodings.
     *
     * @param publicKeyEncoded X.509 public-key bytes
     * @param privateKeyEncoded PKCS#8 private-key bytes
     * @return signer retaining private material in memory only
     * @throws GeneralSecurityException if either encoding is invalid
     */
    public static Ed25519Signer fromEncoded(byte[] publicKeyEncoded, byte[] privateKeyEncoded)
            throws GeneralSecurityException {
        Objects.requireNonNull(publicKeyEncoded, "public key");
        Objects.requireNonNull(privateKeyEncoded, "private key");
        if (publicKeyEncoded.length == 0 || publicKeyEncoded.length > MAX_KEY_BYTES
                || privateKeyEncoded.length == 0 || privateKeyEncoded.length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("key encoding exceeds the supported bound");
        }
        KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
        return new Ed25519Signer(new KeyPair(
                factory.generatePublic(new X509EncodedKeySpec(publicKeyEncoded)),
                factory.generatePrivate(new PKCS8EncodedKeySpec(privateKeyEncoded))));
    }

    /** Returns the stable lowercase node identifier derived from the public key.
     * @return node identifier
     */
    public String nodeId() { return nodeId; }

    /** Returns a copy of the X.509 public-key encoding.
     * @return public-key bytes
     */
    public byte[] publicKeyEncoded() { return publicKey.clone(); }

    /**
     * Signs exact canonical bytes with Ed25519.
     *
     * @param message bytes to sign
     * @return a fresh signature
     * @throws GeneralSecurityException if signing fails
     */
    public byte[] sign(byte[] message) throws GeneralSecurityException {
        Objects.requireNonNull(message, "message");
        Signature signature = Signature.getInstance(ALGORITHM);
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    static boolean verify(byte[] publicKeyBytes, byte[] message, byte[] signatureBytes)
            throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        Signature verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(key);
        verifier.update(message);
        return verifier.verify(signatureBytes);
    }

    static String deriveNodeId(byte[] publicKeyBytes) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the JDK", impossible);
        }
        return "sl1-" + HexFormat.of().formatHex(digest);
    }

    private static boolean isEd25519(String algorithm) {
        return ALGORITHM.equals(algorithm) || "EdDSA".equals(algorithm);
    }
}
