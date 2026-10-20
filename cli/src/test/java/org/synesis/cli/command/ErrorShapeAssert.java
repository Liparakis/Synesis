package org.synesis.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

/**
 * Asserts the error shape (ADR-0103, decision 3): {@code ERROR=<code>}, a {@code MESSAGE=} and a {@code FIX=} that say
 * something, in that order, followed by exactly the legacy lines a command keeps printing.
 */
public final class ErrorShapeAssert {

    private ErrorShapeAssert() {
    }

    /**
     * @param lines  the lines of one stream, from the first {@code ERROR=}
     * @param code   expected error code
     * @param legacy the lines that follow the shape, such as {@code REASON=...}
     */
    public static void assertShaped(List<String> lines, String code, String... legacy) {
        assertEquals(3 + legacy.length, lines.size(), lines::toString);
        assertEquals("ERROR=" + code, lines.get(0), lines::toString);
        assertShape(lines.subList(1, 3));
        for (int i = 0; i < legacy.length; i++) {
            assertEquals(legacy[i], lines.get(3 + i), lines::toString);
        }
    }

    /**
     * @param lines the {@code MESSAGE=} and {@code FIX=} lines
     */
    public static void assertShape(List<String> lines) {
        assertTrue(lines.get(0).startsWith("MESSAGE=") && lines.get(0).length() > "MESSAGE=".length() + 5, lines::toString);
        assertTrue(lines.get(1).startsWith("FIX=") && lines.get(1).length() > "FIX=".length() + 5, lines::toString);
        assertFalse(lines.get(0).contains("The command could not complete."), "a refusal the command knows is explained, not generic: " + lines);
    }
}
