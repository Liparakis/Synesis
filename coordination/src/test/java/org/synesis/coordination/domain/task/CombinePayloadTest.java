package org.synesis.coordination.domain.task;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationArgumentException;

/**
 * The signed payload of a combine: bounded fields, 1 to 128 snapshots, and a work group and task named by the combine.
 */
final class CombinePayloadTest {

    private static CombinePayload payload(String combineId, List<String> snapshots) {
        return new CombinePayload(combineId, "remerge-1", "other-head", "own-head", "base-commit", "theirs-commit",
                snapshots);
    }

    private static CombinePayload payload() {
        return payload("combine-1", List.of("snap_a", "snap_b"));
    }

    @Test
    void roundTripsAndEncodesDeterministically() throws Exception {
        CombinePayload original = payload();

        CombinePayload decoded = CombinePayload.decode(original.encode());

        assertEquals(original, decoded);
        assertArrayEquals(original.encode(), decoded.encode());
        assertEquals(List.of("snap_a", "snap_b"), decoded.snapshotIds());
    }

    @Test
    void aBlankOrOversizedFieldIsRefused() {
        assertThrows(CoordinationArgumentException.class, () -> payload(" ", List.of("snap_a")));
        assertThrows(CoordinationArgumentException.class, () -> payload("", List.of("snap_a")));
        assertThrows(CoordinationArgumentException.class, () -> payload(null, List.of("snap_a")));
        assertThrows(CoordinationArgumentException.class, () -> payload("x".repeat(129), List.of("snap_a")));
        assertThrows(CoordinationArgumentException.class, () -> payload("combine-1", List.of("")));
        assertThrows(CoordinationArgumentException.class, () -> payload("combine-1", List.of("y".repeat(129))));
        assertThrows(CoordinationArgumentException.class,
                () -> new CombinePayload("c", "r", "o", "w", " ", "t", List.of("s")));
        payload("x".repeat(128), List.of("y".repeat(128)));
    }

    @Test
    void zeroOrMoreThan128SnapshotsAreRefused() {
        assertThrows(CoordinationArgumentException.class, () -> payload("combine-1", List.of()));
        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index < 129; index++) {
            tooMany.add("snap_" + index);
        }
        assertThrows(CoordinationArgumentException.class, () -> payload("combine-1", tooMany));
        assertEquals(128, payload("combine-1", tooMany.subList(0, 128)).snapshotIds().size());
    }

    @Test
    void trailingBytesBadMagicAndTruncationAreRefusedAsIOException() {
        byte[] good = payload().encode();

        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        assertThrows(IOException.class, () -> CombinePayload.decode(trailing));

        byte[] badMagic = good.clone();
        badMagic[0] ^= 0x7f;
        assertThrows(IOException.class, () -> CombinePayload.decode(badMagic));

        for (int length = 0; length < good.length; length++) {
            byte[] truncated = Arrays.copyOf(good, length);
            assertThrows(IOException.class, () -> CombinePayload.decode(truncated), "length " + length);
        }
    }

    @Test
    void aWireCountOutOfBoundsOrAnOversizedWireFieldIsRefusedAsIOException() throws Exception {
        assertThrows(IOException.class, () -> CombinePayload.decode(wire("c".repeat(129), 1)));
        assertThrows(IOException.class, () -> CombinePayload.decode(wire("c", 0)));
        assertThrows(IOException.class, () -> CombinePayload.decode(wire("c", 129)));
        assertThrows(IOException.class, () -> CombinePayload.decode(wire("c", -1)));
        assertEquals("c", CombinePayload.decode(wire("c", 1)).combineId());
    }

    private static byte[] wire(String combineId, int count) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x53434231);
        for (String field : new String[] {combineId, "r", "o", "w", "b", "t"}) {
            byte[] utf8 = field.getBytes(StandardCharsets.UTF_8);
            out.writeInt(utf8.length);
            out.write(utf8);
        }
        out.writeInt(count);
        for (int index = 0; index < Math.max(count, 0) && index < 200; index++) {
            byte[] utf8 = ("s" + index).getBytes(StandardCharsets.UTF_8);
            out.writeInt(utf8.length);
            out.write(utf8);
        }
        return bytes.toByteArray();
    }

    @Test
    void theWorkGroupAndTheTaskAreNamedByTheCombine() {
        CombinePayload one = payload("combine-1", List.of("snap_a"));
        CombinePayload sameCombine = new CombinePayload("combine-1", "other-remerge", "x", "y", "z", "w",
                List.of("snap_z"));
        CombinePayload another = payload("combine-2", List.of("snap_a"));

        assertEquals(one.workGroupId(), sameCombine.workGroupId());
        assertEquals(one.taskId(), sameCombine.taskId());
        assertNotEquals(one.workGroupId(), one.taskId());
        assertNotEquals(one.workGroupId(), another.workGroupId());
        assertNotEquals(one.taskId(), another.taskId());
    }
}
