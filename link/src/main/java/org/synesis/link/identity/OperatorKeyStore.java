package org.synesis.link.identity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The operator key of a project (ADR-0104, owner's decision 1): an Ed25519 key kept apart from the node key, used only
 * to sign the owner's decisions, and stored encrypted under a passphrase a person types at a terminal.
 *
 * <p>The node key cannot be protected this way, because the background server uses it unattended. This key is used
 * only by commands a person runs, so it can be: the private half is encrypted with AES-256-GCM under a key derived by
 * PBKDF2-HMAC-SHA-256 from the passphrase and a random salt, and the file never holds the passphrase or the private
 * key in the clear. The public half is stored in the clear, so a command can say which key it expects without
 * asking for the passphrase. The header, public key included, is authenticated: a changed byte fails to unlock.</p>
 *
 * <p>The passphrase is never read from an environment variable or a file here; the caller gets it from a person.
 * PBKDF2 is the JDK's own memory-light derivation, so its iteration count is the whole cost an attacker with a copy
 * of the file pays per guess; the default follows OWASP's figure for PBKDF2-HMAC-SHA-256.</p>
 */
public final class OperatorKeyStore {

    /** The iteration count a new key is written with. */
    public static final int DEFAULT_ITERATIONS = 600_000;

    private static final int MAGIC = 0x534F4B31;
    private static final int FORMAT_VERSION = 1;
    private static final int MINIMUM_NEW_PASSPHRASE = 8;
    private static final int MAX_FILE_BYTES = 4096;
    private static final int MAX_ITERATIONS = 10_000_000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final int iterations;

    /**
     * Binds a store to one key file with the default cost; nothing is read or created.
     *
     * @param file the key file
     */
    public OperatorKeyStore(Path file) {
        this(file, DEFAULT_ITERATIONS);
    }

    /**
     * Binds a store to one key file with a chosen cost for a key it creates, which tests lower.
     *
     * @param file       the key file
     * @param iterations PBKDF2 iterations a new key is written with
     */
    public OperatorKeyStore(Path file, int iterations) {
        this.file = Objects.requireNonNull(file, "file");
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("iterations out of bounds");
        }
        this.iterations = iterations;
    }

    /**
     * Whether a key file exists.
     *
     * @return whether it does
     */
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * Returns the public half without the passphrase.
     *
     * @return the X.509-encoded public key
     * @throws IOException when the file is missing or malformed
     */
    public byte[] publicKey() throws IOException {
        return read().publicKey();
    }

    /**
     * Creates the key, encrypted under the passphrase. Never replaces a key: losing the passphrase means a new key,
     * registered by a decision the old key signs, or a new project baseline, never an overwritten file.
     *
     * @param passphrase what the person typed; the caller clears it
     * @return the new identity, unlocked
     * @throws IOException              when a key file exists already or cannot be written
     * @throws GeneralSecurityException when the key cannot be generated or encrypted
     */
    public NodeIdentity create(char[] passphrase) throws IOException, GeneralSecurityException {
        requireNewPassphrase(passphrase);
        if (Files.exists(file)) {
            throw new FileAlreadyExistsException(file.toString());
        }
        NodeIdentity identity = NodeIdentity.generate();
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        byte[] header = header(iterations, salt, nonce, identity.publicKeyEncoded());
        byte[] sealed = cipher(Cipher.ENCRYPT_MODE, passphrase, iterations, salt, nonce, header).doFinal(identity.privateKeyEncodedForStore());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(header);
            out.writeInt(sealed.length);
            out.write(sealed);
        }
        write(bytes.toByteArray());
        return identity;
    }

    /**
     * Unlocks the key.
     *
     * @param passphrase what the person typed; the caller clears it
     * @return the identity
     * @throws OperatorKeyException     {@code OPERATOR_PASSPHRASE_WRONG} when the passphrase does not open the file,
     *                                  or the file was changed
     * @throws IOException              when the file is missing or malformed
     * @throws GeneralSecurityException when the key material is invalid
     */
    public NodeIdentity unlock(char[] passphrase) throws IOException, GeneralSecurityException {
        requirePassphrase(passphrase);
        Stored stored = read();
        try {
            byte[] privateKey = cipher(Cipher.DECRYPT_MODE, passphrase, stored.iterations(), stored.salt(), stored.nonce(), stored.header()).doFinal(stored.sealed());
            try {
                return NodeIdentity.fromEncoded(stored.publicKey(), privateKey);
            } finally {
                Arrays.fill(privateKey, (byte) 0);
            }
        } catch (AEADBadTagException wrong) {
            throw new OperatorKeyException("OPERATOR_PASSPHRASE_WRONG");
        }
    }

    private static void requirePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("passphrase must not be empty");
        }
    }

    private static void requireNewPassphrase(char[] passphrase) throws OperatorKeyException {
        requirePassphrase(passphrase);
        if (passphrase.length < MINIMUM_NEW_PASSPHRASE) {
            throw new OperatorKeyException("OPERATOR_PASSPHRASE_TOO_SHORT");
        }
    }

    private static Cipher cipher(int mode, char[] passphrase, int iterations, byte[] salt, byte[] nonce, byte[] header) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            Arrays.fill(key, (byte) 0);
            cipher.updateAAD(header);
            return cipher;
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] header(int iterations, byte[] salt, byte[] nonce, byte[] publicKey) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(iterations);
            out.write(salt);
            out.write(nonce);
            out.writeInt(publicKey.length);
            out.write(publicKey);
        }
        return bytes.toByteArray();
    }

    private record Stored(byte[] header, int iterations, byte[] salt, byte[] nonce, byte[] publicKey, byte[] sealed) {
    }

    private Stored read() throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("operator key file exceeds the supported bound");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
                throw new IOException("unsupported operator key file");
            }
            int count = in.readInt();
            if (count < 1 || count > MAX_ITERATIONS) {
                throw new IOException("invalid operator key cost");
            }
            byte[] salt = in.readNBytes(SALT_BYTES);
            byte[] nonce = in.readNBytes(NONCE_BYTES);
            int publicLength = in.readInt();
            if (salt.length != SALT_BYTES || nonce.length != NONCE_BYTES || publicLength <= 0 || publicLength > 256 || publicLength > in.available()) {
                throw new IOException("invalid operator key file");
            }
            byte[] publicKey = in.readNBytes(publicLength);
            int sealedLength = in.readInt();
            if (sealedLength <= 0 || sealedLength > in.available()) {
                throw new IOException("invalid operator key file");
            }
            byte[] sealed = in.readNBytes(sealedLength);
            if (in.available() != 0) {
                throw new IOException("trailing operator key file data");
            }
            return new Stored(header(count, salt, nonce, publicKey), count, salt, nonce, publicKey, sealed);
        }
    }

    private void write(byte[] bytes) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                // A hard link fails when the target exists, where a move would replace it: a lost key is lost for good,
                // so two creators racing never overwrite each other's.
                Files.createLink(file, temporary);
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException noLinks) {
                if (noLinks instanceof FileAlreadyExistsException) {
                    throw (FileAlreadyExistsException) noLinks;
                }
                if (Files.exists(file)) {
                    throw new FileAlreadyExistsException(file.toString());
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, file);
                }
            }
            try {
                Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows and other non-POSIX stores enforce permissions differently.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
