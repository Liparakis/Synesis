package org.synesis.workspace.lifecycle.command;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable durable state for one typed request within one MCP process anchor.
 *
 * @param anchorId               process-anchor identity
 * @param scopeLocator           physical-worktree scope
 * @param requestId              canonical typed request ID
 * @param requestDigest          canonical command-request digest
 * @param semanticDigest         authority-bound semantic digest
 * @param phase                  durable lifecycle phase
 * @param terminalResolution     terminal classification, when terminal
 * @param outcomeKnown           whether command outcome is known
 * @param exitCode               observed process exit code
 * @param stdoutComplete         whether stdout evidence is complete
 * @param stderrComplete         whether stderr evidence is complete
 * @param reviewReference        immutable review evidence reference
 * @param revision               mutable record revision
 * @param createdAtEpochMillis   creation timestamp
 * @param updatedAtEpochMillis   last update timestamp
 * @param response               bounded agent response map
 * @param commandProcessIdentity exact child process identity evidence
 */
public record ProjectCommandRecord(
        String anchorId,
        String scopeLocator,
        String requestId,
        String requestDigest,
        String semanticDigest,
        ProjectCommandPhase phase,
        ProjectCommandTerminalResolution terminalResolution,
        boolean outcomeKnown,
        Integer exitCode,
        boolean stdoutComplete,
        boolean stderrComplete,
        String reviewReference,
        long revision,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        Map<String, Object> response,
        Map<String, Object> commandProcessIdentity
) {

    /**
     * Validates durable command invariants and copies the response map.
     */
    public ProjectCommandRecord {
        Objects.requireNonNull(anchorId, "anchorId");
        Objects.requireNonNull(scopeLocator, "scopeLocator");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(requestDigest, "requestDigest");
        Objects.requireNonNull(semanticDigest, "semanticDigest");
        Objects.requireNonNull(phase, "phase");
        if (terminalResolution != null && phase != ProjectCommandPhase.TERMINAL) {
            throw new IllegalArgumentException("terminal resolution requires TERMINAL phase");
        }
        if (phase == ProjectCommandPhase.TERMINAL && terminalResolution == null) {
            throw new IllegalArgumentException("TERMINAL phase requires terminal resolution");
        }
        if (revision < 1L || createdAtEpochMillis < 0L || updatedAtEpochMillis < createdAtEpochMillis) {
            throw new IllegalArgumentException("invalid command revision or timestamps");
        }
        response = response == null ? Map.of() : Map.copyOf(response);
        commandProcessIdentity = commandProcessIdentity == null ? Map.of() : Map.copyOf(commandProcessIdentity);
    }

    /**
     * Returns whether this record is blocking for callers.
     *
     * @return true for STARTING, RUNNING, and AMBIGUOUS phases
     */
    public boolean blocking() {
        return phase != ProjectCommandPhase.TERMINAL;
    }
}
