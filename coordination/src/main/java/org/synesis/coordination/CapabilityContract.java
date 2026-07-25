package org.synesis.coordination;

import java.util.List;
import java.util.Objects;

/**
 * Bounded immutable specification contract for a requested capability.
 *
 * <p>Enforces strict size limits:
 * <ul>
 *   <li>Inputs and Output: 1 to 2,000 characters each</li>
 *   <li>Required behavior: 0 to 16 items, max 1,000 characters per item</li>
 *   <li>Acceptance tests: 0 to 16 items, max 1,000 characters per item</li>
 *   <li>Total payload bound: max 32 KB</li>
 * </ul>
 *
 * @param inputs           input parameter specifications
 * @param output           output type and semantics
 * @param requiredBehavior required operational behaviors
 * @param acceptanceTests  acceptance test criteria
 * @since 1.0
 */
public record CapabilityContract(
        String inputs,
        String output,
        List<String> requiredBehavior,
        List<String> acceptanceTests
) {

    private static final int MAX_FIELD_LENGTH = 2000;
    private static final int MAX_LIST_ITEMS = 16;
    private static final int MAX_ITEM_LENGTH = 1000;
    private static final int MAX_TOTAL_BYTES = 32 * 1024;

    /**
     * Compact constructor enforcing strict bounds and immutability.
     *
     * @param inputs           input parameter specifications
     * @param output           output type and semantics
     * @param requiredBehavior required operational behaviors
     * @param acceptanceTests  acceptance test criteria
     * @throws IllegalArgumentException if any field violates contract bounds
     */
    public CapabilityContract {
        Objects.requireNonNull(inputs, "inputs cannot be null");
        Objects.requireNonNull(output, "output cannot be null");
        inputs = inputs.trim();
        output = output.trim();

        if (inputs.isEmpty() || inputs.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("inputs must be between 1 and " + MAX_FIELD_LENGTH + " characters");
        }
        if (output.isEmpty() || output.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException("output must be between 1 and " + MAX_FIELD_LENGTH + " characters");
        }

        requiredBehavior = requiredBehavior == null ? List.of() : List.copyOf(requiredBehavior);
        acceptanceTests = acceptanceTests == null ? List.of() : List.copyOf(acceptanceTests);

        if (requiredBehavior.size() > MAX_LIST_ITEMS) {
            throw new IllegalArgumentException("requiredBehavior exceeds maximum item limit of " + MAX_LIST_ITEMS);
        }
        for (String item : requiredBehavior) {
            if (item == null || item.isBlank() || item.length() > MAX_ITEM_LENGTH) {
                throw new IllegalArgumentException("requiredBehavior items must be 1 to " + MAX_ITEM_LENGTH + " characters");
            }
        }

        if (acceptanceTests.size() > MAX_LIST_ITEMS) {
            throw new IllegalArgumentException("acceptanceTests exceeds maximum item limit of " + MAX_LIST_ITEMS);
        }
        for (String item : acceptanceTests) {
            if (item == null || item.isBlank() || item.length() > MAX_ITEM_LENGTH) {
                throw new IllegalArgumentException("acceptanceTests items must be 1 to " + MAX_ITEM_LENGTH + " characters");
            }
        }

        int totalLen = inputs.length() + output.length();
        for (String s : requiredBehavior) totalLen += s.length();
        for (String s : acceptanceTests) totalLen += s.length();

        if (totalLen > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("CapabilityContract exceeds 32 KB total payload bound");
        }
    }

    /**
     * Compares structural equivalence of contracts, ignoring whitespace variations in text fields.
     *
     * @param other target contract
     * @return {@code true} if structurally equivalent
     */
    public boolean isEquivalent(CapabilityContract other) {
        if (other == null) {
            return false;
        }
        return inputs.equals(other.inputs)
                && output.equals(other.output)
                && requiredBehavior.equals(other.requiredBehavior)
                && acceptanceTests.equals(other.acceptanceTests);
    }
}
