package org.synesis.cli.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.synesis.workspace.lifecycle.AdministrativeStateLocator;

/**
 * Builds the command line for a child JVM that runs {@code org.synesis.cli.SynesisCli} on the
 * current classpath.
 */
public final class CliChildJvm {

    private static final String LOOPBACK_SOCKET_DIRECTORY = "jdk.net.unixdomain.tmpdir";

    private CliChildJvm() {
    }

    /**
     * Reports whether this JVM runs on Windows.
     *
     * @return {@code true} on Windows
     */
    public static boolean onWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * The JVM option that sends a crash report to Synesis's own state folder. Without it the JVM writes
     * {@code hs_err_pid<N>.log} into its working directory, which is the person's project, where Synesis counts it as a
     * change it did not make (D9, real run 2026-10-26). Absent when the folder cannot be created: the JVM then keeps
     * its default and the child still starts.
     *
     * @return the {@code -XX:ErrorFile} option
     */
    static Optional<String> crashFileOption() {
        try {
            Path directory = Files.createDirectories(AdministrativeStateLocator.applicationStateRoot().resolve("crash"));
            return Optional.of("-XX:ErrorFile=" + directory.resolve("hs_err_pid%p.log"));
        } catch (IOException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Prepares a child process whose standard input is the null device. Callers add their own
     * output redirects and start it.
     *
     * @param forwardLoopbackSocketDirectory whether a non-blank {@code jdk.net.unixdomain.tmpdir}
     *                                       of this JVM is passed on to the child
     * @param arguments                      arguments after the main class
     * @return process builder, not yet started
     */
    static ProcessBuilder builder(boolean forwardLoopbackSocketDirectory, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", onWindows() ? "java.exe" : "java").toString());
        String loopbackSocketDirectory = System.getProperty(LOOPBACK_SOCKET_DIRECTORY, "");
        if (forwardLoopbackSocketDirectory && !loopbackSocketDirectory.isBlank()) {
            command.add("-D" + LOOPBACK_SOCKET_DIRECTORY + "=" + loopbackSocketDirectory);
        }
        crashFileOption().ifPresent(command::add);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.synesis.cli.SynesisCli");
        command.addAll(arguments);
        return new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(Path.of(onWindows() ? "NUL" : "/dev/null").toFile()));
    }
}
