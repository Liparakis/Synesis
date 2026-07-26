package org.synesis.workspace.lifecycle.cleanup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reusable safety verifier ensuring candidate filesystem paths remain strictly under the
 * allowed external Synesis project workspace root and do not escape via symlinks, junctions,
 * or path traversal.
 *
 * @since 1.0
 */
public final class LifecyclePathVerifier {

    /**
     * Creates a path verifier.
     */
    public LifecyclePathVerifier() {
    }

    /**
     * Result of lifecycle path safety verification.
     *
     * @param safe          {@code true} if path is verified safe under external workspace root
     * @param reasonCode    stable reason code
     * @param normalized    normalized candidate path
     * @param canonical     canonical real path, or {@code null} if unverified
     */
    public record PathVerificationResult(
            boolean safe,
            String reasonCode,
            Path normalized,
            Path canonical
    ) {
        /**
         * Validates non-null components.
         */
        public PathVerificationResult {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(normalized, "normalized");
        }
    }

    /**
     * Resolves the expected external Synesis workspace root for a control project.
     *
     * @param controlRoot control project root path
     * @return normalized absolute external workspace root path
     */
    public static Path resolveWorkspaceRoot(Path controlRoot) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        String projectId = resolveProjectId(controlRoot);
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = Path.of(System.getProperty("user.home"), ".synesis").toString();
        }
        return Path.of(base, "Synesis", "workspaces", projectId)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Verifies that a candidate resource path is safe for inspection/planning.
     *
     * @param controlRoot    control project root path
     * @param candidatePath  candidate filesystem path
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
        Path workspaceRoot = resolveWorkspaceRoot(normControl);

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

    private static boolean verifyGitCommonDirectory(Path controlRoot, Path worktreePath) {
        try {
            String expectedStr = runGit(controlRoot, "rev-parse", "--git-common-dir");
            Path expectedCommon = controlRoot.resolve(expectedStr).toAbsolutePath().normalize();

            String actualStr = runGit(worktreePath, "rev-parse", "--git-common-dir");
            Path actualCommon = worktreePath.resolve(actualStr).toAbsolutePath().normalize();

            Path realExpected = Files.exists(expectedCommon) ? expectedCommon.toRealPath() : expectedCommon;
            Path realActual = Files.exists(actualCommon) ? actualCommon.toRealPath() : actualCommon;
            return realExpected.equals(realActual);
        } catch (Exception failure) {
            return false;
        }
    }

    private static String resolveProjectId(Path controlRoot) {
        Path projFile = controlRoot.resolve(".synesis/project.json");
        if (Files.exists(projFile)) {
            try {
                String content = Files.readString(projFile);
                int idx = content.indexOf("\"projectId\"");
                if (idx != -1) {
                    int colon = content.indexOf(':', idx);
                    int q1 = content.indexOf('"', colon + 1);
                    int q2 = content.indexOf('"', q1 + 1);
                    if (colon != -1 && q1 != -1 && q2 != -1) {
                        return content.substring(q1 + 1, q2);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return "default-project";
    }

    private static String runGit(Path workdir, String... args) throws IOException {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "git";
        cmd[1] = "-C";
        cmd[2] = workdir.toString();
        System.arraycopy(args, 0, cmd, 3, args.length);

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            int code = proc.waitFor();
            if (code != 0) {
                throw new IOException("git command failed (code=" + code + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git command interrupted", e);
        }
        return output;
    }
}
