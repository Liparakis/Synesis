package org.synesis.workspace.provider.codex;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderMcpEvidenceTier;
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
        event.put("cwd", root.toAbsolutePath().normalize().toString());
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
        return "\"" + path.toAbsolutePath().normalize().toString().replace("\"", "\\\"") + "\"";
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
    public ProviderMcpEvidenceTier mcpEvidenceTier() {
        return ProviderMcpEvidenceTier.MCP_CONFIRMED_WORKING;
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
     * @param launcher    stable launcher
     * @param projectRoot ignored project root
     * @return TOML-compatible entry values
     */
    @Override
    public Map<String, Object> managedMcpServer(Path launcher, Path projectRoot) {
        Map<String, Object> server = new LinkedHashMap<>(ProviderIntegration.super.managedMcpServer(launcher, projectRoot));
        server.remove("version");
        return server;
    }

    /**
     * Runs Codex with {@code codex exec}, reading the prompt from standard input, without
     * bypassing its approval or sandbox policy. Codex finds the Synesis server through its own
     * configuration and the project through the working directory.
     */
    @Override
    public java.util.Optional<HeadlessSession> headlessSession() {
        return java.util.Optional.of(new HeadlessSession() {

            @Override
            public List<String> startCommand(Path projectRoot, HeadlessOptions options) {
                List<String> command = new java.util.ArrayList<>(List.of("codex", "exec", "--json", "--cd", projectRoot.toAbsolutePath().normalize().toString()));
                command.addAll(modelArguments(options));
                command.add("-");
                return List.copyOf(command);
            }

            @Override
            public List<String> resumeCommand(Path projectRoot, String providerSessionId, HeadlessOptions options) {
                List<String> command = new java.util.ArrayList<>(List.of("codex", "exec", "resume", providerSessionId, "--json"));
                command.addAll(modelArguments(options));
                command.add("-");
                return List.copyOf(command);
            }

            @Override
            public java.util.Optional<String> sessionId(String outputLine) {
                if (outputLine == null || !outputLine.startsWith("{") || !outputLine.contains("thread.started")) {
                    return java.util.Optional.empty();
                }
                try {
                    if (ProviderJson.parse(outputLine) instanceof Map<?, ?> event && "thread.started".equals(event.get("type")) && event.get("thread_id") instanceof String thread && !thread.isBlank()) {
                        return java.util.Optional.of(thread);
                    }
                } catch (RuntimeException notAnEvent) {
                    // Codex interleaves diagnostics with events; a line that is not JSON is not an event.
                }
                return java.util.Optional.empty();
            }
        });
    }

    private static List<String> modelArguments(HeadlessOptions options) {
        List<String> arguments = new java.util.ArrayList<>();
        if (options.model() != null) {
            arguments.addAll(List.of("--model", options.model()));
        }
        if (options.effort() != null) {
            arguments.addAll(List.of("--config", "model_reasoning_effort=" + options.effort()));
        }
        if (options.connectionInstanceId() != null) {
            // Codex gives an MCP server only the environment its configuration names, so the
            // identity is added to the Synesis server's entry for this one process. The value is
            // a plain token, which Codex takes literally.
            arguments.addAll(List.of("--config", "mcp_servers.synesis.env." + CONNECTION_INSTANCE_VARIABLE + "=" + options.connectionInstanceId()));
        }
        return arguments;
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
        return managedHook(matcher(), null, launcher, profile);
    }

    @Override
    public Map<String, Object> managedSessionHook(Path launcher, Path profile) {
        return managedHook("startup|resume", "synesis-codex-session", launcher, profile);
    }

    private Map<String, Object> managedHook(String hookMatcher, String hookId, Path launcher, Path profile) {
        Map<String, Object> hook = new LinkedHashMap<>();
        if (hookId != null) {
            hook.put("id", hookId);
        }
        hook.put("matcher", hookMatcher);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "command");
        command.put("command", hookCommand(launcher, profile));
        command.put("commandWindows", windowsHookCommand(launcher));
        hook.put("hooks", List.of(command));
        return hook;
    }

    @Override
    public boolean isManagedSessionHook(Object value) {
        return value instanceof Map<?, ?> hook && "synesis-codex-session".equals(hook.get("id"));
    }

    @Override
    public boolean isManagedHook(Object value) {
        if (!(value instanceof Map<?, ?> hook) || !matcher().equals(hook.get("matcher"))) {
            return false;
        }
        if (!(hook.get("hooks") instanceof List<?> handlers) || handlers.size() != 1 || !(handlers.getFirst() instanceof Map<?, ?> handler)) {
            return false;
        }
        Object command = handler.get("command");
        return "command".equals(handler.get("type")) && command instanceof String text && text.endsWith(" hook codex");
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
                valid(blocked.responseJson()) && allowed.responseJson().isEmpty(),
                blocked.responseJson(),
                allowed.responseJson());
    }
}
