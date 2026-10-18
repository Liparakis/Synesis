package org.synesis.workspace.application.agent.workflow;

import org.synesis.workspace.agent.AgentResponse;

/**
 * Protocol vocabulary of the next-action wire format: action names, kinds and state values.
 * Shared so the projections and the review helpers agree on one spelling.
 */
final class NextActionProtocol {

    /**
     * Tell the provider to wait.
     */
    static final String ACTION_WAIT = "wait";

    /**
     * Tell the provider to finish its lane.
     */
    static final String ACTION_FINISH_LANE = "finish_lane";

    /**
     * Tell the provider to publish an implementation revision.
     */
    static final String ACTION_IMPLEMENT = "implement";

    /**
     * Tell the provider to open a coordination request.
     */
    static final String ACTION_REQUEST_COORDINATION = "request_coordination";

    /**
     * Tell the provider to answer a coordination request.
     */
    static final String ACTION_RESPOND_COORDINATION = "respond_coordination";

    /**
     * Tell the provider to record a review decision.
     */
    static final String ACTION_REVIEW_DECISION = "review_decision";

    /**
     * Tool the provider calls to read the next action.
     */
    static final String TOOL_GET_NEXT_ACTION = "get_next_action";

    /**
     * Tool the provider calls to open or restore its session.
     */
    static final String TOOL_ENSURE_SESSION = "ensure_session";

    /**
     * Workspace tool that reads a file.
     */
    static final String TOOL_READ_FILE = "read_file";

    /**
     * Workspace tool that patches a file.
     */
    static final String TOOL_APPLY_PATCH = "apply_patch";

    /**
     * Workspace tool that runs a command.
     */
    static final String TOOL_RUN_COMMAND = "run_command";

    /**
     * Tool that ends a lane without a result.
     */
    static final String TOOL_CANCEL_LANE = "cancel_lane";

    /**
     * Protocol kind of a review decision.
     */
    static final String KIND_REVIEW_VALIDATION = "review_validation";

    /**
     * Protocol kind of a request to join a work group.
     */
    static final String KIND_WORK_GROUP_JOIN = "work_group_join";

    /**
     * Protocol kind of a review request awaiting its owner's answer.
     */
    static final String KIND_REVIEW_ADMISSION = "review_admission";

    /**
     * Phase reported for a failure of the validation run before publication.
     */
    static final String PHASE_PRE_PUBLICATION = "pre_publication";

    /**
     * Protocol kind and request kind for taking over recovery-held work.
     */
    static final String KIND_CONTINUATION = "continuation";

    /**
     * State of a session that was terminalized.
     */
    static final String STATE_SESSION_TERMINAL = "SESSION_TERMINAL";

    /**
     * State of a lane that finished its work.
     */
    static final String STATE_COMPLETED = "COMPLETED";

    /**
     * State of a project in which every announced lane is finished or cancelled, as told to a
     * session that holds no lane.
     */
    static final String STATE_NO_OPEN_WORK = "NO_OPEN_WORK";

    /**
     * State of a lane its own session cancelled.
     */
    static final String STATE_CANCELLED = "CANCELLED";

    /**
     * State of a producer that must publish a corrected revision.
     */
    static final String STATE_REVISION_REQUIRED = "REVISION_REQUIRED";

    /**
     * State of a participant that may take over recovery-held work.
     */
    static final String STATE_RECOVERY_CONTINUATION_AVAILABLE = "RECOVERY_CONTINUATION_AVAILABLE";

    /**
     * State of a clean lane that may finish without publishing a change.
     */
    static final String STATE_NO_CHANGE_COMPLETION_READY = "NO_CHANGE_COMPLETION_READY";

    /**
     * State of a producer waiting for its reviewer to be admitted and to consume a grant.
     */
    static final String STATE_REVIEW_GRANT_PENDING = "REVIEW_GRANT_PENDING";

    /**
     * Protocol kind of an answer to a coordination request.
     */
    static final String KIND_COORDINATION_RESPONSE = "coordination_response";

    /**
     * Protocol kind of a requester's verdict on a published implementation.
     */
    static final String KIND_IMPLEMENTATION_VALIDATION = "implementation_validation";

    /**
     * Delivery guarantee every workflow envelope declares.
     */
    static final String DELIVERY_AT_LEAST_ONCE = "AT_LEAST_ONCE";

    /**
     * Snapshot and integration state of a completion that publishes nothing.
     */
    static final String STATE_NOT_REQUIRED = "NOT_REQUIRED";

    /**
     * Finish evidence no longer matches the lane.
     */
    static final String REASON_SNAPSHOT_COMPLETION_EVIDENCE_STALE = "SNAPSHOT_COMPLETION_EVIDENCE_STALE";

    /**
     * A finish argument that cannot go stale differs from the lane's: it was copied wrong. The refusal names
     * the field and the value expected.
     */
    static final String REASON_SNAPSHOT_COMPLETION_EVIDENCE_MISMATCH = "SNAPSHOT_COMPLETION_EVIDENCE_MISMATCH";

    /**
     * Finish evidence is missing.
     */
    static final String REASON_SNAPSHOT_COMPLETION_EVIDENCE_REQUIRED = "SNAPSHOT_COMPLETION_EVIDENCE_REQUIRED";

    /**
     * The session's terminal state could not be read.
     */
    static final String REASON_SESSION_TERMINAL_STATE_UNAVAILABLE = AgentResponse.SESSION_TERMINAL_STATE_UNAVAILABLE;

    /**
     * The caller has no active intent.
     */
    static final String REASON_NO_ACTIVE_INTENT = "NO_ACTIVE_INTENT";

    /**
     * The work group changed since the evidence was issued.
     */
    static final String REASON_WORK_GROUP_VERSION_STALE = "WORK_GROUP_VERSION_STALE";

    /**
     * The intent's work group does not exist.
     */
    static final String REASON_WORK_GROUP_NOT_FOUND = "WORK_GROUP_NOT_FOUND";

    /**
     * The intent's work group is no longer active.
     */
    static final String REASON_WORK_GROUP_NOT_ACTIVE = "WORK_GROUP_NOT_ACTIVE";

    /**
     * The lane diff could not be inspected.
     */
    static final String REASON_SNAPSHOT_DIFF_INSPECTION_FAILED = "SNAPSHOT_DIFF_INSPECTION_FAILED";

    /**
     * The provider session could not be completed.
     */
    static final String REASON_SESSION_COMPLETE_FAILED = "SESSION_COMPLETE_FAILED";

    /**
     * A snapshot for the lane revision exists, so it cannot finish without change.
     */
    static final String REASON_NO_CHANGE_SNAPSHOT_OBLIGATION = "NO_CHANGE_SNAPSHOT_OBLIGATION";

    /**
     * No-change evidence does not match the lane.
     */
    static final String REASON_NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH = "NO_CHANGE_COMPLETION_EVIDENCE_MISMATCH";

    /**
     * The completion revision was superseded.
     */
    static final String REASON_COMPLETION_REVISION_STALE = "COMPLETION_REVISION_STALE";

    /**
     * The owner still awaits the requester's verdict on a published implementation.
     */
    static final String REASON_IMPLEMENTATION_AWAITING_VALIDATION = "IMPLEMENTATION_AWAITING_VALIDATION";

    /**
     * Not instantiable.
     */
    private NextActionProtocol() {
    }
}
