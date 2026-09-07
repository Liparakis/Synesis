package org.synesis.mcp.application;

import java.nio.file.Files;
import java.nio.file.Path;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;

/**
 * Installs project provider integrations for MCP tests without touching the
 * developer's real provider configuration.
 */
final class McpProviderTestSupport {

    private static final String TEST_LAUNCHER = "synesis.mcp.test.launcher";
    private static final String TEST_MCP_LAUNCHER = "synesis.mcp.test.mcp.launcher";

    private McpProviderTestSupport() {
    }

    /**
     * Runs the normal project provider install with an isolated provider home.
     *
     * @param location initialized project location
     * @param provider canonical provider identifier
     * @throws Exception if normal installation fails
     */
    static void install(ProjectApplicationService.ProjectLocation location, String provider) throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousLauncher = System.getProperty("synesis.launcher");
        String previousMcpLauncher = System.getProperty("synesis.mcp.launcher");
        Path isolatedHome = Files.createTempDirectory("synesis-mcp-provider-home-");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            ensureLauncher("synesis.launcher", TEST_LAUNCHER);
            ensureLauncher("synesis.mcp.launcher", TEST_MCP_LAUNCHER);
            ProviderApplicationService.ProviderResult result = new ProviderApplicationService().install(location,
                    provider);
            if (result.exitCode() != 0 || !"BOUND".equals(result.values()
                    .get("SESSION_BINDING"))) {
                throw new IllegalStateException("Provider installation did not bind a session: " + result.values());
            }
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("synesis.launcher", previousLauncher);
            restoreProperty("synesis.mcp.launcher", previousMcpLauncher);
        }
    }

    private static void ensureLauncher(String property, String testProperty) throws Exception {
        String configured = System.getProperty(property);
        if (configured != null && Files.isRegularFile(Path.of(configured))) {
            return;
        }
        String existing = System.getProperty(testProperty);
        if (existing == null || !Files.isRegularFile(Path.of(existing))) {
            existing = Files.createTempFile(testProperty, ".launcher").toString();
            System.setProperty(testProperty, existing);
        }
        System.setProperty(property, existing);
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
