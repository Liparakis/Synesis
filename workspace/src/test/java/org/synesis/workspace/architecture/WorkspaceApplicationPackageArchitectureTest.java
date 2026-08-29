package org.synesis.workspace.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Verifies responsibility boundaries for workspace application services.
 */
final class WorkspaceApplicationPackageArchitectureTest {

    private static final Path APPLICATION_ROOT = Path.of("src/main/java/org/synesis/workspace/application");

    /**
     * Ensures only the intentionally retained project application facade remains at the root.
     */
    @Test
    void rootContainsOnlyStableFacades() throws Exception {
        Set<String> rootTypes;
        try (var files = Files.list(APPLICATION_ROOT)) {
            rootTypes = files.filter(path -> path.toString()
                            .endsWith(".java"))
                    .map(path -> path.getFileName()
                            .toString())
                    .collect(Collectors.toSet());
        }

        assertEquals(
                Set.of("ProjectApplicationService.java"),
                rootTypes);
    }

    /**
     * Ensures every moved responsibility has an explicit package directory.
     */
    @Test
    void responsibilityPackagesExist() throws Exception {
        Set<String> directories;
        try (var files = Files.list(APPLICATION_ROOT)) {
            directories = files.filter(Files::isDirectory)
                    .map(path -> path.getFileName()
                            .toString())
                    .collect(Collectors.toSet());
        }

        assertTrue(directories.containsAll(Set.of(
                "agent", "capability", "constraint", "control", "guardrail", "hook",
                "integration", "project", "sync", "task", "workspace")));
    }
}
