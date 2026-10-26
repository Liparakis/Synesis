package org.synesis.workspace.application.project.execution.sandbox;

import java.util.Objects;

/**
 * Whether a sandbox works on this computer.
 *
 * @param available whether a command can be confined
 * @param reason    why not, or an empty string when available
 * @param fix       how to make it available, or an empty string when available
 */
public record SandboxAvailability(boolean available, String reason, String fix) {

    /**
     * Validates the record.
     */
    public SandboxAvailability {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(fix, "fix");
    }

    /**
     * Creates the available answer.
     *
     * @return an available answer
     */
    public static SandboxAvailability ready() {
        return new SandboxAvailability(true, "", "");
    }

    /**
     * Creates an unavailable answer.
     *
     * @param reason why no sandbox can be set up
     * @param fix    what the person can do about it
     * @return an unavailable answer
     */
    public static SandboxAvailability unavailable(String reason, String fix) {
        return new SandboxAvailability(false, reason, fix);
    }
}
