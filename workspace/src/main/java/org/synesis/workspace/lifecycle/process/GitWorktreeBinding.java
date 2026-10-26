package org.synesis.workspace.lifecycle.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Binds a linked worktree to its control repository from the control side (ADR-0104, "Synesis's own Git on an agent's
 * worktree is hardened").
 *
 * <p>A linked worktree's {@code .git} file is the agent's to rewrite: it can point Git at a directory in the
 * worktree that holds a hostile {@code config}, and Git would then run a program named there the next time Synesis
 * runs a command in the worktree. So Synesis never believes that file. It asks the control repository, which
 * records each worktree under {@code worktrees/<name>/gitdir}, and names the Git directory, the common directory and
 * the work tree to Git explicitly through the environment, which takes precedence over the file.</p>
 *
 * <p>A control repository is trusted only when Synesis was handed it as a project root
 * ({@link GitProcessRunner#trustControlRoot(Path)}), never because a worktree names it.</p>
 *
 * @since 1.0
 */
public final class GitWorktreeBinding {

    private static final Set<Path> TRUSTED_CONTROL_GIT_DIRECTORIES = ConcurrentHashMap.newKeySet();

    private final Path gitDirectory;
    private final Path commonDirectory;
    private final Path workTree;

    private GitWorktreeBinding(Path gitDirectory, Path commonDirectory, Path workTree) {
        this.gitDirectory = gitDirectory;
        this.commonDirectory = commonDirectory;
        this.workTree = workTree;
    }

    /**
     * Finds the administrative directory {@code <controlGit>/worktrees/<name>} that records this worktree.
     *
     * @param controlGit control repository's {@code .git} directory
     * @param workTree   root of the linked worktree
     * @return the administrative directory, or empty when the control repository does not list the worktree
     */
    public static Optional<Path> administrativeDirectory(Path controlGit, Path workTree) {
        Path registered = controlGit.resolve("worktrees");
        if (!Files.isDirectory(registered)) {
            return Optional.empty();
        }
        Path expected = workTree.resolve(".git");
        try (Stream<Path> entries = Files.list(registered)) {
            for (Path entry : entries.toList()) {
                Path pointer = entry.resolve("gitdir");
                if (Files.isRegularFile(pointer) && sameFile(Path.of(Files.readString(pointer, StandardCharsets.UTF_8).strip()), expected)) {
                    return Optional.of(real(entry));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Trusts a control checkout. A directory under Synesis's lane storage is refused: it is where an agent's worktree
     * lives, and a worktree an agent turned into a repository must not become a control repository that claims others.
     */
    static void trust(Path controlRoot) {
        Path git = real(controlRoot).resolve(".git");
        String separator = java.io.File.separator;
        if (Files.isDirectory(git) && !git.toString().contains(separator + "Synesis" + separator + "workspaces" + separator)) {
            TRUSTED_CONTROL_GIT_DIRECTORIES.add(git);
        }
    }

    /**
     * Binds the linked worktree that contains {@code directory}, when a trusted control repository lists it.
     *
     * @param directory a worktree root or a folder inside one
     * @return the binding, or {@code null} for a directory that is not a listed linked worktree
     */
    static GitWorktreeBinding of(Path directory) {
        Path top = directory.toAbsolutePath().normalize();
        while (top != null && !Files.exists(top.resolve(".git"))) {
            top = top.getParent();
        }
        if (top == null || !Files.isRegularFile(top.resolve(".git"))) {
            return null;
        }
        for (Path controlGit : TRUSTED_CONTROL_GIT_DIRECTORIES) {
            Optional<Path> administrative = administrativeDirectory(controlGit, top);
            if (administrative.isPresent()) {
                return new GitWorktreeBinding(administrative.get(), controlGit, real(top));
            }
        }
        return null;
    }

    void applyTo(java.util.Map<String, String> environment) {
        environment.put("GIT_DIR", gitDirectory.toString());
        environment.put("GIT_COMMON_DIR", commonDirectory.toString());
        environment.put("GIT_WORK_TREE", workTree.toString());
    }

    private static boolean sameFile(Path claimed, Path expected) throws IOException {
        if (Files.exists(claimed) && Files.exists(expected)) {
            return Files.isSameFile(claimed, expected);
        }
        return claimed.toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize());
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException unavailable) {
            return path.toAbsolutePath().normalize();
        }
    }
}
