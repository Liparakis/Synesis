package org.synesis.workspace.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the workspace package boundaries explicit without depending on a third-party architecture
 * test framework.
 */
class WorkspacePackageArchitectureTest {

    /**
     * Rejects package names and imports that would reintroduce the removed integration branch.
     *
     * @throws IOException if production sources cannot be read
     */
    @Test
    void removedIntegrationBranchDoesNotReturn() throws IOException {
        String removedIntegration = "org.synesis.workspace." + "integration";
        String broadInfrastructure = "workspace.infrastructure." + "workspace";
        assertFalse(productionSources().anyMatch(source -> source.contains(removedIntegration)));
        assertFalse(productionSources().anyMatch(source -> source.contains(broadInfrastructure)));
    }

    /**
     * Ensures project and lifecycle code do not depend on delivery adapters.
     *
     * @throws IOException if production sources cannot be read
     */
    @Test
    void projectAndLifecycleDoNotDependOnDeliveryModules() throws IOException {
        List<String> projectAndLifecycle = productionSources()
                .filter(source -> source.contains("package org.synesis.workspace.project;")
                        || source.contains("package org.synesis.workspace.lifecycle."))
                .toList();
        assertTrue(projectAndLifecycle.stream().noneMatch(source -> source.contains("org.synesis.cli")
                || source.contains("org.synesis.mcp")));
    }

    /**
     * Ensures provider-neutral contracts do not import a concrete provider implementation.
     *
     * @throws IOException if production sources cannot be read
     */
    @Test
    void providerContractsRemainProviderNeutral() throws IOException {
        List<String> contracts = productionSources()
                .filter(source -> source.contains("package org.synesis.workspace.provider;")
                        && (source.contains("public interface ProviderIntegration")
                                || source.contains("public enum ProviderSupportLevel")))
                .toList();
        assertFalse(contracts.isEmpty());
        assertTrue(contracts.stream().noneMatch(source -> source.contains("provider.codex")
                || source.contains("provider.antigravity")
                || source.contains("provider.claude")));
    }

    /**
     * Ensures production sources do not refer to test source locations.
     *
     * @throws IOException if production sources cannot be read
     */
    @Test
    void productionDoesNotDependOnTestCode() throws IOException {
        assertTrue(productionSources().noneMatch(source -> source.contains("src/test")
                || source.contains("org.synesis.workspace.test")));
    }

    private static Stream<String> productionSources() throws IOException {
        Path root = Path.of("src/main/java");
        if (!Files.isDirectory(root)) {
            root = Path.of("workspace/src/main/java");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .map(WorkspacePackageArchitectureTest::read)
                    .toList()
                    .stream();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + path, failure);
        }
    }
}
