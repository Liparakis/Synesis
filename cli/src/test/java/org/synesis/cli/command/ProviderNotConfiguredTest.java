package org.synesis.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Records how the provider commands report a directory that is not an initialized project.
 */
class ProviderNotConfiguredTest {

    private static final List<String> NOT_CONFIGURED = List.of("ERROR=PROJECT_NOT_CONFIGURED");

    @TempDir
    Path tmp;

    @Test
    void everyProjectBoundProviderCommandReportsProjectNotConfigured() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Path profile = tmp.resolve("profile");
        for (String[] command : new String[][]{{"provider", "list"}, {"provider", "install", "codex"}, {"provider", "status", "codex"}, {"provider", "uninstall", "codex"}}) {
            String[] arguments = Stream.concat(Arrays.stream(command), Stream.of("--project", empty.toString())).toArray(String[]::new);
            CliHarness.run(profile, arguments).assertIs(10, List.of(), NOT_CONFIGURED);
        }
    }

    @Test
    void globalUninstallConflictsWithAProjectOption() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        CliHarness.run(tmp.resolve("profile"), "provider", "uninstall", "codex", "--global", "--project", empty.toString()).assertIs(2, List.of(), List.of("ERROR=CONFLICTING_UNINSTALL_SCOPE"));
    }
}
