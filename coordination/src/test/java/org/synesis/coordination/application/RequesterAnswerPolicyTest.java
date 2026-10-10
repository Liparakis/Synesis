package org.synesis.coordination.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.synesis.coordination.domain.capability.CapabilityContract;
import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestHandle;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;

/**
 * Verifies how long an owner waits for an answer to its proposal and which requests count as
 * settled by an owner that finished without one.
 */
final class RequesterAnswerPolicyTest {

    private static final long PROPOSED_AT = 1_000_000L;

    private String configured;

    @BeforeEach
    void rememberConfiguration() {
        configured = System.getProperty(RequesterAnswerPolicy.GRACE_PROPERTY);
        System.clearProperty(RequesterAnswerPolicy.GRACE_PROPERTY);
    }

    @AfterEach
    void restoreConfiguration() {
        if (configured == null) {
            System.clearProperty(RequesterAnswerPolicy.GRACE_PROPERTY);
        } else {
            System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, configured);
        }
    }

    @Test
    void ownerWaitsTwoMinutesUnlessAWholeNumberOfSecondsIsConfigured() {
        assertEquals(Duration.ofSeconds(120), RequesterAnswerPolicy.grace());

        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "45");
        assertEquals(Duration.ofSeconds(45), RequesterAnswerPolicy.grace());
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "0");
        assertEquals(Duration.ZERO, RequesterAnswerPolicy.grace(), "zero means the owner never waits");

        for (String unusable : List.of("-1", "soon", "1.5", "99999999999")) {
            System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, unusable);
            assertEquals(Duration.ofSeconds(120), RequesterAnswerPolicy.grace(), "an unusable value must not remove or stretch the bound: " + unusable);
        }
    }

    @Test
    void ownerWaitsOnlyForAnUnansweredProposalAndOnlyUntilItsDeadline() {
        System.setProperty(RequesterAnswerPolicy.GRACE_PROPERTY, "60");
        CapabilityRequestRecord proposal = request(CapabilityLifecycleState.REVISION_REQUESTED, "Null ids must be rejected");

        assertTrue(RequesterAnswerPolicy.awaitsRequesterAnswer(proposal));
        assertEquals(PROPOSED_AT + 60_000L, RequesterAnswerPolicy.answerDeadlineEpochMillis(proposal));
        assertTrue(RequesterAnswerPolicy.ownerMustWait(proposal, PROPOSED_AT + 59_999L));
        assertFalse(RequesterAnswerPolicy.ownerMustWait(proposal, PROPOSED_AT + 60_000L), "at the deadline the owner is free");

        for (CapabilityLifecycleState other : List.of(CapabilityLifecycleState.AWAITING_OWNER, CapabilityLifecycleState.ACCEPTED, CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE,
                CapabilityLifecycleState.VALIDATED, CapabilityLifecycleState.SUPERSEDED)) {
            assertFalse(RequesterAnswerPolicy.ownerMustWait(request(other, null), PROPOSED_AT), "this wait is only for a proposal the requester has not answered: " + other);
        }
    }

    @Test
    void onlyARequestSupersededForThatReasonCountsAsSettledByTheOwnerFinishing() {
        assertTrue(RequesterAnswerPolicy.ownerFinishedWithoutAnswer(request(CapabilityLifecycleState.SUPERSEDED, RequesterAnswerPolicy.OWNER_FINISHED_WITHOUT_ANSWER)));
        assertFalse(RequesterAnswerPolicy.ownerFinishedWithoutAnswer(request(CapabilityLifecycleState.SUPERSEDED, "Replaced by a newer request")));
        assertFalse(RequesterAnswerPolicy.ownerFinishedWithoutAnswer(request(CapabilityLifecycleState.SUPERSEDED, null)));
        assertFalse(RequesterAnswerPolicy.ownerFinishedWithoutAnswer(request(CapabilityLifecycleState.CANCELLED, RequesterAnswerPolicy.OWNER_FINISHED_WITHOUT_ANSWER)),
                "a cancelled or rejected request keeps its own meaning whatever its reason says");
    }

    private static CapabilityRequestRecord request(CapabilityLifecycleState state, String reason) {
        return new CapabilityRequestRecord(CapabilityRequestHandle.parse("req_TEST1234567890ABCDEF"),
                "catalog.product-query",
                "node-requester",
                "supervisor-requester",
                "worker-requester",
                "node-owner",
                "supervisor-owner",
                "worker-owner",
                UUID.nameUUIDFromBytes("lineage".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new CapabilityContract("UUID id", "Optional<P>", List.of("behavior"), List.of("test")),
                state,
                reason,
                PROPOSED_AT - 5_000L,
                PROPOSED_AT,
                7L);
    }
}
