package org.synesis.workspace.lease;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.synesis.workspace.cleanup.ProcessEvidenceState;
import org.synesis.workspace.cleanup.ProcessInspector;

/**
 * Service orchestrating provider-session lease registration, heartbeat renewals, clean shutdown,
 * and liveness evaluation.
 *
 * @since 1.0
 */
public final class SessionLeaseService {

    private final SessionLeaseStore store;
    private final ProcessInspector processInspector;

    /**
     * Creates a lease service with default store and system process inspector.
     */
    public SessionLeaseService() {
        this(new SessionLeaseStore(), ProcessInspector.system());
    }

    /**
     * Creates a lease service with explicit dependencies.
     *
     * @param store            lease persistence store
     * @param processInspector process inspector instance
     */
    public SessionLeaseService(SessionLeaseStore store, ProcessInspector processInspector) {
        this.store = Objects.requireNonNull(store, "store");
        this.processInspector = Objects.requireNonNull(processInspector, "processInspector");
    }

    /**
     * Registers a new session lease or renews an existing lease with fresh heartbeat timestamp.
     *
     * @param controlRoot          control project root path
     * @param projectId            project identity
     * @param provider             provider name
     * @param connectionInstanceId connection instance ID
     * @param workerNodeId         worker node ID
     * @param sessionId            session ID
     * @param policy               lease policy configuration
     * @return updated session lease record
     * @throws IOException if persisting lease fails
     */
    public SessionLeaseRecord createOrRenewLease(
            Path controlRoot,
            String projectId,
            String provider,
            String connectionInstanceId,
            String workerNodeId,
            String sessionId,
            SessionLeasePolicy policy
    ) throws IOException {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        Objects.requireNonNull(workerNodeId, "workerNodeId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(policy, "policy");

        long now = policy.nowMillis();

        Optional<SessionLeaseRecord> existing = store.load(controlRoot, connectionInstanceId);

        SessionProcessIdentity processIdentity;
        long createdAt;

        if (existing.isPresent()) {
            SessionLeaseRecord prev = existing.get();
            processIdentity = prev.processIdentity();
            createdAt = prev.createdAtEpochMillis();
        } else {
            long pid = ProcessHandle.current().pid();
            String exec = ProcessHandle.current().info().command().orElse("java");
            String cmd = ProcessHandle.current().info().commandLine().orElse(exec);
            long start = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(now);
            String nonce = UUID.randomUUID().toString();
            processIdentity = new SessionProcessIdentity(pid, exec, cmd, start, nonce);
            createdAt = now;
        }

        SessionLeaseRecord updated = new SessionLeaseRecord(
                1, projectId, provider, connectionInstanceId, workerNodeId, sessionId,
                processIdentity, "0.1.0-SNAPSHOT", createdAt, now, SessionLeaseState.ACTIVE
        );

        store.save(controlRoot, updated);
        return updated;
    }

    /**
     * Marks an existing lease as cleanly closed upon stdio EOF or graceful shutdown.
     *
     * @param controlRoot          control project root path
     * @param connectionInstanceId connection instance ID
     */
    public void markClosedCleanly(Path controlRoot, String connectionInstanceId) {
        Objects.requireNonNull(controlRoot, "controlRoot");
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");

        Optional<SessionLeaseRecord> existing = store.load(controlRoot, connectionInstanceId);
        if (existing.isPresent()) {
            SessionLeaseRecord prev = existing.get();
            SessionLeaseRecord closed = new SessionLeaseRecord(
                    prev.schemaVersion(), prev.projectId(), prev.provider(), prev.connectionInstanceId(),
                    prev.workerNodeId(), prev.sessionId(), prev.processIdentity(), prev.synesisVersion(),
                    prev.createdAtEpochMillis(), System.currentTimeMillis(), SessionLeaseState.CLOSED_CLEANLY
            );
            try {
                store.save(controlRoot, closed);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Evaluates derived liveness state for a session lease record.
     *
     * @param record lease record to evaluate
     * @param policy lease policy configuration
     * @return derived session lease state
     */
    public SessionLeaseState evaluateLiveness(SessionLeaseRecord record, SessionLeasePolicy policy) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(policy, "policy");

        if (record.leaseState() == SessionLeaseState.CLOSED_CLEANLY) {
            return SessionLeaseState.CLOSED_CLEANLY;
        }

        long now = policy.nowMillis();
        Duration elapsedSinceHeartbeat = Duration.ofMillis(Math.max(0, now - record.lastHeartbeatEpochMillis()));

        ProcessEvidenceState processEvidence = processInspector.evaluateEvidence(
                record.processIdentity().pid(),
                record.processIdentity().executableIdentity(),
                record.processIdentity().commandLine()
        );

        if (processEvidence == ProcessEvidenceState.LIVE_VERIFIED) {
            if (elapsedSinceHeartbeat.compareTo(policy.suspectedStaleThreshold()) <= 0) {
                return SessionLeaseState.ACTIVE;
            } else {
                return SessionLeaseState.SUSPECTED_STALE;
            }
        }

        if (processEvidence == ProcessEvidenceState.PID_REUSED_OR_MISMATCHED || processEvidence == ProcessEvidenceState.PROCESS_EVIDENCE_UNAVAILABLE) {
            return SessionLeaseState.AMBIGUOUS;
        }

        if (processEvidence == ProcessEvidenceState.NOT_OBSERVED) {
            if (elapsedSinceHeartbeat.compareTo(policy.abandonmentGracePeriod()) >= 0) {
                return SessionLeaseState.ABANDONMENT_ELIGIBLE;
            } else {
                return SessionLeaseState.SUSPECTED_STALE;
            }
        }

        return SessionLeaseState.AMBIGUOUS;
    }
}
