package org.synesis.workspace.application.agent.completion;

import java.util.Locale;
import java.util.Objects;

/**
 * Explicit terminal outcome accepted by {@code finish_lane}.
 */
public enum CompletionOutcome {
    /**
     * Publish the normal immutable snapshot and integration candidate.
     */
    SNAPSHOT("snapshot"),
    /**
     * Complete a declared clean, no-mutation intent.
     */
    NO_CHANGE("no_change");

    private final String wireValue;

    CompletionOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * Parses a protocol completion outcome.
     *
     * @param value wire value
     * @return completion outcome
     * @throws IllegalArgumentException if the value is not recognized
     */
    public static CompletionOutcome fromWire(String value) {
        Objects.requireNonNull(value, "completion outcome");
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "snapshot" -> SNAPSHOT;
            case "no_change" -> NO_CHANGE;
            default -> throw new IllegalArgumentException("unknown completion outcome: " + value);
        };
    }

    /**
     * Returns the stable protocol representation.
     *
     * @return wire value
     */
    @SuppressWarnings("unused")
    public String wireValue() {
        return wireValue;
    }
}
