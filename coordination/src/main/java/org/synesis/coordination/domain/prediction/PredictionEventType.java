package org.synesis.coordination.domain.prediction;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.synesis.coordination.exception.CoordinationArgumentException;


/**
 * Append-only events that advance a speculative prediction.
 */
public enum PredictionEventType {
    /**
     * Creates a prediction and its immutable contract.
     */
    PREDICTION_CREATED(0),
    /**
     * Routes a prediction to its owner supervisor.
     */
    PREDICTION_ROUTED(1),
    /**
     * Confirms owner receipt.
     */
    REQUEST_RECEIVED(2),
    /**
     * Accepts the requested contract exactly.
     */
    ACCEPTED_EXACT(3),
    /**
     * Accepts an equivalent contract.
     */
    ACCEPTED_EQUIVALENT(4),
    /**
     * Revises the requested contract.
     */
    CONTRACT_REVISED(5),
    /**
     * Starts owner implementation.
     */
    IMPLEMENTATION_STARTED(6),
    /**
     * Publishes an implementation reference.
     */
    PATCH_READY(7),
    /**
     * Announces capability availability.
     */
    CAPABILITY_AVAILABLE(8),
    /**
     * Starts requester validation.
     */
    VALIDATION_STARTED(9),
    /**
     * Retires resolved speculation.
     */
    SPECULATION_RETIRED(10),
    /**
     * Invalidates speculation and dependent work.
     */
    PREDICTION_INVALIDATED(11),
    /**
     * Rejects the capability request.
     */
    REQUEST_REJECTED(12),
    /**
     * Expires an unresolved prediction.
     */
    PREDICTION_EXPIRED(13),
    /**
     * Creates a claimable task.
     */
    TASK_CREATED(14),
    /**
     * Claims a task for one supervisor.
     */
    TASK_CLAIMED(15),
    /**
     * Releases a task claim.
     */
    TASK_RELEASED(16),
    /**
     * Assigns semantic ownership to a task capability.
     */
    OWNERSHIP_CLAIMED(17),
    /**
     * Releases semantic ownership from a task capability.
     */
    OWNERSHIP_RELEASED(18),
    /**
     * Creates a durable capability request handle and contract.
     */
    CAPABILITY_REQUEST_CREATED(19),
    /**
     * Revises an existing capability request contract.
     */
    CAPABILITY_REQUEST_CONTRACT_REVISED(20),
    /**
     * Accepts a capability request contract.
     */
    CAPABILITY_REQUEST_ACCEPTED(21),
    /**
     * Retired with the request that waited for its owner's answer (ADR-0092): a binding is
     * created already agreed, so there is nothing to reject. Nothing writes it and every
     * projection ignores it; the wire code is kept so it is never given to another event.
     */
    CAPABILITY_REQUEST_REJECTED(22),
    /**
     * Retired for the same reason: a binding is not cancelled while it waits, it is closed with
     * its lane. Nothing writes it and every projection ignores it; the wire code is kept.
     */
    CAPABILITY_REQUEST_CANCELLED(23),
    /**
     * Supersedes a capability request with a replacement.
     */
    CAPABILITY_REQUEST_SUPERSEDED(24),
    /**
     * Owner publishes an immutable implementation snapshot for a capability request.
     */
    CAPABILITY_IMPLEMENTATION_PUBLISHED(25),
    /**
     * Requester begins active validation of the current implementation revision.
     */
    CAPABILITY_VALIDATION_STARTED(26),
    /**
     * Requester accepts the implementation snapshot; capability transitions to VALIDATED.
     */
    CAPABILITY_IMPLEMENTATION_VALIDATED(27),
    /**
     * Requester rejects the implementation snapshot and requests a revision.
     */
    CAPABILITY_IMPLEMENTATION_REVISION_REQUIRED(28),
    /**
     * Agent requests task completion.
     */
    TASK_COMPLETION_REQUESTED(29),
    /**
     * Immutable task snapshot created from worker worktree commit.
     */
    TASK_SNAPSHOT_CREATED(30),
    /**
     * Task snapshot created but waiting for dependent tasks to complete.
     */
    TASK_WAITING_FOR_DEPENDENCIES(31),
    /**
     * Dedicated integration attempt started in external integration worktree.
     */
    INTEGRATION_ATTEMPT_STARTED(32),
    /**
     * Integration attempt failed build or test gate.
     */
    INTEGRATION_ATTEMPT_FAILED(33),
    /**
     * Integration attempt encountered git merge conflict.
     */
    INTEGRATION_CONFLICTED(34),
    /**
     * Verified integration commit created in integration worktree.
     */
    INTEGRATION_COMMIT_CREATED(35),
    /**
     * Control branch fast-forwarded to integration commit.
     */
    CONTROL_BRANCH_ADVANCED(36),
    /**
     * Task successfully integrated into control branch.
     */
    TASK_INTEGRATED(37),
    /**
     * Worker session finalized following successful task integration.
     */
    SESSION_FINALIZED(38),
    /**
     * Provider session marked abandoned due to verified process death beyond grace period.
     */
    SESSION_ABANDONED(39),
    /**
     * Ambient worker requests task cancellation.
     */
    TASK_CANCELLATION_REQUESTED(40),
    /**
     * Task transitioned to terminal cancelled state.
     */
    TASK_CANCELLED(41),
    /**
     * Capability dependency invalidated due to cancellation or abandonment of supplier task.
     */
    DEPENDENCY_INVALIDATED(42),
    /**
     * Announces an authenticated worker intent and atomically requested claims.
     */
    WORK_INTENT_ANNOUNCED(43),
    /**
     * Releases all claims associated with an authenticated worker intent.
     */
    WORK_INTENT_RELEASED(44),
    /**
     * Creates a signed coordination request between conflicting participants.
     */
    COORDINATION_REQUESTED(45),
    /**
     * Records the target participant's coordination response.
     */
    COORDINATION_RESPONDED(46),
    /**
     * Records verified activity for an authenticated participant.
     */
    PARTICIPANT_HEARTBEAT(47),
    /**
     * Atomically transfers an intent after an accepted handoff request.
     */
    CLAIM_HANDOFF_ACCEPTED(48),
    /**
     * Marks a participant abandoned after verified process absence beyond grace.
     */
    PARTICIPANT_ABANDONED(49),
    /**
     * Publishes a revision of the public contract over files a lane claims.
     */
    CONTRACT_PUBLISHED(50),
    /**
     * Binds a consumer's request record to one revision of a contract.
     */
    CONTRACT_DEPENDENCY_BOUND(51),
    /**
     * Retired with the contract store it belonged to: a revision now replaces its predecessor by
     * being published. Nothing writes it and every projection ignores it; the wire code is kept so
     * it is never given to another event.
     */
    CONTRACT_SUPERSEDED(52),
    /**
     * Creates a durable logical work group.
     */
    WORK_GROUP_CREATED(53),
    /**
     * Issues a targeted lane join or continuation grant.
     */
    LANE_GRANT_ISSUED(54),
    /**
     * Consumes a single-use lane grant.
     */
    LANE_GRANT_CONSUMED(55),
    /**
     * Revokes a lane grant or lane authority epoch.
     */
    LANE_REVOKED(56),
    /**
     * Changes logical work-group lifecycle without closing sibling lanes.
     */
    WORK_GROUP_STATUS_CHANGED(57),
    /**
     * Fences a participant after verified process loss without releasing its claims.
     */
    PARTICIPANT_SUSPENDED(58),
    /**
     * Records that an immutable recovery snapshot exists for a suspended participant.
     */
    RECOVERY_SNAPSHOT_HELD(59),
    /**
     * Explicitly revokes a participant lane and releases its claims.
     */
    PARTICIPANT_REVOKED(60),
    /**
     * Idempotently acknowledges a durable inbox item.
     */
    INBOX_ITEM_ACKNOWLEDGED(61),
    /**
     * Explicitly cancels a participant lane and fences its epoch.
     */
    PARTICIPANT_CANCELLED(62),
    /**
     * Atomically transfers a held recovery lane into a new participant lane.
     */
    LANE_CONTINUATION_ACCEPTED(63),
    /**
     * Records a clean connection shutdown without treating the lane as completed.
     */
    PARTICIPANT_DETACHED(64),
    /**
     * Pins a verified prepared lane tree before mutation authority is fenced.
     */
    COMPLETION_PREPARED(65),
    /**
     * Records a structurally invalid immutable integration candidate.
     */
    INTEGRATION_BLOCKED(66),
    /**
     * Records a valid immutable candidate materialized into a repair lane.
     */
    REPAIR_REQUIRED(67),
    /**
     * Atomically transfers reserved selectors into a new repair lane.
     */
    REPAIR_LANE_CREATED(68),
    /**
     * Unfences a prepared but unpublished completion at a new claim epoch.
     */
    COMPLETION_UNWOUND(69),
    /**
     * Records an authenticated review decision for an immutable task snapshot.
     */
    REVIEW_VALIDATION_RECORDED(70),
    /**
     * Commits an irreversible fence for one exact provider session.
     */
    PROVIDER_SESSION_TERMINALIZED(71),
    /**
     * Extends one active intent without releasing its existing claims or dependencies.
     */
    WORK_INTENT_SCOPE_EXTENDED(72),
    /**
     * Records a block that only someone with authority over the project can clear.
     */
    OPERATOR_REQUEST_RAISED(73),
    /**
     * Records that the condition behind an operator request was observed not to hold.
     */
    OPERATOR_REQUEST_CLOSED(74),
    /**
     * Records what a consumer needs from a claimed file that its contract does not say.
     */
    CONTRACT_CHANGE_REQUESTED(75),
    /**
     * Records the owner's answer to one contract change request.
     */
    CONTRACT_CHANGE_ANSWERED(76),
    /**
     * Records that a lane ended: its contracts are frozen and its change requests closed.
     */
    CONTRACT_LANE_ENDED(77),
    /**
     * Records text one lane sent to the other lanes of a conversation. It binds nothing.
     */
    CONVERSATION_MESSAGE_SENT(78),
    /**
     * Records how far a lane has been handed the messages addressed to it.
     */
    CONVERSATION_MESSAGES_DELIVERED(79);


