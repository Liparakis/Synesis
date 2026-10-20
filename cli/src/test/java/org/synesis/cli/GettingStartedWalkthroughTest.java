package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.lifecycle.process.GitProcessRunner;

/**
 * Runs the commands of the getting-started guide, in the order it gives them, through the staged launcher in a
 * disposable Git repository, so a command that is renamed or stops working fails the build instead of the next
 * reader (ADR-0103, "Documentation that is executed"). The commands come from the guide itself: the fenced block after
 * the {@code WALKTHROUGH} marker. What needs a person or a real agent (sign-in, a task) is not run here.
 *
 * <p>Each command runs as a process, as a user types it: the launcher directory is on the {@code PATH} (provider hooks
 * find Synesis there), a stand-in {@code claude} is on the {@code PATH}, and the home directory and Link profile belong
 * to the test, so nothing of the developer's is read or written.</p>
 */
@Timeout(300)
final class GettingStartedWalkthroughTest {

    private static final String MARKER = "<!-- WALKTHROUGH:";

    @TempDir
    Path tmp;

    @Test
    void theGuidesCommandsRunInADisposableProject() throws Exception {
        assumeFalse(isWindows(), "the stand-in agent is a shell script; the Windows gate runs the same guide by hand");
        List<List<String>> commands = walkthrough(Files.readString(guide(), StandardCharsets.UTF_8));
        assertFalse(commands.isEmpty(), "the guide has no WALKTHROUGH block of commands");
        Path project = Files.createDirectories(tmp.resolve("project"));
        GitProcessRunner.run(project, "init", "-q");
        Path standIn = Files.createDirectories(tmp.resolve("agents")).resolve("claude");
        Files.writeString(standIn, "#!/bin/sh\necho 'claude 0.0.0-stand-in'\n");
        assertTrue(standIn.toFile().setExecutable(true));
        Path launcher = DistributionLauncherTest.launcher();

        for (List<String> command : commands) {
            assertEquals("synesis", command.getFirst(), "a walkthrough command starts with synesis: " + command);
            List<String> process = new ArrayList<>(command);
            process.set(0, launcher.toString());
            ProcessBuilder builder = new ProcessBuilder(process).directory(project.toFile()).redirectErrorStream(true);
            builder.environment().put("PATH", standIn.getParent() + java.io.File.pathSeparator + launcher.getParent() + java.io.File.pathSeparator + System.getenv("PATH"));
            builder.environment().put("HOME", Files.createDirectories(tmp.resolve("home")).toString());
            builder.environment().put("SYNESIS_LINK_PROFILE", tmp.resolve("profile").toString());
            Process started = builder.start();
            started.getOutputStream().close();
            String output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(started.waitFor(60, TimeUnit.SECONDS), "did not finish: " + String.join(" ", command));
            assertEquals(0, started.exitValue(), String.join(" ", command) + " failed:\n" + output);
        }
        assertTrue(Files.isRegularFile(project.resolve(".synesis/project.json")), "init left no project metadata");
        assertTrue(Files.isRegularFile(project.resolve(".synesis/local/providers/claude.json")), "provider install left no provider record");
    }

    @Test
    void aMarkedBlockYieldsItsCommandsWithoutComments() {
        String guide = "intro\n" + MARKER + " note -->\n```powershell\nsynesis init\n\nsynesis provider install claude   # or: codex\n```\n```text\nsynesis never-run\n```\n";

        assertEquals(List.of(List.of("synesis", "init"), List.of("synesis", "provider", "install", "claude")), walkthrough(guide));
    }

    /**
     * The commands of the fenced block that follows the marker, one argument list per command; comments after {@code #} are dropped.
     */
    static List<List<String>> walkthrough(String guide) {
        List<String> lines = guide.lines().toList();
        int marker = 0;
        while (marker < lines.size() && !lines.get(marker).startsWith(MARKER)) {
            marker++;
        }
        List<List<String>> commands = new ArrayList<>();
        boolean inside = false;
        for (int i = marker + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("```")) {
                if (inside) {
                    break;
                }
                inside = true;
            } else if (inside) {
                String command = line.contains("#") ? line.substring(0, line.indexOf('#')) : line;
                if (!command.isBlank()) {
                    commands.add(List.of(command.trim().split("\\s+")));
                }
            }
        }
        return commands;
    }

    private static Path guide() throws IOException {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("docs/getting-started/README.md"))) {
            directory = directory.getParent();
        }
        assertTrue(directory != null, "docs/getting-started/README.md not found above " + Path.of("").toAbsolutePath());
        return directory.resolve("docs/getting-started/README.md");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}
