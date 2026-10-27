package org.synesis.workspace.application.project;

import java.nio.file.Path;

import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.project.execution.ProjectProcessExecutor;
import org.synesis.workspace.application.project.execution.UnsandboxedAllowance;
import org.synesis.workspace.application.project.execution.sandbox.Sandboxes;

/**
 * Answers {@link UnsandboxedAllowance} from the project's signed policy (ADR-0104, {@code sandbox.unsandboxed}).
 *
 * <p>Only a signed log can say yes: before a baseline the project file is the policy, and it never grants this. Anything
 * that prevents a clear answer (no control root, no project, an unreadable log, a member that has not received the
 * baseline) is a no, so the command is refused.</p>
 */
public final class SignedUnsandboxedAllowance implements UnsandboxedAllowance {

    /**
     * Creates the allowance.
     */
    public SignedUnsandboxedAllowance() {
    }

    /**
     * Creates the executor every production path uses: this computer's sandbox, and the signed allowance when there is none.
     *
     * @return the executor
     */
    public static ProjectProcessExecutor executor() {
        return new ProjectProcessExecutor(Sandboxes.platform(), new SignedUnsandboxedAllowance());
    }

    @Override
    public boolean allowedFor(Path controlRoot) {
        if (controlRoot == null) {
            return false;
        }
        try {
            return new ProjectApplicationService().require(controlRoot).unsandboxedAllowed();
        } catch (Exception unclear) {
            return false;
        }
    }
}
