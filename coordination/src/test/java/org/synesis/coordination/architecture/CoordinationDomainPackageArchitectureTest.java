package org.synesis.coordination.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Verifies that coordination domain ownership remains responsibility-based. */
final class CoordinationDomainPackageArchitectureTest {

    private static final Set<String> RESPONSIBILITIES = Set.of(
            "capability", "command", "integration", "ownership", "prediction", "speculation", "task");

    @Test
    void domainHasNoFlatProductionTypes() throws Exception {
        Path domain = Path.of("src/main/java/org/synesis/coordination/domain");
        try (Stream<Path> files = Files.list(domain)) {
            assertTrue(files.noneMatch(path -> path.toString().endsWith(".java")),
                    "coordination domain must not regain a flat package");
        }
    }

    @Test
    void approvedResponsibilityPackagesExist() throws Exception {
        Path domain = Path.of("src/main/java/org/synesis/coordination/domain");
        Set<String> packages;
        try (Stream<Path> paths = Files.list(domain)) {
            packages = paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
        assertEquals(RESPONSIBILITIES, packages);
    }
}
