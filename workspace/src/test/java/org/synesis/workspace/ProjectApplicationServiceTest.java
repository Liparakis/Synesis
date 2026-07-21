package org.synesis.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;

/** Verifies discovered project initialization and local-state separation. */
final class ProjectApplicationServiceTest {
    @Test
    void initializesAndDiscoversFromNestedDirectory() throws Exception {
        Path root = Files.createTempDirectory("synesis-init-");
        ProjectApplicationService service = new ProjectApplicationService();
        ProjectApplicationService.InitResult initialized = service.init(root);

        assertEquals(ProjectApplicationService.InitStatus.SUCCESS, initialized.status());
        assertTrue(Files.exists(root.resolve(".synesis/project.json")));
        assertTrue(Files.exists(root.resolve(".synesis/local/profile/link/identity.bin")));
        assertTrue(Files.isDirectory(root.resolve(".synesis/shared/records")));
        String metadata = Files.readString(root.resolve(".synesis/project.json"));
        assertFalse(metadata.contains("identity.bin"));
        assertFalse(metadata.contains("private"));

        Files.createDirectories(root.resolve("nested/child"));
        ProjectApplicationService.ProjectLocation discovered = service.locate(root.resolve("nested/child"));
        assertEquals(root.toAbsolutePath().normalize(), discovered.root());
        assertEquals(initialized.location().projectId(), discovered.projectId());
        assertEquals(ProjectApplicationService.InitStatus.ALREADY_INITIALIZED, service.init(root).status());
    }

    @Test
    void partialAndMalformedStateFailsClosed() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path partial = Files.createTempDirectory("synesis-partial-");
        Files.createDirectories(partial.resolve(".synesis/local"));
        ProjectApplicationService.ProjectApplicationException partialFailure =
                assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> service.init(partial));
        assertEquals("CONFLICT", partialFailure.code());

        Path malformed = Files.createTempDirectory("synesis-malformed-");
        Files.createDirectories(malformed.resolve(".synesis"));
        Files.writeString(malformed.resolve(".synesis/project.json"), "{}\n");
        ProjectApplicationService.ProjectApplicationException malformedFailure =
                assertThrows(ProjectApplicationService.ProjectApplicationException.class, () -> service.locate(malformed));
        assertEquals("MALFORMED", malformedFailure.code());
    }

    @Test
    void projectCreateUsesShareableProjectId() throws Exception {
        ProjectApplicationService service = new ProjectApplicationService();
        Path root = Files.createTempDirectory("synesis-project-");
        var init = service.init(root);
        String peer = "sl1-" + "a".repeat(64);
        var created = service.createProject(init.location(), peer);
        assertEquals(init.location().projectId(), created.projectId());
        assertTrue(Files.readString(root.resolve(".synesis/local/profile/project.conf")).contains(init.location().projectId().toString()));
    }
}
