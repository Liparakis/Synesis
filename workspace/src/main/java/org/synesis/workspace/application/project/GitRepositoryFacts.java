package org.synesis.workspace.application.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Reads the Git facts project initialization depends on, without changing the repository.
 */
public final class GitRepositoryFacts {

    /**
     * Status reported when the project is not a Git checkout.
     */
    public static final String HEAD_UNAVAILABLE = "GIT_HEAD_UNAVAILABLE";
    /**
     * Status reported when {@code HEAD} resolves to a commit.
     */
    public static final String HEAD_VALID = "GIT_HEAD_VALID";

    private GitRepositoryFacts() {
    }

    /**
     * Reports whether the directory carries Git metadata.
     *
     * @param root project directory
     * @return whether {@code .git} exists
     */
    public static boolean hasGitMetadata(Path root) {
        return Files.exists(root.resolve(".git"));
    }

    /**
     * Classifies the repository head.
     *
     * @param root project directory
     * @return {@link #HEAD_UNAVAILABLE} outside Git, or {@link #HEAD_VALID} when {@code HEAD} is a commit
     * @throws IOException if Git cannot resolve {@code HEAD} to a commit ID
     */
    public static String headStatus(Path root) throws IOException {
        if (!hasGitMetadata(root)) {
            return HEAD_UNAVAILABLE;
        }
        if (output(root, "rev-parse", "--verify", "HEAD").matches("[0-9a-fA-F]{40}")) {
            return HEAD_VALID;
        }
        throw new IOException("invalid Git HEAD");
    }

    /**
     * Requires Git to be able to read the repository metadata.
     *
     * @param root project directory
     * @throws IOException if Git fails or reports nothing
     */
    public static void requireReadableMetadata(Path root) throws IOException {
        output(root, "rev-parse", "--git-dir");
    }

    private static String output(Path root, String... arguments) throws IOException {
        String output = GitProcessRunner.run(root, arguments).trim();
        if (output.isBlank()) {
            throw new IOException("empty Git output");
        }
        return output;
    }
}
