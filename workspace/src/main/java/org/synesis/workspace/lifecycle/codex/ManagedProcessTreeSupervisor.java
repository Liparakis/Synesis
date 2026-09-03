package org.synesis.workspace.lifecycle.codex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Owns launch and teardown of one managed provider process tree.
 *
 * <p>An implementation must establish containment before the child can run,
 * verify the exact root identity, and provide a definitive empty/dead result.
 * The default implementation intentionally refuses launch because ordinary
 * {@link ProcessBuilder} is not sufficient for that contract.</p>
 */
public interface ManagedProcessTreeSupervisor {

    /**
     * Launches a process inside a newly owned process container.
     *
     * @param command executable argv
     * @param workingDirectory process working directory
     * @param environment process environment
     * @return contained process
     * @throws IOException when containment or launch cannot be established
     */
    Process launch(List<String> command, Path workingDirectory, Map<String, String> environment)
            throws IOException;

    /**
     * Verifies that the process is the exact root owned by this supervisor.
     *
     * @param process process returned from {@link #launch(List, Path, Map)}
     * @return true only when ownership is proven
     */
    boolean owns(Process process);

    /**
     * Tears down the owned tree and proves it is empty/dead.
     *
     * @param process contained root process
     * @return true only when no owned process remains
     * @throws IOException when liveness is ambiguous or teardown fails
     */
    boolean teardownAndProveEmpty(Process process) throws IOException;

    /**
     * Tears down an owned root and returns supervisor-produced death evidence.
     *
     * @param process exact owned root process
     * @param generation managed attachment generation
     * @param executable verified root executable
     * @param commandIdentity verified root command identity
     * @param startEpochMillis verified root start time
     * @return evidence produced only after the empty-tree proof succeeds
     * @throws IOException when ownership or empty-tree proof fails
     */
    default DeathEvidence teardownAndProveEmptyWithEvidence(Process process, long generation,
            String executable, String commandIdentity, long startEpochMillis) throws IOException {
        if (!teardownAndProveEmpty(process)) {
            throw new IOException("managed_process_tree_death_unproven");
        }
        return new DeathEvidence(generation, process.pid(), startEpochMillis, executable, commandIdentity,
                getClass().getName(), 1L, System.currentTimeMillis());
    }

    /**
     * Returns the production supervisor for the current platform.
     *
     * <p>Managed launches remain fail-closed on platforms without an
     * implemented containment primitive.</p>
     *
     * @return Windows Job Object supervisor, or an unavailable supervisor
     */
    static ManagedProcessTreeSupervisor platformDefault() {
        if (WindowsJobObjectProcessTreeSupervisor.isWindows()) {
            try {
                return new WindowsJobObjectProcessTreeSupervisor();
            } catch (IOException failure) {
                return unavailable();
            }
        }
        return unavailable();
    }

    /**
     * Returns a supervisor that refuses unsafe uncontained managed launches.
     *
     * @return fail-closed unavailable supervisor
     */
    static ManagedProcessTreeSupervisor unavailable() {
        return new ManagedProcessTreeSupervisor() {
            @Override
            public Process launch(List<String> command, Path workingDirectory, Map<String, String> environment)
                    throws IOException {
                throw new IOException("managed_process_tree_supervisor_unavailable");
            }

            @Override
            public boolean owns(Process process) {
                return false;
            }

            @Override
            public boolean teardownAndProveEmpty(Process process) throws IOException {
                throw new IOException("managed_process_tree_supervisor_unavailable");
            }
        };
    }

    /**
     * Non-secret evidence returned by a trusted process-tree supervisor.
     *
     * @param generation managed attachment generation
     * @param rootPid verified root PID
     * @param rootStartEpochMillis verified root start time
     * @param rootExecutable verified executable
     * @param rootCommandIdentity verified command identity
     * @param supervisorProvenance supervisor implementation provenance
     * @param supervisorRevision supervisor evidence revision
     * @param observedAtEpochMillis evidence observation time
     */
    record DeathEvidence(long generation, long rootPid, long rootStartEpochMillis, String rootExecutable,
            String rootCommandIdentity, String supervisorProvenance, long supervisorRevision,
            long observedAtEpochMillis) {

        /** Validates bounded non-secret supervisor evidence. */
        public DeathEvidence {
            if (generation < 1 || rootPid < 1 || rootStartEpochMillis < 1 || supervisorRevision < 1
                    || observedAtEpochMillis < 1 || rootExecutable == null || rootExecutable.isBlank()
                    || rootCommandIdentity == null || rootCommandIdentity.isBlank()
                    || supervisorProvenance == null || supervisorProvenance.isBlank()) {
                throw new IllegalArgumentException("invalid managed death evidence");
            }
        }
    }
}
