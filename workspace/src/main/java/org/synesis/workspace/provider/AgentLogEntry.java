package org.synesis.workspace.provider;

import java.util.Objects;

/**
 * One thing a headless agent did, as {@code synesis agents log} shows it: something it said, a tool it called, what
 * the tool answered, or a failure.
 *
 * <p>The text is what the provider's output stream held. It is not yet shortened or cleaned of secrets; whoever
 * shows it does that.</p>
 *
 * @param kind what the entry is
 * @param name the tool the entry belongs to, or empty when it names none
 * @param text the words said, the tool's arguments, or its answer
 */
public record AgentLogEntry(Kind kind, String name, String text) {

    /**
     * What an entry records.
     */
    public enum Kind {
        /** A message of the agent. */
        SAID,
        /** A tool call, with its arguments as text. */
        CALLED,
        /** What a tool answered. */
        RESULT,
        /** A failed tool call or a failed turn. */
        FAILED
    }

    /**
     * Validates the entry.
     */
    public AgentLogEntry {
        Objects.requireNonNull(kind, "kind");
        name = name == null ? "" : name;
        text = text == null ? "" : text;
    }
}
