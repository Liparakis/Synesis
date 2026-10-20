package org.synesis.workspace.doctor;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What only the process that runs the command can observe about the network of a shared project: whether the project's
 * runtime is up and whether the Windows Firewall rule Link needs is right. The doctor service lives below the daemon
 * and the Link commands, so it asks through this interface and treats an empty answer as "could not tell", which
 * produces no finding.
 *
 * @since 1.0
 */
public interface NetworkProbe {

    /**
     * A probe that can tell nothing.
     */
    NetworkProbe UNKNOWN = new NetworkProbe() {
        @Override
        public Optional<Boolean> runtimeRunning(Path projectRoot) {
            return Optional.empty();
        }

        @Override
        public Optional<Firewall> firewall() {
            return Optional.empty();
        }
    };

    /**
     * The state of the Windows Firewall rule that lets another computer dial this one.
     */
    enum Firewall {
        /**
         * No rule exists.
         */
        MISSING,
        /**
         * A rule exists but names another version's executable.
         */
        STALE
    }

    /**
     * Asks whether the project's runtime, the process that holds its Link sessions, is running now. Asking never starts it.
     *
     * @param projectRoot project directory
     * @return whether it runs, or empty when that cannot be told
     */
    Optional<Boolean> runtimeRunning(Path projectRoot);

    /**
     * Asks about the firewall rule.
     *
     * @return the problem with it, or empty when there is none or it cannot be told (not Windows, rules unreadable)
     */
    Optional<Firewall> firewall();
}
