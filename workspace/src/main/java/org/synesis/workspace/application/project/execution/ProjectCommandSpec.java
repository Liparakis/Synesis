package org.synesis.workspace.application.project.execution;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Project-owned direct process configuration used by server-side validation.
 *
 * <p>The project supplies the executable and every argument. Synesis stores and
 * validates this value but never infers a build system or rewrites the argv into a shell
 * command.</p>
 *
 * @param argv             direct executable and argument vector
 * @param workingDirectory project-relative working directory, default {@code "."}
 * @param timeoutSeconds   maximum execution duration in seconds
 * @since 1.0
 */
public record ProjectCommandSpec(List<String> argv, String workingDirectory, int timeoutSeconds) {

    /**
     * Default project validation timeout in seconds.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;
    /**
     * Maximum project validation timeout in seconds.
     */
    static final int MAX_TIMEOUT_SECONDS = 3600;
    /**
     * Maximum number of argv entries accepted from project metadata.
     */
    static final int MAX_ARGUMENTS = 256;
    /**
     * Maximum UTF-16 length of one argv entry.
     */
    static final int MAX_ARGUMENT_LENGTH = 32_768;

    /**
     * Validates and defensively copies the immutable command specification.
     */
    public ProjectCommandSpec {
        argv = validatedArgv(argv);
        workingDirectory = workingDirectoryOrDefault(workingDirectory);
        if (workingDirectory.length() > MAX_ARGUMENT_LENGTH) {
            throw new IllegalArgumentException("workingDirectory is too long");
        }
        requireValidTimeout(timeoutSeconds);
    }

    /**
     * Validates a direct argv and returns an immutable copy.
     *
     * @param argv executable followed by its arguments
     * @return immutable copy of {@code argv}
     * @throws IllegalArgumentException if the vector is empty, too large, has an oversized or
     *                                  null entry, or starts with a blank executable
     */
    static List<String> validatedArgv(List<String> argv) {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty() || argv.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("argv must contain between 1 and " + MAX_ARGUMENTS + " entries");
        }
        List<String> copy = List.copyOf(argv);
        if (copy.getFirst() == null || copy.getFirst().isBlank()) {
            throw new IllegalArgumentException("argv executable cannot be blank");
        }
        for (String argument : copy) {
            if (argument == null || argument.length() > MAX_ARGUMENT_LENGTH) {
                throw new IllegalArgumentException("argv entry is null or too long");
            }
        }
        return copy;
    }

    /**
     * Returns the configured working directory, or {@code "."} when none is configured.
     *
     * @param workingDirectory configured value, possibly {@code null} or blank
     * @return the value, or {@code "."}
     */
    static String workingDirectoryOrDefault(String workingDirectory) {
        return workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory;
    }

    /**
     * Reports whether a working directory is absolute, drive- or UNC-qualified, or climbs above the
     * root it is resolved against. Drive and UNC forms are rejected on every platform.
     *
     * @param workingDirectory non-blank configured working directory
     * @return {@code true} when the directory cannot stay below its root
     * @throws java.nio.file.InvalidPathException if the value is not a valid path
     */
    public static boolean escapesRoot(String workingDirectory) {
        Path relative = Path.of(workingDirectory);
        return looksLikeAbsolutePath(workingDirectory) || relative.isAbsolute() || relative.normalize().startsWith(Path.of(".."));
    }

    /**
     * Requires a timeout within the supported range.
     *
     * @param timeoutSeconds timeout in seconds
     * @throws IllegalArgumentException if the timeout is outside {@code 1..MAX_TIMEOUT_SECONDS}
     */
    static void requireValidTimeout(int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }
    }

    private static boolean looksLikeAbsolutePath(String value) {
        return value.startsWith("/") || value.startsWith("\\\\") || value.matches("^[A-Za-z]:[\\\\/].*");
    }
}
