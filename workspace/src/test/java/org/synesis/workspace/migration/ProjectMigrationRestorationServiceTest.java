package org.synesis.workspace.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.ProjectApplicationService.ProjectLocation;
import org.synesis.workspace.migration.ProjectMigrationService.Entry;
import org.synesis.workspace.migration.ProjectMigrationService.MigrationFailure;
import org.synesis.workspace.migration.ProjectMigrationService.MigrationStep;
import org.synesis.workspace.migration.ProjectMigrationService.Outcome;
import org.synesis.workspace.migration.ProjectMigrationService.Plan;
import org.synesis.workspace.migration.ProjectMigrationService.Result;

/**
 * Failure-injection tests for exact project metadata restoration.
 */
final class ProjectMigrationRestorationServiceTest {

    private static MigrationStep step(MigrationAction action) {
        return action::apply;
    }

    private static Fixture fixture() throws Exception {
        Path project = Files.createTempDirectory("project-restore-");
        ProjectLocation location = new ProjectApplicationService().init(project)
                .location();
        Path note = project.resolve(".synesis/test-metadata.json");
        Files.writeString(note, "original");
        Path admin = Files.createTempDirectory("project-restore-admin-");
        ProjectMigrationService service = new ProjectMigrationService(admin);
        var prepared = service.prepare(project);
        var plan = getPlan(prepared, location, note);
        return new Fixture(project, location, note, admin, service, plan);
    }

    private static Plan getPlan(Plan prepared, ProjectLocation location, Path note) {
        var entry = new Entry(prepared.entry()
                .metadata(),
                1,
                1,
                prepared.entry()
                        .sourceHash(),
                Outcome.MIGRATION_REQUIRED,
                prepared.entry()
                        .projectId());
        return new Plan(prepared.planId(),
                prepared.createdAt(),
                prepared.projectRoot(),
                entry,
                List.of(location.metadataFile(), note));
    }

    private static String hash(byte[] value) throws Exception {
        return java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value));
    }

    @Test
    void partialMigrationFailureRestoresEveryMutableFile() throws Exception {
        Fixture fixture = fixture();
        String metadata = Files.readString(fixture.location.metadataFile());
        String note = Files.readString(fixture.note);
        Result result = fixture.service.execute(fixture.plan(), step((location) -> {
            Files.writeString(location.metadataFile(), metadata);
            Files.writeString(fixture.note, "changed");
            throw new MigrationFailure("metadata_write_failed");
        }));
        assertEquals(Outcome.FAILED_RESTORED, result.outcome());
        assertEquals(metadata, Files.readString(fixture.location.metadataFile()));
        assertEquals(note, Files.readString(fixture.note));
    }

    @Test
    void malformedMigrationIsRestoredThroughNormalReaderFailure() throws Exception {
        Fixture fixture = fixture();
        String metadata = Files.readString(fixture.location.metadataFile());
        Result result = fixture.service.execute(fixture.plan(),
                step((location) -> Files.writeString(location.metadataFile(), "{broken")));
        assertEquals(Outcome.FAILED_RESTORED, result.outcome());
        assertEquals(metadata, Files.readString(fixture.location.metadataFile()));
    }

    @Test
    void semanticMismatchRestoresMetadataAndIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        String metadata = Files.readString(fixture.location.metadataFile());
        Result result = fixture.service.execute(fixture.plan(), step((_) -> {
            Files.writeString(fixture.note, "changed");
            throw new MigrationFailure("post_migration_replay_mismatch");
        }));
        assertEquals(Outcome.FAILED_RESTORED, result.outcome());
        assertEquals(metadata, Files.readString(fixture.location.metadataFile()));
        assertEquals("original", Files.readString(fixture.note));
    }

    @Test
    void restorationRefusesTargetRaceAndInjectedRestoreFailure() throws Exception {
        Fixture fixture = fixture();
        ProjectMigrationRestorationService restoration = new ProjectMigrationRestorationService();
        var before = new PostMigrationReplayVerifier().capture(fixture.location);
        var manifest = restoration.prepare(fixture.admin,
                fixture.plan.planId(),
                fixture.location,
                1,
                1,
                "SAFE",
                List.of(fixture.note),
                before);
        Files.writeString(fixture.note, "unexpected operator change");
        var race = restoration.restore(fixture.admin,
                manifest,
                fixture.location,
                before,
                Map.of(fixture.note, "known-migration-hash"));
        assertEquals(ProjectMigrationRestorationService.Outcome.REQUIRES_HUMAN_REVIEW, race.outcome());

        Files.writeString(fixture.note, "changed-by-migration");
        String changedHash = hash(Files.readAllBytes(fixture.note));
        var failed = restoration.restore(fixture.admin,
                manifest,
                fixture.location,
                before,
                Map.of(fixture.note, changedHash),
                _ -> {
                    throw new java.io.IOException("injected");
                });
        assertEquals(ProjectMigrationRestorationService.Outcome.REQUIRES_HUMAN_REVIEW, failed.outcome());
        assertNotEquals("original", Files.readString(fixture.note));
    }

    @Test
    void persistedManifestSupportsRestartAndCorruptionFailsClosed() throws Exception {
        Fixture fixture = fixture();
        ProjectMigrationRestorationService restoration = new ProjectMigrationRestorationService();
        var before = new PostMigrationReplayVerifier().capture(fixture.location);
        var manifest = restoration.prepare(fixture.admin,
                fixture.plan.planId(),
                fixture.location,
                1,
                1,
                "SAFE",
                List.of(fixture.note),
                before);
        var loaded = restoration.load(fixture.admin, fixture.plan.planId());
        assertEquals(manifest.manifestHash(), loaded.manifestHash());
        Files.writeString(fixture.note, "changed");
        String changedHash = hash(Files.readAllBytes(fixture.note));
        var firstRestore = restoration.restore(fixture.admin,
                loaded,
                fixture.location,
                before,
                Map.of(fixture.note, changedHash));
        assertEquals(ProjectMigrationRestorationService.Outcome.RESTORED,
                firstRestore.outcome(),
                firstRestore.reason());
        assertEquals(ProjectMigrationRestorationService.Outcome.RESTORED,
                restoration.restore(fixture.admin, loaded, fixture.location, before, Map.of())
                        .outcome());

        Files.writeString(loaded.files()
                .getFirst()
                .backup(), "corrupt");
        assertEquals(ProjectMigrationRestorationService.Outcome.REQUIRES_HUMAN_REVIEW,
                restoration.restore(fixture.admin, loaded, fixture.location, before, Map.of(fixture.note, changedHash))
                        .outcome());
    }

    /** Encapsulates one migration action used by restoration tests. */
    @FunctionalInterface
    private interface MigrationAction {

        void apply(ProjectApplicationService.ProjectLocation location) throws Exception;
    }

    /** Holds the disposable project state used by restoration tests. */
    private record Fixture(Path project, ProjectApplicationService.ProjectLocation location, Path note, Path admin,
                           ProjectMigrationService service, ProjectMigrationService.Plan plan) {

    }
}
