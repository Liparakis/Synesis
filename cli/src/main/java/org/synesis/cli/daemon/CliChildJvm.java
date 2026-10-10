package org.synesis.cli.daemon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.synesis.cli.SynesisCli");
        command.addAll(arguments);
        return new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(Path.of(onWindows() ? "NUL" : "/dev/null").toFile()));
    }
}
