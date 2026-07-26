package org.synesis.mcp.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the narrow MCP package boundaries without introducing an architecture framework.
 */
class McpPackageArchitectureTest {

    /**
     * Protocol and application code must not depend on the stdio transport.
     *
     * @throws IOException if production sources cannot be read
     */
    @Test
    void protocolAndApplicationDoNotDependOnStdioTransport() throws IOException {
        List<String> sources = productionSources()
                .filter(source -> source.contains("package org.synesis.mcp.protocol;")
                        || source.contains("package org.synesis.mcp.application;"))
                .toList();
        assertFalse(sources.isEmpty());
        assertTrue(sources.stream().noneMatch(source -> source.contains("org.synesis.mcp.transport.stdio")));
        assertTrue(sources.stream().noneMatch(source -> source.contains("System.in")
                || source.contains("System.out")));
    }

    /**
     * The stdio transport delegates requests and does not define tool business logic.
     *
     * @throws IOException if production sources cannot be read
     */
    @Test
    void stdioTransportDoesNotOwnToolDispatch() throws IOException {
        List<String> transport = productionSources()
                .filter(source -> source.contains("package org.synesis.mcp.transport.stdio;"))
                .toList();
        assertTrue(transport.stream().noneMatch(source -> source.contains("handleToolsList")
                || source.contains("handleToolCall")));
    }

    /**
     * The stable server entrypoint remains in the root MCP package.
     *
     * @throws IOException if production sources cannot be read
     */
    @Test
    void stableEntrypointRemainsAtRoot() throws IOException {
        assertTrue(productionSources().anyMatch(source -> source.contains("package org.synesis.mcp;")
                && source.contains("class SynesisMcpServer")));
    }

    private static Stream<String> productionSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .map(McpPackageArchitectureTest::read)
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
