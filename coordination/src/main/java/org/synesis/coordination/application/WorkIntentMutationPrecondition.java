package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.identity.AuthorityLineageId;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.exception.CoordinationArgumentException;
import org.synesis.coordination.exception.CoordinationException;
import org.synesis.coordination.exception.CoordinationNullException;
import org.synesis.coordination.exception.CoordinationStateException;

/**
 * Optimistic precondition for a mutation of one authenticated work intent.
 *
 * <p>The precondition is captured before a lock release/reacquire gap and is
 * checked again while the project append lock is held. It prevents a stale lane, participant, claim epoch, lineage, or
 * selector set from
 * being mutated by a delayed caller.</p>
 *
 * @param intentId           intent identity
 * @param participant        authenticated participant handle
 * @param expectedVersion    expected claim epoch
 * @param authorityLineageId expected authority lineage
 * @param selectorsDigest    digest of the exact selector set
 */
public record WorkIntentMutationPrecondition(UUID intentId, String participant, long expectedVersion,
                                             UUID authorityLineageId, String selectorsDigest) {

    /**
     * Validates the bounded precondition.
     */
    public WorkIntentMutationPrecondition {
        CoordinationNullException.requireNonNull(intentId, "intentId");
        CoordinationNullException.requireNonNull(participant, "participant");
        CoordinationNullException.requireNonNull(authorityLineageId, "authorityLineageId");
        CoordinationNullException.requireNonNull(selectorsDigest, "selectorsDigest");
        if (participant.isBlank() || expectedVersion < 1L || selectorsDigest.isBlank()) {
            throw new CoordinationArgumentException("invalid work-intent mutation precondition");
        }
    }

    /**
     * Captures a precondition from the current immutable intent projection.
     *
     * @param intent current work intent
     * @return exact optimistic precondition
     */
    public static WorkIntentMutationPrecondition capture(WorkIntent intent) {
        CoordinationNullException.requireNonNull(intent, "intent");
        return new WorkIntentMutationPrecondition(intent.identity().value(),
                intent.participantIdentity().value(),
                intent.claimEpoch().value(),
                intent.lineage().value(),
                selectorsDigest(intent));
    }

    /**
     * Hashes the canonical selector encoding to bind a precondition to its requested scope.
     */
    private static String selectorsDigest(WorkIntent intent) {
        String material = intent.selectors().stream()
                .map(selector -> selector.kind().name() + "\u001f" + selector.value())
                .collect(Collectors.joining("\u001e"));
        try {
            return HexFormatHolder.HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CoordinationStateException("work-intent selector digest unavailable", failure);
        }
    }

    /**
     * Returns the typed work-intent identity fenced by this precondition.
     *
     * @return the typed work-intent identity fenced by this precondition.
     */
    public WorkIntentId intentIdentity() {
        return new WorkIntentId(intentId);
    }

    /**
     * Returns the typed participant incarnation expected to own the intent.
     *
     * @return the typed participant incarnation expected to own the intent.
     */
    public ParticipantId participantIdentity() {
        return new ParticipantId(participant);
    }

    /**
     * Returns the typed claim epoch expected by the mutation.
     *
     * @return the typed claim epoch expected by the mutation.
     */
    public ClaimEpoch claimEpoch() {
        return new ClaimEpoch(expectedVersion);
    }

    /**
     * Returns the typed authority lineage expected by the mutation.
     *
     * @return the typed authority lineage expected by the mutation.
     */
    public AuthorityLineageId lineageIdentity() {
        return new AuthorityLineageId(authorityLineageId);
    }

    /**
     * Returns whether an intent still satisfies this precondition.
     *
     * @param intent candidate current intent
     * @return true when all mutation identity fields match
     */
    public boolean matches(WorkIntent intent) {
        return intent != null && intentIdentity().equals(intent.identity())
                && participantIdentity().equals(intent.participantIdentity())
                && claimEpoch().equals(intent.claimEpoch()) && lineageIdentity().equals(intent.lineage())
                && selectorsDigest.equals(selectorsDigest(intent));
    }

    /**
     * Requires a matching intent while the append lock is held.
     *
     * @param intent current intent projection
     * @throws IOException when the precondition is stale
     */
    public void requireMatches(WorkIntent intent) throws IOException {
        if (!matches(intent)) {
            throw new CoordinationException("WORK_INTENT_MUTATION_PRECONDITION_STALE");
        }
    }

    /**
     * Lazily exposes the immutable hexadecimal formatter used by validation.
     */
    private static final class HexFormatHolder {

        private static final HexFormat HEX = HexFormat.of();

        /**
         * Validates and initializes this hex format holder.
         */
        private HexFormatHolder() {
        }
    }
}
