package org.synesis.link.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Loads or creates the one long-term identity used by terminal onboarding.
 *
 * <p>Creation is atomic and never replaces an existing identity. Corrupt or
 * inconsistent files fail closed. The private identity file is owned by the
 * existing {@link FileIdentityStore}; public metadata is written separately
 * for inspection and checked against the loaded identity.
 *
 * @since 1.0
 */
public final class IdentityBootstrap {
    private static final String PRIVATE_FILE = "identity.bin";
    private static final String PUBLIC_FILE = "identity.pub";
    private final Path directory;

    /** Creates a bootstrapper rooted at one profile directory.
     * @param directory profile directory
     */
    public IdentityBootstrap(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Returns the platform-local default Synesis profile directory.
     * @return default profile directory
     */
    public static Path defaultDirectory() {
        String testProfile = System.getenv("SYNESIS_LINK_PROFILE");
        if (testProfile != null && !testProfile.isBlank()) return Path.of(testProfile);
        String local = System.getenv("LOCALAPPDATA");
        return local == null || local.isBlank()
                ? Path.of(System.getProperty("user.home"), ".synesis", "link")
                : Path.of(local, "Synesis", "Link");
    }

    /**
     * Loads a valid existing identity or creates one on first use.
     *
     * @return immutable identity and whether it was newly created
     * @throws IOException if storage is missing, corrupt, or inconsistent
     * @throws GeneralSecurityException if key material is invalid
     */
    public Result loadOrCreate() throws IOException, GeneralSecurityException {
        Files.createDirectories(directory);
        Path privatePath = directory.resolve(PRIVATE_FILE);
        FileIdentityStore store = new FileIdentityStore(privatePath);
        if (Files.notExists(privatePath)) {
            NodeIdentity created = NodeIdentity.generate();
            try {
                store.save(created);
            } catch (java.nio.file.FileAlreadyExistsException concurrentCreation) {
                NodeIdentity loaded = store.load();
                validatePublicMetadata(loaded);
                return new Result(loaded, false);
            }
            writePublicMetadata(created);
            return new Result(created, true);
        }
        NodeIdentity loaded = store.load();
        validatePublicMetadata(loaded);
        return new Result(loaded, false);
    }

    /** Returns the private identity file path for diagnostics without exposing key bytes.
     * @return private identity path
     */
    public Path privatePath() { return directory.resolve(PRIVATE_FILE); }

    private void writePublicMetadata(NodeIdentity identity) throws IOException {
        String value = "version=1\nnodeId=" + identity.nodeId() + "\npublicKey="
                + HexFormat.of().formatHex(identity.publicKeyEncoded()) + "\n";
        Path path = directory.resolve(PUBLIC_FILE);
        Files.writeString(path, value, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) { }
    }

    private void validatePublicMetadata(NodeIdentity identity) throws IOException {
        Path path = directory.resolve(PUBLIC_FILE);
        if (Files.notExists(path)) { writePublicMetadata(identity); return; }
        String value = Files.readString(path, StandardCharsets.US_ASCII);
        String expected = "version=1\nnodeId=" + identity.nodeId() + "\npublicKey="
                + HexFormat.of().formatHex(identity.publicKeyEncoded()) + "\n";
        if (!expected.equals(value)) throw new IOException("local identity metadata is inconsistent");
    }

    /** Result of one load-or-create operation.
     * @param identity loaded or created identity
     * @param created whether the identity was created during this operation
     */
    public record Result(NodeIdentity identity, boolean created) {
        /** Validates the non-null identity result. */
        public Result {
            Objects.requireNonNull(identity, "identity");
        }
    }
}
