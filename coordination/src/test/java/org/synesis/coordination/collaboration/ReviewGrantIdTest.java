package org.synesis.coordination.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.domain.collaboration.LaneGrant;

/**
 * Pins the persisted derivation of a review grant ID, which a retry after a lost append recomputes.
 */
final class ReviewGrantIdTest {

    @Test
    void reviewGrantIdIsDerivedFromTheRequestIdAndStaysStable() {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertEquals(UUID.fromString("6865daa2-8ab4-3f02-b435-fdcc0205c8c9"), LaneGrant.reviewGrantId(requestId));
    }
}
