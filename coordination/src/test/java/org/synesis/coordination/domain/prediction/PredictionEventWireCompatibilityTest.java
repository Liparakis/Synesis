package org.synesis.coordination.domain.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.link.identity.NodeIdentity;

/**
 * Verifies stable event wire codes across the SYN-020 event insertion.
 */
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
        assertEquals(PredictionEventType.DEPENDENCY_INVALIDATED,
                PredictionEvent.decode(dependency.encoded())
                        .type());
    }

    @Test
    void currentIntentFormatRemainsAnIntentAfterDurableReplay() throws Exception {
        UUID project = UUID.randomUUID();
        NodeIdentity identity = NodeIdentity.generate();
        WorkIntent intent = new WorkIntent(UUID.randomUUID(), project, "agt-owner", "codex", UUID.randomUUID(),
                "implement", "tests", "base", List.of(ResourceSelector.pathExact("src/a.py")), 1,
                UUID.randomUUID(), UUID.randomUUID(), WorkIntent.Status.ANNOUNCED);
        PredictionEvent event = PredictionEvent.create(project, intent.intentId(), 1,
                PredictionEventType.WORK_INTENT_ANNOUNCED, identity.nodeId(), CollaborationCodec.encodeIntent(intent),
                new byte[32], identity, 1L);

        assertEquals(PredictionEventType.WORK_INTENT_ANNOUNCED,
                PredictionEvent.decode(event.encoded())
                        .type());
    }

    @Test
    void removedIntentFormatsRequireFreshInitialization() {
        for (int version = 1; version <= 6; version++) {
            int removedVersion = version;
            IOException failure = assertThrows(IOException.class,
                    () -> CollaborationCodec.decodeIntent(new byte[] {0x53, 0x49, 0x4e,
                            (byte) ('0' + removedVersion)}));
            assertTrue(failure.getMessage()
                    .contains("fresh initialization required"), failure.getMessage());
        }
    }

    @Test
    void knownDependenciesSurviveIntentEncodingAndReplay() throws Exception {
        UUID project = UUID.randomUUID();
        WorkIntent intent = new WorkIntent(UUID.randomUUID(), project, "agt-requester", "codex", UUID.randomUUID(),
                "build service", "service tests", "base", List.of(ResourceSelector.pathExact("src/service")), 1,
                UUID.randomUUID(), UUID.randomUUID(), WorkIntent.Status.ANNOUNCED,
                WorkIntent.Role.PRODUCER, List.of(),
                List.of("domain.persistence"));

        WorkIntent replayed = CollaborationCodec.decodeIntent(CollaborationCodec.encodeIntent(intent));

        assertEquals(List.of("domain.persistence"), replayed.knownDependencies());
        assertEquals(intent.intentId(), replayed.intentId());
    }
}
