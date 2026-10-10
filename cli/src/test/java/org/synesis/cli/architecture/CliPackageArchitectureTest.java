package org.synesis.cli.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps CLI command-family packages independent of unrelated command delivery code.
 */
class CliPackageArchitectureTest {

    private static final Set<String> FAMILIES = Set.of("identity", "sync", "provider", "hook", "project", "workspace", "coordination", "prediction", "ownership", "task", "speculation", "lifecycle");

    private static String familyOf(String source) {
        for (String family : FAMILIES) {
            if (source.contains("package org.synesis.cli.command." + family + ";")) {
                return family;
            }
        }
        return null;
    }

    private static final Pattern PACKAGE = Pattern.compile("^package (org\\.synesis\\.cli[a-z.]*);", Pattern.MULTILINE);
    private static final Pattern CLI_IMPORT = Pattern.compile("^import (?:static )?(org\\.synesis\\.cli(?:\\.[a-z]+)*)\\.[A-Z]", Pattern.MULTILINE);

    private static Stream<String> productionSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            return paths.filter(path -> path.toString().endsWith(".java")).map(CliPackageArchitectureTest::read).toList().stream();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read " + path, failure);
        }
    }

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
            assertTrue(source.lines().noneMatch(line -> FAMILIES.stream().filter(other -> !other.equals(family)).anyMatch(other -> line.contains("org.synesis.cli.command." + other + "."))), family);
        }
    }

    /**
     * Ensures no CLI package imports another package that reaches back to it.
     *
     * @throws IOException if CLI sources cannot be read
     */
    @Test
    void cliPackagesAreAcyclic() throws IOException {
        Map<String, Set<String>> imports = new HashMap<>();
        for (String source : productionSources().toList()) {
            Matcher declared = PACKAGE.matcher(source);
            assertTrue(declared.find());
            Set<String> targets = imports.computeIfAbsent(declared.group(1), key -> new HashSet<>());
            Matcher imported = CLI_IMPORT.matcher(source);
            while (imported.find()) {
                targets.add(imported.group(1));
            }
        }
        for (Map.Entry<String, Set<String>> entry : imports.entrySet()) {
            entry.getValue().remove(entry.getKey());
        }
        for (String start : imports.keySet()) {
            assertFalse(reaches(imports, start, start, new HashSet<>()), "package cycle through " + start);
        }
    }

    private static boolean reaches(Map<String, Set<String>> imports, String from, String goal, Set<String> visited) {
        for (String next : imports.getOrDefault(from, Set.of())) {
            if (next.equals(goal) || visited.add(next) && reaches(imports, next, goal, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ensures the stable CLI entrypoint remains in its root package.
     *
     * @throws IOException if CLI sources cannot be read
     */
    @Test
    void stableCliEntrypointRemainsAtRoot() throws IOException {
        assertTrue(productionSources().anyMatch(source -> source.contains("package org.synesis.cli;") && source.contains("class SynesisCli")));
    }
}
