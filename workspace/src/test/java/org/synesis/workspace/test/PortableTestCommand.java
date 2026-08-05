package org.synesis.workspace.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds platform-neutral commands for process and validation fixtures. */
public final class PortableTestCommand {

    private PortableTestCommand() {
    }

    /**
     * Builds a command invoking the portable child-process fixture.
     *
     * @param arguments fixture arguments
     * @return direct Java argv
     */
    public static List<String> fixture(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(PortableTestProcess.class.getName());
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    /**
     * Builds a command that writes one logical value to standard output.
     *
     * @param value value to write
     * @return direct Java argv
     */
    public static List<String> stdout(String value) {
        return fixture("stdout", value, "1");
    }

    /**
     * Builds the configured validation command used by completion tests.
     *
     * @return direct Java argv
     */
    public static List<String> validation() {
        return fixture("validate", Path.of("src", "task_tracker.txt").toString(), "implemented");
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
