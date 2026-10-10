package org.synesis.workspace.application.provider;

/**
 * Lifecycle status of a provider session binding, spelled on disk as the constant name.
 *
 * <p>The binding file keeps the raw status text, so a value written by a newer build loads and is
 * saved back unchanged and reads here as {@link #UNKNOWN}. Every predicate answers for a known
 * status only; each documents which way {@link #UNKNOWN} falls, always the way that grants less.</p>
 */
public enum ProviderBindingStatus {
    /**
     * The session is live and may act.
     */
    BOUND,
    /**
     * The session is paused and may still be completed.
     */
    SUSPENDED,
    /**
     * The lane is complete; its worktree is preserved.
     */
    COMPLETED,
    /**
     * The session was cancelled.
     */
    CANCELLED,
    /**
     * The provider's sessions were revoked.
     */
    REVOKED,
    /**
     * An irreversible terminal fence was recorded for the session.
     */
    TERMINAL,
    /**
     * Any other stored text; never active.
     */
    UNKNOWN;

    /**
     * Parses the stored spelling exactly; any other text is {@link #UNKNOWN}.
     *
     * @param persisted stored status text
     * @return matching status
     */
    public static ProviderBindingStatus parse(String persisted) {
        for (ProviderBindingStatus status : values()) {
            if (status != UNKNOWN && status.name().equals(persisted)) {
                return status;
            }
        }
        return UNKNOWN;
    }

    /**
     * Parses the stored spelling ignoring case, for callers that have always compared that way.
     *
     * @param persisted stored status text
     * @return matching status
     */
    public static ProviderBindingStatus parseIgnoreCase(String persisted) {
        for (ProviderBindingStatus status : values()) {
            if (status != UNKNOWN && status.name().equalsIgnoreCase(persisted)) {
                return status;
            }
        }
        return UNKNOWN;
    }

    /**
     * Returns the spelling written to the binding file.
     *
     * @return persisted spelling
     * @throws IllegalStateException for {@link #UNKNOWN}, which has no spelling of its own
     */
    String persisted() {
        if (this == UNKNOWN) {
            throw new IllegalStateException("UNKNOWN has no persisted spelling");
        }
        return name();
    }

    /**
     * Returns whether a live session may be completed: {@link #BOUND} or {@link #SUSPENDED}.
     *
     * @return {@code false} for {@link #UNKNOWN}
     */
    boolean isBoundOrSuspended() {
        return this == BOUND || this == SUSPENDED;
    }

    /**
     * Returns whether the lane is live or finished but not yet fenced: {@link #BOUND} or
     * {@link #COMPLETED}. Terminalization and review authority both start from this set.
     *
     * @return {@code false} for {@link #UNKNOWN}
     */
    public boolean isBoundOrCompleted() {
        return this == BOUND || this == COMPLETED;
    }

    /**
     * Returns whether a terminal seal may be requested or repeated: {@link #BOUND},
     * {@link #COMPLETED}, or {@link #TERMINAL}.
     *
     * @return {@code false} for {@link #UNKNOWN}
     */
    public boolean acceptsTerminalSeal() {
        return isBoundOrCompleted() || this == TERMINAL;
    }

    /**
     * Returns whether the binding has given up its worktree for the duplicate-owner check:
     * {@link #REVOKED} or {@link #COMPLETED}. {@link #SUSPENDED}, {@link #CANCELLED}, and
     * {@link #TERMINAL} still count as owners there.
     *
     * @return {@code false} for {@link #UNKNOWN}, so an unrecognised binding still counts as an owner
     */
    boolean releasesWorktree() {
        return this == REVOKED || this == COMPLETED;
    }

    /**
     * Returns whether {@code ensure} resumes the stored binding instead of replacing it: every state
     * except the four that ended or parked the session. {@link #TERMINAL} is decided before this
     * question is asked.
     *
     * @return {@code true} for {@link #UNKNOWN}, so a binding with an unrecognised status is resumed
     *         and not overwritten by a fresh one
     */
    boolean resumesOnEnsure() {
        return this != REVOKED && this != COMPLETED && this != SUSPENDED && this != CANCELLED;
    }
}
