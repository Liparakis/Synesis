package org.synesis.workspace.application.project;

import java.util.List;
import java.util.Objects;

/**
 * Project-owned direct process configuration used by server-side validation.
 *
 * <p>The project supplies the executable and every argument. Synesis stores and
 * validates this value but never infers a build system or rewrites the argv
 * into a shell command.</p>
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
    public static final int MAX_TIMEOUT_SECONDS = 3600;
    /**
     * Maximum number of argv entries accepted from project metadata.
     */
    public static final int MAX_ARGUMENTS = 256;
    /**
     * Maximum UTF-16 length of one argv entry.
     */
    public static final int MAX_ARGUMENT_LENGTH = 32_768;

    /**
     * Validates and defensively copies the immutable command specification.
     */
    public ProjectCommandSpec {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty() || argv.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("argv must contain between 1 and " + MAX_ARGUMENTS + " entries");
        }
        argv = List.copyOf(argv);
        if (argv.getFirst() == null || argv.getFirst()
                .isBlank()) {
            throw new IllegalArgumentException("argv executable cannot be blank");
        }
        for (String argument : argv) {
            if (argument == null || argument.length() > MAX_ARGUMENT_LENGTH) {
                throw new IllegalArgumentException("argv entry is null or too long");
            }
        }
        workingDirectory = workingDirectory == null || workingDirectory.isBlank() ? "." : workingDirectory;
        if (workingDirectory.length() > MAX_ARGUMENT_LENGTH) {
            throw new IllegalArgumentException("workingDirectory is too long");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }
    }

    /**
     * Creates a command specification using the default validation timeout.
     *
     * @param argv             direct executable and arguments
     * @param workingDirectory project-relative directory, or {@code null}
     * @return validated command specification
     */
    @SuppressWarnings("unused")
    public static ProjectCommandSpec withDefaultTimeout(List<String> argv, String workingDirectory) {
        return new ProjectCommandSpec(argv, workingDirectory, DEFAULT_TIMEOUT_SECONDS);
    }
}
