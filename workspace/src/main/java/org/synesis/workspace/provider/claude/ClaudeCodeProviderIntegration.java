package org.synesis.workspace.provider.claude;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.AgentLogEntry;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.StreamEvents;
import org.synesis.workspace.provider.ProviderMcpEvidenceTier;
import org.synesis.workspace.provider.ProviderSupportLevel;

/**
 * Claude Code provider configuration and synthetic hook contract.
 */
public final class ClaudeCodeProviderIntegration implements ProviderIntegration {

    private static final java.util.regex.Pattern LOGGED_IN = java.util.regex.Pattern.compile("\"loggedIn\"\\s*:\\s*(true|false)");
    private static final String STOP_HOOK_ID = "synesis-claude-code-stop";
    // A long, as ProviderJson reads a written number back, so a written entry compares equal to this one.
    private static final long STOP_HOOK_TIMEOUT_SECONDS = 660L;

    /**
     * Creates the Claude Code integration.
     */
    public ClaudeCodeProviderIntegration() {
    }

    private static boolean valid(String json) {
        try {
            return ProviderJson.parse(json) instanceof java.util.Map<?, ?>;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public String id() {
        return "claude";
    }

    /**
     * {@code claude auth status} answers with JSON that holds {@code "loggedIn": true|false} (checked
     * against Claude Code 2.1, 2026-10-20); only that field is read, never the account it names.
     */
    @Override
    public java.util.Optional<List<String>> signInStatusArguments() {
        return java.util.Optional.of(List.of("auth", "status", "--json"));
    }

    @Override
    public SignIn signInState(int exitCode, String output) {
        java.util.regex.Matcher answer = LOGGED_IN.matcher(output == null ? "" : output);
        if (!answer.find()) {
            return SignIn.UNKNOWN;
        }
        return Boolean.parseBoolean(answer.group(1)) ? SignIn.SIGNED_IN : SignIn.SIGNED_OUT;
    }

    @Override
    public ProviderSupportLevel supportLevel() {
        return ProviderSupportLevel.EXPERIMENTAL;
    }

    @Override
    public ProviderMcpEvidenceTier mcpEvidenceTier() {
        return ProviderMcpEvidenceTier.MCP_CONFIRMED_WORKING;
    }

    @Override
    public Path configurationPath(Path projectRoot) {
        return projectRoot.resolve(".claude/settings.json");
    }

    /**
     * Resolves the Claude Code project-scoped MCP configuration.
     *
     * @param projectRoot project root
     * @return Claude Code project MCP configuration
     */
    @Override
    public Path mcpConfigurationPath(Path projectRoot) {
        return projectRoot.resolve(".mcp.json");
    }

    /**
     * Builds a project-scoped MCP entry. The trusted environment root preserves Claude's local
     * project context without passing a project argument to the shared MCP CLI.
     *
     * @param launcher    generated Synesis MCP launcher
     * @param projectRoot initialized project root
     * @return JSON-compatible MCP server entry
     */
    @Override
    public Map<String, Object> managedMcpServer(Path launcher, Path projectRoot) {
        Map<String, Object> server = new LinkedHashMap<>(ProviderIntegration.super.managedMcpServer(launcher, projectRoot));
        server.put("args", List.of("mcp", "--provider", id()));
        server.put("env", Map.of("SYNESIS_MCP_PROJECT", projectRoot.toAbsolutePath().normalize().toString()));
        return server;
    }

    /**
     * Runs Claude Code in print mode against the project's own MCP configuration.
     *
     * <p>Print mode does not load a project-scoped {@code .mcp.json} without an interactive
     * approval, so the file is named explicitly and nothing else is loaded. Only the Synesis tools
     * and Claude Code's read-only tools are allowed: every change and command goes through
     * Synesis, which is the rule the project instructions state.</p>
     */
    @Override
    public java.util.Optional<HeadlessSession> headlessSession() {
        return java.util.Optional.of(new HeadlessSession() {

            @Override
            public List<String> startCommand(Path projectRoot, HeadlessOptions options) {
                List<String> command = new java.util.ArrayList<>(List.of("claude", "-p", "--output-format", "stream-json", "--verbose",
                        "--mcp-config", mcpConfigurationPath(projectRoot).toAbsolutePath().normalize().toString(), "--strict-mcp-config"));
                if (options.model() != null) {
                    command.addAll(List.of("--model", options.model()));
                }
                if (options.effort() != null) {
                    command.addAll(List.of("--effort", options.effort()));
                }
                command.addAll(List.of("--allowedTools", "mcp__synesis", "Read", "Glob", "Grep"));
                return List.copyOf(command);
            }

            @Override
            public List<String> resumeCommand(Path projectRoot, String providerSessionId, HeadlessOptions options) {
                List<String> command = new java.util.ArrayList<>(startCommand(projectRoot, options));
                // Inserted ahead of the variadic tool list, which would otherwise swallow it.
                command.addAll(2, List.of("--resume", providerSessionId));
                return List.copyOf(command);
            }

            @Override
            public java.util.Optional<String> sessionId(String outputLine) {
                return announcedIdentifier(outputLine, "system", "session_id");
            }

            @Override
            public Map<String, String> environment(HeadlessOptions options) {
                // Claude Code starts a stdio MCP server with its own environment.
                return options.connectionInstanceId() == null ? Map.of() : Map.of(CONNECTION_INSTANCE_VARIABLE, options.connectionInstanceId());
            }

            @Override
            public List<AgentLogEntry> logEntries(String outputLine) {
                return claudeEntries(StreamEvents.parse(outputLine));
            }
        });
    }

    /**
     * Reads one {@code stream-json} event: the assistant's text and tool calls, the tool results the user turn carries
     * back, and a failed run. The closing {@code result} event repeats the last message, so only its failure is read.
     */
    private static List<AgentLogEntry> claudeEntries(Map<?, ?> event) {
        if (event == null) {
            return List.of();
        }
        String type = StreamEvents.text(event, "type");
        if ("result".equals(type)) {
            return Boolean.TRUE.equals(event.get("is_error")) ? List.of(new AgentLogEntry(AgentLogEntry.Kind.FAILED, "", StreamEvents.text(event, "result"))) : List.of();
        }
        if (!(event.get("message") instanceof Map<?, ?> message) || !(message.get("content") instanceof List<?> blocks)) {
            return List.of();
        }
        List<AgentLogEntry> entries = new java.util.ArrayList<>();
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> part)) {
                continue;
            }
            switch (StreamEvents.text(part, "type")) {
                case "text" -> {
                    if (type.equals("assistant")) {
                        entries.add(new AgentLogEntry(AgentLogEntry.Kind.SAID, "", StreamEvents.text(part, "text")));
                    }
                }
                case "tool_use" -> entries.add(new AgentLogEntry(AgentLogEntry.Kind.CALLED, StreamEvents.text(part, "name"), ProviderJson.write(part.get("input"))));
                case "tool_result" -> entries.add(new AgentLogEntry(Boolean.TRUE.equals(part.get("is_error")) ? AgentLogEntry.Kind.FAILED : AgentLogEntry.Kind.RESULT, "", toolResultText(part.get("content"))));
                default -> {
                }
            }
        }
        return entries;
    }

    /** A tool result is text, or a list of blocks of which the text ones and the names of loaded tools are read. */
    private static String toolResultText(Object content) {
        if (content instanceof String text) {
            return text;
        }
        StringBuilder text = new StringBuilder();
        if (content instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> part) {
                    String piece = part.get("text") instanceof String words ? words : StreamEvents.text(part, "tool_name");
                    text.append(text.isEmpty() || piece.isEmpty() ? "" : " ").append(piece);
                }
            }
        }
        return text.toString();
    }

    /** Reads an identifier from a JSON event line of the given type; any other line yields nothing. */
    private static java.util.Optional<String> announcedIdentifier(String outputLine, String eventType, String field) {
        if (outputLine == null || !outputLine.startsWith("{") || !outputLine.contains(field)) {
            return java.util.Optional.empty();
        }
        try {
            if (ProviderJson.parse(outputLine) instanceof Map<?, ?> event && eventType.equals(event.get("type")) && event.get(field) instanceof String identifier && !identifier.isBlank()) {
                return java.util.Optional.of(identifier);
            }
        } catch (RuntimeException notAnEvent) {
            // Providers interleave diagnostics with events; a line that is not JSON is not an event.
        }
        return java.util.Optional.empty();
    }

    /**
     * The rule file earlier versions wrote so that Claude Code, which does not read {@code AGENTS.md},
     * would import the managed {@code AGENTS.md} block. Neither exists any more: Claude Code learns the
     * rules from the {@code synesis-provider-manual} skill and the MCP server's instructions.
     *
     * @param projectRoot control project root
     * @return the retired Claude Code rule file
     */
    @Override
    public java.util.Optional<Path> retiredProjectInstructions(Path projectRoot) {
        return java.util.Optional.of(projectRoot.resolve(".claude/rules/synesis.md").toAbsolutePath().normalize());
    }

    @Override
    public String managedHookId() {
        return "synesis-claude-code";
    }

    /**
     * The shell tools are matched so a command that only sleeps is refused there too (ADR-0099), and the file-reading
     * tools so that Synesis's state and keys stay unreadable to them (ADR-0104).
     */
    @Override
    public String matcher() {
        return "Edit|Write|Bash|PowerShell|Read|Glob|Grep";
    }

    /**
     * Holds an agent told WAIT on its Stop event until something arrives for its lane (ADR-0099). Claude Code lets the
     * turn end when a hook outlives its timeout, so the timeout is the hold, ten minutes, plus a minute to answer.
     */
    @Override
    public Map<String, Object> managedStopHook(Path launcher, Path profile) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "command");
        command.put("command", hookCommand(launcher, profile));
        command.put("timeout", STOP_HOOK_TIMEOUT_SECONDS);
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("id", STOP_HOOK_ID);
        hook.put("hooks", List.of(command));
        return hook;
    }

    @Override
    public boolean isManagedStopHook(Object value) {
        return value instanceof Map<?, ?> hook && STOP_HOOK_ID.equals(hook.get("id"));
    }

    @Override
    public SyntheticCheck syntheticCheck(Path profile, Path projectRoot) {
        ClaudeCodeHookAdapter adapter = new ClaudeCodeHookAdapter(profile);
        String protectedEvent = "{\"tool_name\":\"Edit\",\"cwd\":\"" + projectRoot + "\",\"tool_input\":{\"file_path\":\"src/protected.txt\"}}";
        String allowedEvent = protectedEvent.replace("src/protected.txt", "src/free.txt");
        var blocked = adapter.processJson(protectedEvent);
        var allowed = adapter.processJson(allowedEvent);
        return new SyntheticCheck(blocked.outcome() == ClaudeCodeHookAdapter.Outcome.BLOCKED,
                allowed.outcome() == ClaudeCodeHookAdapter.Outcome.ALLOWED,
                valid(blocked.responseJson()) && valid(allowed.responseJson()),
                blocked.responseJson(),
                allowed.responseJson());
    }
}