    private static final Map<Integer, PredictionEventType> BY_WIRE_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(PredictionEventType::wireCode, Function.identity()));

    private final int wireCode;

    PredictionEventType(int wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * Resolves a stable persisted wire code.
     *
     * @param wireCode wire code
     * @return event type
     */
    public static PredictionEventType fromWireCode(int wireCode) {
        PredictionEventType type = BY_WIRE_CODE.get(wireCode);
        if (type == null) {
            throw new CoordinationArgumentException("unknown event wire code: " + wireCode);
        }
        return type;
    }

    /**
     * Returns the stable persisted wire code for this event kind.
     *
     * @return wire code
     */
    public int wireCode() {
        return wireCode;
    }

    /**
     * Tells whether this event records activity or speech only. Such an event changes no claim,
     * grant, snapshot or capability state, so nothing projected before it is made stale by it:
     * a heartbeat, a message between lanes, a mark of delivery.
     *
     * @return true when no lane state can differ because of this event
     */
    public boolean recordsNoStateChange() {
        return this == PARTICIPANT_HEARTBEAT || this == CONVERSATION_MESSAGE_SENT || this == CONVERSATION_MESSAGES_DELIVERED;
    }

    /**
     * Tells whether this event advances a prediction's own lifecycle, as opposed to belonging to another
     * projection of the shared event sequence.
     *
     * @return true for the prediction lifecycle events
     */
    boolean isPredictionLifecycle() {
        return switch (this) {
            case PREDICTION_CREATED, PREDICTION_ROUTED, REQUEST_RECEIVED, ACCEPTED_EXACT, ACCEPTED_EQUIVALENT,
                 CONTRACT_REVISED, IMPLEMENTATION_STARTED, PATCH_READY, CAPABILITY_AVAILABLE, VALIDATION_STARTED,
                 SPECULATION_RETIRED, PREDICTION_INVALIDATED, REQUEST_REJECTED, PREDICTION_EXPIRED -> true;
            default -> false;
        };
    }
}
