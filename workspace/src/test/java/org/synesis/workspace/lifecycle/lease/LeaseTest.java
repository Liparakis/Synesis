package org.synesis.workspace.lifecycle.lease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.workspace.application.ProjectApplicationService;

/** Exercises provider-session lease creation, renewal, and closure. */
class LeaseTest {

    @Test
    void createsAndRenewsSessionLeaseOutsideControlCheckout(@TempDir Path tempDir) throws Exception {
        Path controlRoot = tempDir.resolve("control-repo");
        Files.createDirectories(controlRoot);
        new ProjectApplicationService().init(controlRoot);

        SessionLeaseService service = new SessionLeaseService();
        SessionLeasePolicy policy = new SessionLeasePolicy();

        SessionLeaseRecord record = service.createOrRenewLease(
                controlRoot, "proj-1", "codex", "conn-123", "worker-1", "sess-1", policy
        );

        assertEquals("conn-123", record.connectionInstanceId());
        assertEquals(SessionLeaseState.ACTIVE, record.leaseState());

        // Verify lease file is stored outside control checkout
        Path leasesDir = SessionLeaseStore.resolveLeasesDirectory(controlRoot);
        Path leaseFile = leasesDir.resolve("conn-123.json");
        assertTrue(Files.exists(leaseFile));
        assertFalse(leaseFile.startsWith(controlRoot));

        // Mark closed cleanly
        service.markClosedCleanly(controlRoot, "conn-123");

        SessionLeaseStore store = new SessionLeaseStore();
        Optional<SessionLeaseRecord> closed = store.load(controlRoot, "conn-123");
        assertTrue(closed.isPresent());
        assertEquals(SessionLeaseState.CLOSED_CLEANLY,
                closed.get()
                        .leaseState());
    }
}
