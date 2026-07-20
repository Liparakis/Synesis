package org.synesis.projectrecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Verifies strict local project configuration persistence and allowlisting. */
final class ProjectConfigTest {
    @Test
    void persistsSortedAllowlistAndRejectsUnknownKeys() throws Exception {
        String peer = "sl1-" + "a".repeat(64);
        ProjectConfig config = new ProjectConfig(UUID.randomUUID(), Set.of(peer));
        Path path = Files.createTempDirectory("project-config-").resolve("project.conf");
        config.save(path);
        ProjectConfig loaded = ProjectConfig.load(path);
        assertEquals(config.projectId(), loaded.projectId());
        assertTrue(loaded.allows(peer));
        Files.writeString(path, "projectId=" + config.projectId() + "\nunknown=value\n");
        assertThrows(java.io.IOException.class, () -> ProjectConfig.load(path));
    }
}
