package org.synesis.mcp.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.AdministrativeStateLocator;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Compares provider-advertised roots with an explicitly pinned MCP control checkout without
 * creating or repairing repository-private state.
 */
final class McpProjectRootAuthority {

    /** Marker written inside an assigned worker workspace. */
    private static final String ASSIGNED_WORKSPACE_MARKER = ".synesis/local/workspace-binding.json";

    /** Portable path segment under which assigned worker worktrees live. */
    private static final String ASSIGNED_WORKTREES_SEGMENT = "/.synesis/local/worktrees/";

    /** Project metadata file whose presence marks an initialized Synesis project. */
    private static final String PROJECT_METADATA = ".synesis/project.json";

    /** Git entry that is a directory in a main checkout and a file in a linked worktree. */
    private static final String GIT_ENTRY = ".git";

    /** Prefix of each worktree line in {@code git worktree list --porcelain} output. */
    private static final String WORKTREE_LINE_PREFIX = "worktree ";

    /** Utility class; root authority is evaluated through its static operations. */
    private McpProjectRootAuthority() {
    }

    /**
     * Selects the pinned control root only when every advertised root is consistent with it.
     *
     * <p>The user home and unusable paths are skipped; an assigned worktree, an unverifiable root, or a
     * root of a different project or Git repository rejects the whole selection.
     *
     * @param pinnedRoot launcher-authorized control root
     * @param candidates roots advertised by the MCP client; {@code null} or empty accepts the pin
     * @param userHome   normalized user home excluded from project-root candidates
     * @return accepted pinned root or a fail-closed rejection code
     */
    static Selection select(Path pinnedRoot, List<Path> candidates, Path userHome) {
        ProjectIdentity pinned = inspect(pinnedRoot, true);
        if (pinned == null) {
            return Selection.rejected("PROJECT_ROOT_PIN_INVALID");
        }
        if (candidates == null || candidates.isEmpty()) {
            return Selection.accepted(pinned.root());
        }

        for (Path candidate : candidates) {
            Path normalized = normalize(candidate);
            if (normalized == null || normalized.equals(userHome)) {
                continue;
            }
            if (isAssignedWorkspace(normalized)) {
                return Selection.rejected("PROJECT_ROOT_ASSIGNED_WORKTREE_REJECTED");
            }
            ProjectIdentity advertised = inspect(normalized, false);
            if (advertised == null) {
                return Selection.rejected("PROJECT_ROOT_PIN_VERIFICATION_FAILED");
            }
            if (!pinned.sameProjectAs(advertised)) {
                return Selection.rejected("PROJECT_ROOT_PIN_MISMATCH");
            }
        }
        return Selection.accepted(pinned.root());
    }

    /**
     * Detects roots reserved for assigned Synesis worker workspaces.
     *
     * @param root candidate project root
     * @return whether the path is lexically or physically under assigned workspace state
     */
    static boolean isAssignedWorkspace(Path root) {
        Path normalized = normalize(root);
        if (normalized == null) {
            return false;
        }
        Path reservedWorkspaces = AdministrativeStateLocator.applicationStateRoot().resolve("workspaces").toAbsolutePath().normalize();
        String portable = normalized.toString().replace('\\', '/');
        // Cheap lexical checks first; resolving links touches the filesystem and is only needed for the last case.
        if (portable.contains(ASSIGNED_WORKTREES_SEGMENT) || normalized.startsWith(reservedWorkspaces) || Files.isRegularFile(normalized.resolve(ASSIGNED_WORKSPACE_MARKER))) {
            return true;
        }
        try {
            return normalized.toRealPath().startsWith(reservedWorkspaces);
        } catch (Exception unavailable) {
            // Nonexistent paths are still classified by their lexical location, which already failed above.
            return false;
        }
    }

