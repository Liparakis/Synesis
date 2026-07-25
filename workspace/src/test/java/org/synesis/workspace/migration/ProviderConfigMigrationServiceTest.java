package org.synesis.workspace.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Focused safety tests for provider migration. */
final class ProviderConfigMigrationServiceTest {

    @Test
    void migratesCodexWithCompareAndSetAndPreservesUnrelatedData() throws Exception {
        Path home = Files.createTempDirectory("provider-home-");
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            Path config = home.resolve(".codex/mcp.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{\"settings\":{\"keep\":true},\"mcpServers\":{\"other\":{\"command\":\"other\"},\"synesis\":{\"command\":\"old\"}}}");
            Path launcher = Files.createFile(home.resolve("synesis.cmd"));
            ProviderConfigMigrationService service = new ProviderConfigMigrationService(home.resolve("admin"), launcher);
            ProviderConfigMigrationService.Plan plan = service.prepare();
            ProviderConfigMigrationService.Result result = service.execute(plan);
            assertEquals(ProviderConfigMigrationService.Outcome.MIGRATED, result.outcome());
            Map<?, ?> root = (Map<?, ?>) org.synesis.workspace.provider.ProviderJson.parse(Files.readString(config));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) root.get("settings")).get("keep"));
            assertTrue(((Map<?, ?>) root.get("mcpServers")).containsKey("other"));
            assertEquals(ProviderConfigMigrationService.Outcome.UP_TO_DATE, service.inspect("codex").outcome());
        } finally {
            if (oldHome == null) System.clearProperty("user.home"); else System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void malformedProviderConfigIsNotModified() throws Exception {
        Path home = Files.createTempDirectory("provider-home-");
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            Path config = home.resolve(".codex/mcp.json");
            Files.createDirectories(config.getParent());
            String malformed = "{not-json";
            Files.writeString(config, malformed);
            ProviderConfigMigrationService service = new ProviderConfigMigrationService(home.resolve("admin"), home.resolve("synesis.cmd"));
            assertEquals(ProviderConfigMigrationService.Outcome.MALFORMED, service.inspect("codex").outcome());
            assertEquals(malformed, Files.readString(config));
        } finally {
            if (oldHome == null) System.clearProperty("user.home"); else System.setProperty("user.home", oldHome);
        }
    }
}
