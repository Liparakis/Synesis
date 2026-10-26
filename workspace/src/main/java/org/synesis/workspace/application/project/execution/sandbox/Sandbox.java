package org.synesis.workspace.application.project.execution.sandbox;

import java.util.List;
import java.util.Map;

/**
 * Confines a process that runs agent-authored commands or agent-authored code (ADR-0104).
 *
 * <p>A platform implementation is the only code that knows how to confine (bubblewrap, AppContainer,
 * {@code sandbox-exec}). It does not start the process: it returns the command line and environment that
 * the caller starts, so the caller keeps one process lifecycle (timeout, output bounds, tree kill).</p>
 *
 * @since 1.0
 */
public interface Sandbox {

    /**
     * Names the sandbox for result evidence.
     *
     * @return short stable name, for example {@code bubblewrap}
     */
    String name();

    /**
     * Tells whether this computer can confine a command, by trying it.
     *
     * <p>An implementation caches the answer for its own lifetime. A probe that cannot prove the negative
     * facts (a command cannot write the control area and cannot read hidden state) reports unavailable.</p>
     *
     * @return availability with a reason and a fix when unavailable
     */
    SandboxAvailability probe();

    /**
     * Builds the confined command line and environment for one run.
     *
     * @param policy      what the command may read and write
     * @param argv        the command, direct argv without a shell
     * @param environment the already scrubbed environment the command gets
     * @return the command line and environment to start
     * @throws SandboxSetupException if the confinement cannot be set up for this run
     */
    WrappedCommand wrap(SandboxPolicy policy, List<String> argv, Map<String, String> environment) throws SandboxSetupException;
}
