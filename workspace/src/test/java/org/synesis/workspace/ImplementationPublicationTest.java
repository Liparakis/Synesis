package org.synesis.workspace.application;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.CapabilityContract;
import org.synesis.coordination.domain.CapabilityRequestHandle;
import org.synesis.coordination.domain.ImplementationRevisionRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for implementation publication and snapshot idempotency helper.
 */
class ImplementationPublicationTest {

    @Test
    void isIdempotentPublicationReturnsTrueForIdenticalCommitSha() {
        ImplementationSnapshotService service = new ImplementationSnapshotService();
        CapabilityRequestHandle handle = CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF");

        ImplementationRevisionRecord record = new ImplementationRevisionRecord(
                handle, 1, "base123", "commit456",
                List.of("file.txt"), "summary", 1000L);

        assertTrue(service.isIdempotentPublication(record, "commit456"));
        assertFalse(service.isIdempotentPublication(record, "commit789"));
    }
}
