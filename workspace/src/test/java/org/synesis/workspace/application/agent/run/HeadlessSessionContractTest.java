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
import org.synesis.workspace.application.provider.ProviderRegistry;

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
                "--model", "sonnet", "--effort", "low",
                "--setting-sources", "project", "--permission-mode", "dontAsk", "--allowedTools", "mcp__synesis", "Read", "Glob", "Grep",
                "--disallowedTools", "Bash", "PowerShell", "Edit", "Write", "NotebookEdit", "WebFetch", "WebSearch", "Task", "Agent"), start);
        assertFalse(start.contains("--dangerously-skip-permissions"));

        List<String> resume = claude.resumeCommand(PROJECT, "29224900-073b-4f18-a846-a12588353dfe", new HeadlessOptions(null, null));
        assertEquals(List.of("claude", "-p", "--resume", "29224900-073b-4f18-a846-a12588353dfe"), resume.subList(0, 4));
        assertTrue(resume.indexOf("--resume") < resume.indexOf("--allowedTools"), "the tool lists are variadic and must come last");
        assertTrue(resume.indexOf("--allowedTools") < resume.indexOf("--disallowedTools"));
        assertFalse(resume.contains("--model"));

        assertEquals(Optional.of("29224900-073b-4f18-a846-a12588353dfe"),
                claude.sessionId("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"29224900-073b-4f18-a846-a12588353dfe\",\"tools\":[]}"));
        assertEquals(Optional.empty(), claude.sessionId("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"session_id is abc\"}]}}"));
        assertEquals(Optional.empty(), claude.sessionId("Warning: session_id {not json"));
        assertEquals(Optional.empty(), claude.sessionId(null));
    }

    @Test
    void codexRunsExecWithThePromptOnStandardInputInTheReadOnlySandboxWithTheSynesisToolsApproved() {
        HeadlessSession codex = ProviderRegistry.find("codex").headlessSession().orElseThrow();

        assertEquals(List.of("codex", "exec", "--json", "--cd", PROJECT.toString(), "--config", "sandbox_mode=read-only", "--config", "mcp_servers.synesis.default_tools_approval_mode=approve", "--model", "gpt-6-luna", "--config", "model_reasoning_effort=medium", "-"),
                codex.startCommand(PROJECT, new HeadlessOptions("gpt-6-luna", "medium")));
        List<String> resume = codex.resumeCommand(PROJECT, "01a12165-781c-70e0-b010-e8d2c048e4e5", new HeadlessOptions(null, null));
        // A resumed session is a new process and must be held to the same two settings (ADR-0091).
        assertEquals(List.of("codex", "exec", "resume", "01a12165-781c-70e0-b010-e8d2c048e4e5", "--json", "--config", "sandbox_mode=read-only", "--config", "mcp_servers.synesis.default_tools_approval_mode=approve", "-"), resume);
        assertFalse(resume.stream().anyMatch(argument -> argument.contains("bypass") || argument.contains("danger")), "Synesis tightens the provider's policy and never loosens it");

        assertEquals(Optional.of("01a12165-781c-70e0-b010-e8d2c048e4e5"), codex.sessionId("{\"type\":\"thread.started\",\"thread_id\":\"01a12165-781c-70e0-b010-e8d2c048e4e5\"}"));
        assertEquals(Optional.empty(), codex.sessionId("{\"type\":\"item.completed\",\"item\":{\"text\":\"thread.started thread_id\"}}"));
        assertEquals(Optional.empty(), codex.sessionId("2026-10-09T15:57:35Z ERROR rmcp::transport::worker: thread.started"));
    }

    @Test
    void eachProviderHandsTheConnectionIdentityToItsMcpServerInTheWayItSupports() {
        HeadlessOptions named = new HeadlessOptions("gpt-6-luna", null).withConnection("conn-instance-7f3a");
        HeadlessSession claude = ProviderRegistry.find("claude").headlessSession().orElseThrow();
        HeadlessSession codex = ProviderRegistry.find("codex").headlessSession().orElseThrow();

        assertEquals(java.util.Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-instance-7f3a"), claude.environment(named));
        assertFalse(claude.startCommand(PROJECT, named).stream().anyMatch(argument -> argument.contains("conn-instance-7f3a")), "Claude Code needs no argument for it");
        assertEquals(java.util.Map.of(), claude.environment(new HeadlessOptions(null, null)), "without an identity the server chooses its own");

        assertEquals(java.util.Map.of("SYNESIS_MCP_CONNECTION_INSTANCE_ID", "conn-instance-7f3a"), codex.environment(named),
                "Codex gives its hooks its environment, though not its MCP servers");
        assertEquals(List.of("codex", "exec", "--json", "--cd", PROJECT.toString(), "--config", "sandbox_mode=read-only", "--config", "mcp_servers.synesis.default_tools_approval_mode=approve", "--model", "gpt-6-luna", "--config",
                "mcp_servers.synesis.env.SYNESIS_MCP_CONNECTION_INSTANCE_ID=conn-instance-7f3a", "-"), codex.startCommand(PROJECT, named));
        List<String> resume = codex.resumeCommand(PROJECT, "01a12165-781c-70e0-b010-e8d2c048e4e5", named);
        assertTrue(resume.indexOf("mcp_servers.synesis.env.SYNESIS_MCP_CONNECTION_INSTANCE_ID=conn-instance-7f3a") < resume.indexOf("-"), "the prompt marker stays last: " + resume);

        assertThrows(IllegalArgumentException.class, () -> new HeadlessOptions(null, null).withConnection("conn \" --config x=y"));
    }

    /**
     * Codex 0.163 runs a hook headless only when trusted (ADR-0099, 2026-10-18). A controller agent is given Synesis's
     * own Stop hook on the command line with its trust pinned to that hook's hash, for that run only; nothing else is
     * trusted and the global bypass is never passed.
     */
    @Test
    void aControllerCodexAgentIsGivenSynesissOwnStopHookTrustedForThatRunOnly() {
        HeadlessSession codex = ProviderRegistry.find("codex").headlessSession().orElseThrow();
        Path launcher = Path.of("plain-launcher", "synesis").toAbsolutePath().normalize();
        HeadlessOptions agent = new HeadlessOptions(null, null).withHookLauncher(launcher).withConnection("conn-instance-7f3a");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

        for (List<String> command : List.of(codex.startCommand(PROJECT, agent), codex.resumeCommand(PROJECT, "01a12165-781c-70e0-b010-e8d2c048e4e5", agent))) {
            String hook = command.get(command.indexOf("hooks.Stop=[{hooks=[{type='command',command='" + launcher + " hook codex',timeout=660}]}]"));
            assertEquals("--config", command.get(command.indexOf(hook) - 1));
            String trust = command.stream().filter(argument -> argument.startsWith("hooks.state=")).findFirst().orElseThrow();
            assertEquals("hooks.state={'" + (windows ? "C:\\<session-flags>\\config.toml" : "/<session-flags>/config.toml") + ":stop:0:0'={trusted_hash='sha256:", trust.substring(0, trust.indexOf("sha256:") + 7));
            assertEquals("-", command.getLast(), "the prompt marker stays last");
            assertFalse(command.stream().anyMatch(argument -> argument.contains("bypass") || argument.contains("trust_level")), "no other hook and no project is trusted: " + command);
        }
        assertFalse(codex.startCommand(PROJECT, new HeadlessOptions(null, null).withHookLauncher(launcher)).stream().anyMatch(argument -> argument.startsWith("hooks.")),
                "an agent the controller did not start is given no hook");
        assertFalse(codex.startCommand(PROJECT, new HeadlessOptions(null, null).withHookLauncher(Path.of("Program Files", "synesis").toAbsolutePath()).withConnection("conn-instance-7f3a"))
                .stream().anyMatch(argument -> argument.startsWith("hooks.")), "a launcher no shell can run unquoted gets no hook; the controller still wakes the agent");
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
        assertEquals(List.of("cmd.exe", "/d", "/s", "/c", "\"" + codexShim.toAbsolutePath() + " exec\""), processes.executable(List.of("codex", "exec")));
        assertThrows(IOException.class, () -> processes.executable(List.of("gemini")));

        ProviderAgentProcesses elsewhere = new ProviderAgentProcesses(temp.resolve("logs"), "", false);
        assertEquals(List.of("codex", "exec"), elsewhere.executable(List.of("codex", "exec")), "other platforms start a script like any executable");

        String trust = "hooks.state={'C:\\<session-flags>\\config.toml:stop:0:0'={trusted_hash='sha256:00'}}";
        assertEquals(List.of("cmd.exe", "/d", "/s", "/c", "\"" + codexShim.toAbsolutePath() + " --config \"" + trust + "\"\""), processes.executable(List.of("codex", "--config", trust)),
                "an argument the interpreter would read as a redirection is quoted");
        assertThrows(IOException.class, () -> processes.executable(List.of("codex", "say \"hi\"")), "a quote inside an argument would end the line early");
    }

    /**
     * On Windows a script shim in a folder with a space receives arguments holding spaces and interpreter operators
     * exactly as Synesis built them.
     */
    @Test
    void aScriptShimOnWindowsReceivesTheHookArgumentsIntact(@TempDir Path temp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
        Path shims = Files.createDirectories(temp.resolve("my shims"));
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Files.writeString(shims.resolve("codex.cmd"), "@\"" + javaExecutable + "\" -cp \"%ECHO_CLASSPATH%\" " + ArgumentEcho.class.getName() + " %*\r\n");
        List<String> arguments = List.of("exec", "--config", "hooks.Stop=[{hooks=[{type='command',command='C:\\synesis\\bin\\synesis.exe hook codex',timeout=660}]}]",
                "--config", "hooks.state={'C:\\<session-flags>\\config.toml:stop:0:0'={trusted_hash='sha256:49c0'}}", "--cd", "C:\\a project", "-");
        List<String> command = new java.util.ArrayList<>(List.of("codex"));
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(new ProviderAgentProcesses(temp.resolve("logs"), shims.toString(), true).executable(command)).redirectErrorStream(true);
        builder.environment().put("ECHO_CLASSPATH", System.getProperty("java.class.path"));
        Process echo = builder.start();
        List<String> received = new String(echo.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("ARG=")).map(line -> line.substring(4)).toList();
        assertTrue(echo.waitFor(60, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(arguments, received);
    }

    /** Prints each argument it was started with on a line of its own. */
    public static final class ArgumentEcho {

        private ArgumentEcho() {
        }

        /**
         * Prints the arguments.
         *
         * @param arguments the arguments
         */
        public static void main(String[] arguments) {
            for (String argument : arguments) {
                System.out.println("ARG=" + argument);
            }
        }
    }
}
