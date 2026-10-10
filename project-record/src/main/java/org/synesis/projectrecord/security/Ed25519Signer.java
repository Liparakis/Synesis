package org.synesis.projectrecord.security;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Objects;
import org.synesis.link.identity.NodeIdentity;

/**
 * JDK-only Ed25519 signer used to create local decision revisions.
 */
public final class Ed25519Signer {

  private static final String ALGORITHM = "Ed25519";
  private static final int MAX_KEY_BYTES = 256;

  private final PrivateKey privateKey;
  private final SigningOperation signingOperation;
  private final byte[] publicKey;
  private final String nodeId;

  private Ed25519Signer(KeyPair pair) {
    this.privateKey = Objects.requireNonNull(pair.getPrivate(), "private key");
    PublicKey key = Objects.requireNonNull(pair.getPublic(), "public key");
    if (isUnsupportedAlgorithm(privateKey.getAlgorithm()) || isUnsupportedAlgorithm(
        key.getAlgorithm())) {
      throw new IllegalArgumentException("only Ed25519 keys are supported");
    }
    this.publicKey = key.getEncoded()
        .clone();
    if (publicKey.length == 0 || publicKey.length > MAX_KEY_BYTES
        || privateKey.getEncoded().length == 0 || privateKey.getEncoded().length > MAX_KEY_BYTES) {
      throw new IllegalArgumentException("key encoding exceeds the supported bound");
    }
    this.nodeId = NodeIdentity.deriveNodeId(publicKey);
    this.signingOperation = message -> signWithKey(privateKey, message);
  }

  private Ed25519Signer(byte[] publicKeyEncoded, String nodeId, SigningOperation operation) {
    this.privateKey = null;
    this.publicKey = publicKeyEncoded.clone();
    this.nodeId = nodeId;
    this.signingOperation = Objects.requireNonNull(operation, "signing operation");
  }

  /**
   * Generates a signer using the JDK secure provider.
   *
   * @return a new signer
   * @throws GeneralSecurityException if Ed25519 is unavailable
   */
  public static Ed25519Signer generate() throws GeneralSecurityException {
    return new Ed25519Signer(KeyPairGenerator.getInstance(ALGORITHM)
        .generateKeyPair());
  }

  /**
   * Adapts the authenticated Link identity for signing project records.
   *
   * @param identity Link identity whose node ID and key sign records
   * @return signer backed by the identity's private key
   */
  public static Ed25519Signer from(NodeIdentity identity) {
    Objects.requireNonNull(identity, "identity");
    return new Ed25519Signer(identity.publicKeyEncoded(), identity.nodeId(), identity::sign);
  }

  private static byte[] signWithKey(PrivateKey key, byte[] message)
      throws GeneralSecurityException {
    Signature signature = Signature.getInstance(ALGORITHM);
    signature.initSign(key);
    signature.update(message);
    return signature.sign();
  }

  private static boolean isUnsupportedAlgorithm(String algorithm) {
    return !ALGORITHM.equals(algorithm) && !"EdDSA".equals(algorithm);
  }

  /**
   * Returns the stable lowercase node identifier derived from the public key.
   *
   * @return node identifier
   */
  public String nodeId() {
    return nodeId;
  }

  /**
   * Returns a copy of the X.509 public-key encoding.
   *
   * @return public-key bytes
   */
  public byte[] publicKeyEncoded() {
    return publicKey.clone();
  }

  /**
   * Signs exact canonical bytes with Ed25519.
   *
   * @param message bytes to sign
   * @return a fresh signature
   * @throws GeneralSecurityException if signing fails
   */
  public byte[] sign(byte[] message) throws GeneralSecurityException {
    Objects.requireNonNull(message, "message");
    return signingOperation.sign(message);
  }

  /**
   * Defers one checked signing operation behind the signer boundary.
   */
  @FunctionalInterface
  private interface SigningOperation {

    byte[] sign(byte[] message) throws GeneralSecurityException;
  }
}
