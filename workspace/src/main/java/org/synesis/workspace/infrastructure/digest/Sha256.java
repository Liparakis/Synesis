package org.synesis.workspace.infrastructure.digest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes lower-case hexadecimal SHA-256 digests for persisted identifiers and fingerprints.
 */
public final class Sha256 {

    private Sha256() {
    }

    /**
     * Returns a fresh SHA-256 digest for callers that hash several inputs incrementally.
     *
     * @return a new SHA-256 message digest
     * @throws IllegalStateException if the JVM lacks SHA-256, which the Java platform guarantees
     */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every Java platform implementation", impossible);
        }
    }

    /**
     * Hashes bytes.
     *
     * @param bytes the content to hash
     * @return the 64-character lower-case hexadecimal digest
     */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    /**
     * Hashes the UTF-8 encoding of text.
     *
     * @param text the text to hash
     * @return the 64-character lower-case hexadecimal digest
     */
    public static String hex(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hashes the content of a file without loading it whole.
     *
     * @param file the file to hash
     * @return the 64-character lower-case hexadecimal digest
     * @throws IOException if the file cannot be read
     */
    public static String hex(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
