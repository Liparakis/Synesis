package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies managed-home isolation, configuration hygiene, and auth gating. */
final class CodexManagedRuntimeHomeTest {

    @Test
    void createsUniqueHomeAndKeepsProofOutOfConfiguration() throws Exception {
        CodexManagedRuntimeHome first = CodexManagedRuntimeHome.create("project-a");
        CodexManagedRuntimeHome second = CodexManagedRuntimeHome.create("project-a");
        try {
            assertNotEquals(first.homeId(), second.homeId());
            assertTrue(Files.isDirectory(first.path()));
            assertFalse(first.path().startsWith(Path.of(System.getProperty("user.dir"))));
            Path launcher = first.path().resolve("synesis-mcp.exe");
            first.writeConfiguration(launcher, Path.of("C:/fixture"),
                    CodexManagedAuthentication.Strategy.SHARED_KEYRING);
            String config = Files.readString(first.configPath());
            assertTrue(config.contains("env_vars = [\"SYNESIS_ATTACH_PROOF\"]"));
            assertFalse(config.contains("secret-proof"));
            assertEquals(first.path().toString(), first.environment("secret-proof").values().get("CODEX_HOME"));
            assertEquals("secret-proof", first.environment("secret-proof").values().get("SYNESIS_ATTACH_PROOF"));
            assertTrue(first.environment("secret-proof").toString().contains("<redacted>"));
        } finally {
            first.deleteAfterTerminal();
            second.deleteAfterTerminal();
        }
        assertFalse(Files.exists(first.path()));
        assertFalse(Files.exists(second.path()));
    }

    @Test
    void refusesFileCredentialStrategyAndDoesNotReadCredentialContents() throws Exception {
        Path userHome = Files.createTempDirectory("codex-auth-inspect-");
        Files.writeString(userHome.resolve("auth.json"), "sensitive-value");
        assertEquals(CodexManagedAuthentication.Strategy.UNSAFE_FILE_AUTH,
                CodexManagedAuthentication.inspect(userHome));
    }
}
