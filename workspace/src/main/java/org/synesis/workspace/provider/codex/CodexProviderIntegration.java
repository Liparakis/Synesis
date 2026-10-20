package org.synesis.workspace.provider.codex;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.provider.AgentLogEntry;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderMcpEvidenceTier;
import org.synesis.workspace.provider.ProviderSupportLevel;
import org.synesis.workspace.provider.StreamEvents;

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

    /**
     * {@code codex login status} prints "Logged in using ..." with exit 0, or "Not logged in" (checked
     * against Codex 0.163, 2026-10-20). A version without the command answers with a usage error and is
     * reported as unknown.
     */
    @Override
    public java.util.Optional<List<String>> signInStatusArguments() {
        return java.util.Optional.of(List.of("login", "status"));
    }

    @Override
    public SignIn signInState(int exitCode, String output) {
        String answer = output == null ? "" : output.strip().toLowerCase(java.util.Locale.ROOT);
        if (answer.startsWith("not logged in")) {
            return SignIn.SIGNED_OUT;
        }
        return exitCode == 0 && answer.startsWith("logged in") ? SignIn.SIGNED_IN : SignIn.UNKNOWN;
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
     * Runs Codex with {@code codex exec}, reading the prompt from standard input. Codex finds the
     * Synesis server through its own configuration and the project through the working directory.
     *
     * <p>The session is started in Codex's read-only sandbox with the Synesis server's tools
     * approved (ADR-0091). Codex starts in the project checkout, and it runs a project's hooks
     * only after a person has reviewed them, which a headless session has nobody to do: the hook
     * that should route a native write into the lane never ran, and a write reached the checkout.
     * Read-only makes that write impossible instead of forbidden. Alone it would also refuse
     * every Synesis tool, which is the only way such a session may change anything. Neither
     * setting loosens what Codex may do outside the Synesis tools.</p>
     */
    @Override
    public java.util.Optional<HeadlessSession> headlessSession() {
        return java.util.Optional.of(new HeadlessSession() {

            @Override
            public List<String> startCommand(Path projectRoot, HeadlessOptions options) {
                List<String> command = new java.util.ArrayList<>(List.of("codex", "exec", "--json", "--cd", projectRoot.toAbsolutePath().normalize().toString()));
                command.addAll(sessionArguments(options));
                command.add("-");
                return List.copyOf(command);
            }

            @Override
            public List<String> resumeCommand(Path projectRoot, String providerSessionId, HeadlessOptions options) {
                List<String> command = new java.util.ArrayList<>(List.of("codex", "exec", "resume", providerSessionId, "--json"));
                command.addAll(sessionArguments(options));
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

            @Override
            public Map<String, String> environment(HeadlessOptions options) {
                // Codex's MCP servers get the identity through the configuration above; its hooks get
                // Codex's own environment, and the Stop hook finds the agent's wait by this identity.
                return options.connectionInstanceId() == null ? Map.of() : Map.of(CONNECTION_INSTANCE_VARIABLE, options.connectionInstanceId());
            }

            @Override
            public List<AgentLogEntry> logEntries(String outputLine) {
                return codexEntries(StreamEvents.parse(outputLine));
            }
        });
    }

    /**
     * Reads one {@code exec --json} event. Only a completed item says what happened: its message, its MCP call with
     * the answer, or its command with the output. A failed turn is read as a failure.
     */
    private static List<AgentLogEntry> codexEntries(Map<?, ?> event) {
        if (event == null) {
            return List.of();
        }
        String type = StreamEvents.text(event, "type");
        if ("turn.failed".equals(type) || "error".equals(type)) {
            return List.of(new AgentLogEntry(AgentLogEntry.Kind.FAILED, "", failureText(event.get("error") == null ? event.get("message") : event.get("error"))));
        }
        if (!"item.completed".equals(type) || !(event.get("item") instanceof Map<?, ?> item)) {
            return List.of();
        }
        switch (StreamEvents.text(item, "type")) {
            case "agent_message":
                return List.of(new AgentLogEntry(AgentLogEntry.Kind.SAID, "", StreamEvents.text(item, "text")));
            case "mcp_tool_call":
                String tool = StreamEvents.text(item, "server") + "." + StreamEvents.text(item, "tool");
                AgentLogEntry call = new AgentLogEntry(AgentLogEntry.Kind.CALLED, tool, ProviderJson.write(item.get("arguments")));
                return item.get("error") != null ? List.of(call, new AgentLogEntry(AgentLogEntry.Kind.FAILED, tool, failureText(item.get("error"))))
                        : List.of(call, new AgentLogEntry(AgentLogEntry.Kind.RESULT, tool, mcpResultText(item.get("result"))));
            case "command_execution":
                return List.of(new AgentLogEntry(AgentLogEntry.Kind.CALLED, "command", StreamEvents.text(item, "command")),
                        new AgentLogEntry(AgentLogEntry.Kind.RESULT, "command", StreamEvents.text(item, "aggregated_output")));
            default:
                return List.of();
        }
    }

    /** An MCP result is a list of content blocks; the text ones are read. */
    private static String mcpResultText(Object result) {
        StringBuilder text = new StringBuilder();
        if (result instanceof Map<?, ?> answer && answer.get("content") instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> part && part.get("text") instanceof String words) {
                    text.append(text.isEmpty() ? "" : " ").append(words);
                }
            }
        }
        return text.toString();
    }

    private static String failureText(Object failure) {
        return failure instanceof Map<?, ?> detail && detail.get("message") instanceof String message ? message : String.valueOf(failure);
    }

    private static List<String> sessionArguments(HeadlessOptions options) {
        // Given as configuration overrides: `exec resume` takes no sandbox flag.
        List<String> arguments = new java.util.ArrayList<>(List.of("--config", "sandbox_mode=read-only", "--config", "mcp_servers.synesis.default_tools_approval_mode=approve"));
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
            if (options.hookLauncher() != null) {
                // Its Stop hook holds the agent told WAIT until something arrives for its lane, as
                // Claude Code's does (ADR-0099); trusted for this run only, and only this hook.
                arguments.addAll(CodexStopHook.arguments(options.hookLauncher(), System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")));
            }
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
    public boolean sharesHookFileWithUser() {
        return true;
    }

    @Override
    public boolean usesGlobalMcpConfiguration() {
        return true;
    }

    @Override
    public boolean supportsManagedContinuity() {
        return true;
    }

    /**
     * Codex runs this project's hooks in a session the person starts only after they trust the project and review the
     * hooks in Codex, and Synesis never does that for them. That is a step of theirs, not a fault of the installation:
     * an agent Synesis starts needs neither (its writes go through Synesis in Codex's read-only sandbox, and it is given
     * Synesis's own Stop hook for its run, {@link CodexStopHook}), so the state is reported beside a healthy install
     * rather than as a degraded one (ADR-0099, 2026-10-18).
     */
    @Override
    public String trustStatus() {
        return "REVIEW_REQUIRED";
    }

    @Override
    public String trustNote() {
        return "Codex runs this project's hooks in a session you start only after you trust the project and review its hooks with /hooks in Codex; agents that synesis agents run starts do not need that.";
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
