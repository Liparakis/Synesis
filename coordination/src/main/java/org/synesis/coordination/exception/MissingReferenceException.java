package org.synesis.coordination.exception;

import java.io.Serial;

/**
 * An entry names something this computer has not seen yet: a lane, a grant, a request or a snapshot written in
 * another computer's log that has not arrived (ADR-0098 step 4). On this computer's own append it is the refusal it
 * always was, with the same code. On an entry that arrives from another log it means "not yet": the store holds the
 * entry back, with the later entries of its log, and applies it when what it names arrives.
 */
public final class MissingReferenceException extends CoordinationException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the refusal with its stable code.
     *
     * @param code stable machine-readable failure code, such as {@code INTENT_NOT_FOUND}
     */
    public MissingReferenceException(String code) {
        super(code);
    }
}
