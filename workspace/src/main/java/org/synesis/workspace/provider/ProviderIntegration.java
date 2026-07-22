package org.synesis.workspace.provider;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Describes one provider-specific configuration and hook contract. */
public interface ProviderIntegration {
    /**
     * Returns the stable provider identifier.
     * @return provider identifier
     */
    String id();

    /**
     * Returns provider maturity.
     * @return support level
     */
    ProviderSupportLevel supportLevel();

    /**
     * Resolves the provider configuration path.
     * @param projectRoot project root
     * @return provider configuration path
     */
    Path configurationPath(Path projectRoot);

    /**
     * Returns the JSON object key containing the provider hook group.
     * @return hook group
     */
    String hookGroup();

    /**
     * Returns the stable managed hook identifier.
     * @return managed ID
     */
    String managedHookId();

    /**
     * Returns the provider matcher for supported structured mutations.
     * @return matcher
     */
    String matcher();

    /**
     * Builds the managed hook entry for this provider.
     *
     * @param launcher generated Synesis launcher
     * @param profile local profile
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
     * Identifies a managed hook entry during lifecycle operations.
     * @param value candidate JSON value
     * @return true when the value belongs to this integration
     */
    default boolean isManagedHook(Object value) {
        return value instanceof Map<?, ?> map && managedHookId().equals(map.get("id"));
    }

    /**
     * Returns the command installed for this provider.
     * @param launcher generated Synesis launcher
     * @param profile local profile
     * @return command string
     */
    default String hookCommand(Path launcher, Path profile) {
        return quote(launcher) + " hook " + id() + " --profile " + quote(profile);
    }

    /**
     * Returns an optional Windows-specific command override.
     * @param launcher generated Synesis launcher
     * @param profile local profile
     * @return Windows command, or {@code null} when the generic command is used
     */
    default String windowsHookCommand(Path launcher, Path profile) {
        return null;
    }

    /**
     * Reports whether trust and real-agent evidence are required for health.
     * @return true when synthetic checks alone cannot produce a healthy state
     */
    default boolean requiresRealValidation() {
        return false;
    }

    /**
     * Returns the observable trust state.
     * @return trust state
     */
    default String trustStatus() {
        return "NOT_APPLICABLE";
    }

    /**
     * Runs the isolated synthetic hook check.
     * @param profile local profile
     * @param projectRoot project root
     * @return synthetic check result
     */
    SyntheticCheck syntheticCheck(Path profile, Path projectRoot);

    /** Result of an isolated provider hook check.
     * @param blocked protected operation was denied
     * @param allowed unrelated operation was allowed
     * @param validJson both responses were JSON-shaped
     * @param blockedOutput protected response
     * @param allowedOutput unrelated response
     */
    record SyntheticCheck(boolean blocked, boolean allowed, boolean validJson, String blockedOutput, String allowedOutput) {
    }

    private static String quote(Path path) {
        return "\"" + path.toAbsolutePath().normalize().toString().replace("\"", "\\\"") + "\"";
    }
}
