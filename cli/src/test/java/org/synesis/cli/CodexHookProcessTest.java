package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.workspace.provider.ProviderJson;

/** Verifies Codex hook behavior through the generated launcher process. */
@Timeout(60)
final class CodexHookProcessTest {
    @Test
    void generatedLauncherEmitsDenyForBlockedPatchAndNothingForAllowedPatch() throws Exception {
        Path project = Files.createTempDirectory("synesis-codex-process-");
        try {
            assertEquals(0, run(project, "init", "--project", project.toString()).exit());
            assertEquals(0, run(project, "project", "create", "--project", project.toString(), "--peer",
                    "sl1-" + "0".repeat(64)).exit());
            CommandResult constraint = run(project, "constraint", "create", "--project", project.toString(),
                    "--title", "Protect source", "--rationale", "Frozen source scope.", "--scope",
                    "src/protected/**", "--effect", "block");
            assertEquals(0, constraint.exit(), constraint.output());

            CommandResult blocked = hook(project, event(project, "*** Begin Patch\n*** Update File: src/protected/file.txt\n*** End Patch"));
            assertEquals(0, blocked.exit(), blocked.output());
            assertTrue(blocked.output().contains("\"permissionDecision\":\"deny\""), blocked.output());
            assertTrue(blocked.output().contains("Protect source"), blocked.output());

            CommandResult allowed = hook(project, event(project, "*** Begin Patch\n*** Add File: docs/readme.txt\n*** End Patch"));
            assertEquals(0, allowed.exit(), allowed.output());
            assertEquals("", allowed.output());
        } finally {
            cleanup(project);
        }
    }

    private static CommandResult hook(Path project, String event) throws Exception {
        Process process = DistributionLauncherTest.start(DistributionLauncherTest.launcher(), project, "hook", "codex");
        process.getOutputStream().write(event.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return new CommandResult(process.exitValue(), DistributionLauncherTest.output(process));
    }

    private static CommandResult run(Path project, String... arguments) throws Exception {
        Process process = DistributionLauncherTest.start(DistributionLauncherTest.launcher(), project, arguments);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return new CommandResult(process.exitValue(), DistributionLauncherTest.output(process));
    }

    private static String event(Path root, String command) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", command);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("hook_event_name", "PreToolUse");
        event.put("cwd", root.toString());
        event.put("tool_name", "apply_patch");
        event.put("tool_input", input);
        return ProviderJson.write(event);
    }

    private static void cleanup(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private record CommandResult(int exit, String output) { }
}
