package org.synesis.coordination.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.command.CoordinationCommand;
import org.synesis.coordination.domain.identity.TaskId;
import org.synesis.coordination.domain.prediction.PredictionEvent;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.CoordinationTask;
import org.synesis.coordination.domain.task.TaskClaim;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.link.identity.NodeIdentity;

/**
 * A service shares the project log with writers that append through their own freshly opened stores, as
 * {@link ProjectAppends} does, so it must decide and report on the durable log and not on what it appended itself.
 */
class CoordinationServiceSharedLogTest {

    private final Path root = tempRoot();
    private final UUID project = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final NodeIdentity requester;
    private final NodeIdentity owner;
    private final NodeIdentity coordinator;

    CoordinationServiceSharedLogTest() throws GeneralSecurityException {
        requester = NodeIdentity.generate();
        owner = NodeIdentity.generate();
        coordinator = NodeIdentity.generate();
    }

    private static Path tempRoot() {
        try {
            return Files.createTempDirectory("synesis-shared-log-");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private CoordinationCommand createTask() throws Exception {
        CoordinationTask task = new CoordinationTask(taskId, project, "shared", "workspace.shared", requester.nodeId(),
                "sup-a", "worker-a");
        return CoordinationCommand.create(UUID.randomUUID(), project, taskId, PredictionEventType.TASK_CREATED,
                requester.nodeId(), task.encoded(), requester);
    }

    private CoordinationCommand claimTask() throws Exception {
        TaskClaim claim = new TaskClaim(taskId, owner.nodeId(), "sup-b", "worker-b");
        return CoordinationCommand.create(UUID.randomUUID(), project, taskId, PredictionEventType.TASK_CLAIMED,
                owner.nodeId(), claim.encoded(), owner);
    }

    private PredictionEvent appendThroughAnotherStore(CoordinationCommand command) throws Exception {
        return new PredictionEventStore(root, project).append(command.predictionId(), command.type(),
                coordinator.nodeId(), command.encoded(), coordinator);
    }

    @Test
    void commandAppliedByAnotherWriterIsRecognizedAsDuplicate() throws Exception {
        CoordinationService service = new CoordinationService(new PredictionEventStore(root, project), coordinator);
        CoordinationCommand create = createTask();
        PredictionEvent applied = appendThroughAnotherStore(create);

        PredictionEvent again = service.submit(create);

        assertEquals(applied.eventId(), again.eventId());
        assertEquals(1L, service.headSequence());
        assertEquals(1, new PredictionEventStore(root, project).events().size());
    }

    @Test
    void stateAppendedByAnotherWriterIsVisibleToDecisionsAndReads() throws Exception {
        CoordinationService service = new CoordinationService(new PredictionEventStore(root, project), coordinator);
        appendThroughAnotherStore(createTask());

        assertEquals(1L, service.headSequence());
        assertEquals(1L, service.replayAfter(0).size());
        assertTrue(service.coordinationProjection().task(new TaskId(taskId)).isPresent());

        PredictionEvent claimed = service.submit(claimTask());

        assertEquals(2L, claimed.sequence());
        assertEquals(owner.nodeId(),
                service.coordinationProjection().task(new TaskId(taskId)).orElseThrow().ownerNodeId());
    }
}
