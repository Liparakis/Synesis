package org.synesis.workspace.lifecycle.lease;

import java.util.Objects;

/**
 * Immutable session lease record stored under the external project administration directory.
 *
 * @param schemaVersion            schema version (1)
 * @param projectId                project identity
 * @param provider                 provider name
 * @param connectionInstanceId     connection-instance identifier
 * @param workerNodeId             worker node identifier
 * @param sessionId                session identifier
 * @param processIdentity          process identity evidence
 * @param synesisVersion           installed Synesis version
 * @param createdAtEpochMillis     creation timestamp
 * @param lastHeartbeatEpochMillis last heartbeat timestamp
 * @param leaseState               current derived lease state
 * @since 1.0
 */
public record SessionLeaseRecord(
        int schemaVersion,
        String projectId,
        String provider,
        String connectionInstanceId,
        String workerNodeId,
        String sessionId,
        SessionProcessIdentity processIdentity,
        String synesisVersion,
        long createdAtEpochMillis,
        long lastHeartbeatEpochMillis,
        SessionLeaseState leaseState
) {
    /**
     * Invariant validation.
     */
    public SessionLeaseRecord {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        Objects.requireNonNull(workerNodeId, "workerNodeId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(processIdentity, "processIdentity");
        Objects.requireNonNull(synesisVersion, "synesisVersion");
        Objects.requireNonNull(leaseState, "leaseState");
    }
}
