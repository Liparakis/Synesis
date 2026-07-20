package org.synesis.link.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Verifies automatic profile creation, reuse, and fail-closed metadata checks. */
final class IdentityBootstrapTest {
    @Test
    void createsOnceAndReusesTheSameIdentity() throws Exception {
        Path directory = Files.createTempDirectory("synesis-identity-bootstrap");
        try {
            IdentityBootstrap bootstrap = new IdentityBootstrap(directory);
            IdentityBootstrap.Result first = bootstrap.loadOrCreate();
            IdentityBootstrap.Result second = bootstrap.loadOrCreate();
            assertTrue(first.created());
            assertTrue(!second.created());
            assertEquals(first.identity().nodeId(), second.identity().nodeId());
            assertTrue(Files.exists(directory.resolve("identity.pub")));
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }

    @Test
    void inconsistentPublicMetadataFailsWithoutReplacement() throws Exception {
        Path directory = Files.createTempDirectory("synesis-identity-corrupt");
        try {
            IdentityBootstrap bootstrap = new IdentityBootstrap(directory);
            bootstrap.loadOrCreate();
            Files.writeString(directory.resolve("identity.pub"), "corrupt");
            assertThrows(java.io.IOException.class, bootstrap::loadOrCreate);
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
                });
            }
        }
    }
}
