package org.synesis.link.identity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

/**
 * A bounded single-write file identity store.
 *
 * <p>The store is thread-confined, writes a versioned binary record through a
 * sibling temporary file, and refuses to overwrite an existing identity. On
 * POSIX file systems it attempts owner-only permissions. The file contains
 * private key bytes, so callers must protect the path and may replace this
 * implementation with an operating-system key store.
 *
 * @since 1.0
 */
public final class FileIdentityStore implements IdentityStore {

    private static final int MAX_FILE_BYTES = 32_768;
    private static final int FORMAT_VERSION = 1;
    private static final int MAGIC = 0x534C4944;
    private final Path path;

    /**
     * Creates a store for one identity file.
     *
     * @param path target file; it is not read or created by this constructor
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public FileIdentityStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    /**
     * Loads and validates the bounded identity record.
     *
     * @return stored identity
     * @throws IOException if the file is absent, unreadable, oversized, or malformed
     * @throws GeneralSecurityException if encoded keys are invalid
     */
    @Override
    public NodeIdentity load() throws IOException, GeneralSecurityException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("identity file exceeds the supported bound");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IOException("unsupported identity file format");
            }
            byte[] publicKey = readBounded(input);
            byte[] privateKey = readBounded(input);
            if (input.available() != 0) {
                throw new IOException("trailing identity file data");
            }
            return NodeIdentity.fromEncoded(publicKey, privateKey);
        }
    }

    /**
     * Atomically writes an identity and refuses unsafe replacement.
     *
     * @param identity identity to save
     * @throws IOException if the target exists or writing/moving fails
     * @throws GeneralSecurityException if key material cannot be encoded
     */
    @Override
    public void save(NodeIdentity identity) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(identity, "identity");
        if (Files.exists(path)) {
            throw new FileAlreadyExistsException(path.toString());
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] bytes = encode(identity);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path);
            }
            try {
                Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows and other non-POSIX stores enforce permissions differently.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] encode(NodeIdentity identity) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            byte[] publicKey = identity.publicKeyEncoded();
            byte[] privateKey = identity.privateKeyEncodedForStore();
            output.writeInt(publicKey.length);
            output.write(publicKey);
            output.writeInt(privateKey.length);
            output.write(privateKey);
        }
        return bytes.toByteArray();
    }

    private static byte[] readBounded(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FILE_BYTES || length > input.available()) {
            throw new IOException("invalid identity key length");
        }
        return input.readNBytes(length);
    }
}
