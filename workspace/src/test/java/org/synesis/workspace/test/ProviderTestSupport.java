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
        if (!result.values()
                .containsKey("PROVIDER_INSTALL_RESULT")) {
            throw new IllegalStateException("Provider test installation returned no result: " + result.values());
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
    }
}
