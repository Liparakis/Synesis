package org.synesis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.synesis.workspace.provider.ProviderJson;

/** Verifies the generated Antigravity wrapper from a non-project directory. */
@Timeout(60)
final class AntigravityHookProcessTest {
    @Test
    void wrapperSetsProjectRootBeforeInvokingSynesis() throws Exception {
        Assumptions.assumeTrue(isWindows());
        Path root = Files.createTempDirectory("synesis-antigravity-process-");
        try {
            Path project = Files.createDirectories(root.resolve("project"));
            Path outside = Files.createDirectories(root.resolve("outside"));
            Path pathBin = Files.createDirectories(root.resolve("path-bin"));
            Path launcher = DistributionLauncherTest.launcher().toAbsolutePath();
            Files.writeString(pathBin.resolve("synesis.cmd"),
                    "@echo off\r\ncall \"" + launcher + "\" %*\r\nexit /b %ERRORLEVEL%\r\n");
            Map<String, String> environment = Map.of("PATH_PREFIX", pathBin.toString());

            assertEquals(0, run(launcher, project, environment, "init", "--project", project.toString()).exit());
            assertEquals(0, run(launcher, project, environment, "project", "create", "--project",
                    project.toString(), "--peer", "sl1-" + "0".repeat(64)).exit());
            Files.createDirectories(project.resolve("src"));
            Path protectedFile = project.resolve("src/protected.txt");
            Files.writeString(protectedFile, "unchanged");
            CommandResult constraint = run(launcher, project, environment, "constraint", "create",
                    "--project", project.toString(), "--title", "Protect file", "--rationale", "Frozen.",
                    "--scope", "src/protected.txt", "--effect", "block");
            assertEquals(0, constraint.exit(), constraint.output());

            CommandResult install = run(launcher, project, environment, "provider", "install", "antigravity",
                    "--project", project.toString());
            assertEquals(0, install.exit(), install.output());
            Path wrapper = project.resolve(".synesis/local/run-antigravity-hook.ps1");
            assertTrue(Files.isRegularFile(wrapper));
            Map<?, ?> config = (Map<?, ?>) ProviderJson.parse(Files.readString(project.resolve(".agents/hooks.json")));
            Map<?, ?> group = (Map<?, ?>) config.get("synesis-guardrail");
            Map<?, ?> managedHook = (Map<?, ?>) ((java.util.List<?>) group.get("PreToolUse")).getFirst();
            assertEquals("write_to_file|replace_file_content|multi_replace_file_content", managedHook.get("matcher"));
            String command = String.valueOf(((Map<?, ?>) ((java.util.List<?>) managedHook.get("hooks")).getFirst()).get("command"));
            assertTrue(command.contains("-File " + wrapper));
            assertFalse(command.contains("-File \"" + wrapper));

            String payload = "{\"workspacePaths\":[\"" + jsonPath(project)
                    + "\"],\"toolCall\":{\"name\":\"replace_file_content\",\"args\":{\"TargetFile\":\""
                    + jsonPath(protectedFile) + "\"}}}";
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", wrapper.toString()).directory(outside.toFile());
            builder.environment().put("PATH", pathBin + ";" + System.getenv("PATH"));
            Process hook = builder.start();
            hook.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            hook.getOutputStream().close();
            String stdout = new String(hook.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(hook.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(hook.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, hook.exitValue(), stderr);
            assertEquals(1, stdout.lines().count(), stdout);
            Map<?, ?> decision = (Map<?, ?>) ProviderJson.parse(stdout);
            assertEquals("deny", decision.get("decision"));
            assertFalse(stdout.contains("Project is not initialized"), stdout);
            assertFalse(stdout.contains("\"decision\": \""), stdout);
            assertEquals("unchanged", Files.readString(protectedFile));
        } finally {
            cleanup(root);
        }
    }

    private static CommandResult run(Path launcher, Path directory, Map<String, String> settings, String... arguments)
            throws Exception {
        ArrayList<String> command = new ArrayList<>();
        command.add("cmd.exe");
        command.add("/d");
        command.add("/c");
        command.add(launcher.toString());
        for (String argument : arguments) command.add(argument);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        String path = builder.environment().get("PATH");
        builder.environment().put("PATH", settings.get("PATH_PREFIX") + ";" + path);
        Process process = builder.start();
        return new CommandResult(process.waitFor(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static void cleanup(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        }
    }

    private record CommandResult(int exit, String output) { }
}
