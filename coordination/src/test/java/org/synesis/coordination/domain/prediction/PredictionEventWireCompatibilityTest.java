package org.synesis.coordination.domain.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.link.identity.NodeIdentity;

/** Verifies stable event wire codes across the SYN-020 event insertion. */
class PredictionEventWireCompatibilityTest {
    @Test
    void dependencyCodeRemainsHistoricalAndCollaborationCodesAreDistinct() throws Exception {
        assertEquals(42, PredictionEventType.DEPENDENCY_INVALIDATED.wireCode());
        assertEquals(43, PredictionEventType.WORK_INTENT_ANNOUNCED.wireCode());
        assertEquals(44, PredictionEventType.WORK_INTENT_RELEASED.wireCode());

        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        PredictionEvent dependency = PredictionEvent.create(project, UUID.randomUUID(), 1,
                PredictionEventType.DEPENDENCY_INVALIDATED, identity.nodeId(), new byte[0],
                new byte[32], identity, 1L);
        assertEquals(PredictionEventType.DEPENDENCY_INVALIDATED, PredictionEvent.decode(dependency.encoded()).type());
    }
}
