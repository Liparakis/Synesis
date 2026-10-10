package org.synesis.workspace.application.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the coordination store and signing identity derived from a project location.
 */
class ProjectLocationTest {

    @TempDir
    Path temp;

    private ProjectLocation location() {
        Path root = temp.resolve("project");
        return new ProjectLocation(root, root.resolve(".synesis"), root.resolve(".synesis/project.json"),
                temp.resolve("profile"), UUID.randomUUID(), Instant.now());
    }

    @Test
    void derivesPersistedDirectoryNames() {
        ProjectLocation location = location();
        assertEquals(location.root().resolve(".synesis/coordination"), location.coordinationDirectory());
        assertEquals(location.profile().resolve("link"), location.linkDirectory());
    }

    @Test
    void opensCoordinationStoreAndIdentityOnFreshProject() throws Exception {
        ProjectLocation location = location();
        var store = location.openCoordinationStore();
        assertEquals(location.projectId(), store.projectId());
        assertEquals(location.coordinationDirectory(), store.rootDirectory());
        var identity = location.loadOrCreateIdentity();
        assertTrue(location.linkDirectory().resolve("identity.bin").toFile().isFile());
        assertEquals(identity.nodeId(), location.loadOrCreateIdentity().nodeId());
    }
}
