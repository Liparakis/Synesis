package org.synesis.coordination.application;

import java.time.Duration;
import java.util.Objects;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;

/**
 * Bounds how long a capability owner waits for a requester to answer a contract the owner
 * proposed, and names what the request becomes when the owner finishes without that answer.
 *
 * <p>The dependency runs one way: the requester needs the owner's work, the owner needs nothing
 * from the requester. An owner made to wait without limit for an acceptance keeps an agent
 * reserved for a peer that may be late or gone, and the two can end up waiting on each other. So
 * the owner waits a bounded time. If the answer has not come by then, it may finish; its request
 * is superseded by the work it integrates, and the requester is told that the owner went ahead,
 * possibly with a different design than the requester asked for, and that the owner's files are
 * free to claim if a change is needed. Reworking a released file later costs less than holding an
 * agent idle now.</p>
 *
 * <p>The rule grants no authority. It reads one request record and a clock.</p>
 */
public final class RequesterAnswerPolicy {

    /**
     * Reason recorded on a request that its owner superseded by finishing without an answer.
     */
    public static final String OWNER_FINISHED_WITHOUT_ANSWER = CapabilityRequestRecord.OWNER_FINISHED_WITHOUT_ANSWER;

    /**
     * System property that overrides the wait, in whole seconds.
     */
    public static final String GRACE_PROPERTY = "synesis.requesterAnswerGraceSeconds";

    /**
     * Environment variable that overrides the wait, in whole seconds, when the property is unset.
     */
    static final String GRACE_VARIABLE = "SYNESIS_REQUESTER_ANSWER_GRACE_SECONDS";

    private static final Duration DEFAULT_GRACE = Duration.ofSeconds(120);

    private static final long LONGEST_GRACE_SECONDS = Duration.ofHours(24).toSeconds();

    private RequesterAnswerPolicy() {
    }

    /**
     * Returns how long an owner waits for an answer to a contract it proposed.
     *
     * @return the configured wait, or two minutes when none is configured or the value is not a
     *         whole number of seconds between zero and one day
     */
    static Duration grace() {
        String configured = System.getProperty(GRACE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(GRACE_VARIABLE);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_GRACE;
        }
        try {
            long seconds = Long.parseLong(configured.trim());
            return seconds < 0 || seconds > LONGEST_GRACE_SECONDS ? DEFAULT_GRACE : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notANumber) {
            return DEFAULT_GRACE;
        }
    }

    /**
     * Reports whether the next move on a request belongs to the requester because the owner
     * proposed a contract that has not been answered.
     *
     * @param request capability request
     * @return {@code true} while the owner's proposal is unanswered
     */
    public static boolean awaitsRequesterAnswer(CapabilityRequestRecord request) {
        return Objects.requireNonNull(request, "request").state() == CapabilityLifecycleState.REVISION_REQUESTED;
    }

    /**
     * Returns when the owner may stop waiting for the answer to its proposal.
     *
     * @param request capability request whose proposal is unanswered
     * @return the moment the proposal was recorded plus the wait, in epoch milliseconds
     */
    public static long answerDeadlineEpochMillis(CapabilityRequestRecord request) {
        return Objects.requireNonNull(request, "request").updatedAtEpochMillis() + grace().toMillis();
    }

    /**
     * Reports whether the owner must still wait for the requester's answer.
     *
     * @param request   capability request
     * @param nowMillis current time in epoch milliseconds
     * @return {@code true} while the proposal is unanswered and its deadline has not passed
     */
    public static boolean ownerMustWait(CapabilityRequestRecord request, long nowMillis) {
        return awaitsRequesterAnswer(request) && nowMillis < answerDeadlineEpochMillis(request);
    }

    /**
     * Reports whether a request ended because its owner finished without the requester's answer.
     *
     * <p>Such a request binds nobody: the requester holds no agreement, and what it depended on
     * is in the owner's integrated work.</p>
     *
     * @param request capability request
     * @return {@code true} for a request superseded for that reason
     */
    public static boolean ownerFinishedWithoutAnswer(CapabilityRequestRecord request) {
        return Objects.requireNonNull(request, "request").ownerFinishedWithoutAnswer();
    }
}
