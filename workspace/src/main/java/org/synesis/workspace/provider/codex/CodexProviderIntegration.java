package org.synesis.workspace.provider.codex;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.synesis.workspace.provider.codex.CodexHookAdapter;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderSupportLevel;

/**
 * Codex project-local PreToolUse provider integration.
 */
public final class CodexProviderIntegration implements ProviderIntegration {

    /**
     * Creates the Codex integration.
     */
    public CodexProviderIntegration() {
    }

    private static String event(Path root, String path) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("command", "*** Begin Patch\n*** Update File: " + path + "\n*** End Patch");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("hook_event_name", "PreToolUse");
        event.put("cwd",
                root.toAbsolutePath()
                        .normalize()
                        .toString());
        event.put("tool_name", "apply_patch");
        event.put("tool_input", input);
        return ProviderJson.write(event);
    }

    private static boolean valid(String json) {
        try {
            return ProviderJson.parse(json) instanceof Map<?, ?>;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String quote(Path path) {
        return "\"" + path.toAbsolutePath()
                .normalize()
                .toString()
                .replace("\"", "\\\"") + "\"";
    }

    private static String windowsHookCommand(Path launcher) {
        return "cmd.exe /d /s /c \"" + quote(launcher) + " hook codex\"";
    }

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public ProviderSupportLevel supportLevel() {
        return ProviderSupportLevel.EXPERIMENTAL;
    }

    @Override
    public Path configurationPath(Path projectRoot) {
        return projectRoot.resolve(".codex/hooks.json");
    }

    @Override
    public Path mcpConfigurationPath(Path projectRoot) {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return null;
        }
        return Path.of(userHome, ".codex", "config.toml");
    }

    /**
     * Builds the Codex TOML MCP entry.
     *
     * @param launcher stable launcher
     * @param projectRoot ignored project root
     * @return TOML-compatible entry values
     */
    @Override
    public Map<String, Object> managedMcpServer(Path launcher, Path projectRoot) {
        Map<String, Object> server = new LinkedHashMap<>(ProviderIntegration.super.managedMcpServer(launcher, projectRoot));
        server.remove("version");
        return server;
    }

    @Override
    public String hookGroup() {
        return "hooks";
    }

    @Override
    public String managedHookId() {
        return "synesis-codex";
    }

    @Override
    public String matcher() {
        return "^apply_patch$";
    }

    @Override
    public boolean requiresRealValidation() {
        return true;
    }

    @Override
    public String trustStatus() {
        return "REVIEW_REQUIRED";
    }

    @Override
    public String hookCommand(Path launcher, Path profile) {
        return quote(launcher) + " hook codex";
    }

    @Override
    public String windowsHookCommand(Path launcher, Path profile) {
        return windowsHookCommand(launcher);
    }

    @Override
    public Map<String, Object> managedHook(Path launcher, Path profile) {
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("matcher", matcher());
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "command");
        command.put("command", hookCommand(launcher, profile));
        command.put("commandWindows", windowsHookCommand(launcher));
        hook.put("hooks", List.of(command));
        return hook;
    }

    @Override
    public boolean isManagedHook(Object value) {
        if (!(value instanceof Map<?, ?> hook) || !matcher().equals(hook.get("matcher"))) {
            return false;
        }
        if (!(hook.get("hooks") instanceof List<?> handlers) || handlers.size() != 1
                || !(handlers.getFirst() instanceof Map<?, ?> handler)) {
            return false;
        }
        Object command = handler.get("command");
        return "command".equals(handler.get("type")) && command instanceof String text
                && text.endsWith(" hook codex");
    }

    @Override
    public SyntheticCheck syntheticCheck(Path profile, Path projectRoot) {
        CodexHookAdapter adapter = new CodexHookAdapter();
        String protectedEvent = event(projectRoot, "src/protected.txt");
        String allowedEvent = event(projectRoot, "src/free.txt");
        CodexHookAdapter.Result blocked = adapter.processJson(protectedEvent);
        CodexHookAdapter.Result allowed = adapter.processJson(allowedEvent);
        return new SyntheticCheck(blocked.outcome() == CodexHookAdapter.Outcome.BLOCKED,
                allowed.outcome() == CodexHookAdapter.Outcome.ALLOWED,
                valid(blocked.responseJson()) && allowed.responseJson()
                        .isEmpty(),
                blocked.responseJson(), allowed.responseJson());
    }
}