    /**
     * Resolves an advertised project root to the main checkout that owns its Git common directory.
     *
     * <p>A normal checkout is already the control checkout. A linked worktree is resolved through
     * Git's read-only worktree projection and is accepted only when exactly one listed main checkout
     * has the same initialized Synesis project identity.
     *
     * @param advertisedRoot provider-advertised project root
     * @return the matching main checkout, or {@code null} when it cannot be proven uniquely
     */
    static Path resolveMainCheckout(Path advertisedRoot) {
        ProjectIdentity advertised = inspect(advertisedRoot, false);
        if (advertised == null) {
            return null;
        }
        Path dotGit = advertised.root().resolve(GIT_ENTRY);
        if (Files.isDirectory(dotGit)) {
            return advertised.root();
        }
        if (!Files.isRegularFile(dotGit)) {
            return null;
        }

        try {
            GitProcessRunner.Result result = GitProcessRunner.runResult(advertised.root(), "worktree", "list", "--porcelain");
            if (result.exitCode() != 0) {
                return null;
            }
            Path resolved = null;
            for (String line : result.output().lines().toList()) {
                if (!line.startsWith(WORKTREE_LINE_PREFIX)) {
                    continue;
                }
                String listedText = line.substring(WORKTREE_LINE_PREFIX.length()).trim();
                if (listedText.isEmpty()) {
                    continue;
                }
                ProjectIdentity main = inspect(normalize(Path.of(listedText)), true);
                if (main == null || !advertised.sameProjectAs(main)) {
                    continue;
                }
                if (resolved != null && !resolved.equals(main.root())) {
                    return null;
                }
                resolved = main.root();
            }
            return resolved;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /**
     * Reads project identity and Git common-directory evidence without mutating repository state.
     *
     * @param root                candidate checkout root
     * @param requireMainCheckout whether a normal .git directory is required
     * @return verified identity, or {@code null} if any required evidence is unavailable
     */
    private static ProjectIdentity inspect(Path root, boolean requireMainCheckout) {
        Path normalized = normalize(root);
        if (normalized == null || isAssignedWorkspace(normalized)) {
            return null;
        }
        if (!hasGitEntry(normalized.resolve(GIT_ENTRY), requireMainCheckout)) {
            return null;
        }
        Path metadata = normalized.resolve(PROJECT_METADATA);
        if (!Files.isRegularFile(metadata)) {
            return null;
        }
        try {
            Object parsed = ProviderJson.parse(Files.readString(metadata, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> values) || !(values.get("projectId") instanceof String projectIdText)) {
                return null;
            }
            UUID projectId = UUID.fromString(projectIdText);
            Path common = new AdministrativeStateLocator().resolveGitCommonDirectory(normalized);
            return new ProjectIdentity(normalized, projectId, common);
        } catch (Exception invalid) {
            return null;
        }
    }

    /**
     * Checks the {@code .git} entry of a checkout.
     *
     * @param dotGit              path of the {@code .git} entry
     * @param requireMainCheckout whether only a main checkout (a {@code .git} directory) qualifies
     * @return {@code true} for a directory, or for a plain file (linked worktree) when allowed
     */
    private static boolean hasGitEntry(Path dotGit, boolean requireMainCheckout) {
        return Files.isDirectory(dotGit) || (!requireMainCheckout && Files.isRegularFile(dotGit));
    }

    /**
     * Converts a candidate to an absolute normalized path without following links.
     *
     * @param path candidate path
     * @return normalized absolute path, or {@code null} when absent or invalid
     */
    private static Path normalize(Path path) {
        try {
            return path == null ? null : path.toAbsolutePath().normalize();
        } catch (Exception invalid) {
            return null;
        }
    }

    /**
     * Result of resolving launcher-pinned project-root authority.
     *
     * @param root      verified root, or {@code null} when rejected
     * @param rejection stable rejection code, or {@code null} when accepted
     */
    record Selection(Path root, String rejection) {

        /**
         * Creates an accepted result for the selected root.
         *
         * @param root verified root
         * @return accepted selection
         */
        static Selection accepted(Path root) {
            return new Selection(root, null);
        }

        /**
         * Creates a rejected result with a stable diagnostic code.
         *
         * @param rejection rejection code
         * @return rejected selection
         */
        static Selection rejected(String rejection) {
            return new Selection(null, rejection);
        }

        /**
         * Indicates whether root selection failed closed.
         *
         * @return {@code true} when this selection carries a rejection
         */
        boolean rejected() {
            return rejection != null;
        }
    }

    /**
     * Immutable identity evidence shared by the root-selection checks.
     *
     * @param root               normalized checkout root
     * @param projectId          project id from the checkout's metadata
     * @param gitCommonDirectory Git common directory shared by all worktrees of the repository
     */
    private record ProjectIdentity(Path root, UUID projectId, Path gitCommonDirectory) {

        /**
         * Checks that another checkout is the same Synesis project in the same Git repository.
         *
         * @param other identity to compare
         * @return {@code true} when both project id and Git common directory match
         */
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean sameProjectAs(ProjectIdentity other) {
            return projectId.equals(other.projectId) && gitCommonDirectory.equals(other.gitCommonDirectory);
        }
    }
}