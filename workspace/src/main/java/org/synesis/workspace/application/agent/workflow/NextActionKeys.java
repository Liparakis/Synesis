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
     * Tool a recommended or completion request names.
     */
    static final String TOOL = "tool";

    /**
     * Arguments passed to {@link #TOOL}.
     */
    static final String ARGUMENTS = "arguments";

    /**
     * Kind of a protocol payload, request, or resource selector.
     */
    static final String KIND = "kind";

    /**
     * Payload that accompanies a {@link #KIND}.
     */
    static final String PAYLOAD = "payload";

    /**
     * Identifier of an immutable snapshot.
     */
    static final String SNAPSHOT_ID = "snapshotId";

    /**
     * Revision of a negotiated capability contract.
     */
    static final String CONTRACT_REVISION = "contractRevision";

    /**
     * Identifier of the lane (intent) a completion refers to.
     */
    static final String LANE_ID = "laneId";

    /**
     * Identifier of an inbox item.
     */
    static final String INBOX_ITEM_ID = "inboxItemId";

    /**
     * Identifier of the intent under review.
     */
    static final String REVIEWED_INTENT_ID = "reviewedIntentId";

    /**
     * Participant whose work is under review.
     */
    static final String REVIEWED_PARTICIPANT_ID = "reviewedParticipantId";

    /**
     * Identifier of a coordination request.
     */
    static final String REQUEST_ID = "requestId";

    /**
     * Identifier of a lane grant.
     */
    static final String GRANT_ID = "grantId";

    /**
     * Participant a grant is addressed to.
     */
    static final String TARGET_PARTICIPANT = "targetParticipant";

    /**
     * Time after which recovery-held work may be taken over.
     */
    static final String RECOVERY_DEADLINE_EPOCH_MILLIS = "recoveryDeadlineEpochMillis";

    /**
     * Review candidates the caller may request admission to.
     */
    static final String ACTIONABLE_REVIEW_CANDIDATES = "actionableReviewCandidates";

    /**
     * Argument that asks for the exact finish projection.
     */
    static final String COMPLETION_REQUESTED = "completionRequested";

    /**
     * Ready-made call that asks for the exact finish projection.
     */
    static final String COMPLETION_REQUEST = "completionRequest";

    /**
     * Access the reviewer has to the reviewed snapshot.
     */
    static final String REVIEW_ACCESS = "reviewAccess";

    /**
     * Lifecycle state of a snapshot.
     */
    static final String SNAPSHOT_STATE = "snapshotState";

    /**
     * Outcome of the owner-defined validation run before publication.
     */
    static final String PRE_PUBLICATION_VALIDATION = "prePublicationValidation";

    /**
     * Outcome of sealing a session after its last completion.
     */
    static final String SESSION_TERMINATION = "sessionTermination";

    /**
     * Whether a validation workspace is available to the requester.
     */
    static final String VALIDATION_WORKSPACE_AVAILABLE = "validationWorkspaceAvailable";

    /**
     * Evidence a requester needs to validate an implementation.
     */
    static final String VALIDATION_EVIDENCE = "validationEvidence";

    /**
     * Snapshot of the latest rejected review.
     */
    static final String LATEST_REJECTED_SNAPSHOT_ID = "latestRejectedSnapshotId";

    /**
     * Whether a capability owner's implementation has been validated.
     */
    static final String IMPLEMENTATION_VALIDATED = "implementationValidated";

    /**
     * Selectors a reviewer declared as its review targets.
     */
    static final String REVIEW_TARGETS = "reviewTargets";

    /**
     * Optimistic version of a work group.
     */
    static final String WORK_GROUP_VERSION = "workGroupVersion";

    /**
     * Event-store head sequence a finish payload is fenced to.
     */
    static final String EXPECTED_REVISION = "expectedRevision";

    /**
     * Whether a snapshot is subject to review.
     */
    static final String REVIEW_REQUIRED = "reviewRequired";

    /**
     * Phase of a validation failure.
     */
    static final String PHASE = "phase";

    /**
     * Status of an intent or coordination request.
     */
    static final String STATUS = "status";

    /**
     * Participants of a collaboration projection.
     */
    static final String PARTICIPANTS = "participants";

    /**
     * Intents of a collaboration projection.
     */
    static final String INTENTS = "intents";

    /**
     * Revision number of a published capability implementation.
     */
    static final String IMPLEMENTATION_REVISION = "implementationRevision";

    /**
     * Stable identity of a workflow action.
     */
    static final String ACTION_ID = "actionId";

    /**
     * Delivery guarantee of a workflow action.
     */
    static final String DELIVERY = "delivery";

    /**
     * Whether the provider must acknowledge a workflow action.
     */
    static final String ACKNOWLEDGEMENT_REQUIRED = "acknowledgementRequired";

    /**
     * Whether a capability owner has accepted a request and not yet published its implementation.
     */
    static final String IMPLEMENTATION_PENDING = "implementationPending";

    /**
     * Whether a lane that asked to finish has changed nothing it could publish.
     */
    static final String NOTHING_TO_PUBLISH = "nothingToPublish";

    /**
     * Not instantiable.
     */
    private NextActionKeys() {
    }
}
