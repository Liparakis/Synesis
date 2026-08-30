package org.synesis.workspace.provider;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes one provider-specific configuration, hook, and MCP contract.
 *
 * <p>The default methods deliberately distinguish discovered MCP configuration
 * from verified autonomous execution. An integration that cannot provide
 * independently verified direct supervision must return an empty command and
 * remain pull-safe.</p>
 */
public interface ProviderIntegration {

    private static String quote(Path path) {
        return "\"" + path.toAbsolutePath()
                .normalize()
                .toString()
                .replace("\"", "\\\"") + "\"";
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
    ProviderSupportLevel supportLevel();

    /**
     * Returns the independently tracked MCP evidence tier.
     *
     * @return MCP evidence tier
     */
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
        String cmd = launcher != null && java.nio.file.Files.isRegularFile(launcher)
                ? launcher.toAbsolutePath()
                  .normalize()
                  .toString()
                : (System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                   ? "synesis-mcp.exe" : "synesis-mcp");
        server.put("command", cmd);
        server.put("args", List.of("mcp", "--provider", id()));
        server.put("version", 1);
        return server;
    }

    /**
     * Returns an optional direct argv command for noninteractive supervision.
     *
     * <p>An empty result is intentional: a provider may expose MCP transport
     * without exposing a verified noninteractive driver. Callers must keep
     * such providers pull-safe and must not infer autonomous support.
     *
     * @param worktree isolated lane worktree
     * @param prompt   initial provider task prompt
     * @return direct executable argv, or empty when unsupported/unverified
     */
    default java.util.Optional<List<String>> autonomousCommand(Path worktree, String prompt) {
        return java.util.Optional.empty();
    }

    /**
     * Returns the JSON object key containing the provider hook group.
     *
     * @return hook group
     */
    String hookGroup();

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
     * Result of an isolated provider hook check.
     *
     * @param blocked       protected operation was denied
     * @param allowed       unrelated operation was allowed
     * @param validJson     both responses were JSON-shaped
     * @param blockedOutput protected response
     * @param allowedOutput unrelated response
     */
    record SyntheticCheck(boolean blocked, boolean allowed, boolean validJson, String blockedOutput,
                          String allowedOutput) {

    }
}
