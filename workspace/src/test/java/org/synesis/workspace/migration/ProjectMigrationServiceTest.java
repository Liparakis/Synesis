package org.synesis.workspace.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;

/**
 * Focused safety tests for project schema detection.
 */
final class ProjectMigrationServiceTest {

    @Test
    void currentProjectIsExplicitlyUpToDateAndIdentityRemainsReadable() throws Exception {
        Path project = Files.createTempDirectory("project-migration-");
        var location = new ProjectApplicationService().init(project)
                .location();
        String before = Files.readString(location.metadataFile());
        ProjectMigrationService service = new ProjectMigrationService(project.resolve("admin"));
        var plan = service.prepare(project);
        assertEquals(ProjectMigrationService.Outcome.UP_TO_DATE,
                plan.entry()
                        .outcome());
        assertEquals(ProjectMigrationService.Outcome.UP_TO_DATE,
                service.execute(plan)
                        .outcome());
        assertEquals(before, Files.readString(location.metadataFile()));
        assertEquals(location.projectId()
                        .toString(), plan.entry()
                .projectId());
    }

    @Test
    void newerSchemaFailsClosed() throws Exception {
        Path project = Files.createTempDirectory("project-migration-");
        var location = new ProjectApplicationService().init(project)
                .location();
        Files.writeString(location.metadataFile(),
                "{\"schemaVersion\":99,\"projectId\":\"" + location.projectId()
                        + "\",\"createdAt\":\"2020-01-01T00:00:00Z\"}");
        ProjectMigrationService service = new ProjectMigrationService(project.resolve("admin"));
        assertEquals(ProjectMigrationService.Outcome.UNSUPPORTED_SCHEMA,
                service.inspect(project)
                        .outcome());
    }

    @Test
    void versionOneSchemaRemainsReadableWithoutMigrationRewrite() throws Exception {
        Path project = Files.createTempDirectory("project-migration-v1-");
        var location = new ProjectApplicationService().init(project)
                .location();
        String v1 = "{\"schemaVersion\":1,\"projectId\":\"" + location.projectId()
                + "\",\"createdAt\":\"" + location.createdAt() + "\"}";
        Files.writeString(location.metadataFile(), v1);
        ProjectMigrationService service = new ProjectMigrationService(project.resolve("admin"));
        assertEquals(ProjectMigrationService.Outcome.UP_TO_DATE,
                service.inspect(project)
                        .outcome());
        assertEquals(v1, Files.readString(location.metadataFile()));
    }
}
