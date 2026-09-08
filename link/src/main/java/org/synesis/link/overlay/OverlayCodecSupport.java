package org.synesis.link.overlay;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.KDF;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.synesis.link.identity.NodeIdentity;

/**
 * Package-private validation and cryptographic helpers for the overlay wire
 * records.
 */
final class OverlayCodecSupport {

    static final int NODE_ID_BYTES = 32;
    static final int MAX_PUBLIC_KEY_BYTES = 256;
    static final int SIGNATURE_BYTES = 64;
    static final int X25519_PUBLIC_KEY_BYTES = 32;
    static final int NONCE_BYTES = 32;
    static final int AEAD_NONCE_BYTES = 12;
    static final int AEAD_TAG_BYTES = 16;
    static final int MAX_PLAINTEXT_BYTES = 3_840;
    static final int MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + AEAD_TAG_BYTES;
    static final byte[] OVERLAY_DOMAIN = "synesis-overlay-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int SLE1_MAGIC = 0x534C4531;
    private static final int SLK1_MAGIC = 0x534C4B31;

    private OverlayCodecSupport() {
    }

    static void requireNodeId(String nodeId) {
        Objects.requireNonNull(nodeId, "node ID");
        if (!nodeId.matches("sl1-[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid node ID");
        }
    }

    static void requireLogicalRecord(byte[] record) {
        Objects.requireNonNull(record, "logical record");
        if (record.length < Integer.BYTES) {
            throw new IllegalArgumentException("logical record is truncated");
        }
        int magic = ((record[0] & 0xFF) << 24) | ((record[1] & 0xFF) << 16)
                | ((record[2] & 0xFF) << 8) | (record[3] & 0xFF);
        if (magic != SLE1_MAGIC && magic != SLK1_MAGIC) {
            throw new IllegalArgumentException("unsupported logical record type");
        }
    }

    static byte[] nodeIdBytes(String nodeId) {
        requireNodeId(nodeId);
        return HexFormat.of().parseHex(nodeId.substring(4));
    }

    static String nodeId(byte[] bytes) {
        Objects.requireNonNull(bytes, "node ID bytes");
        if (bytes.length != NODE_ID_BYTES) {
            throw new IllegalArgumentException("node ID must contain 32 bytes");
        }
        return "sl1-" + HexFormat.of().formatHex(bytes);
    }

    static void writeUuid(DataOutputStream output, java.util.UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    static java.util.UUID readUuid(DataInputStream input) throws IOException {
        return new java.util.UUID(input.readLong(), input.readLong());
    }

    static void writeBytes(DataOutputStream output, byte[] bytes, int max, String field) throws IOException {
        Objects.requireNonNull(bytes, field);
        if (bytes.length <= 0 || bytes.length > max || bytes.length > 0xffff) {
            throw new IllegalArgumentException(field + " exceeds its bound");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    static byte[] readBytes(DataInputStream input, int max, String field) throws IOException {
        int length = input.readUnsignedShort();
        if (length <= 0 || length > max || length > input.available()) {
            throw new IOException("invalid " + field + " length");
        }
        return input.readNBytes(length);
    }

    static void requireNoTrailing(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IOException("trailing overlay record bytes");
        }
    }

    static void requireMillis(java.time.Instant value, String field) {
        Objects.requireNonNull(value, field);
        if (value.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException(field + " must have millisecond precision");
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
    }

    static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length = Math.addExact(length, Objects.requireNonNull(value, "value").length);
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    static byte[] uuidBytes(java.util.UUID value) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(16);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeUuid(output, value);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static boolean verifyEd25519(byte[] publicKeyBytes, byte[] message, byte[] signatureBytes)
            throws GeneralSecurityException {
        PublicKey key = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(message);
        return verifier.verify(signatureBytes);
    }

    static byte[] x25519PublicKeyBytes(java.security.KeyPair keyPair) {
        if (!(keyPair.getPublic() instanceof XECPublicKey publicKey)) {
            throw new IllegalArgumentException("X25519 public key is required");
        }
        return littleEndian(publicKey.getU(), X25519_PUBLIC_KEY_BYTES);
    }

    static PublicKey x25519PublicKey(byte[] raw) throws GeneralSecurityException {
        if (raw == null || raw.length != X25519_PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("X25519 public key must contain 32 bytes");
        }
        byte[] bigEndian = raw.clone();
        reverse(bigEndian);
        BigInteger coordinate = new BigInteger(1, bigEndian);
        return KeyFactory.getInstance("X25519").generatePublic(
                new XECPublicKeySpec(NamedParameterSpec.X25519, coordinate));
    }

    static byte[] deriveSharedSecret(java.security.PrivateKey privateKey, byte[] remotePublicKey)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(x25519PublicKey(remotePublicKey), true);
        byte[] sharedSecret = agreement.generateSecret();
        if (Arrays.equals(sharedSecret, new byte[sharedSecret.length])) {
            throw new GeneralSecurityException("X25519 produced an all-zero shared secret");
        }
        return sharedSecret;
    }

    static byte[] hkdf(byte[] sharedSecret, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        if (length <= 0 || length > 255 * 32) {
            throw new IllegalArgumentException("invalid HKDF output length");
        }
        KDF kdf = KDF.getInstance("HKDF-SHA256");
        HKDFParameterSpec parameters = HKDFParameterSpec.ofExtract()
                .addSalt(salt)
                .addIKM(sharedSecret)
                .thenExpand(info, length);
        return kdf.deriveData(parameters);
    }

    static byte[] aeadEncrypt(byte[] keyBytes, byte[] nonce, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "ChaCha20"),
                new IvParameterSpec(nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    static byte[] aeadDecrypt(byte[] keyBytes, byte[] nonce, byte[] aad, byte[] ciphertext)
            throws GeneralSecurityException {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "ChaCha20"),
                new IvParameterSpec(nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    private static byte[] littleEndian(BigInteger value, int length) {
        byte[] bigEndian = value.toByteArray();
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            int source = bigEndian.length - 1 - index;
            result[index] = source >= 0 ? bigEndian[source] : 0;
        }
        return result;
    }

    private static void reverse(byte[] bytes) {
        for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
            byte value = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = value;
        }
    }
}
