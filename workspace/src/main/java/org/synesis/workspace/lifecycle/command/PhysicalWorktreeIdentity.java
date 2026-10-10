package org.synesis.workspace.lifecycle.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.lifecycle.cleanup.inventory.LifecyclePathVerifier;

/**
 * Verified physical worktree identity used as the only command exclusion key.
 *
 * @param locator  versioned SHA-256 locator over the exact real path
 * @param realPath verified filesystem real path
 */
public record PhysicalWorktreeIdentity(String locator, Path realPath) {

    /**
     * Validates and normalizes the identity values without Unicode or case folding.
     */
    public PhysicalWorktreeIdentity {
        Objects.requireNonNull(locator, "locator");
        realPath = Objects.requireNonNull(realPath, "realPath").toAbsolutePath().normalize();
    }

    /**
     * Captures a versioned locator from the exact filesystem real path.
     *
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
        return new PhysicalWorktreeIdentity("v1-" + Sha256.hex(real.toString()), real);
    }

    /**
     * Captures identity only after the existing lifecycle path verifier approves it.
     *
     * @param controlRoot control checkout used by the lifecycle verifier
     * @param worktree    candidate assigned worktree
     * @param verifier    existing lifecycle path verifier
     * @return verified physical identity
     * @throws IOException if verification fails
     */
    public static PhysicalWorktreeIdentity capture(Path controlRoot, Path worktree, LifecyclePathVerifier verifier) throws IOException {
        Objects.requireNonNull(verifier, "verifier");
        LifecyclePathVerifier.PathVerificationResult result = verifier.verifyPath(controlRoot, worktree);
        if (!result.safe()) {
            throw new IOException("WORKTREE_IDENTITY_UNVERIFIED:" + result.reasonCode());
        }
        return capture(result.canonical() == null ? result.normalized() : result.canonical());
    }
}
