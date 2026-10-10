package org.synesis.workspace.lifecycle.command;

import java.util.Objects;
import org.synesis.workspace.infrastructure.digest.Sha256;
import org.synesis.workspace.lifecycle.lease.SessionProcessIdentity;

/**
 * Immutable identity of one exact MCP process bound to one physical scope.
 *
 * @param anchorId             versioned anchor locator
 * @param scopeLocator         verified physical-worktree scope locator
 * @param processIdentity      exact process evidence
 * @param createdAtEpochMillis anchor creation epoch milliseconds
 */
public record ProjectCommandProcessAnchor(String anchorId, String scopeLocator, SessionProcessIdentity processIdentity, long createdAtEpochMillis) {

    /**
     * Validates anchor identity and process evidence.
     */
    public ProjectCommandProcessAnchor {
        Objects.requireNonNull(anchorId, "anchorId");
        Objects.requireNonNull(scopeLocator, "scopeLocator");
        Objects.requireNonNull(processIdentity, "processIdentity");
        if (createdAtEpochMillis < 0L) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
    }

    /**
     * Decides whether the anchoring process still runs, comparing the full recorded identity:
     * start time, executable, and command line. Evidence the platform withholds makes the answer
     * {@link Liveness#UNKNOWN}, never dead.
     *
     * @return liveness of the anchoring process
     */
    Liveness liveness() {
        var handle = ProcessHandle.of(processIdentity.pid());
        if (handle.isEmpty()) {
            return Liveness.DEAD;
        }
        ProcessHandle.Info info = handle.get().info();
        if (info.startInstant().isEmpty() || info.command().isEmpty() || info.commandLine().isEmpty()) {
            return Liveness.UNKNOWN;
        }
        boolean same = info.startInstant().get().toEpochMilli() == processIdentity.processStartTime() && info.command().get().equals(processIdentity.executableIdentity())
                && info.commandLine().get().equals(processIdentity.commandLine());
        return same ? Liveness.LIVE : Liveness.DEAD;
    }

    /**
     * Diagnostics' coarser variant of {@link #liveness()}, working from a stored pid and start time
     * only: the process counts as gone when it is absent, when its start time is withheld, or when
     * it differs. {@link #liveness()} treats a withheld start time as unknown, and also compares
     * executable and command line.
     *
     * @param pid                 recorded process ID
     * @param recordedStartMillis recorded process start time in epoch milliseconds
     * @return true when no process with that pid and start time is running
     */
    static boolean startTimeGone(long pid, long recordedStartMillis) {
        var handle = ProcessHandle.of(pid);
        return handle.isEmpty() || handle.get().info().startInstant().map(instant -> instant.toEpochMilli() != recordedStartMillis).orElse(true);
    }

    /**
     * Whether the process an anchor names is running.
     */
    enum Liveness {
        LIVE, DEAD, UNKNOWN
    }

    /**
     * Creates an anchor from captured process evidence.
     *
     * @param scopeLocator         physical scope locator
     * @param processIdentity      process evidence
     * @param createdAtEpochMillis creation epoch milliseconds
     * @return immutable process anchor
     */
    public static ProjectCommandProcessAnchor capture(String scopeLocator, SessionProcessIdentity processIdentity, long createdAtEpochMillis) {
        Objects.requireNonNull(scopeLocator, "scopeLocator");
        Objects.requireNonNull(processIdentity, "processIdentity");
        String material = scopeLocator + "\n" + processIdentity.pid() + "\n" + processIdentity.executableIdentity() + "\n" + processIdentity.commandLine() + "\n" + processIdentity.processStartTime() + "\n"
                + processIdentity.connectionNonce() + "\n" + createdAtEpochMillis;
        return new ProjectCommandProcessAnchor("v1-" + Sha256.hex(material), scopeLocator, processIdentity, createdAtEpochMillis);
    }
}
