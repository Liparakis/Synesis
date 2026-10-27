package org.synesis.workspace.application.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.infrastructure.json.ProviderJson;

/**
 * The provider's own file tools are refused Synesis's state and keys (ADR-0104): they run outside the sandbox, so the
 * PreToolUse hook is where they are stopped. The agent's own lane worktree, which lives inside the state, stays readable.
 */
class SynesisStateReadGuardTest {

    @TempDir
    Path temp;

    private String previousHome;
    private Path control;
    private Path lane;
    private Path otherLane;

    @BeforeEach
    void layout() throws Exception {
        previousHome = System.getProperty("user.home");
        Path home = Files.createDirectories(temp.resolve("home"));
        System.setProperty("user.home", home.toString());
        // Under user.home/.synesis, which is an application-state folder on every platform.
        Path worktrees = Files.createDirectories(home.resolve(".synesis/workspaces/p/worktrees"));
        lane = Files.createDirectories(worktrees.resolve("lane1"));
        otherLane = Files.createDirectories(worktrees.resolve("lane2"));
        Files.createDirectories(lane.resolve(".synesis/local"));
        Files.writeString(lane.resolve(".synesis/local/workspace-binding.json"), "{}");
        Files.writeString(lane.resolve("README.md"), "mine");
        Files.writeString(otherLane.resolve("README.md"), "theirs");
        control = Files.createDirectories(temp.resolve("project"));
        Files.createDirectories(control.resolve(".synesis/local/profile/operator"));
        Files.createDirectories(control.resolve(".synesis/local/profile/link"));
        Files.writeString(control.resolve(".synesis/local/profile/operator/operator.key"), "key");
        Files.writeString(control.resolve(".synesis/local/profile/link/identity.bin"), "key");
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", previousHome);
    }

    private boolean refuses(String tool, Map<String, Object> input, Path cwd) {
        return SynesisStateReadGuard.denial(tool, input, cwd.toString()).isPresent();
    }

    @Test
    void theKeysOfAProjectCannotBeReadByAnyOfTheThreeTools() {
        Path operatorKey = control.resolve(".synesis/local/profile/operator/operator.key");
        Path nodeKey = control.resolve(".synesis/local/profile/link/identity.bin");

        assertTrue(refuses("Read", Map.of("file_path", operatorKey.toString()), lane));
        assertTrue(refuses("Read", Map.of("file_path", nodeKey.toString()), lane));
        assertTrue(refuses("Grep", Map.of("pattern", "x", "path", operatorKey.getParent().toString()), lane));
        assertTrue(refuses("Glob", Map.of("pattern", "*", "path", nodeKey.getParent().toString()), lane));
        assertTrue(refuses("Glob", Map.of("pattern", control.toString().replace('\\', '/') + "/.synesis/**"), lane));
        assertTrue(refuses("Read", Map.of("file_path", ".synesis/local/profile/operator/operator.key"), control));
    }

    @Test
    void theApplicationStateOfOtherLanesAndSearchesAboveItAreRefusedButTheOwnLaneIsNot() {
        assertEquals(java.util.Optional.empty(), SynesisStateReadGuard.denial("Read", Map.of("file_path", lane.resolve("README.md").toString()), lane.toString()));
        assertEquals(java.util.Optional.empty(), SynesisStateReadGuard.denial("Read", Map.of("file_path", "README.md"), lane.toString()));
        assertEquals(java.util.Optional.empty(), SynesisStateReadGuard.denial("Grep", Map.of("pattern", "mine"), lane.toString()), "a search with no path starts in the lane");
        assertEquals(java.util.Optional.empty(), SynesisStateReadGuard.denial("Glob", Map.of("pattern", "**/*.md"), lane.toString()));

        assertTrue(refuses("Read", Map.of("file_path", otherLane.resolve("README.md").toString()), lane));
        assertTrue(refuses("Read", Map.of("file_path", "../lane2/README.md"), lane));
        assertTrue(refuses("Glob", Map.of("pattern", "*", "path", lane.getParent().toString()), lane), "the folder of the lanes");
        assertTrue(refuses("Grep", Map.of("pattern", "x", "path", Path.of(System.getProperty("user.home")).toString()), lane), "a search above the state reads it");
    }

    @Test
    void aSubfolderOfTheLaneKeepsTheLaneExceptionAndOtherToolsAreNotTouched() throws Exception {
        Path src = Files.createDirectories(lane.resolve("src"));

        assertEquals(java.util.Optional.empty(), SynesisStateReadGuard.denial("Read", Map.of("file_path", "../README.md"), src.toString()));
        assertEquals(java.util.Optional.empty(), SynesisStateReadGuard.denial("Bash", Map.of("command", "cat " + control.resolve(".synesis/x")), lane.toString()));
        assertEquals(java.util.Optional.empty(), SynesisStateReadGuard.denial("Edit", Map.of("file_path", otherLane.resolve("README.md").toString()), lane.toString()),
                "writes are the other guards' business");
    }

    @Test
    void theHookRefusesTheCallWithTheProvidersDenyAnswer() {
        String event = ProviderJson.write(Map.of("hook_event_name", "PreToolUse", "tool_name", "Read", "cwd", lane.toString(),
                "tool_input", Map.of("file_path", control.resolve(".synesis/local/profile/operator/operator.key").toString())));

        HookApplicationService.HookExecutionResult result = new HookApplicationService().claudeCode(temp.resolve("profile"), event, null, java.time.Duration.ZERO);

        assertEquals("BLOCKED", result.outcome());
        assertTrue(result.responseJson().contains("\"permissionDecision\":\"deny\""), result.responseJson());
    }
}
