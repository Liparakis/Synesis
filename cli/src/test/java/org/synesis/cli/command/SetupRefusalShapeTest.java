package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.cli.exit.ExitCodes;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Every setup refusal path of {@code provider} prints {@code ERROR}, {@code MESSAGE} and {@code FIX} (ADR-0103, decision 3,
 * "Setup failure messages: 100 %") and keeps the keys and exit codes it printed before. The refusal paths of {@code link}
 * and {@code agents run} are walked the same way in {@code LinkCommandsTest} and {@code AgentsRunCommandTest}, which
 * need a runtime to refuse against.
 */
class SetupRefusalShapeTest {

    private static final String[][] PROJECT_COMMANDS = {{"provider", "list"}, {"provider", "install", "codex"}, {"provider", "status", "codex"}, {"provider", "uninstall", "codex"}};

    @TempDir
    Path tmp;

    @Test
    void everyProviderCommandThatCannotFindItsProjectPrintsTheShapeWithItsOwnCause() throws Exception {
        Path profile = tmp.resolve("profile");
        Path bare = Files.createDirectories(tmp.resolve("bare"));
        Path damaged = initialized("damaged");
        Files.writeString(damaged.resolve(".synesis/project.json"), "{ not json");
        for (String[] command : PROJECT_COMMANDS) {
            CliHarness.run(profile, withProject(command, bare)).assertIs(10, List.of(), CliHarness.shaped("PROJECT_NOT_CONFIGURED"));
            CliHarness.run(profile, withProject(command, tmp.resolve("missing"))).assertIs(10, List.of(), CliHarness.shaped("PROJECT_INVALID", "REASON=Invalid project directory"));
            CliHarness.run(profile, withProject(command, damaged)).assertIs(10, List.of(), CliHarness.shaped("MALFORMED", "REASON=Project metadata is malformed"));
        }
    }

    @Test
    void everyProviderResultThatFailsGetsAMessageAndAFixBesideItsOwnError() throws Exception {
        Path profile = tmp.resolve("profile");
        Path project = initialized("project");
        for (String verb : List.of("install", "status", "uninstall")) {
            CliHarness.Result refused = CliHarness.run(profile, withProject(new String[]{"provider", verb, "nope"}, project));
            assertEquals(ExitCodes.PROVIDER_UNAVAILABLE, refused.exit(), refused.toString());
            assertShapeFollowsError(refused.stdout(), "UNKNOWN_PROVIDER");
            assertTrue(refused.stdout().contains("PROVIDER=nope"), "the keys it printed before are still printed: " + refused);
            assertEquals(List.of(), refused.stderr());
        }
        CliHarness.Result global = CliHarness.run(profile, "provider", "uninstall", "nope", "--global");
        assertEquals(ExitCodes.PROVIDER_UNAVAILABLE, global.exit(), global.toString());
        assertShapeFollowsError(global.stdout(), "UNKNOWN_PROVIDER");
        CliHarness.Result unsupported = CliHarness.run(profile, "provider", "uninstall", "claude", "--global");
        assertEquals(ExitCodes.PROVIDER_UNAVAILABLE, unsupported.exit(), unsupported.toString());
        assertShapeFollowsError(unsupported.stdout(), "GLOBAL_MCP_UNSUPPORTED");
    }

    @Test
    void aBrokenProviderStatusNamesAnErrorOfItsOwn() throws Exception {
        Path project = initialized("broken");
        Path metadata = project.resolve(".synesis/local/providers/codex.json");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "{}");

        CliHarness.Result status = CliHarness.run(tmp.resolve("profile"), withProject(new String[]{"provider", "status", "codex"}, project));

        assertEquals(ExitCodes.PROVIDER_BROKEN, status.exit(), status.toString());
        assertTrue(status.stdout().stream().anyMatch(line -> line.startsWith("ERROR=")), status.toString());
        assertShapeFollowsError(status.stdout(), status.stdout().stream().filter(line -> line.startsWith("ERROR=")).findFirst().orElseThrow().substring("ERROR=".length()));
    }

    @Test
    void conflictingModesOfProviderMigrateKeepTheUsageLineAndGainTheShape() {
        CliHarness.Result conflicting = CliHarness.run(tmp.resolve("profile"), "provider", "migrate", "--dry-run", "--prepare");

        conflicting.assertIs(2, List.of(), concat(List.of("USAGE_ERROR=CONFLICTING_MODES"), CliHarness.shaped("CONFLICTING_MODES")));
    }

    @Test
    void aCodeNobodyExplainedStillGetsAMessageAndAFixButNeverPassesAsExplained() {
        List<String> lines = new ArrayList<>();

        ErrorShape.print(lines::add, "SOMETHING_NEW");

        assertEquals("ERROR=SOMETHING_NEW", lines.getFirst());
        assertTrue(lines.get(1).startsWith("MESSAGE=") && lines.get(2).startsWith("FIX="), lines.toString());
        assertFalse(ErrorShape.explains("SOMETHING_NEW"));
        assertTrue(ErrorShape.explains("TASK_REQUIRED"));
    }

    private static void assertShapeFollowsError(List<String> lines, String code) {
        int error = lines.indexOf("ERROR=" + code);
        assertTrue(error >= 0, "ERROR=" + code + " in " + lines);
        List<String> after = lines.subList(error + 1, lines.size());
        List<String> shape = after.stream().filter(line -> line.startsWith("MESSAGE=") || line.startsWith("FIX=")).toList();
        assertEquals(2, shape.size(), "one MESSAGE and one FIX in " + lines);
        ErrorShapeAssert.assertShape(shape);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private Path initialized(String name) throws Exception {
        Path project = Files.createDirectories(tmp.resolve(name));
        new ProjectApplicationService().init(project);
        return project;
    }

    private static String[] withProject(String[] command, Path project) {
        return Stream.concat(Arrays.stream(command), Stream.of("--project", project.toString())).toArray(String[]::new);
    }
}
