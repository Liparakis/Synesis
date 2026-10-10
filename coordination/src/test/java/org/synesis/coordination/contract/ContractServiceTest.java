package org.synesis.coordination.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.synesis.coordination.application.ContractService;
import org.synesis.coordination.domain.contract.ContractDependency;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies exact contract revision and dependency invalidation semantics.
 */
final class ContractServiceTest {

    @Test
    void supersessionMarksConsumersReplanRequiredAndRejectsStaleBinding(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID(), contractId = UUID.randomUUID(), intentId = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        ContractService service = new ContractService(new PredictionEventStore(temp, project), identity);
        ContractRecord first = service.publish(contractId,
                "agt-owner",
                "TaskTracker API v1",
                List.of("src/task_tracker.py"));
        service.bind(intentId, "agt-consumer", contractId, first.revision());
        ContractRecord second = service.publish(contractId,
                "agt-owner",
                "TaskTracker API v2",
                List.of("src/task_tracker.py"));
        assertEquals(2, second.revision());
        assertEquals(ContractDependency.State.REPLAN_REQUIRED, service.dependencies().getFirst().state());
        assertThrows(IOException.class, () -> service.bind(UUID.randomUUID(), "agt-consumer", contractId, 1));
        PredictionEventStore replay = new PredictionEventStore(temp, project);
        assertEquals(2, replay.contractProjection().contract(contractId).revision());
        assertEquals(ContractDependency.State.REPLAN_REQUIRED,
                replay.contractProjection().dependencies().getFirst().state());
    }

    @Test
    void revisionWhoseBodyExceedsTheEncodedBoundLeavesThePriorRevisionActive(@TempDir Path temp) throws Exception {
        UUID project = UUID.randomUUID(), contractId = UUID.randomUUID();
        ContractService service = new ContractService(new PredictionEventStore(temp, project), NodeIdentity.generate());
        service.publish(contractId, "agt-owner", "TaskTracker API v1", List.of("src/task_tracker.py"));
        // 20,000 characters, 40,000 UTF-8 bytes: inside a character count of 32,768, outside the encoded bound.
        String oversized = "\u00e9".repeat(20_000);

        assertThrows(CoordinationArgumentException.class,
                () -> service.publish(contractId, "agt-owner", oversized, List.of("src/task_tracker.py")));

        ContractRecord current = new PredictionEventStore(temp, project).contractProjection().contract(contractId);
        assertEquals(1, current.revision());
        assertEquals(ContractRecord.Status.ACTIVE, current.status());
    }
}
