package org.synesis.cli.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps CLI command-family packages independent of unrelated command delivery code.
 */
class CliPackageArchitectureTest {

    private static final Set<String> FAMILIES = Set.of(
            "identity", "sync", "provider", "hook", "project", "workspace", "coordination",
            "prediction", "ownership", "task", "speculation", "lifecycle");

    /**
     * Ensures command families do not import other command-family implementations or MCP code.
     *
     * @throws IOException if CLI sources cannot be read
     */
    @Test
    void commandFamiliesDoNotDependOnUnrelatedDeliveryInternals() throws IOException {
        List<String> sources = productionSources().toList();
        assertFalse(sources.isEmpty());
        for (String source : sources) {
            String family = familyOf(source);
            if (family == null) {
                continue;
            }
            assertTrue(source.lines().noneMatch(line -> line.contains("org.synesis.mcp")), family);
            assertTrue(source.lines().noneMatch(line -> FAMILIES.stream()
                    .filter(other -> !other.equals(family))
                    .filter(other -> !lineContainsSharedCoordinationSupport(other, source))
                    .anyMatch(other -> line.contains("org.synesis.cli.command." + other + "."))), family);
        }
    }

    /**
     * Ensures the stable CLI entrypoint remains in its root package.
     *
     * @throws IOException if CLI sources cannot be read
     */
    @Test
    void stableCliEntrypointRemainsAtRoot() throws IOException {
        assertTrue(productionSources().anyMatch(source -> source.contains("package org.synesis.cli;")
                && source.contains("class SynesisCli")));
    }

    private static String familyOf(String source) {
        for (String family : FAMILIES) {
            if (source.contains("package org.synesis.cli.command." + family + ";")) {
                return family;
            }
        }
        return null;
    }

    private static boolean lineContainsSharedCoordinationSupport(String family, String source) {
        return "coordination".equals(family)
                && (source.contains("CoordinationCliSupport") || source.contains("CoordinationEventFollower"));
    }

    private static Stream<String> productionSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .map(CliPackageArchitectureTest::read)
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
