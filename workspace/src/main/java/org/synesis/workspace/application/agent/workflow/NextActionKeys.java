package org.synesis.workspace.application.agent.workflow;

/**
 * JSON keys of the next-action wire format that the projections write or read in more than one
 * place. Keys used at a single site stay inline next to the value they describe. They are shared
 * so that each key is spelled once.
 */
final class NextActionKeys {

    /**
     * Lifecycle state of a projection.
     */
    static final String STATE = "state";

    /**
     * Lane (session) a terminal or completed response refers to.
     */
    static final String LANE = "lane";

    /**
     * Human- or machine-readable reason accompanying a response.
     */
    static final String REASON = "reason";

    /**
     * Number of pending items behind the reported one.
     */
    static final String PENDING = "pending";

    /**
     * Identifier of a work intent.
     */
    static final String INTENT_ID = "intentId";

    /**
     * Identifier of a work group.
     */
    static final String WORK_GROUP_ID = "workGroupId";

    /**
     * Claim epoch (intent version) the response is bound to.
     */
    static final String CLAIM_EPOCH = "claimEpoch";

    /**
     * Participant handle of the caller.
     */
    static final String PARTICIPANT = "participant";

    /**
     * Authority lineage identifier shared across continuations.
     */
    static final String AUTHORITY_LINEAGE_ID = "authorityLineageId";

    /**
     * Human-readable summary of a finish payload.
     */
    static final String SUMMARY = "summary";

    /**
     * Projection of the caller's active intent.
     */
    static final String CURRENT_INTENT = "currentIntent";

    /**
     * Projection of an immutable snapshot.
     */
    static final String SNAPSHOT = "snapshot";

    /**
     * Projection of a work group.
     */
    static final String WORK_GROUP = "workGroup";

    /**
     * Capability name.
     */
    static final String CAPABILITY = "capability";

    /**
     * Handle that identifies a capability request.
     */
    static final String CAPABILITY_REQUEST_HANDLE = "capabilityRequestHandle";

    /**
     * Capability contract (inputs, output, behavior, acceptance tests).
     */
    static final String CONTRACT = "contract";

    /**
     * Dependencies an intent declared up front.
     */
    static final String KNOWN_DEPENDENCIES = "knownDependencies";

    /**
     * Fields a requester must supply.
     */
    static final String REQUIRED_FIELDS = "requiredFields";

    /**
     * Resource selectors claimed by an intent.
     */
    static final String SELECTORS = "selectors";

    /**
     * Decision descriptor the caller must complete.
     */
    static final String REVIEW_DECISION = "reviewDecision";

    /**
     * Participant acting as reviewer.
     */
    static final String REVIEWER_PARTICIPANT = "reviewerParticipant";

    /**
     * Protocol action the provider should perform next.
     */
    static final String NEXT_PROTOCOL_ACTION = "nextProtocolAction";

    /**
     * Protocol kind that qualifies {@link #NEXT_PROTOCOL_ACTION}.
     */
    static final String NEXT_PROTOCOL_KIND = "nextProtocolKind";

    /**
     * Executable arguments for {@link #NEXT_PROTOCOL_ACTION}.
     */
    static final String NEXT_PROTOCOL_PAYLOAD = "nextProtocolPayload";

    /**
     * Pending coordination requests addressed to the caller.
     */
    static final String PENDING_COORDINATION = "pendingCoordination";

    /**
     * Recovery-held work the caller may continue.
     */
    static final String CONTINUATION_CANDIDATES = "continuationCandidates";

    /**
     * Whether the caller must choose a review candidate explicitly.
     */
    static final String REVIEW_CANDIDATE_SELECTION_REQUIRED = "reviewCandidateSelectionRequired";

    /**
     * Identifier of the recovery-held intent a continuation would take over.
     */
    static final String SOURCE_INTENT_ID = "sourceIntentId";

    /**
     * Whether a continuation candidate can be taken by the caller.
     */
    static final String AVAILABLE = "available";

    /**
     * Not instantiable.
     */
    private NextActionKeys() {
    }
}
