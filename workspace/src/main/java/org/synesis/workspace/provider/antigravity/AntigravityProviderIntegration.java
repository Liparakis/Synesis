package org.synesis.workspace.provider.antigravity;

import java.nio.file.Path;

import org.synesis.workspace.integration.antigravity.AntigravityHookAdapter;
import org.synesis.workspace.provider.ProviderIntegration;
import org.synesis.workspace.provider.ProviderJson;
import org.synesis.workspace.provider.ProviderSupportLevel;

/** Antigravity provider configuration and synthetic hook contract. */
public final class AntigravityProviderIntegration implements ProviderIntegration {
    /** Creates the Antigravity integration. */
    public AntigravityProviderIntegration() {
    }
    @Override public String id() { return "antigravity"; }
    @Override public ProviderSupportLevel supportLevel() { return ProviderSupportLevel.BETA; }
    @Override public Path configurationPath(Path projectRoot) { return projectRoot.resolve(".agents/hooks.json"); }
    @Override public String hookGroup() { return "synesis-guardrail"; }
    @Override public String managedHookId() { return "synesis-antigravity"; }
    @Override public String matcher() { return "write_to_file|replace_file_content|multi_replace_file_content"; }

    @Override
    public SyntheticCheck syntheticCheck(Path profile, Path projectRoot) {
        AntigravityHookAdapter adapter = new AntigravityHookAdapter(profile);
        String root = jsonPath(projectRoot);
        String protectedEvent = "{\"workspacePaths\":[\"" + root + "\"],\"toolCall\":{\"name\":\"write_to_file\",\"args\":{\"TargetFile\":\"" + jsonPath(projectRoot.resolve("src/protected.txt")) + "\"}}}";
        String allowedEvent = protectedEvent.replace("src/protected.txt", "src/free.txt");
        var blocked = adapter.processJson(protectedEvent);
        var allowed = adapter.processJson(allowedEvent);
        return new SyntheticCheck(blocked.outcome() == AntigravityHookAdapter.Outcome.BLOCKED,
                allowed.outcome() == AntigravityHookAdapter.Outcome.ALLOWED,
                valid(blocked.responseJson()) && valid(allowed.responseJson()), blocked.responseJson(), allowed.responseJson());
    }

    private static boolean valid(String json) { try { return ProviderJson.parse(json) instanceof java.util.Map<?, ?>; } catch (RuntimeException failure) { return false; } }
    private static String jsonPath(Path path) { return path.toAbsolutePath().normalize().toString().replace('\\', '/'); }
}
