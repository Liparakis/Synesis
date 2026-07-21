package org.synesis.workspace.provider;

import java.nio.file.Path;

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
}
