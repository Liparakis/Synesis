package org.synesis.coordination.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;

/**
 * Verifies that a dependency counts as satisfied by its owner only when the record says the owner's work was integrated.
 */
final class PeerDependencyPolicyTest {

    @Test
    void aRecordClosedAfterTheOwnerWasIntegratedSatisfiesItsDependencyAndOneClosedWithoutWorkDoesNot() {
        CapabilityRequestRecord integrated = closed("catalog.lookup", CapabilityRequestRecord.OWNER_FINISHED_WITHOUT_ANSWER);
        CapabilityRequestRecord noWork = closed("catalog.search", CapabilityRequestRecord.OWNER_LANE_ENDED_WITHOUT_WORK);
        CapabilityRequestRecord replaced = closed("catalog.other", "Replaced by a newer request");

        assertEquals(Set.of("catalog.lookup"), PeerDependencyPolicy.satisfiedByIntegratedOwner(List.of(integrated, noWork, replaced)));
        assertEquals(Set.of(), PeerDependencyPolicy.satisfiedByIntegratedOwner(List.of(noWork)));
    }

    @Test
    void bothReasonsMeanTheOwnersLaneEndedAndNothingElseDoes() {
        assertTrue(closed("a", CapabilityRequestRecord.OWNER_FINISHED_WITHOUT_ANSWER).ownerLaneEnded());
        assertTrue(closed("a", CapabilityRequestRecord.OWNER_LANE_ENDED_WITHOUT_WORK).ownerLaneEnded());
        assertFalse(closed("a", CapabilityRequestRecord.OWNER_LANE_ENDED_WITHOUT_WORK).ownerFinishedWithoutAnswer());
        assertFalse(closed("a", "Replaced by a newer request").ownerLaneEnded());
        assertFalse(closed("a", null).ownerLaneEnded());
    }

    private static CapabilityRequestRecord closed(String capability, String reason) {
        return new CapabilityRequestRecord(CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF"), capability,
                "node-requester", "supervisor-requester", "worker-requester", "node-owner", "supervisor-owner", "worker-owner",
                UUID.nameUUIDFromBytes("lineage".getBytes(StandardCharsets.UTF_8)),
                new CapabilityContract("UUID id", "Optional<P>", List.of("behavior"), List.of("test")),
                CapabilityLifecycleState.SUPERSEDED, reason, 1_000L, 2_000L, 3L);
    }
}
