package org.synesis.workspace.provider;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes one provider-specific configuration, hook, and MCP contract.
 *
 * <p>The default methods deliberately distinguish discovered MCP configuration
 * from verified autonomous execution. An integration that cannot provide independently verified
 * direct supervision must return an empty command and remain pull-safe.</p>
 */
public interface ProviderIntegration {

    private static String quote(Path path) {
        return "\"" + path.toAbsolutePath().normalize().toString().replace("\"", "\\\"") + "\"";
    }

    /**
     * Returns the stable provider identifier.
     *
     * @return provider identifier
     */
    String id();

    /**
     * Returns provider maturity.
     *
     * @return support level
     */
    @SuppressWarnings("SameReturnValue") // Maturity is a provider-specific contract even while current providers share a level.
    ProviderSupportLevel supportLevel();

    /**
     * Returns the independently tracked MCP evidence tier.
     *
     * @return MCP evidence tier
     */
    @SuppressWarnings("SameReturnValue") // Evidence is provider-specific; never infer it from another provider's result.
    default ProviderMcpEvidenceTier mcpEvidenceTier() {
        return ProviderMcpEvidenceTier.MCP_CONFIG_DISCOVERED;
    }

    /**
     * Resolves the provider configuration path.
     *
     * @param projectRoot project root
     * @return provider configuration path
     */
    Path configurationPath(Path projectRoot);

    /**
     * Resolves the provider MCP configuration path.
     *
     * @param projectRoot project root
     * @return provider MCP configuration path, or {@code null} if unsupported
     */
    default Path mcpConfigurationPath(Path projectRoot) {
        return null;
    }

    /**
     * Builds the managed MCP server entry for this provider.
     *
     * @param launcher    generated Synesis launcher
     * @param projectRoot project root path
     * @return JSON-compatible MCP server object
     */
    default Map<String, Object> managedMcpServer(Path launcher, Path projectRoot) {
        Map<String, Object> server = new LinkedHashMap<>();
        String cmd = launcher != null && java.nio.file.Files.isRegularFile(launcher) ? launcher.toAbsolutePath().normalize().toString()
                : (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "synesis-mcp.exe" : "synesis-mcp");
        server.put("command", cmd);
        server.put("args", List.of("mcp", "--provider", id()));
        server.put("version", 1);
        return server;
    }

    /**
     * Describes how this provider's agent is run without a person at the keyboard, for providers
     * whose noninteractive mode Synesis has verified.
     *
     * <p>An empty result is intentional: a provider may expose MCP transport without a verified
     * noninteractive driver. Callers must keep such providers pull-safe and must not infer one.</p>
     *
     * @return the headless session contract, or empty when unsupported or unverified
     */
    default java.util.Optional<HeadlessSession> headlessSession() {
        return java.util.Optional.empty();
    }

    /**
     * How one provider starts, identifies, and resumes a noninteractive agent session.
     *
     * <p>The prompt is always supplied on standard input, never as an argument, so that no shell
     * or launcher quoting rule can alter it. Commands run with the control project as their
     * working directory and never bypass the provider's own approval or sandbox policy.</p>
     */
    interface HeadlessSession {

        /**
         * Builds the argv that starts a new session.
         *
         * @param projectRoot control project root
         * @param options     model selection
         * @return argv whose first element is the provider executable name
         */
        List<String> startCommand(Path projectRoot, HeadlessOptions options);

        /**
         * Builds the argv that continues an earlier session with a further prompt.
         *
         * @param projectRoot       control project root
         * @param providerSessionId identifier previously reported by {@link #sessionId(String)}
         * @param options           model selection
         * @return argv whose first element is the provider executable name
         */
        List<String> resumeCommand(Path projectRoot, String providerSessionId, HeadlessOptions options);

        /**
         * Reads the provider's own session identifier from one line of its output stream.
         *
         * @param outputLine one line the provider printed
         * @return the identifier when this line announces it, otherwise empty
         */
        java.util.Optional<String> sessionId(String outputLine);

        /**
         * Returns environment variables the provider process needs so that the Synesis MCP
         * server it starts uses the connection identity named in the options.
         *
         * <p>A provider that hands the identity over through its arguments returns nothing here.
         * Whoever starts the process knows that identity and can therefore tell Synesis when the
         * connection has ended, which a provider that kills its MCP server cannot.</p>
         *
         * @param options model selection and connection identity
         * @return variables to add to the provider process's environment; empty by default
         */
        default Map<String, String> environment(HeadlessOptions options) {
            return Map.of();
        }
    }

    /**
     * Name of the variable through which the Synesis MCP server receives its connection identity.
     */
    String CONNECTION_INSTANCE_VARIABLE = "SYNESIS_MCP_CONNECTION_INSTANCE_ID";

    /**
     * Model selection and connection identity for a headless session.
     *
     * @param model                provider model name, or {@code null} for the provider's default
     * @param effort               provider reasoning-effort level, or {@code null} for the default
     * @param connectionInstanceId identity the Synesis MCP server of this process must use, or
     *                             {@code null} to let the server choose its own
     */
    record HeadlessOptions(String model, String effort, String connectionInstanceId) {

        private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:\\[\\]-]{0,127}");

        /**
         * Rejects values that are not plain tokens, since they become arguments or environment.
         */
        public HeadlessOptions {
            if (model != null && !TOKEN.matcher(model).matches()) {
                throw new IllegalArgumentException("model must be a plain token");
            }
            if (effort != null && !TOKEN.matcher(effort).matches()) {
                throw new IllegalArgumentException("effort must be a plain token");
            }
            if (connectionInstanceId != null && !TOKEN.matcher(connectionInstanceId).matches()) {
                throw new IllegalArgumentException("connection identity must be a plain token");
            }
        }

