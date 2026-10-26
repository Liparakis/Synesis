package org.synesis.workspace.application.project.execution;

import java.nio.file.Path;

/**
 * Asks whether a project's owner has signed the decision that commands may run unsandboxed on a computer that has no
 * sandbox (ADR-0104, {@code sandbox.unsandboxed = ALLOW}).
 *
 * <p>This is the one seam for that decision. It never lets a computer that can sandbox skip the sandbox: the
 * executor asks only after the sandbox reported itself unavailable. The answer must come from the signed policy
 * log and from nothing a project file, an environment variable or a flag can set.</p>
 */
@FunctionalInterface
public interface UnsandboxedAllowance {

    /**
     * The allowance until the signed policy entry exists: never allowed, so that an unavailable sandbox refuses
     * the command (fail closed).
     */
    UnsandboxedAllowance DENY = controlRoot -> false;

    /**
     * Tells whether commands of this project may run unsandboxed.
     *
     * @param controlRoot control checkout of the project, or {@code null} when the caller has none
     * @return {@code true} only when a signed decision allows it
     */
    boolean allowedFor(Path controlRoot);
}
