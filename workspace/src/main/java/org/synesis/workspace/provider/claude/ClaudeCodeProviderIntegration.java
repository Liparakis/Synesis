package org.synesis.workspace.provider.claude;

import java.nio.file.Path;

import org.synesis.workspace.provider.claude.ClaudeCodeHookAdapter;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.infrastructure.json.ProviderJson;
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
     * Returns the Claude Code hook adapter command. The provider lifecycle ID
     * is {@code claude}, while the CLI hook command retains the explicit
     * compatibility name {@code claude-code}.
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
                .replace("\"", "\\\"") + "\" hook claude-code --profile \""
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
