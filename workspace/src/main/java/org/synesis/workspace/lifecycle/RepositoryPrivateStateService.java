package org.synesis.workspace.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/**
 * Maintains the narrow repository-private Git exclusion contract used by
 * Synesis runtime state.
 *
 * <p>The service changes only {@code info/exclude}; it does not change tracked
 * files, inspect provider ownership, or claim the surrounding {@code .synesis}
 * or {@code .codex} directories. Git exclusions affect visibility only.</p>
 *
 * @since 1.0
 */
public final class RepositoryPrivateStateService {

    /** Exact root-anchored exclusions owned by Synesis. */
    public static final List<String> SYNESIS_EXCLUSIONS = List.of(
            "/.synesis/local/",
            "/.synesis/coordination/",
            "/.codex/hooks.json");

    private static final Object LOCK = new Object();

    private RepositoryPrivateStateService() {
    }

    /**
     * Ensures the exact exclusions exist in the repository's canonical common
     * Git directory while preserving unrelated content.
     *
     * @param repositoryRoot checkout or linked worktree
     * @throws IOException if Git identity or the exclusion file cannot be verified or updated
     */
    public static void ensure(Path repositoryRoot) throws IOException {
        if (repositoryRoot == null || !Files.isDirectory(repositoryRoot)) {
            return;
        }
        Path common;
        try {
            common = new AdministrativeStateLocator().resolveGitCommonDirectory(repositoryRoot);
        } catch (IOException unavailable) {
            // Uninitialized/non-Git project state cannot have a Git exclude.
            if (!Files.exists(repositoryRoot.resolve(".git"))) {
                return;
            }
            throw unavailable;
        }
        Path exclude = common.resolve("info").resolve("exclude");
        synchronized (LOCK) {
            if (Files.exists(exclude, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(exclude) || !Files.isRegularFile(exclude,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("GIT_EXCLUDE_NOT_REGULAR");
            }
            String existing = Files.exists(exclude) ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
            String updated = appendMissing(existing);
            if (updated.equals(existing)) {
                return;
            }
            Files.createDirectories(exclude.getParent());
            Path temporary = exclude.resolveSibling(exclude.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(temporary, updated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                try {
                    Files.move(temporary, exclude, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, exclude, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String appendMissing(String existing) {
        String newline = existing.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder result = new StringBuilder(existing);
        for (String exclusion : SYNESIS_EXCLUSIONS) {
            if (!hasLine(existing, exclusion)) {
                if (result.length() > 0 && !result.toString().endsWith("\n") && !result.toString().endsWith("\r")) {
                    result.append(newline);
                }
                result.append(exclusion).append(newline);
            }
        }
        return result.toString();
    }

    private static boolean hasLine(String content, String expected) {
        return content.lines().anyMatch(line -> line.trim().equals(expected));
    }
}
