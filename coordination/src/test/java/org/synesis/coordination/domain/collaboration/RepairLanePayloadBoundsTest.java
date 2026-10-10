package org.synesis.coordination.domain.collaboration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.synesis.coordination.WireGolden.id;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationArgumentException;

/**
 * Text the repair payload encoder refuses is refused when the payload is built.
 */
class RepairLanePayloadBoundsTest {

    private static final String TOO_LONG = "x".repeat(64 * 1024 + 1);

    private static WorkIntent intent() {
        return new WorkIntent(id(1),
                id(2),
                "agt",
                "codex",
                id(3),
                "goal",
                "acceptance",
                "base",
                List.of(ResourceSelector.pathExact("src/a.py")),
                4,
                id(4),
                id(5),
                WorkIntent.Status.ANNOUNCED);
    }

    @Test
    void oversizedSnapshotAndControlHeadAreArgumentErrors() {
        assertThrows(CoordinationArgumentException.class,
                () -> new RepairLanePayload(id(1), intent(), TOO_LONG, "head", 3, 4));
        assertThrows(CoordinationArgumentException.class,
                () -> new RepairLanePayload(id(1), intent(), "snap", TOO_LONG, 3, 4));
    }
}
