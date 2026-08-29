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
     * Builds a project-scoped MCP entry. The explicit project is a local
     * configuration fallback for Claude sessions that do not send MCP roots;
     * it does not alter global provider configuration or replace root-based
     * resolution when roots are supplied.
     *
     * @param launcher    generated Synesis MCP launcher
     * @param projectRoot initialized project root
     * @return JSON-compatible MCP server entry
     */
    @Override
    public Map<String, Object> managedMcpServer(Path launcher, Path projectRoot) {
        Map<String, Object> server = new LinkedHashMap<>(ProviderIntegration.super.managedMcpServer(launcher,
                projectRoot));
        server.put("args", List.of("mcp", "--provider", id(), "--project",
                projectRoot.toAbsolutePath()
                        .normalize()
                        .toString()));
        return server;
    }

    /**
     * Builds Claude Code's documented print-mode argv for supervised work.
     *
     * @param worktree isolated lane worktree
     * @param prompt   initial task prompt
     * @return direct Claude argv
     */
    @Override
    public java.util.Optional<java.util.List<String>> autonomousCommand(Path worktree, String prompt) {
        if (worktree == null || prompt == null || prompt.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(java.util.List.of("claude",
                "-p",
                prompt,
                "--add-dir",
                worktree.toAbsolutePath()
                        .normalize()
                        .toString(),
                "--output-format",
                "stream-json"));
    }

    @Override
    public String hookGroup() {
        return "hooks";
    }

    @Override
    public String managedHookId() {
        return "synesis-claude-code";
    }

    @Override
    public String matcher() {
        return "Edit|Write";
    }

    /**
     * Returns the Claude Code hook adapter command using the canonical
     * {@code claude} provider identifier.
     *
     * @param launcher generated Synesis launcher
     * @param profile  local profile
     * @return Claude Code hook command
     */
    @Override
    public String hookCommand(Path launcher, Path profile) {
        return "\"" + launcher.toAbsolutePath()
                .normalize()
                .toString()
                .replace("\"", "\\\"") + "\" hook claude --profile \""
                + profile.toAbsolutePath()
                .normalize()
                .toString()
                .replace("\"", "\\\"") + "\"";
    }

    @Override
    public SyntheticCheck syntheticCheck(Path profile, Path projectRoot) {
        ClaudeCodeHookAdapter adapter = new ClaudeCodeHookAdapter(profile);
        String protectedEvent = "{\"tool_name\":\"Edit\",\"cwd\":\"" + projectRoot
                + "\",\"tool_input\":{\"file_path\":\"src/protected.txt\"}}";
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
