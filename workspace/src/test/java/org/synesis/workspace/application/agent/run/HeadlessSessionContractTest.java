package org.synesis.workspace.application.agent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.synesis.workspace.provider.ProviderIntegration.HeadlessOptions;
import org.synesis.workspace.provider.ProviderIntegration.HeadlessSession;
import org.synesis.workspace.provider.ProviderRegistry;

/**
 * Verifies how each provider is started, identified, and resumed without a person, and how its
 * executable is found.
 */
final class HeadlessSessionContractTest {

    private static final Path PROJECT = Path.of("demo-project").toAbsolutePath().normalize();

    @Test
    void claudeRunsInPrintModeAgainstTheProjectMcpFileWithOnlySynesisAndReadOnlyTools() {
        HeadlessSession claude = ProviderRegistry.find("claude").headlessSession().orElseThrow();

        List<String> start = claude.startCommand(PROJECT, new HeadlessOptions("sonnet", "low"));
        assertEquals(List.of("claude", "-p", "--output-format", "stream-json", "--verbose", "--mcp-config", PROJECT.resolve(".mcp.json").toString(), "--strict-mcp-config",
                "--model", "sonnet", "--effort", "low", "--allowedTools", "mcp__synesis", "Read", "Glob", "Grep"), start);
        assertFalse(start.contains("--dangerously-skip-permissions"));

        List<String> resume = claude.resumeCommand(PROJECT, "29224900-073b-4f18-a846-a12588353dfe", new HeadlessOptions(null, null));
        assertEquals(List.of("claude", "-p", "--resume", "29224900-073b-4f18-a846-a12588353dfe"), resume.subList(0, 4));
        assertTrue(resume.indexOf("--resume") < resume.indexOf("--allowedTools"), "the tool list is variadic and must come last");
        assertFalse(resume.contains("--model"));

        assertEquals(Optional.of("29224900-073b-4f18-a846-a12588353dfe"),
                claude.sessionId("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"29224900-073b-4f18-a846-a12588353dfe\",\"tools\":[]}"));
        assertEquals(Optional.empty(), claude.sessionId("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"session_id is abc\"}]}}"));
        assertEquals(Optional.empty(), claude.sessionId("Warning: session_id {not json"));
        assertEquals(Optional.empty(), claude.sessionId(null));
    }

    @Test
    void codexRunsExecWithThePromptOnStandardInputAndKeepsItsOwnSandboxPolicy() {
        HeadlessSession codex = ProviderRegistry.find("codex").headlessSession().orElseThrow();

        assertEquals(List.of("codex", "exec", "--json", "--cd", PROJECT.toString(), "--model", "gpt-6-luna", "--config", "model_reasoning_effort=medium", "-"),
                codex.startCommand(PROJECT, new HeadlessOptions("gpt-6-luna", "medium")));
        List<String> resume = codex.resumeCommand(PROJECT, "01a12165-781c-70e0-b010-e8d2c048e4e5", new HeadlessOptions(null, null));
        assertEquals(List.of("codex", "exec", "resume", "01a12165-781c-70e0-b010-e8d2c048e4e5", "--json", "-"), resume);
        assertFalse(resume.stream().anyMatch(argument -> argument.contains("bypass") || argument.contains("sandbox")), "the provider's own policy is never overridden");

        assertEquals(Optional.of("01a12165-781c-70e0-b010-e8d2c048e4e5"), codex.sessionId("{\"type\":\"thread.started\",\"thread_id\":\"01a12165-781c-70e0-b010-e8d2c048e4e5\"}"));
        assertEquals(Optional.empty(), codex.sessionId("{\"type\":\"item.completed\",\"item\":{\"text\":\"thread.started thread_id\"}}"));
        assertEquals(Optional.empty(), codex.sessionId("2026-10-09T15:57:35Z ERROR rmcp::transport::worker: thread.started"));
    }

    @Test
    void modelAndEffortMustBePlainTokensBecauseTheyBecomeArguments() {
        assertThrows(IllegalArgumentException.class, () -> new HeadlessOptions("sonnet --dangerously-skip-permissions", null));
        assertThrows(IllegalArgumentException.class, () -> new HeadlessOptions(null, "low&calc"));
        assertThrows(IllegalArgumentException.class, () -> new HeadlessOptions("", null));
        assertEquals("claude-opus-5-5[1m]", new HeadlessOptions("claude-opus-5-5[1m]", "high").model());
    }

    @Test
    void windowsResolvesABinaryDirectlyAndAScriptShimThroughTheCommandInterpreter(@TempDir Path temp) throws Exception {
        Path binaries = Files.createDirectories(temp.resolve("bin"));
        Path shims = Files.createDirectories(temp.resolve("shims"));
        Path claude = Files.createFile(binaries.resolve("claude.exe"));
        Path codexShim = Files.createFile(shims.resolve("codex.cmd"));
        ProviderAgentProcesses processes = new ProviderAgentProcesses(temp.resolve("logs"), shims + java.io.File.pathSeparator + binaries, true);

        assertEquals(List.of(claude.toAbsolutePath().toString(), "-p"), processes.executable(List.of("claude", "-p")));
        assertEquals(List.of("cmd.exe", "/d", "/c", codexShim.toAbsolutePath().toString(), "exec"), processes.executable(List.of("codex", "exec")));
        assertThrows(IOException.class, () -> processes.executable(List.of("gemini")));

        ProviderAgentProcesses elsewhere = new ProviderAgentProcesses(temp.resolve("logs"), "", false);
        assertEquals(List.of("codex", "exec"), elsewhere.executable(List.of("codex", "exec")), "other platforms start a script like any executable");
    }
}