        /**
         * Creates options that leave the connection identity to the MCP server.
         *
         * @param model  provider model name, or {@code null}
         * @param effort provider reasoning-effort level, or {@code null}
         */
        public HeadlessOptions(String model, String effort) {
            this(model, effort, null);
        }

        /**
         * Returns these options for one process start under a given connection identity.
         *
         * @param identity connection identity for that process
         * @return options carrying the identity
         */
        public HeadlessOptions withConnection(String identity) {
            return new HeadlessOptions(model, effort, identity);
        }
    }

    /**
     * Returns the JSON object key containing the provider hook group.
     *
     * @return hook group
     */
    @SuppressWarnings("SameReturnValue") // Built-in providers share this default but can override it for another config schema.
    default String hookGroup() {
        return "hooks";
    }

    /**
     * Returns the stable managed hook identifier.
     *
     * @return managed ID
     */
    String managedHookId();

    /**
     * Returns the provider matcher for supported structured mutations.
     *
     * @return matcher
     */
    String matcher();

    /**
     * Builds the managed hook entry for this provider.
     *
     * @param launcher generated Synesis launcher
     * @param profile  local profile
     * @return JSON-compatible hook object
     */
    default Map<String, Object> managedHook(Path launcher, Path profile) {
        Map<String, Object> hook = new LinkedHashMap<>();
        hook.put("id", managedHookId());
        hook.put("matcher", matcher());
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "command");
        command.put("command", hookCommand(launcher, profile));
        hook.put("hooks", List.of(command));
        return hook;
    }

    /**
     * Returns an optional managed session-start hook.
     *
     * @param launcher generated Synesis launcher
     * @param profile  local provider profile
     * @return hook object, or {@code null} when unsupported
     */
    default Map<String, Object> managedSessionHook(Path launcher, Path profile) {
        return null;
    }

    /**
     * Identifies an optional managed session-start hook.
     *
     * @param value candidate JSON value
     * @return true when the value belongs to this integration
     */
    default boolean isManagedSessionHook(Object value) {
        return false;
    }

    /**
     * Identifies a managed hook entry during lifecycle operations.
     *
     * @param value candidate JSON value
     * @return true when the value belongs to this integration
     */
    default boolean isManagedHook(Object value) {
        return value instanceof Map<?, ?> map && managedHookId().equals(map.get("id"));
    }

    /**
     * Returns the command installed for this provider.
     *
     * @param launcher generated Synesis launcher
     * @param profile  local profile
     * @return command string
     */
    default String hookCommand(Path launcher, Path profile) {
        return quote(launcher) + " hook " + id() + " --profile " + quote(profile);
    }

    /**
     * Returns an optional Windows-specific command override.
     *
     * @param launcher generated Synesis launcher
     * @param profile  local profile
     * @return Windows command, or {@code null} when the generic command is used
     */
    default String windowsHookCommand(Path launcher, Path profile) {
        return null;
    }

    /**
     * Reports whether trust and real-agent evidence are required for health.
     *
     * @return true when synthetic checks alone cannot produce a healthy state
     */
    default boolean requiresRealValidation() {
        return false;
    }

    /**
     * Returns the observable trust state.
     *
     * @return trust state
     */
    default String trustStatus() {
        return "NOT_APPLICABLE";
    }

    /**
     * Runs the isolated synthetic hook check.
     *
     * @param profile     local profile
     * @param projectRoot project root
     * @return synthetic check result
     */
    SyntheticCheck syntheticCheck(Path profile, Path projectRoot);

    /**
     * Names the project instruction file this provider's harness loads by itself, when it does
     * not read {@code AGENTS.md}.
     *
     * <p>Synesis states its coordination rules once, in the managed block of {@code AGENTS.md}.
     * A harness that never opens that file would start work without them, so its integration
     * points the harness's own instruction mechanism at it rather than keeping a second copy.</p>
     *
     * @param projectRoot control project root
     * @return the file to install, or empty when the harness already reads {@code AGENTS.md}
     */
    default java.util.Optional<ProjectInstructions> projectInstructions(Path projectRoot) {
        return java.util.Optional.empty();
    }

    /**
     * A project-local instruction file owned entirely by Synesis.
     *
     * @param path    absolute location of the file
     * @param content complete file content, beginning with {@link #OWNERSHIP_MARKER}
     */
    record ProjectInstructions(Path path, String content) {

        /**
         * First line of every instruction file Synesis owns. A file at the same path without it
         * belongs to the user and is never replaced or removed.
         */
        public static final String OWNERSHIP_MARKER = "<!-- Managed by Synesis: replaced by `synesis provider install`, removed by uninstall. -->";

        /**
         * Validates that the content declares Synesis ownership.
         */
        public ProjectInstructions {
            java.util.Objects.requireNonNull(path, "path");
            if (content == null || !content.startsWith(OWNERSHIP_MARKER)) {
                throw new IllegalArgumentException("project instructions must begin with the ownership marker");
            }
        }
    }

    /**
     * Result of an isolated provider hook check.
     *
     * @param blocked       protected operation was denied
     * @param allowed       unrelated operation was allowed
     * @param validJson     both responses were JSON-shaped
     * @param blockedOutput protected response
     * @param allowedOutput unrelated response
     */
    record SyntheticCheck(boolean blocked, boolean allowed, boolean validJson, String blockedOutput, String allowedOutput) {

    }
}
