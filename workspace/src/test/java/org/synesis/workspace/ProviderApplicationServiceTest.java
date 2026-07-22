package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProviderApplicationService;
import org.synesis.workspace.provider.ProviderJson;
import org.synesis.workspace.provider.ProviderRegistry;

/** Verifies provider registry and isolated lifecycle behavior. */
final class ProviderApplicationServiceTest {
    @Test
    void registryIsDeterministicAndListsCodexAsExperimental() {
        assertEquals(java.util.List.of("antigravity", "claude-code", "codex"),
                ProviderRegistry.providers().stream().map(provider -> provider.id()).toList());
        assertEquals(org.synesis.workspace.provider.ProviderSupportLevel.EXPERIMENTAL,
                ProviderRegistry.find("codex").supportLevel());
        assertEquals("REVIEW_REQUIRED", ProviderRegistry.find("codex").trustStatus());
        assertTrue(ProviderRegistry.find("codex").requiresRealValidation());
    }

    @Test
    void antigravityLifecyclePreservesUnrelatedConfiguration() throws Exception {
        Path root = Files.createTempDirectory("provider-lifecycle-");
        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        String previous = System.getProperty("synesis.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        try {
            ProjectApplicationService projectService = new ProjectApplicationService();
            var location = projectService.init(root).location();
            Path config = root.resolve(".agents/hooks.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{\"unrelated\":{\"value\":true},\"synesis-guardrail\":{\"PreToolUse\":[]}}\n");
            ProviderApplicationService service = new ProviderApplicationService();

            var installed = service.install(location, "antigravity");
            assertEquals("SUCCESS", installed.values().get("PROVIDER_INSTALL_RESULT"));
            Map<?, ?> merged = (Map<?, ?>) ProviderJson.parse(Files.readString(config));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) merged.get("unrelated")).get("value"));
            assertEquals("HEALTHY", service.status(location, "antigravity").values().get("PROVIDER_STATUS"));
            assertEquals("SUCCESS", service.uninstall(location, "antigravity").values().get("PROVIDER_UNINSTALL_RESULT"));
            assertTrue(Files.exists(config));
            Map<?, ?> after = (Map<?, ?>) ProviderJson.parse(Files.readString(config));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) after.get("unrelated")).get("value"));
        } finally {
            if (previous == null) System.clearProperty("synesis.launcher");
            else System.setProperty("synesis.launcher", previous);
        }
    }

    @Test
    void malformedConfigurationIsNotOverwritten() throws Exception {
        Path root = Files.createTempDirectory("provider-malformed-");
        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        String previous = System.getProperty("synesis.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        try {
            var location = new ProjectApplicationService().init(root).location();
            Path config = root.resolve(".agents/hooks.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{broken");
            var result = new ProviderApplicationService().install(location, "antigravity");
            assertEquals("INVALID_CONFIG", result.values().get("PROVIDER_INSTALL_RESULT"));
            assertEquals("{broken", Files.readString(config));
        } finally {
            if (previous == null) System.clearProperty("synesis.launcher");
            else System.setProperty("synesis.launcher", previous);
        }
    }

    @Test
    void codexLifecycleUsesProjectHookShapeAndPreservesUnrelatedConfiguration() throws Exception {
        Path root = Files.createTempDirectory("codex-provider-lifecycle-");
        Path launcher = Files.createTempFile("synesis-launcher-", ".bat");
        String previous = System.getProperty("synesis.launcher");
        System.setProperty("synesis.launcher", launcher.toString());
        try {
            var location = new ProjectApplicationService().init(root).location();
            Path config = root.resolve(".codex/hooks.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{\"unrelated\":{\"value\":true},\"hooks\":{\"Stop\":[]}}\n");
            ProviderApplicationService service = new ProviderApplicationService();

            var installed = service.install(location, "codex");
            assertEquals("DEGRADED", installed.values().get("PROVIDER_INSTALL_RESULT"));
            assertEquals("REVIEW_REQUIRED", installed.values().get("TRUST_STATUS"));
            Map<?, ?> merged = (Map<?, ?>) ProviderJson.parse(Files.readString(config));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) merged.get("unrelated")).get("value"));
            Map<?, ?> hooks = (Map<?, ?>) merged.get("hooks");
            assertTrue(hooks.containsKey("Stop"));
            Map<?, ?> preToolUse = (Map<?, ?>) ((java.util.List<?>) hooks.get("PreToolUse")).getFirst();
            Map<?, ?> handler = (Map<?, ?>) ((java.util.List<?>) preToolUse.get("hooks")).getFirst();
            assertTrue(String.valueOf(handler.get("commandWindows")).startsWith("cmd.exe /d /s /c"));
            assertEquals("DEGRADED", service.status(location, "codex").values().get("PROVIDER_STATUS"));
            assertEquals("REVIEW_REQUIRED", service.status(location, "codex").values().get("TRUST_STATUS"));
            assertEquals("SUCCESS", service.uninstall(location, "codex").values().get("PROVIDER_UNINSTALL_RESULT"));
            Map<?, ?> after = (Map<?, ?>) ProviderJson.parse(Files.readString(config));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) after.get("unrelated")).get("value"));
            assertTrue(((Map<?, ?>) after.get("hooks")).containsKey("Stop"));
        } finally {
            if (previous == null) System.clearProperty("synesis.launcher");
            else System.setProperty("synesis.launcher", previous);
        }
    }
}
