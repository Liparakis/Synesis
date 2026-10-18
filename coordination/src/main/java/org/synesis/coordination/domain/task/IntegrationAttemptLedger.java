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
    /** The computer whose merge pass made each attempt: since ADR-0098 step 5 every computer merges its own lanes. */
    private final Map<IntegrationAttemptId, String> writers = new LinkedHashMap<>();
    private final Map<String, IntegrationAttemptId> activeByWriter = new LinkedHashMap<>();
    private final Map<String, CommitId> headByWriter = new LinkedHashMap<>();
    private IntegrationAttemptId activeAttemptId = null;
    private CommitId lastControlHeadAdvanced = null;

    IntegrationAttemptLedger() {
    }

    IntegrationAttemptLedger(IntegrationAttemptLedger source) {
        attempts.putAll(source.attempts);
        writers.putAll(source.writers);
        activeByWriter.putAll(source.activeByWriter);
        headByWriter.putAll(source.headByWriter);
        activeAttemptId = source.activeAttemptId;
        lastControlHeadAdvanced = source.lastControlHeadAdvanced;
    }

    /**
     * Records a started attempt and makes it the active one.
     */
    void begin(String writer, IntegrationAttemptId id, IntegrationAttemptRecord record) {
        attempts.put(id, record);
        writers.put(id, writer);
        activeByWriter.put(writer, id);
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
        String writer = writers.get(id);
        if (writer != null && id.equals(activeByWriter.get(writer))) {
            activeByWriter.remove(writer);
        }
    }

    void advanceHead(String writer, CommitId commit) {
        lastControlHeadAdvanced = commit;
        headByWriter.put(writer, commit);
    }

    Optional<IntegrationAttemptRecord> active(String writer) {
        IntegrationAttemptId id = activeByWriter.get(writer);
        return id == null ? Optional.empty() : Optional.ofNullable(attempts.get(id));
    }

    String lastControlHeadAdvanced(String writer) {
        CommitId head = headByWriter.get(writer);
        return head == null ? null : head.value();
    }

    Optional<IntegrationAttemptRecord> active() {
        return activeAttemptId == null ? Optional.empty() : Optional.ofNullable(attempts.get(activeAttemptId));
    }

    /**
     * Returns the attempt begun just before the given one, as it ended.
     */
    Optional<IntegrationAttemptRecord> before(IntegrationAttemptId id) {
        // The attempt before it of the same computer: another computer's attempts are not its pass's history.
        String writer = writers.get(id);
        IntegrationAttemptRecord previous = null;
        for (Map.Entry<IntegrationAttemptId, IntegrationAttemptRecord> attempt : attempts.entrySet()) {
            if (attempt.getKey().equals(id)) {
                return Optional.ofNullable(previous);
            }
            if (java.util.Objects.equals(writers.get(attempt.getKey()), writer)) {
                previous = attempt.getValue();
            }
        }
        return Optional.empty();
    }

    String lastControlHeadAdvanced() {
        return lastControlHeadAdvanced == null ? null : lastControlHeadAdvanced.value();
    }
}
