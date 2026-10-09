package org.synesis.workspace.application.agent.workflow;

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
     * Not instantiable.
     */
    private NextActionProtocol() {
    }
}
