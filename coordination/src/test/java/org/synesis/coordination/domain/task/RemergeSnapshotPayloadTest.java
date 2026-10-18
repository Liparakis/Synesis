package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationArgumentException;

/**
 * Wire record of a re-merge snapshot (ADR-0098 step 5, designer items 13 to 15).
 */
final class RemergeSnapshotPayloadTest {

    private static TaskSnapshotPayload inner(String snapshotId) {
        SnapshotProvenance provenance = new SnapshotProvenance(UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "agt-owner",
                "session-owner",
                1,
                List.of(),
                List.of(),
                List.of("PATH:src/a.py"),
                "refs/synesis/snapshots/" + snapshotId,
                "integrity",
                "artifacts");
        return new TaskSnapshotPayload(UUID.randomUUID(),
                snapshotId,
                "node",
                "supervisor",
                "worker",
                "session-owner",
                "base",
                "commit-" + snapshotId,
                List.of("src/a.py"),
                List.of(),
                "remerged",
                provenance,
                true);
    }

    private static RemergeSnapshotPayload payload() {
        return new RemergeSnapshotPayload("remerge-1", "snap_original", "other-head", "own-head", "result",
                inner("snap_remerge"));
    }

    @Test
    void roundTripsThroughItsEncoding() throws Exception {
        RemergeSnapshotPayload original = payload();
        byte[] encoded = original.encode();

        RemergeSnapshotPayload decoded = RemergeSnapshotPayload.decode(encoded);

        assertEquals(original, decoded);
        assertEquals("snap_remerge", decoded.snapshot().snapshotId());
        assertArrayEquals(encoded, decoded.encode());
        assertEquals(0x53524D31, ByteBuffer.wrap(encoded).getInt());
    }

    @Test
    void blankNullOrOverlongTextFieldsAreRefused() {
        TaskSnapshotPayload snapshot = inner("snap_remerge");
        String tooLong = "x".repeat(129);
        String maximal = "x".repeat(128);

        assertThrows(CoordinationArgumentException.class,
                () -> new RemergeSnapshotPayload(" ", "o", "h", "h", "r", snapshot));
        assertThrows(CoordinationArgumentException.class,
                () -> new RemergeSnapshotPayload("id", "", "h", "h", "r", snapshot));
        assertThrows(CoordinationArgumentException.class,
                () -> new RemergeSnapshotPayload("id", "o", null, "h", "r", snapshot));
        assertThrows(CoordinationArgumentException.class,
                () -> new RemergeSnapshotPayload("id", "o", "h", tooLong, "r", snapshot));
        assertThrows(CoordinationArgumentException.class,
                () -> new RemergeSnapshotPayload("id", "o", "h", "h", tooLong, snapshot));
        assertEquals(maximal, new RemergeSnapshotPayload(maximal, "o", "h", "h", "r", snapshot).remergeId());
    }

    @Test
    void theInnerSnapshotMustBeNewRelativeToTheOriginal() {
        TaskSnapshotPayload snapshot = inner("snap_same");

        assertThrows(CoordinationArgumentException.class,
                () -> new RemergeSnapshotPayload("id", "snap_same", "h", "h", "r", snapshot));
    }

    @Test
    void trailingBytesBadMagicAndTruncationAreMalformed() {
        byte[] encoded = payload().encode();

        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IOException.class, () -> RemergeSnapshotPayload.decode(trailing));

        byte[] badMagic = encoded.clone();
        badMagic[0] ^= 0x01;
        assertThrows(IOException.class, () -> RemergeSnapshotPayload.decode(badMagic));

        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 3);
        assertThrows(IOException.class, () -> RemergeSnapshotPayload.decode(truncated));
        assertThrows(IOException.class, () -> RemergeSnapshotPayload.decode(new byte[0]));
    }
}
