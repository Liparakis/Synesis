package org.synesis.coordination.application;

import java.time.Duration;
import java.util.Objects;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;

/**
 * States how long a bound consumer may stay silent before it counts as absent, and names what a
 * binding becomes when its owner's lane ends first.
 *
 * <p>The dependency runs one way: the consumer needs the owner's work. An owner used to be let
 * go after this time, its unanswered consumer dropped and told to build on whatever was
 * integrated. With one public contract that every bound consumer checks (ADR-0092), a consumer
 * is not dropped: the owner waits while the consumer's lane is open, and a consumer silent for
 * this long is put to the operator, who starts an agent for its lane or cancels it. The time
 * therefore no longer ends the owner's wait. It marks the moment the run asks for help.</p>
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
     * Returns the moment a consumer that owes an action counts as absent: the wait after the
     * later of the record's last change and the consumer's last verified call. Whoever asks
     * (the guard that raises the operator request, the controller that resumes the owner for it)
     * reads the moment here, so they cannot disagree about it.
     *
     * @param request                    binding whose consumer owes an action
     * @param consumerLastCallEpochMillis last verified call of the consumer's participant, or zero
     * @return the moment in epoch milliseconds
     */
    public static long absentAtEpochMillis(CapabilityRequestRecord request, long consumerLastCallEpochMillis) {
        return Math.max(Objects.requireNonNull(request, "request").updatedAtEpochMillis(), consumerLastCallEpochMillis) + grace().toMillis();
    }

    /**
     * Reports whether the next move on a binding belongs to its consumer: binding again after a
     * contract revision, or checking a published implementation.
     *
     * @param request capability request
     * @return {@code true} while the consumer owes that action
     */
    public static boolean consumerOwesAction(CapabilityRequestRecord request) {
        CapabilityLifecycleState state = Objects.requireNonNull(request, "request").state();
        return state == CapabilityLifecycleState.REVISION_REQUESTED || state == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE || state == CapabilityLifecycleState.VALIDATING;
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
