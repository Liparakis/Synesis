package org.synesis.workspace.provider.claude;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderMcpEvidenceTier;
import org.synesis.workspace.provider.ProviderSupportLevel;

/**
 * Claude Code provider configuration and synthetic hook contract.
 */
public final class ClaudeCodeProviderIntegration implements ProviderIntegration {

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
        });
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
     * Points Claude Code at the Synesis rules through a project rule file.
     *
     * <p>Claude Code loads every Markdown file under {@code .claude/rules} as project
     * instructions and resolves {@code @} imports relative to the importing file; it does not read
     * {@code AGENTS.md} itself. The rule file therefore imports the managed {@code AGENTS.md}
     * block, and lives beside the other provider-local files that the control checkout and lane
     * snapshots already exclude.</p>
     *
     * @param projectRoot control project root
     * @return the Claude Code rule file
     */
    @Override
    public java.util.Optional<ProjectInstructions> projectInstructions(Path projectRoot) {
        String content = ProjectInstructions.OWNERSHIP_MARKER + "\n# Synesis\n\nThis project is coordinated through Synesis. The rules imported below apply to this session;\nfollow them before reading, changing, or running anything in the project.\n\n@../../AGENTS.md\n";
        return java.util.Optional.of(new ProjectInstructions(projectRoot.resolve(".claude/rules/synesis.md").toAbsolutePath().normalize(), content));
    }

    @Override
    public String managedHookId() {
        return "synesis-claude-code";
    }

    /** The shell tools are matched so a command that only sleeps is refused there too (ADR-0099). */
    @Override
    public String matcher() {
        return "Edit|Write|Bash|PowerShell";
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
