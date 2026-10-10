package org.synesis.coordination.domain.capability;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Bounded immutable API contract negotiated before a dependent lane builds against a capability.
 *
 * <p>Fields that do not apply must still be stated explicitly, for example as {@code "none"};
 * this keeps acceptance and implementation decisions tied to one complete contract revision.
 *
 * @param purpose          purpose of the capability
 * @param inputs           input parameter names, types, and constraints
 * @param output           output type and semantics
 * @param requiredBehavior required operational behaviors
 * @param errorSemantics   failure cases and their observable results
 * @param sideEffects      externally visible effects of calls
 * @param invariants       conditions that must remain true
 * @param compatibility   compatibility and evolution requirements
 * @param performance     latency or resource requirements
 * @param concurrency     concurrency and ordering guarantees
 * @param acceptanceTests acceptance criteria for the published implementation
 * @since 1.0
 */
public record CapabilityContract(String purpose,
                                 String inputs,
                                 String output,
                                 List<String> requiredBehavior,
                                 String errorSemantics,
                                 String sideEffects,
                                 String invariants,
                                 String compatibility,
                                 String performance,
                                 String concurrency,
                                 List<String> acceptanceTests) {

    static final int MAX_ENCODED_BYTES = 32 * 1024;
    private static final int MAX_FIELD_LENGTH = 2000;
    private static final int MAX_FIELD_BYTES = 8 * 1024;
    private static final int MAX_LIST_ITEMS = 16;
    private static final int MAX_ITEM_LENGTH = 1000;
    private static final int MAX_ITEM_BYTES = 2 * 1024;

    /**
     * Enforces explicit non-empty terms, bounded UTF-8 payload size, and immutable list values.
     */
    public CapabilityContract {
        purpose = boundedText(purpose, "purpose");
        inputs = boundedText(inputs, "inputs");
        output = boundedText(output, "output");
        errorSemantics = boundedText(errorSemantics, "errorSemantics");
        sideEffects = boundedText(sideEffects, "sideEffects");
        invariants = boundedText(invariants, "invariants");
        compatibility = boundedText(compatibility, "compatibility");
        performance = boundedText(performance, "performance");
        concurrency = boundedText(concurrency, "concurrency");
        requiredBehavior = boundedList(requiredBehavior, "requiredBehavior");
        acceptanceTests = boundedList(acceptanceTests, "acceptanceTests");

        int totalBytes = utf8Length(purpose) + utf8Length(inputs) + utf8Length(output) + utf8Length(errorSemantics)
                + utf8Length(sideEffects) + utf8Length(invariants) + utf8Length(compatibility)
                + utf8Length(performance) + utf8Length(concurrency);
        totalBytes += requiredBehavior.stream().mapToInt(CapabilityContract::utf8Length).sum();
        totalBytes += acceptanceTests.stream().mapToInt(CapabilityContract::utf8Length).sum();
        if (totalBytes > MAX_ENCODED_BYTES) {
            throw new CoordinationArgumentException("CapabilityContract exceeds 32 KB total payload bound");
        }
    }

    /**
     * Retains the original constructor for local callers and historical fixtures.
     * New MCP requests use the complete constructor and explicitly fill every term.
     *
     * @param inputs API inputs
     * @param output API output
     * @param requiredBehavior observable behavior required of the capability
     * @param acceptanceTests acceptance checks for the capability
     */
    public CapabilityContract(String inputs, String output, List<String> requiredBehavior, List<String> acceptanceTests) {
        this("unspecified",
                inputs,
                output,
                requiredBehavior,
                "unspecified",
                "unspecified",
                "unspecified",
                "unspecified",
                "unspecified",
                "unspecified",
                acceptanceTests);
    }

    /**
     * Compares all negotiated API terms.
     *
     * @param other candidate contract
     * @return {@code true} only when every term is equivalent
     */
    public boolean isEquivalent(CapabilityContract other) {
        if (other == null) {
            return false;
        }
        return purpose.equals(other.purpose)
                && inputs.equals(other.inputs)
                && output.equals(other.output)
                && requiredBehavior.equals(other.requiredBehavior)
                && errorSemantics.equals(other.errorSemantics)
                && sideEffects.equals(other.sideEffects)
                && invariants.equals(other.invariants)
                && compatibility.equals(other.compatibility)
                && performance.equals(other.performance)
                && concurrency.equals(other.concurrency)
                && acceptanceTests.equals(other.acceptanceTests);
    }

    private static String boundedText(String value, String field) {
        CoordinationNullException.requireNonNull(value, field + " cannot be null");
        String normalized = value.trim();
        int bytes = utf8Length(normalized);
        if (normalized.isEmpty() || normalized.length() > MAX_FIELD_LENGTH || bytes > MAX_FIELD_BYTES) {
            throw new CoordinationArgumentException(field + " must be 1 to " + MAX_FIELD_LENGTH + " characters and at most " + MAX_FIELD_BYTES + " UTF-8 bytes");
        }
        return normalized;
    }

    private static List<String> boundedList(List<String> values, String field) {
        List<String> copy = values == null ? List.of() : List.copyOf(values);
        if (copy.size() > MAX_LIST_ITEMS) {
            throw new CoordinationArgumentException(field + " exceeds maximum item limit of " + MAX_LIST_ITEMS);
        }
        for (String value : copy) {
            if (value == null || value.isBlank() || value.length() > MAX_ITEM_LENGTH || utf8Length(value) > MAX_ITEM_BYTES) {
                throw new CoordinationArgumentException(field + " items must be 1 to " + MAX_ITEM_LENGTH + " characters and at most " + MAX_ITEM_BYTES + " UTF-8 bytes");
            }
        }
        return copy;
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
