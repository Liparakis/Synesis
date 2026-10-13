package org.synesis.coordination.domain.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.synesis.coordination.WireGolden.id;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;

/**
 * Behaviour of the refusal payload beyond its pinned bytes: round trip, refused encodings and the bound on output.
 */
class ValidationRefusalPayloadTest {

    private static final int BOUND = 8 * 1024;

    private static ValidationRefusalPayload payload(String output) {
        return new ValidationRefusalPayload("att_1", "snap_1", id(1), id(2), id(3), 2, id(4), 2, "agt-owner",
                "commit0123", "[\"git\"]", ".", "non_zero_exit", 3, output);
    }

    private static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void roundTripKeepsEveryField() throws Exception {
        ValidationRefusalPayload original = payload("FAIL: expected 1 but was 2\n");

        assertEquals(original, ValidationRefusalPayload.decode(original.encode()));
    }

    @Test
    void anotherVersionIsRefused() {
        byte[] encoded = payload("x").encode();
        encoded[7] = 2;

        assertThrows(CoordinationException.class, () -> ValidationRefusalPayload.decode(encoded));
    }

    @Test
    void trailingBytesAreRefused() {
        byte[] encoded = payload("x").encode();
        byte[] longer = Arrays.copyOf(encoded, encoded.length + 1);

        assertThrows(CoordinationException.class, () -> ValidationRefusalPayload.decode(longer));
    }

    @Test
    void outputBeyondTheBoundIsRefused() {
        assertThrows(CoordinationArgumentException.class, () -> payload("a".repeat(BOUND + 1)));
        assertDoesNotThrow(() -> payload("a".repeat(BOUND)));
    }

    @Test
    void tailKeepsTheEndOfLongOutputWithinTheBound() {
        String tail = ValidationRefusalPayload.tail("a".repeat(20_000) + "THE FAILURE", "");

        assertTrue(utf8(tail) <= BOUND);
        assertTrue(tail.endsWith("THE FAILURE"));
        assertDoesNotThrow(() -> payload(tail));
    }

    @Test
    void tailNeverSplitsACharacter() {
        for (String character : new String[] {"é", "€", "😀"}) {
            String tail = ValidationRefusalPayload.tail(character.repeat(10_000), "");

            assertTrue(utf8(tail) <= BOUND, character);
            assertFalse(tail.contains("�"), character);
            assertEquals(tail.length(), tail.codePoints().filter(cp -> new String(Character.toChars(cp)).equals(character)).count() * character.length(), character);
        }
    }

    @Test
    void tailPutsErrorOutputAfterStandardOutput() {
        assertEquals("out\nerr", ValidationRefusalPayload.tail("out", "err"));
        assertEquals("err", ValidationRefusalPayload.tail("", "err"));
        assertEquals("out", ValidationRefusalPayload.tail("out", ""));
        assertEquals("", ValidationRefusalPayload.tail("", ""));
    }

    @Test
    void theCommandAndTheWorkingDirectoryAreRequired() {
        for (String blank : new String[] {"", "  "}) {
            assertThrows(CoordinationArgumentException.class, () -> new ValidationRefusalPayload("att_1", "snap_1", id(1), id(2), id(3), 2, id(4), 2, "agt-owner",
                    "commit0123", blank, ".", "non_zero_exit", 3, "x"), "command " + blank.length());
            assertThrows(CoordinationArgumentException.class, () -> new ValidationRefusalPayload("att_1", "snap_1", id(1), id(2), id(3), 2, id(4), 2, "agt-owner",
                    "commit0123", "[\"git\"]", blank, "non_zero_exit", 3, "x"), "working directory " + blank.length());
        }
    }

    @Test
    void headKeepsTheStartWithinTheBoundWithoutSplittingACharacter() {
        assertEquals("[\"git\",\"status\"]", ValidationRefusalPayload.head("[\"git\",\"status\"]"));
        for (String character : new String[] {"é", "€", "😀"}) {
            String text = "a" + character.repeat(10_000);

            String head = ValidationRefusalPayload.head(text);

            assertTrue(utf8(head) <= BOUND, character);
            assertTrue(text.startsWith(head), character);
            assertFalse(head.contains("�"), character);
            assertTrue(utf8(text) - utf8(head) > 0 && utf8(head) > BOUND - 4, character);
            assertDoesNotThrow(() -> new ValidationRefusalPayload("att_1", "snap_1", id(1), id(2), id(3), 2, id(4), 2, "agt-owner",
                    "commit0123", head, ".", "non_zero_exit", 3, "x"));
        }
    }
}
