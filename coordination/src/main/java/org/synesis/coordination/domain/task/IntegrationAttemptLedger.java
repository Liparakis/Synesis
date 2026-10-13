package org.synesis.coordination.domain.task;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.IntegrationAttemptId;
import org.synesis.coordination.domain.integration.IntegrationAttemptRecord;

/**
 * Integration attempts of a {@link TaskCompletionProjection}, the single attempt still in flight, and the last
 * control head that integration advanced. The status strings are persisted in {@link IntegrationAttemptRecord}.
 */
final class IntegrationAttemptLedger {

    static final String STARTED = "started";
    static final String PENDING = "pending";
    static final String FAILED = "failed";
    static final String CONFLICT = "conflict";
    static final String ADVANCED = "advanced";

    private final Map<IntegrationAttemptId, IntegrationAttemptRecord> attempts = new LinkedHashMap<>();
    private IntegrationAttemptId activeAttemptId = null;
    private CommitId lastControlHeadAdvanced = null;

    IntegrationAttemptLedger() {
    }

    IntegrationAttemptLedger(IntegrationAttemptLedger source) {
        attempts.putAll(source.attempts);
        activeAttemptId = source.activeAttemptId;
        lastControlHeadAdvanced = source.lastControlHeadAdvanced;
    }

    /**
     * Records a started attempt and makes it the active one.
     */
    void begin(IntegrationAttemptId id, IntegrationAttemptRecord record) {
        attempts.put(id, record);
        activeAttemptId = id;
    }

    /**
     * Rewrites a known attempt; an unknown attempt is left alone.
     */
    void update(IntegrationAttemptId id, UnaryOperator<IntegrationAttemptRecord> change) {
        IntegrationAttemptRecord current = attempts.get(id);
        if (current != null) {
            attempts.put(id, change.apply(current));
        }
    }

    /**
     * Rewrites a known attempt and, if it was the active one, ends it.
     */
    void finish(IntegrationAttemptId id, UnaryOperator<IntegrationAttemptRecord> change) {
        update(id, change);
        if (id.equals(activeAttemptId)) {
            activeAttemptId = null;
        }
    }

    void advanceHead(CommitId commit) {
        lastControlHeadAdvanced = commit;
    }

    Optional<IntegrationAttemptRecord> active() {
        return activeAttemptId == null ? Optional.empty() : Optional.ofNullable(attempts.get(activeAttemptId));
    }

    /**
     * Returns the attempt begun just before the given one, as it ended.
     */
    Optional<IntegrationAttemptRecord> before(IntegrationAttemptId id) {
        IntegrationAttemptRecord previous = null;
        for (Map.Entry<IntegrationAttemptId, IntegrationAttemptRecord> attempt : attempts.entrySet()) {
            if (attempt.getKey().equals(id)) {
                return Optional.ofNullable(previous);
            }
            previous = attempt.getValue();
        }
        return Optional.empty();
    }

    String lastControlHeadAdvanced() {
        return lastControlHeadAdvanced == null ? null : lastControlHeadAdvanced.value();
    }
}
