package org.synesis.workspace.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Focused safety tests for provider migration. */
final class ProviderConfigMigrationServiceTest {

    @Test
    void migratesCodexWithCompareAndSetAndPreservesUnrelatedData() throws Exception {
        Path home = Files.createTempDirectory("provider-home-");
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            Path config = home.resolve(".codex/config.toml");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "notify = [\"keep\"]\n\n[mcp_servers.other]\ncommand = \"other\"\n\n[mcp_servers.synesis]\nenabled = true\ncommand = \"old\"\nargs = []\nstartup_timeout_sec = 1\n");
            Path launcher = Files.createFile(home.resolve("synesis.cmd"));
            ProviderConfigMigrationService service = new ProviderConfigMigrationService(home.resolve("admin"), launcher);
            ProviderConfigMigrationService.Plan plan = service.prepare();
            ProviderConfigMigrationService.Result result = service.execute(plan);
            assertEquals(ProviderConfigMigrationService.Outcome.MIGRATED, result.outcome());
            String root = Files.readString(config);
            assertTrue(root.contains("notify = [\"keep\"]"));
            assertTrue(root.contains("[mcp_servers.other]"));
            assertTrue(root.contains("command = '" + launcher.toAbsolutePath().normalize() + "'"));
            assertTrue(root.contains("\"mcp\", \"--provider\", \"codex\""));
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
            Path config = home.resolve(".codex/config.toml");
            Files.createDirectories(config.getParent());
            String malformed = "[mcp_servers.synesis\ncommand = \"unterminated";
            Files.writeString(config, malformed);
            ProviderConfigMigrationService service = new ProviderConfigMigrationService(home.resolve("admin"), home.resolve("synesis.cmd"));
            assertEquals(ProviderConfigMigrationService.Outcome.MALFORMED, service.inspect("codex").outcome());
            assertEquals(malformed, Files.readString(config));
        } finally {
            if (oldHome == null) System.clearProperty("user.home"); else System.setProperty("user.home", oldHome);
        }
    }
}
