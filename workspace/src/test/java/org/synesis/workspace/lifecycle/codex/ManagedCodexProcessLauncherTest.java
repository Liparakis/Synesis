package org.synesis.workspace.lifecycle.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.lifecycle.codex.runtime.CodexManagedRuntimeHome;

/**
 * Verifies that managed Codex MCP context is carried through trusted overrides.
 */
final class ManagedCodexProcessLauncherTest {

    @Test
    void managedMcpUsesEnvironmentProjectBindingWithoutPublicProjectArgument() {
        Path launcher = Path.of("C:/Synesis/bin/synesis-mcp.exe");
        Path projectRoot = Path.of("C:/Projects/managed-project");
        List<String> command = ManagedCodexProcessLauncher.configuredCommand(
                CodexManagedRuntimeHome.normalProviderHome(), launcher, projectRoot, "connection-managed");
        String argsOverride = command.stream()
                .filter(value -> value.startsWith("mcp_servers.synesis.args="))
                .findFirst()
                .orElseThrow();
        String project = projectRoot.toAbsolutePath().normalize().toString().replace('\\', '/');

        assertEquals("mcp_servers.synesis.args=[\"mcp\",\"--provider\",\"codex\"]", argsOverride);
        assertFalse(argsOverride.contains("--project"), argsOverride);
        assertTrue(command.contains("mcp_servers.synesis.env.SYNESIS_MCP_PROJECT=\"" + project + "\""));
        assertTrue(command.contains("mcp_servers.synesis.env.SYNESIS_MCP_CONNECTION_INSTANCE_ID=\"connection-managed\""));
        assertTrue(command.stream().anyMatch(value -> value.contains("SYNESIS_ATTACH_PROOF")));
    }
}
