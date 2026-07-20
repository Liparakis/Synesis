package org.synesis.link.transport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

final class DemoCliTest {
    @Test
    void identityCommandCreatesSafePersistentIdentity() throws Exception {
        Path directory = Files.createTempDirectory("synesis-demo-cli");
        try {
            DemoCli.main(new String[] {"identity", "--identity", directory.resolve("node.identity").toString()});
            assertTrue(Files.size(directory.resolve("node.identity")) > 0);
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }
}
