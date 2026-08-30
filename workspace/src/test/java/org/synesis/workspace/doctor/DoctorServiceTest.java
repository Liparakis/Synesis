package org.synesis.workspace.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

/** Exercises workspace doctor diagnostics and failure classification. */
public class DoctorServiceTest {

    @Test
    public void testDoctorReadOnlyGuarantee(@TempDir Path tempDir) throws Exception {
        ProjectApplicationService projectService = new ProjectApplicationService();
        projectService.init(tempDir);

        long countBefore = countFiles(tempDir);

        DoctorService doctorService = new DoctorService();
        DoctorReport report = doctorService.diagnose(tempDir);

        long countAfter = countFiles(tempDir);
        assertEquals(countBefore, countAfter, "DoctorService must create, modify, or delete zero files");
        assertNotNull(report);
        // The durable command namespace is host-wide, so prior tests may leave valid terminal
        // evidence or dead anchors for the existing cleanup workflow to report.
        assertTrue(report.overallStatus() == DoctorStatus.HEALTHY
                        || report.overallStatus() == DoctorStatus.DEGRADED,
                report.findings()
                        .toString());
        assertTrue(report.findings()
                        .stream()
                        .allMatch(f -> f.code() == DoctorFindingCode.COMMAND_NAMESPACE_RECONCILIATION_REQUIRED
                                || f.code() == DoctorFindingCode.COMMAND_CAPACITY_OR_RETENTION),
                report.findings()
                        .toString());
    }

    @Test
    public void testDoctorDetectionUninitialized(@TempDir Path tempDir) {
        DoctorService doctorService = new DoctorService();
        DoctorReport report = doctorService.diagnose(tempDir);

        assertEquals(DoctorStatus.UNHEALTHY, report.overallStatus());
        assertTrue(report.findings()
                .stream()
                .anyMatch(f -> f.code() == DoctorFindingCode.PROJECT_NOT_INITIALIZED));
    }

    @Test
    public void testDoctorSeverityAndStatusMapping(@TempDir Path tempDir) throws Exception {
        ProjectApplicationService projectService = new ProjectApplicationService();
        projectService.init(tempDir);

        Path workspaceRoot = org.synesis.workspace.lifecycle.cleanup.LifecyclePathVerifier.resolveWorkspaceRoot(tempDir);
        Path adminDir = workspaceRoot.resolve("admin");
        Files.createDirectories(adminDir);
        Files.writeString(adminDir.resolve("cleanup-execution.lock"), "{ \"pid\": 9999999 }");

        DoctorService doctorService = new DoctorService();
        DoctorReport report = doctorService.diagnose(tempDir);

        assertEquals(DoctorStatus.DEGRADED, report.overallStatus());
        assertTrue(report.repairAvailable());
        assertTrue(report.findings()
                .stream()
                .anyMatch(f -> f.code() == DoctorFindingCode.STALE_CLEANUP_EXECUTION_LOCK));
    }

    private long countFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.count();
        }
    }
}
