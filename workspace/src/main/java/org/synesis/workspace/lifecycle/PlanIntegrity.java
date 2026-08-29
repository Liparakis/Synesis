package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the canonical digest used to protect persisted lifecycle plans.
 */
public final class PlanIntegrity {

    private PlanIntegrity() {
    }

    /**
     * Computes the SHA-256 digest of UTF-8 text.
     *
     * @param text canonical plan text
     * @return lowercase hexadecimal SHA-256 digest
     * @throws IOException if the required digest algorithm is unavailable
     */
    public static String sha256Utf8(String text) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 algorithm unavailable", failure);
        }
    }
}
