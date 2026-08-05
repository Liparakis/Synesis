package org.synesis.workspace.test;

import java.io.IOException;
import java.nio.file.Path;

import org.synesis.workspace.lifecycle.GitProcessRunner;

/** Shared deterministic Git setup helper for workspace tests. */
public final class TestGit {

    private TestGit() {
    }

    /**
     * Runs a required Git command in a test repository.
     *
     * @param root repository working directory
     * @param arguments Git arguments after {@code git}
     * @throws IOException if Git fails, times out, or cannot start
     */
    public static void run(Path root, String... arguments) throws IOException {
        GitProcessRunner.run(root, arguments);
        if (arguments.length > 0 && "init".equals(arguments[0])) {
            GitProcessRunner.run(root, "config", "core.autocrlf", "false");
            GitProcessRunner.run(root, "config", "core.eol", "lf");
        }
    }

    /**
     * Runs a required Git command and returns bounded output.
     *
     * @param root repository working directory
     * @param arguments Git arguments after {@code git}
     * @return bounded Git output
     * @throws IOException if Git fails, times out, or cannot start
     */
    public static String output(Path root, String... arguments) throws IOException {
        return GitProcessRunner.run(root, arguments);
    }
}
