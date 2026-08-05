import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderApplicationService;
import org.synesis.workspace.infrastructure.json.ProviderJson;
import org.synesis.workspace.lifecycle.RepositoryPrivateStateService;

/** Verifies Codex hook ownership classification and fail-closed materialization. */
class CodexHookOwnershipTest {

    private String previousHome;
    private String previousLauncher;

    @BeforeEach
    void isolateHome() throws Exception {
        previousHome = System.getProperty("user.home");
        previousLauncher = System.getProperty("synesis.launcher");
        System.setProperty("user.home", Files.createTempDirectory("synesis-hook-home-").toString());
    }

    @AfterEach
    void restoreHome() {
        if (previousHome == null) System.clearProperty("user.home");
        else System.setProperty("user.home", previousHome);
        if (previousLauncher == null) System.clearProperty("synesis.launcher");
        else System.setProperty("synesis.launcher", previousLauncher);
    }

    private static void git(Path root, String... args) throws Exception {
        org.synesis.workspace.test.TestGit.run(root, args);
    }

    private static Path project() throws Exception {
        Path root = Files.createTempDirectory("synesis-hook-project-");
        git(root, "init");
        git(root, "config", "user.name", "Synesis Test");
        git(root, "config", "user.email", "synesis-test@example.invalid");
        Files.writeString(root.resolve("README.md"), "baseline\n");
        git(root, "add", ".");
        git(root, "commit", "-m", "baseline");
        new ProjectApplicationService().init(root, false);
        Path launcher = Files.createTempFile("synesis-launcher-", ".cmd");
        System.setProperty("synesis.launcher", launcher.toString());
        return root;
    }

    private static ProviderApplicationService.ProviderResult install(Path root) throws Exception {
        return new ProviderApplicationService().install(new ProjectApplicationService().locate(root), "codex");
    }

    @Test
    void absentHookIsCreatedAndRepeatedMaterializationIsIdempotent() throws Exception {
        Path root = project();
        Path config = root.resolve(".codex/hooks.json");
        assertTrue(Files.notExists(config));
        var first = install(root);
        assertEquals("DEGRADED", first.values().get("PROVIDER_INSTALL_RESULT"));
        String content = Files.readString(config);
        assertTrue(content.contains("synesis-codex-session"));
        assertTrue(content.contains("hook codex"));
        install(root);
        assertEquals(content, Files.readString(config));
    }

    @Test
    void userAndMixedContentIsPreservedWhenMatchersDoNotOverlap() throws Exception {
        Path root = project();
        Path config = root.resolve(".codex/hooks.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"custom\":true,\"hooks\":{\"PreToolUse\":[{\"matcher\":\"*\",\"hooks\":[]}],\"Stop\":[]}}\n");
        var result = install(root);
        assertEquals("DEGRADED", result.values().get("PROVIDER_INSTALL_RESULT"));
        String content = Files.readString(config);
        assertTrue(content.contains("\"custom\":true"));
        assertTrue(content.contains("\"matcher\":\"*\""));
        assertTrue(content.contains("synesis-codex-session"));
    }

    @Test
    void overlappingUserMatcherFailsWithStableConflict() throws Exception {
        Path root = project();
        Path config = root.resolve(".codex/hooks.json");
        Files.createDirectories(config.getParent());
        String original = "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"^apply_patch$\",\"hooks\":[]}]}}\n";
        Files.writeString(config, original);
        var result = install(root);
        assertEquals("PROVIDER_CONFIGURATION_CONFLICT", result.values().get("PROVIDER_INSTALL_RESULT"));
        assertEquals(original, Files.readString(config));
    }

    @Test
    void privateExclusionDoesNotProveHookOwnership() throws Exception {
        Path root = project();
        RepositoryPrivateStateService.ensure(root);
        Path config = root.resolve(".codex/hooks.json");
        Files.createDirectories(config.getParent());
        String original = "{\"providerOwned\":true,\"hooks\":{\"PreToolUse\":[{\"matcher\":\"^apply_patch$\",\"hooks\":[]}]}}\n";
        Files.writeString(config, original);
        var result = install(root);
        assertEquals("PROVIDER_CONFIGURATION_CONFLICT", result.values().get("PROVIDER_INSTALL_RESULT"));
        assertEquals(original, Files.readString(config));
    }

    @Test
    void malformedAndTrackedFilesFailClosed() throws Exception {
        Path malformed = project();
        Path malformedConfig = malformed.resolve(".codex/hooks.json");
        Files.createDirectories(malformedConfig.getParent());
        Files.writeString(malformedConfig, "{broken");
        assertEquals("PROVIDER_CONFIGURATION_CONFLICT",
                install(malformed).values().get("PROVIDER_INSTALL_RESULT"));
        assertEquals("{broken", Files.readString(malformedConfig));

        Path tracked = project();
        Path trackedConfig = tracked.resolve(".codex/hooks.json");
        Files.createDirectories(trackedConfig.getParent());
        Files.writeString(trackedConfig, "{\"user\":true}\n");
        git(tracked, "add", "-f", ".codex/hooks.json");
        git(tracked, "commit", "-m", "track provider hook");
        assertEquals("PROVIDER_CONFIGURATION_CONFLICT",
                install(tracked).values().get("PROVIDER_INSTALL_RESULT"));
        assertTrue(Files.readString(trackedConfig).contains("\"user\":true"));
    }

    @Test
    void symlinkFailsClosedWhenSupported() throws Exception {
        Path root = project();
        Path config = root.resolve(".codex/hooks.json");
        Path target = Files.createTempFile("synesis-hook-target-", ".json");
        Files.createDirectories(config.getParent());
        try {
            Files.createSymbolicLink(config, target);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException failure) {
            Assumptions.abort("symbolic links unavailable on this test host");
        }
        assertEquals("PROVIDER_CONFIGURATION_CONFLICT",
                install(root).values().get("PROVIDER_INSTALL_RESULT"));

        Path parentSymlinkRoot = project();
        Path parent = parentSymlinkRoot.resolve(".codex");
        Path parentTarget = Files.createTempDirectory("synesis-hook-parent-target-");
        Files.createDirectories(parent.getParent());
        try {
            Files.createSymbolicLink(parent, parentTarget);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException failure) {
            Assumptions.abort("symbolic links unavailable on this test host");
        }
        assertEquals("PROVIDER_CONFIGURATION_CONFLICT",
                install(parentSymlinkRoot).values().get("PROVIDER_INSTALL_RESULT"));
    }
}
