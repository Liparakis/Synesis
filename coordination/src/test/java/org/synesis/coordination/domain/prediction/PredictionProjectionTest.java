package org.synesis.coordination.domain.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies that events owned by other projections never enter the prediction lifecycle.
 */
final class PredictionProjectionTest {

    private static PredictionEvent event(UUID project, UUID prediction, long sequence, PredictionEventType type)
            throws Exception {
        NodeIdentity identity = NodeIdentity.generate();
        return PredictionEvent.create(project,
                prediction,
                sequence,
                type,
                identity.nodeId(),
                new byte[0],
                new byte[32],
                identity,
                sequence);
    }

    @Test
    void eventOutsideThePredictionLifecycleLeavesNoStateAndSnapshotStillWorks() throws Exception {
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        PredictionProjection projection = new PredictionProjection();

        projection.apply(event(project, prediction, 1, PredictionEventType.WORK_INTENT_ANNOUNCED));

        assertTrue(projection.snapshot().isEmpty());
        assertTrue(projection.state(prediction).isEmpty());
        projection.apply(event(project, prediction, 2, PredictionEventType.PREDICTION_CREATED));
        projection.apply(event(project, prediction, 3, PredictionEventType.PARTICIPANT_REVOKED));
        assertEquals(PredictionState.PROPOSED, projection.snapshot().get(prediction));
    }

    @Test
    void validatingAnEventOutsideThePredictionLifecycleYieldsNoState() throws Exception {
        UUID project = UUID.randomUUID();
        UUID prediction = UUID.randomUUID();
        PredictionProjection projection = new PredictionProjection();
        projection.apply(event(project, prediction, 1, PredictionEventType.PREDICTION_CREATED));

        assertNull(projection.validate(event(project, prediction, 2, PredictionEventType.TASK_CREATED)));
        assertEquals(PredictionState.PROPOSED, projection.state(prediction).orElseThrow());
    }
}
