package org.synesis.coordination.domain.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Whether a request blocks is carried by its condition, so the wire format is unchanged and every
 * request written before the flag existed reads as blocking.
 */
final class OperatorRequestBlockingTest {

    private static final String LINEAGE = "00000000-0000-4000-8000-000000000001";

    @Test
    void aRequestAskedWithoutTheFlagBlocks() throws IOException {
        OperatorRequest asked = OperatorRequest.askedByLane("opr_1", LINEAGE, "Which license?", "participant a");

        assertEquals(OperatorRequest.LANE_ASKS_OPERATOR, asked.condition());
        assertTrue(OperatorRequest.decodeRaised(asked.encodeRaised()).blocking());
    }

    @Test
    void aNoteSurvivesTheWireAndDoesNotBlock() throws IOException {
        OperatorRequest note = OperatorRequest.askedByLane("opr_2", LINEAGE, "I kept X", "participant a", false);

        OperatorRequest read = OperatorRequest.decodeRaised(note.encodeRaised());

        assertEquals(OperatorRequest.LANE_NOTES_OPERATOR, read.condition());
        assertFalse(read.blocking());
    }

    @Test
    void synesisRequestsAndUnknownConditionsBlock() {
        assertTrue(OperatorRequest.raised("opr_3", OperatorRequest.CONTROL_CHECKOUT_FOREIGN_CHANGES, "s", "").blocking());
        assertTrue(new OperatorRequest("opr_4", "a_future_condition", "", "s", "", "", OperatorRequest.State.OPEN, "").blocking());
    }
}
