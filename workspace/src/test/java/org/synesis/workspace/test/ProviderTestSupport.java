package org.synesis.workspace.test;

import java.nio.file.Files;
import java.nio.file.Path;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;

/**
 * Installs real project provider integrations for tests that exercise agent
 * admission or workspace readiness.
 */
public final class ProviderTestSupport {

    private static final String ISOLATED_HOME = "synesis.test.provider.home";
    private static final String TEST_LAUNCHER = "synesis.test.launcher";
    private static final String TEST_MCP_LAUNCHER = "synesis.test.mcp.launcher";

    private ProviderTestSupport() {
    }

    /**
     * Installs one provider while isolating provider-global configuration from
     * the developer's real home directory.
     *
     * @param location initialized project location
     * @param provider canonical provider identifier
     * @throws Exception when the normal provider install cannot complete
     */
    public static void install(ProjectApplicationService.ProjectLocation location, String provider) throws Exception {
        isolateHome();
        ProviderApplicationService.ProviderResult result = new ProviderApplicationService().install(location,
                provider);
        if (result.exitCode() != 0 || !"BOUND".equals(result.values()
                .get("SESSION_BINDING"))) {
            throw new IllegalStateException("Provider test installation did not bind a session: " + result.values());
        }
    }

    private static void isolateHome() throws Exception {
        String isolatedHome = System.getProperty(ISOLATED_HOME);
        if (isolatedHome == null) {
            Path home = Files.createTempDirectory("synesis-provider-test-home-");
            isolatedHome = home.toString();
            System.setProperty(ISOLATED_HOME, isolatedHome);
        }
        System.setProperty("user.home", isolatedHome);
        ensureLauncher("synesis.launcher", TEST_LAUNCHER);
        ensureLauncher("synesis.mcp.launcher", TEST_MCP_LAUNCHER);
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
}
