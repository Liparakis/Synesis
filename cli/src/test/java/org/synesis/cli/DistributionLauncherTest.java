package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Verifies the generated platform launcher smoke path. */
@Timeout(45)
final class DistributionLauncherTest {
    @Test
    void generatedLauncherSupportsHelpVersionAndIdentity() throws Exception {
        Path profile = Files.createTempDirectory("synesis-launcher-profile");
        Path launcher = launcher();
        Process help = start(launcher, profile, "--help");
        assertEquals(0, help.waitFor());
        assertTrue(output(help).contains("Usage: synesis"));

        Process version = start(launcher, profile, "--version");
        assertEquals(0, version.waitFor());
        assertTrue(output(version).contains("synesis 0.1.0-SNAPSHOT"));

        Process identity = start(launcher, profile, "identity", "show");
        assertEquals(0, identity.waitFor());
        assertTrue(output(identity).contains("NODE_ID=sl1-"));
    }

    static Path launcher() {
        String executable = isWindows() ? "synesis.bat" : "synesis";
        Path local = Path.of("build", "install", "synesis", "bin", executable);
        return Files.exists(local) ? local.toAbsolutePath() : Path.of("cli").resolve(local).toAbsolutePath();
    }

    static Process start(Path launcher, Path profile, String... arguments) throws IOException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        if (isWindows()) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(launcher.toString());
        for (String argument : arguments) {
            command.add(isWindows() && argument.matches(".*[&*].*")
                    ? "\"" + argument + "\"" : argument);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("SYNESIS_LINK_PROFILE", profile.toString());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    static String output(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
