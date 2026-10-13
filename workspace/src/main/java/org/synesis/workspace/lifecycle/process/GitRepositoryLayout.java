package org.synesis.workspace.lifecycle.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads where a checkout keeps its Git data from the checkout itself.
 *
 * <p>Which repository a checkout belongs to was asked of a {@code git} process on every tool
 * call, several times over: in one measured test class, 2,654 of 6,187 Git processes asked only
 * that. The answer is in two small files Git itself reads (gitrepository-layout): {@code .git} is
 * the Git directory or names it, and a linked worktree's Git directory names the shared one in
 * {@code commondir}. Reading them gives the same answer without starting a process, and it is
 * read afresh every time, so nothing is remembered that could go stale.</p>
 *
 * <p>Only the plain layouts are answered here. Anything else is left to Git by answering
 * nothing: a directory that is not the top of a checkout, a Git directory this does not
 * recognise, and a process whose environment redirects Git.</p>
 */
public final class GitRepositoryLayout {

    /** A {@code .git} file or a {@code commondir} file is one short line; anything larger is not one. */
    private static final long MAX_POINTER_BYTES = 4096L;

    private static final String GIT_DIR_PREFIX = "gitdir:";

    private GitRepositoryLayout() {
    }

    /**
     * Returns the Git common directory of a checkout: the repository's own Git directory, which
     * a main checkout and all its linked worktrees share.
     *
     * @param checkoutRoot top directory of a main checkout or of a linked worktree
     * @return the common directory as the layout names it, not yet made canonical; empty when the
     *         layout is not one of the plain ones and Git has to be asked
     */
    public static Optional<Path> commonDirectory(Path checkoutRoot) {
        // Git would answer from these instead of from the checkout.
        if (System.getenv("GIT_DIR") != null || System.getenv("GIT_COMMON_DIR") != null) {
            return Optional.empty();
        }
        try {
            Path root = checkoutRoot.toAbsolutePath().normalize();
            Path dotGit = root.resolve(".git");
            Path gitDirectory;
            if (Files.isDirectory(dotGit)) {
                gitDirectory = dotGit;
            } else {
                Optional<String> named = pointer(dotGit).filter(line -> line.startsWith(GIT_DIR_PREFIX)).map(line -> line.substring(GIT_DIR_PREFIX.length()).strip());
                if (named.isEmpty() || named.get().isEmpty()) {
                    return Optional.empty();
                }
                gitDirectory = root.resolve(named.get()).normalize();
            }
            if (!Files.isRegularFile(gitDirectory.resolve("HEAD"))) {
                return Optional.empty();
            }
            Path common = gitDirectory;
            if (Files.exists(gitDirectory.resolve("commondir"))) {
                Optional<String> shared = pointer(gitDirectory.resolve("commondir"));
                if (shared.isEmpty() || shared.get().isEmpty()) {
                    return Optional.empty();
                }
                common = gitDirectory.resolve(shared.get()).normalize();
            }
            // What makes a directory the shared one: it holds the objects and the references.
            return Files.isDirectory(common.resolve("objects")) && Files.isDirectory(common.resolve("refs")) ? Optional.of(common) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Tells whether Git could find a repository from a directory: the directory or one above it
     * holds a {@code .git}, or this process's environment names a repository. Where neither is
     * so, Git answers "not a git repository" and there is no need to start it to hear that.
     *
     * @param directory directory Git would be started in
     * @return false only when Git is certain to find no repository there
     */
    public static boolean mayBeInsideARepository(Path directory) {
        if (System.getenv("GIT_DIR") != null || System.getenv("GIT_COMMON_DIR") != null) {
            return true;
        }
        try {
            for (Path current = directory.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
                if (Files.exists(current.resolve(".git"))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    /** Reads a one-line pointer file; empty when it is missing, not a regular file, or too large to be one. */
    private static Optional<String> pointer(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_POINTER_BYTES) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(file, StandardCharsets.UTF_8).strip());
    }
}
