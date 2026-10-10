package org.synesis.workspace.lifecycle.cleanup.inventory;

import org.synesis.workspace.lifecycle.ProjectAdministrativeLayout;
import org.synesis.workspace.lifecycle.cleanup.plan.CleanupReason;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Reusable safety verifier ensuring candidate filesystem paths remain strictly under the allowed
 * external Synesis project workspace root and do not escape via symlinks, junctions, or path
 * traversal.
 *
 * @since 1.0
 */
@SuppressWarnings("DuplicatedCode")
public final class LifecyclePathVerifier {

    /**
     * Creates a path verifier.
     */
    public LifecyclePathVerifier() {
    }

    /**
     * Resolves the expected external Synesis workspace root for a control project.
     *
     * @param controlRoot control project root path
     * @return normalized absolute external workspace root path
     * @see ProjectAdministrativeLayout#workspaceRoot(Path)
     */
    public static Path resolveWorkspaceRoot(Path controlRoot) {
        return ProjectAdministrativeLayout.workspaceRoot(controlRoot);
    }

    private static boolean verifyGitCommonDirectory(Path controlRoot, Path worktreePath) {
        try {
            String expectedStr = runGit(controlRoot);
            Path expectedCommon = controlRoot.resolve(expectedStr).toAbsolutePath().normalize();

            String actualStr = runGit(worktreePath);
            Path actualCommon = worktreePath.resolve(actualStr).toAbsolutePath().normalize();

            Path realExpected = Files.exists(expectedCommon) ? expectedCommon.toRealPath() : expectedCommon;
            Path realActual = Files.exists(actualCommon) ? actualCommon.toRealPath() : actualCommon;
            return realExpected.equals(realActual);
        } catch (Exception failure) {
            return false;
        }
    }

    private static String runGit(Path workdir) throws IOException {
        return GitProcessRunner.run(workdir, "rev-parse", "--git-common-dir").trim();
    }

    /**
     * Verifies that a candidate resource path is safe for inspection/planning.
     *
     * @param controlRoot   control project root path
     * @param candidatePath candidate filesystem path
     * @return path verification result
     */
    public PathVerificationResult verifyPath(Path controlRoot, Path candidatePath) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(candidatePath, "candidatePath");

        Path normControl = controlRoot.toAbsolutePath().normalize();
        Path normCandidate = candidatePath.toAbsolutePath().normalize();

        // 1. Reject if candidate is equal to or inside control checkout root
        if (normCandidate.equals(normControl) || normCandidate.startsWith(normControl) || normControl.startsWith(normCandidate)) {
            return new PathVerificationResult(false, CleanupReason.CONTROL_CHECKOUT_PROTECTED.code(), normCandidate, null);
        }

        // 2. Reject if candidate is .git directory or contains .git
        if (normCandidate.getFileName() != null && ".git".equals(normCandidate.getFileName().toString())) {
            return new PathVerificationResult(false, CleanupReason.CONTROL_CHECKOUT_PROTECTED.code(), normCandidate, null);
        }

        // 3. Resolve workspace root for this project
        Path workspaceRoot = ProjectAdministrativeLayout.workspaceRoot(normControl);

        // 4. Verify candidate starts with workspaceRoot
        if (!normCandidate.startsWith(workspaceRoot)) {
            return new PathVerificationResult(false, CleanupReason.PATH_OUTSIDE_WORKSPACE_ROOT.code(), normCandidate, null);
        }

        // 5. Inspect canonical real paths to prevent symlink/junction escape
        try {
            Path canonicalWorkspace = Files.exists(workspaceRoot) ? workspaceRoot.toRealPath() : workspaceRoot;
            Path canonicalCandidate = Files.exists(normCandidate) ? normCandidate.toRealPath() : normCandidate;

            if (!canonicalCandidate.startsWith(canonicalWorkspace)) {
                return new PathVerificationResult(false, CleanupReason.PATH_IDENTITY_UNVERIFIED.code(), normCandidate, null);
            }

            if (canonicalCandidate.equals(normControl.toRealPath()) || canonicalCandidate.startsWith(normControl.toRealPath())) {
                return new PathVerificationResult(false, CleanupReason.CONTROL_CHECKOUT_PROTECTED.code(), normCandidate, canonicalCandidate);
            }

            // 6. If Git worktree, verify Git common directory matches control repo
            if (Files.isDirectory(normCandidate) && Files.exists(normCandidate.resolve(".git"))) {
                if (!verifyGitCommonDirectory(normControl, normCandidate)) {
                    return new PathVerificationResult(false, CleanupReason.GIT_REPOSITORY_MISMATCH.code(), normCandidate, canonicalCandidate);
                }
            }

            return new PathVerificationResult(true, "path_verified", normCandidate, canonicalCandidate);

        } catch (Exception failure) {
            return new PathVerificationResult(false, CleanupReason.PATH_IDENTITY_UNVERIFIED.code(), normCandidate, null);
        }
    }

    /**
     * Result of lifecycle path safety verification.
     *
     * @param safe       {@code true} if path is verified safe under external workspace root
     * @param reasonCode stable reason code
     * @param normalized normalized candidate path
     * @param canonical  canonical real path, or {@code null} if unverified
     */
    public record PathVerificationResult(boolean safe, String reasonCode, Path normalized, Path canonical) {

        /**
         * Validates non-null components.
         */
        public PathVerificationResult {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(normalized, "normalized");
        }
    }
}
