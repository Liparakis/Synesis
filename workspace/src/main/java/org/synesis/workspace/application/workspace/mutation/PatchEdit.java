package org.synesis.workspace.application.workspace.mutation;

import java.util.Objects;

/**
 * Single text edit instruction for modification mode.
 *
 * @param find                text to find
 * @param replace             replacement text
 * @param expectedOccurrences expected exact count of occurrences
 */
public record PatchEdit(String find, String replace, int expectedOccurrences) {

    /**
     * Validates edit parameters.
     */
    public PatchEdit {
        Objects.requireNonNull(find, "find");
        Objects.requireNonNull(replace, "replace");
        if (expectedOccurrences < 1) {
            throw new IllegalArgumentException("expectedOccurrences must be at least 1");
        }
    }
}
