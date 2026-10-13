package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.cli.command.CliHarness.Result;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * What the operator sets, sees and clears of the command that gates integration.
 */
class ProjectValidationCommandTest {

    @TempDir
    Path tmp;
    private Path projectFile;
    private String project;

    @BeforeEach
    void initProject() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("project"));
        projectFile = new ProjectApplicationService().init(root).location().metadataFile();
        project = root.toString();
    }

    private Result run(String... arguments) {
        return CliHarness.run(tmp.resolve("cli-profile"), arguments);
    }

    @Test
    void setThenShowPrintsTheCommandItsDirectoryItsTimeoutAndWhetherItRunsInTheLane() throws Exception {
        Result set = run("project", "validation", "set", "--project", project, "--", "git", "status");

        assertEquals(0, set.exit(), set.toString());
        assertTrue(set.stdout().contains("PROJECT_FILE_CHANGED=.synesis/project.json"), set.toString());

        Result shown = run("project", "validation", "show", "--project", project);

        assertEquals(0, shown.exit(), shown.toString());
        shown.assertIs(0, java.util.List.of("VALIDATION=DECLARED", "~VALIDATION_ARGV=\\[\\s*\"git\",\\s*\"status\"\\s*\\]", "VALIDATION_DIRECTORY=.",
                "VALIDATION_TIMEOUT_SECONDS=120", "VALIDATION_ALSO_IN_LANE=false"), java.util.List.of());
    }

    @Test
    void timeoutDirectoryAndAlsoInLaneAreReflected() throws Exception {
        Result set = run("project", "validation", "set", "--project", project, "--timeout", "5", "--directory", "sub", "--also-in-lane", "--", "git", "log");

        assertEquals(0, set.exit(), set.toString());

        Result shown = run("project", "validation", "show", "--project", project);

        assertTrue(shown.stdout().contains("VALIDATION_DIRECTORY=sub"), shown.toString());
        assertTrue(shown.stdout().contains("VALIDATION_TIMEOUT_SECONDS=5"), shown.toString());
        assertTrue(shown.stdout().contains("VALIDATION_ALSO_IN_LANE=true"), shown.toString());
    }

    @Test
    void clearRemovesTheCommand() throws Exception {
        run("project", "validation", "set", "--project", project, "--", "git", "status");

        Result cleared = run("project", "validation", "clear", "--project", project);

        assertEquals(0, cleared.exit(), cleared.toString());
        assertEquals("VALIDATION=NONE", cleared.stdout().getFirst());
        Result shown = run("project", "validation", "show", "--project", project);
        shown.assertIs(0, java.util.List.of("VALIDATION=NONE"), java.util.List.of());
    }

    @Test
    void aTimeoutOutOfRangeAndADirectoryOutsideTheProjectAreRefusedAndTheFileIsUnchanged() throws Exception {
        String before = Files.readString(projectFile);

        for (String[] invalid : new String[][] {
                {"--timeout", "0"}, {"--timeout", "3601"}, {"--timeout", "-1"}, {"--directory", ".."}, {"--directory", "../elsewhere"}, {"--directory", "/abs"}}) {
            String[] arguments = new String[5 + invalid.length + 3];
            arguments[0] = "project";
            arguments[1] = "validation";
            arguments[2] = "set";
            arguments[3] = "--project";
            arguments[4] = project;
            System.arraycopy(invalid, 0, arguments, 5, invalid.length);
            arguments[5 + invalid.length] = "--";
            arguments[6 + invalid.length] = "git";
            arguments[7 + invalid.length] = "status";

            Result refused = run(arguments);

            assertTrue(refused.exit() != 0, String.join(" ", invalid) + " " + refused);
            assertEquals(before, Files.readString(projectFile), String.join(" ", invalid));
        }
    }

    @Test
    void setWithoutACommandIsAUsageError() throws Exception {
        String before = Files.readString(projectFile);

        Result refused = run("project", "validation", "set", "--project", project);

        assertEquals(ExitCodes.USAGE, refused.exit(), refused.toString());
        assertEquals(before, Files.readString(projectFile));
    }

    private static String git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString(), "-c", "user.name=Test", "-c", "user.email=test@example.invalid"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), String.join(" ", command) + "\n" + output);
        return output;
    }

    @Test
    void aCommittedSetPrintsTheShortCommitAndAnUnchangedSetPrintsNeitherLine() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("git-project"));
        git(root, "init");
        Files.writeString(root.resolve("README.md"), "base\n");
        git(root, "add", "README.md");
        git(root, "commit", "-m", "base");
        new ProjectApplicationService().init(root);
        String git = root.toString();

        Result set = run("project", "validation", "set", "--project", git, "--", "git", "status");

        assertEquals(0, set.exit(), set.toString());
        assertTrue(set.stdout().contains("PROJECT_FILE_COMMITTED=" + git(root, "rev-parse", "--short", "HEAD")), set.toString());
        assertTrue(set.stdout().contains("PROJECT_FILE_CHANGED=.synesis/project.json"), set.toString());

        Result again = run("project", "validation", "set", "--project", git, "--", "git", "status");

        assertEquals(0, again.exit(), again.toString());
        assertTrue(again.stdout().stream().noneMatch(line -> line.startsWith("PROJECT_FILE_")), again.toString());
        assertTrue(again.stdout().contains("VALIDATION=DECLARED"), again.toString());
    }
}
