package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.synesis.coordination.WireGolden.id;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Bounds of the task payloads that a hostile or damaged event could otherwise exceed.
 */
class TaskPayloadBoundsTest {

    private static final String TOO_LONG = "x".repeat(64 * 1024 + 1);
    private static final int CHANGED_PATH_COUNT_OFFSET = 4 + 4 + 16 + 7 * (4 + 1);

    private static SnapshotProvenance provenance() {
        return new SnapshotProvenance(id(2), id(3), id(4), "p", "b", 1, List.of(), List.of(), List.of(), "r", "i", "d");
    }

    private static TaskSnapshotPayload snapshot(List<String> changedPaths, String summary) {
        return new TaskSnapshotPayload(id(1),
                "a",
                "a",
                "a",
                "a",
                "a",
                "a",
                "a",
                changedPaths,
                List.of(),
                summary,
                provenance(),
                false);
    }

    @Test
    void changedPathCountIsBoundedBeforeAnythingIsAllocated() {
        byte[] encoded = snapshot(List.of("p"), "s").encode();
        ByteBuffer.wrap(encoded).putInt(CHANGED_PATH_COUNT_OFFSET, Integer.MAX_VALUE);

        CoordinationException rejected = assertThrows(CoordinationException.class,
                () -> TaskSnapshotPayload.decode(encoded));

        assertEquals("list bound", rejected.getMessage());
    }

    @Test
    void snapshotListLargerThanItsDecoderAcceptsIsAnArgumentError() {
        assertThrows(CoordinationArgumentException.class,
                () -> snapshot(java.util.Collections.nCopies(129, "p"), "s"));
    }

    @Test
    void oversizedTextIsAnArgumentErrorAtConstruction() {
        assertThrows(CoordinationArgumentException.class, () -> snapshot(List.of("p"), TOO_LONG));
        assertThrows(CoordinationArgumentException.class,
                () -> new CompletionPreparedPayload(id(1), "c", id(2), 1, "b", "r", TOO_LONG, List.of()));
    }
}
