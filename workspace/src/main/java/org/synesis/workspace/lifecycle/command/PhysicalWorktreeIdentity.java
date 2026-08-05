package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier;

/** Verified physical worktree identity used as the only command exclusion key.
 * @param locator versioned SHA-256 locator over the exact real path
 * @param realPath verified filesystem real path
 */
public record PhysicalWorktreeIdentity(String locator, Path realPath) {

    /** Validates and normalizes the identity values without Unicode or case folding. */
    public PhysicalWorktreeIdentity {
        Objects.requireNonNull(locator, "locator");
        realPath = Objects.requireNonNull(realPath, "realPath").toAbsolutePath().normalize();
    }

    /** Captures a versioned locator from the exact filesystem real path.
     * @param worktree candidate worktree directory
     * @return verified physical identity
     * @throws IOException if the path cannot be resolved as a directory
     */
    public static PhysicalWorktreeIdentity capture(Path worktree) throws IOException {
        Objects.requireNonNull(worktree, "worktree");
        Path real = worktree.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IOException("WORKTREE_IDENTITY_NOT_DIRECTORY");
        }
        try {
            String exact = real.toString();
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(exact.getBytes(StandardCharsets.UTF_8)));
            return new PhysicalWorktreeIdentity("v1-" + digest, real);
        } catch (Exception failure) {
            throw new IOException("WORKTREE_IDENTITY_HASH_FAILED", failure);
        }
    }

    /** Captures identity only after the existing lifecycle path verifier approves it.
     * @param controlRoot control checkout used by the lifecycle verifier
     * @param worktree candidate assigned worktree
     * @param verifier existing lifecycle path verifier
     * @return verified physical identity
     * @throws IOException if verification fails
     */
    public static PhysicalWorktreeIdentity capture(Path controlRoot, Path worktree,
            LifecyclePathVerifier verifier) throws IOException {
        Objects.requireNonNull(verifier, "verifier");
        LifecyclePathVerifier.PathVerificationResult result = verifier.verifyPath(controlRoot, worktree);
        if (!result.safe()) {
            throw new IOException("WORKTREE_IDENTITY_UNVERIFIED:" + result.reasonCode());
        }
        return capture(result.canonical() == null ? result.normalized() : result.canonical());
    }

    /** Compares complete physical identity using the filesystem's same-file check.
     * @param other identity to compare
     * @return whether both paths identify the same filesystem object
     * @throws IOException if filesystem identity cannot be checked
     */
    public boolean isSameFile(PhysicalWorktreeIdentity other) throws IOException {
        Objects.requireNonNull(other, "other");
        return Files.isSameFile(realPath, other.realPath);
    }
}
