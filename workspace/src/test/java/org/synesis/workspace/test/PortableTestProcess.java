package org.synesis.workspace.test;

import java.io.IOException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Child process fixture used to keep process assertions portable across CI hosts. */
public final class PortableTestProcess {

    private PortableTestProcess() {
    }

    /**
     * Runs one bounded process fixture.
     *
     * @param arguments fixture operation and arguments
     * @throws Exception if the fixture cannot read its validation file
     */
    public static void main(String[] arguments) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                StandardCharsets.UTF_8));
        if (arguments.length == 0) {
            throw new IllegalArgumentException("fixture operation is required");
        }
        switch (arguments[0]) {
            case "exit" -> System.exit(0);
            case "stdout" -> {
                writeRepeated(arguments[1], Integer.parseInt(arguments[2]), false);
                System.exit(0);
            }
            case "stderr" -> {
                writeRepeated(arguments[1], Integer.parseInt(arguments[2]), true);
                System.exit(0);
            }
            case "both" -> {
                writeRepeated("O", 100_000, false);
                writeRepeated("E", 100_000, true);
                System.exit(0);
            }
            case "utf8" -> {
                writeRepeated("😀", Integer.parseInt(arguments[1]), false);
                System.exit(0);
            }
            case "utf8-prefix" -> {
                System.out.print(arguments[1]);
                writeRepeated("😀", Integer.parseInt(arguments[2]), false);
                System.exit(0);
            }
            case "fail" -> {
                System.err.print(arguments[1]);
                System.exit(Integer.parseInt(arguments[2]));
            }
            case "partial" -> {
                writeRepeated(arguments[1], Integer.parseInt(arguments[2]), false);
                Thread.sleep(Long.parseLong(arguments[3]) * 1_000L);
                System.exit(0);
            }
            case "validate" -> {
                validate(Path.of(arguments[1]), arguments[2]);
                System.exit(0);
            }
            default -> throw new IllegalArgumentException("unknown fixture: " + arguments[0]);
        }
    }

    private static void writeRepeated(String value, int count, boolean error) throws IOException {
        String output = value.repeat(count);
        if (error) {
            System.err.print(output);
            System.err.flush();
        } else {
            System.out.print(output);
            System.out.flush();
        }
    }

    private static void validate(Path file, String expected) throws IOException {
        String actual = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (!actual.equals(expected)) {
            System.exit(1);
        }
    }
}
