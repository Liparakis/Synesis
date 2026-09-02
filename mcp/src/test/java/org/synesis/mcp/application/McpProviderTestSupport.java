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
        Path isolatedHome = Files.createTempDirectory("synesis-mcp-provider-home-");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            ProviderApplicationService.ProviderResult result = new ProviderApplicationService().install(location,
                    provider);
            if (!result.values().containsKey("PROVIDER_INSTALL_RESULT")) {
                throw new IllegalStateException("Provider installation returned no result: " + result.values());
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }
}
