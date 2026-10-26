package org.synesis.workspace.application.project;

/**
 * The project's signed policy was asked for on a computer that has not received it (ADR-0104): a member of a shared
 * project that has not applied the owner's baseline entry. It is unchecked so that the one door every gate reads policy
 * through can refuse without each caller remembering to ask; the gate does not run, integration does not proceed, and
 * nothing treats the missing policy as "no gate".
 */
public final class PolicyNotReceivedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The stable code. */
    public static final String CODE = "POLICY_NOT_RECEIVED";

    /**
     * Creates the refusal.
     */
    public PolicyNotReceivedException() {
        super(CODE + ": this computer has not received the owner's signed policy yet, so it will not integrate or run a gate");
    }
}
