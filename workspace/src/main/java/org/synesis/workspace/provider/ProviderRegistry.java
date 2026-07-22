package org.synesis.workspace.provider;

import java.util.List;
import java.util.Locale;

import org.synesis.workspace.provider.antigravity.AntigravityProviderIntegration;
import org.synesis.workspace.provider.claude.ClaudeCodeProviderIntegration;
import org.synesis.workspace.provider.codex.CodexProviderIntegration;

/** Static registry for the currently implemented provider integrations. */
public final class ProviderRegistry {
    private static final List<ProviderIntegration> PROVIDERS = List.of(
            new AntigravityProviderIntegration(), new ClaudeCodeProviderIntegration(), new CodexProviderIntegration());

    private ProviderRegistry() {
    }

    /**
     * Returns the deterministic installable provider list.
     * @return provider list
     */
    public static List<ProviderIntegration> providers() {
        return PROVIDERS;
    }

    /**
     * Finds a provider.
     *
     * @param id provider identifier
     * @return matching integration, or {@code null}
     */
    public static ProviderIntegration find(String id) {
        if (id == null) return null;
        String normalized = id.toLowerCase(Locale.ROOT);
        return PROVIDERS.stream().filter(provider -> provider.id().equals(normalized)).findFirst().orElse(null);
    }
}
