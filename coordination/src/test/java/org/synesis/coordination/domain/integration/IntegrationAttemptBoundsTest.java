package org.synesis.coordination.domain.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.synesis.coordination.WireGolden.id;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Bounds of the integration-attempt payload that a hostile or damaged event could otherwise exceed.
 */
class IntegrationAttemptBoundsTest {

    private static final int SNAPSHOT_COUNT_OFFSET = 4 + 4 + 4 + 1 + 16;

    @Test
    void snapshotCountIsBoundedBeforeAnythingIsAllocated() {
        byte[] encoded = new IntegrationAttemptPayload("a", id(1), List.of("s"), "h", "c", "started", "").encode();
        ByteBuffer.wrap(encoded).putInt(SNAPSHOT_COUNT_OFFSET, Integer.MAX_VALUE);

        CoordinationException rejected = assertThrows(CoordinationException.class,
                () -> IntegrationAttemptPayload.decode(encoded));

        assertEquals("Invalid snapshot count: " + Integer.MAX_VALUE, rejected.getMessage());
    }

    @Test
    void oversizedTextIsAnArgumentErrorAtConstruction() {
        String tooLong = "x".repeat(64 * 1024 + 1);

        assertThrows(CoordinationArgumentException.class,
                () -> new IntegrationAttemptPayload("a", id(1), List.of(), "h", "c", "blocked", tooLong));
    }
}
