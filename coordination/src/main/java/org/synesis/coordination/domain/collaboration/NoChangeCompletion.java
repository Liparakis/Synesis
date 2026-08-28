package org.synesis.coordination.domain.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated evidence for explicitly completing an intent without a
 * repository snapshot.
 *
 * <p>The event sequence and the project identity are supplied by the event
 * store. The remaining fields bind the completion to the exact intent,
 * participant, authority lineage, workspace generation, and optimistic
 * work-group/revision facts observed by the caller.</p>
 *
 * @param intentId intent being completed
 * @param workGroupId logical work group containing the intent
 * @param participant exact participant handle
 * @param provider provider bound to the participant
 * @param bindingIdentity durable provider-session binding identity
 * @param authorityLineageId intent authority lineage
 * @param claimEpoch current intent claim epoch
 * @param workGroupVersion current work-group version
 * @param expectedRevision current project event revision before append
 * @param workspaceCommit verified clean workspace commit
 * @param summary bounded completion explanation
 */
public record NoChangeCompletion(
        UUID intentId,
        UUID workGroupId,
        String participant,
        String provider,
        String bindingIdentity,
        UUID authorityLineageId,
        long claimEpoch,
        long workGroupVersion,
        long expectedRevision,
        String workspaceCommit,
        String summary) {

    private static final int MAX_TEXT_BYTES = 8192;

    /** Validates immutable completion evidence and its optimistic versions. */
    public NoChangeCompletion {
        Objects.requireNonNull(intentId, "intent ID");
        Objects.requireNonNull(workGroupId, "work-group ID");
        requireText(participant, "participant");
        requireText(provider, "provider");
        requireText(bindingIdentity, "binding identity");
        Objects.requireNonNull(authorityLineageId, "authority lineage ID");
        if (claimEpoch < 1) {
            throw new IllegalArgumentException("claim epoch must be positive");
        }
        if (workGroupVersion < 1) {
            throw new IllegalArgumentException("work-group version must be positive");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expected revision must not be negative");
        }
        requireText(workspaceCommit, "workspace commit");
        summary = summary == null || summary.isBlank() ? "Completed successfully without repository mutation" : summary.trim();
        requireText(summary, "summary");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException(field + " is empty or exceeds bound");
        }
    }
}
