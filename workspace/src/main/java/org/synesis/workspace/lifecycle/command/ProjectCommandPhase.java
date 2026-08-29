package org.synesis.workspace.lifecycle.command;

/**
 * Durable project-command lifecycle phases and their blocking semantics.
 */
public enum ProjectCommandPhase {
    /**
     * Admission is committed but launch outcome is not yet known.
     */
    STARTING,
    /**
     * The exact command process identity has been durably captured.
     */
    RUNNING,
    /**
     * The outcome is unresolved and must remain blocking.
     */
    AMBIGUOUS,
    /**
     * The record is integrity-valid and has a terminal resolution.
     */
    TERMINAL
}
