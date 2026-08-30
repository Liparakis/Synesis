package org.synesis.coordination.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.synesis.coordination.domain.collaboration.WorkIntent;

/**
 * Optimistic precondition for a mutation of one authenticated work intent.
 *
 * <p>The precondition is captured before a lock release/reacquire gap and is
 * checked again while the project append lock is held. It prevents a stale
 * lane, participant, claim epoch, lineage, or selector set from being
 * mutated by a delayed caller.</p>
 *
 * @param intentId           intent identity
 * @param participant        authenticated participant handle
 * @param expectedVersion    expected claim epoch
 * @param authorityLineageId expected authority lineage
 * @param selectorsDigest    digest of the exact selector set
 */
public record WorkIntentMutationPrecondition(
        UUID intentId,
        String participant,
        long expectedVersion,
        UUID authorityLineageId,
        String selectorsDigest
) {

    /**
     * Validates the bounded precondition.
     */
    public WorkIntentMutationPrecondition {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(authorityLineageId, "authorityLineageId");
        Objects.requireNonNull(selectorsDigest, "selectorsDigest");
        if (participant.isBlank() || expectedVersion < 1L || selectorsDigest.isBlank()) {
            throw new IllegalArgumentException("invalid work-intent mutation precondition");
        }
    }

    /**
     * Captures a precondition from the current immutable intent projection.
     *
     * @param intent current work intent
     * @return exact optimistic precondition
     */
    public static WorkIntentMutationPrecondition capture(WorkIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return new WorkIntentMutationPrecondition(intent.intentId(), intent.participant(), intent.version(),
                intent.authorityLineageId(), selectorsDigest(intent));
    }

    private static String selectorsDigest(WorkIntent intent) {
        String material = intent.selectors()
                .stream()
                .map(selector -> selector.kind()
                        .name() + "\u001f" + selector.value())
                .collect(Collectors.joining("\u001e"));
        try {
            return HexFormatHolder.HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("work-intent selector digest unavailable", failure);
        }
    }

    /**
     * Returns whether an intent still satisfies this precondition.
     *
     * @param intent candidate current intent
     * @return true when all mutation identity fields match
     */
    public boolean matches(WorkIntent intent) {
        return intent != null
                && intentId.equals(intent.intentId())
                && participant.equals(intent.participant())
                && expectedVersion == intent.version()
                && authorityLineageId.equals(intent.authorityLineageId())
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
            throw new IOException("WORK_INTENT_MUTATION_PRECONDITION_STALE");
        }
    }

    /** Lazily exposes the immutable hexadecimal formatter used by validation. */
    private static final class HexFormatHolder {

        private static final java.util.HexFormat HEX = java.util.HexFormat.of();

        private HexFormatHolder() {
        }
    }
}
