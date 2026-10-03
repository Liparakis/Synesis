package org.synesis.coordination.domain.collaboration;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.synesis.coordination.domain.identity.AuthorityLineageId;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.CommitId;
import org.synesis.coordination.domain.identity.EventSequence;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.ProviderId;
import org.synesis.coordination.domain.identity.ProviderSessionId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkGroupVersion;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationNullException;

/**
 * Authenticated evidence for explicitly completing an intent without a repository snapshot.
 *
 * <p>The event sequence and the project identity are supplied by the event
 * store. The remaining fields bind the completion to the exact intent, participant, authority lineage, workspace
 * generation, and optimistic
 * work-group/revision facts observed by the caller.</p>
 *
 * @param intentId           intent being completed
 * @param workGroupId        logical work group containing the intent
 * @param participant        exact participant handle
 * @param provider           provider bound to the participant
 * @param bindingIdentity    durable provider-session binding identity
 * @param authorityLineageId intent authority lineage
 * @param claimEpoch         current intent claim epoch
 * @param workGroupVersion   current work-group version
 * @param expectedRevision   current project event revision before append
 * @param workspaceCommit    verified clean workspace commit
 * @param summary            bounded completion explanation
 */
public record NoChangeCompletion(UUID intentId, UUID workGroupId, String participant, String provider,
                                 String bindingIdentity, UUID authorityLineageId, long claimEpoch,
                                 long workGroupVersion, long expectedRevision, String workspaceCommit, String summary) {

    private static final int MAX_TEXT_BYTES = 8192;

    /**
     * Validates immutable completion evidence and its optimistic versions.
     */
    public NoChangeCompletion {
        CoordinationNullException.requireNonNull(intentId, "intent ID");
        CoordinationNullException.requireNonNull(workGroupId, "work-group ID");
        requireText(participant, "participant");
        requireText(provider, "provider");
        requireText(bindingIdentity, "binding identity");
        CoordinationNullException.requireNonNull(authorityLineageId, "authority lineage ID");
        if (claimEpoch < 1) {
            throw new CoordinationArgumentException("claim epoch must be positive");
        }
        if (workGroupVersion < 1) {
            throw new CoordinationArgumentException("work-group version must be positive");
        }
        if (expectedRevision < 0) {
            throw new CoordinationArgumentException("expected revision must not be negative");
        }
        requireText(workspaceCommit, "workspace commit");
        summary = summary == null || summary.isBlank() ? "Completed successfully without repository mutation"
                : summary.trim();
        requireText(summary, "summary");
    }

    /**
     * Checks that the text value satisfies this type's bounds.
     */
    private static void requireText(String value, String field) {
        CoordinationNullException.requireNonNull(value, field);
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new CoordinationArgumentException(field + " is empty or exceeds bound");
        }
    }

    /**
     * Returns the typed identity of the completed intent.
     *
     * @return the typed identity of the completed intent.
     */
    public WorkIntentId intentIdentity() {
        return new WorkIntentId(intentId);
    }

    /**
     * Returns the typed identity of the containing work group.
     *
     * @return the typed identity of the containing work group.
     */
    public WorkGroupId workGroupIdentity() {
        return new WorkGroupId(workGroupId);
    }

    /**
     * Returns the typed authority lineage.
     *
     * @return the typed authority lineage.
     */
    public AuthorityLineageId lineageIdentity() {
        return new AuthorityLineageId(authorityLineageId);
    }

    /**
     * Returns the participant incarnation bound to this completion.
     *
     * @return the participant incarnation bound to this completion.
     */
    public ParticipantId participantIdentity() {
        return new ParticipantId(participant);
    }

    /**
     * Returns the provider identity bound to this completion.
     *
     * @return the provider identity bound to this completion.
     */
    public ProviderId providerIdentity() {
        return new ProviderId(provider);
    }

    /**
     * Returns the provider session that established the durable binding.
     *
     * @return the provider session that established the durable binding.
     */
    public ProviderSessionId bindingSessionIdentity() {
        return new ProviderSessionId(bindingIdentity);
    }

    /**
     * Returns the claim epoch being completed.
     *
     * @return the claim epoch being completed.
     */
    public ClaimEpoch claimEpochIdentity() {
        return new ClaimEpoch(claimEpoch);
    }

    /**
     * Returns the containing work-group version.
     *
     * @return the containing work-group version.
     */
    public WorkGroupVersion workGroupVersionIdentity() {
        return new WorkGroupVersion(workGroupVersion);
    }

    /**
     * Returns the project event revision observed before appending this completion.
     *
     * @return the project event revision observed before appending this completion.
     */
    public EventSequence expectedRevisionIdentity() {
        return new EventSequence(expectedRevision);
    }

    /**
     * Returns the verified workspace commit.
     *
     * @return the verified workspace commit.
     */
    public CommitId workspaceCommitIdentity() {
        return new CommitId(workspaceCommit);
    }
}
