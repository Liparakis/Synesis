package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.synesis.coordination.application.ContinuationResult;
import org.synesis.coordination.application.ContractService;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.coordination.application.WorkIntentMutationPrecondition;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.NoChangeCompletion;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.ReviewPolicy;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.domain.identity.ClaimEpoch;
import org.synesis.coordination.domain.identity.ContractId;
import org.synesis.coordination.domain.identity.ContractRevision;
import org.synesis.coordination.domain.identity.LaneGrantId;
import org.synesis.coordination.domain.identity.ParticipantId;
import org.synesis.coordination.domain.identity.WorkGroupId;
import org.synesis.coordination.domain.identity.WorkGroupVersion;
import org.synesis.coordination.domain.identity.WorkIntentId;
import org.synesis.coordination.persistence.PredictionEventStore;

import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

/**
 * Resolves authenticated workspace sessions into collaboration intents and claims.
 */
public final class WorkspaceCollaborationService {

    /**
     * Indicates that the caller must repeat a lane extension with the exact current epoch.
     */
    public static final class ScopeExtensionRequiredException extends IOException {

        private static final long serialVersionUID = 1L;

        private final transient WorkIntent currentIntent;

        /**
         * Creates the typed response needed to project a fenced retry.
         *
         * @param currentIntent exact active intent whose scope was requested for extension
         */
        public ScopeExtensionRequiredException(WorkIntent currentIntent) {
            super("SCOPE_EXTENSION_PRECONDITION_REQUIRED");
            this.currentIntent = Objects.requireNonNull(currentIntent, "currentIntent");
        }

        /**
         * Returns the exact current intent for the retry projection.
         *
         * @return current active intent
         */
        public WorkIntent currentIntent() {
            return currentIntent;
        }
    }

    private final ProjectApplicationService projectService = new ProjectApplicationService();
    private final ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
    private final SessionAuthorityResolver authorityResolver = new SessionAuthorityResolver(bindingService);
    private final ProviderManualService manualService = new ProviderManualService();
    private final WorkspaceReadinessService readinessService = new WorkspaceReadinessService(bindingService);
    private final WorkIntentAnnouncement announcements = new WorkIntentAnnouncement(projectService, manualService, authorityResolver);
    private final NoChangeLaneCompletion noChangeCompletions = new NoChangeLaneCompletion(projectService, manualService, readinessService);
    private final RecoveryLaneContinuation recoveryContinuations = new RecoveryLaneContinuation(projectService, manualService, authorityResolver);

    /**
     * Creates a workspace collaboration adapter.
     */
    public WorkspaceCollaborationService() {
    }

    /**
     * Finds the lane a session holds. A lane it announced carries the identifier derived from the
     * session; one it took over by continuation keeps the identifier the continuation gave it and
     * is found by the session's participant. Looked up by the derived identifier alone, a session
     * holding a continued lane appeared to hold none.
     *
     * @param store     current project projection
     * @param provider  provider ID
     * @param sessionId provider session ID
     * @return the held lane, when there is one
     */
    static java.util.Optional<WorkIntent> heldIntent(PredictionEventStore store, String provider, String sessionId) {
        String participant = participantHandle(sessionId);
        return store.collaborationProjection().intent(LaneIdentities.sessionIntentId(provider, sessionId))
                .or(() -> store.collaborationProjection().activeIntents().stream().filter(candidate -> candidate.participant().equals(participant)).findFirst());
    }

    /**
     * Returns the stable opaque participant handle for a connection.
     *
     * @param connectionInstanceId connection ID
     * @return opaque participant handle
     */
    public static String participantHandle(String connectionInstanceId) {
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        return "agt_" + LaneIdentities.connectionUuid(connectionInstanceId);
    }

    /**
     * Derives the deterministic successor intent identity for one exact caller and source epoch.
     *
     * @param sourceIntentId    source intent
     * @param targetParticipant fresh participant handle
     * @param claimEpoch        source claim epoch
     * @return successor intent identity
     */
    static UUID recoveryContinuationIntentId(UUID sourceIntentId, String targetParticipant, long claimEpoch) {
        Objects.requireNonNull(sourceIntentId, "sourceIntentId");
        Objects.requireNonNull(targetParticipant, "targetParticipant");
        return UUID.nameUUIDFromBytes(("synesis-recovery-intent:" + sourceIntentId + ":" + targetParticipant + ":" + claimEpoch).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Derives the deterministic single-use grant identity for one exact caller and source epoch.
     *
     * @param sourceIntentId    source intent
     * @param targetParticipant fresh participant handle
     * @param claimEpoch        source claim epoch
     * @return successor grant identity
     */
    static UUID recoveryContinuationGrantId(UUID sourceIntentId, String targetParticipant, long claimEpoch) {
        Objects.requireNonNull(sourceIntentId, "sourceIntentId");
        Objects.requireNonNull(targetParticipant, "targetParticipant");
        return UUID.nameUUIDFromBytes(("synesis-recovery-grant:" + sourceIntentId + ":" + targetParticipant + ":" + claimEpoch).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Announces an intent for one verified provider session.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId connection ID
     * @param goal                 goal
     * @param acceptance           acceptance criteria
     * @param selectors            requested selectors
     * @return claim result
     * @throws Exception if project authority, session state, or event persistence fails
     */
    public ClaimResult announce(Path projectRoot, String provider, String connectionInstanceId, String goal, String acceptance, List<ResourceSelector> selectors) throws Exception {
        return announce(projectRoot, provider, connectionInstanceId, goal, acceptance, selectors, null);
    }

    /**
     * Announces an intent in an optional logical work group.
     *
     * @param projectRoot          project root
     * @param provider             provider
     * @param connectionInstanceId connection ID
     * @param goal                 goal
     * @param acceptance           acceptance
     * @param selectors            claims
     * @param workGroupId          group ID
     * @return claim result
     * @throws Exception if project authority, session state, or event persistence fails
     */
    public ClaimResult announce(Path projectRoot, String provider, String connectionInstanceId, String goal, String acceptance, List<ResourceSelector> selectors, UUID workGroupId) throws Exception {
        return announce(projectRoot, provider, connectionInstanceId, goal, acceptance, selectors, workGroupId, WorkIntent.Role.PRODUCER, List.of(), List.of());
    }

    /**
     * Announces an intent with explicit review-routing metadata.
     *
     * @param projectRoot           project root
     * @param provider              provider ID
     * @param connectionInstanceId  connection ID
     * @param goal                  goal
     * @param acceptance            acceptance criteria
     * @param selectors             claims
     * @param workGroupId           group ID
     * @param role                  semantic role
     * @param reviewTargetSelectors non-ownership review selectors
     * @return claim result
     * @throws Exception resolution or append failure
     */
    public ClaimResult announce(Path projectRoot,
            String provider,
            String connectionInstanceId,
            String goal,
            String acceptance,
            List<ResourceSelector> selectors,
            UUID workGroupId,
            WorkIntent.Role role,
            List<ResourceSelector> reviewTargetSelectors) throws Exception {
        return announce(projectRoot, provider, connectionInstanceId, goal, acceptance, selectors, workGroupId, role, reviewTargetSelectors, List.of());
    }

    /**
     * Announces an intent with explicit review-routing metadata.
     *
     * @param projectRoot           project root
     * @param provider              provider ID
     * @param connectionInstanceId  connection ID
     * @param goal                  goal
     * @param acceptance            acceptance criteria
     * @param selectors             ownership selectors for this intent
     * @param workGroupId           group ID
     * @param role                  semantic producer or reviewer role
     * @param reviewTargetSelectors non-ownership selectors identifying producer work this reviewer
     *                              may review
     * @param knownDependencies     capability identifiers explicitly required by this intent
     * @return claim result
     * @throws Exception resolution or append failure
     */
    public ClaimResult announce(Path projectRoot,
            String provider,
            String connectionInstanceId,
            String goal,
            String acceptance,
            List<ResourceSelector> selectors,
            UUID workGroupId,
            WorkIntent.Role role,
            List<ResourceSelector> reviewTargetSelectors,
            List<String> knownDependencies) throws Exception {
        return announce(projectRoot, provider, connectionInstanceId, goal, acceptance, selectors, workGroupId, role,
                reviewTargetSelectors, knownDependencies, null, null);
    }

    /**
     * Announces a new lane or extends this binding's existing lane under exact scope preconditions.
     *
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact bound connection
     * @param goal optional task description
     * @param acceptance optional acceptance description
     * @param selectors requested ownership selectors
     * @param workGroupId optional existing group for a new lane
     * @param role lane role
     * @param reviewTargetSelectors non-ownership review targets
     * @param knownDependencies requested capability dependencies
     * @param expectedIntentId server-projected current intent, or {@code null} for ordinary admission
     * @param expectedClaimEpoch server-projected current epoch, or {@code null} for ordinary admission
     * @return complete acquisition result
     * @throws Exception resolution or append failure
     */
    public ClaimResult announce(Path projectRoot,
            String provider,
            String connectionInstanceId,
            String goal,
            String acceptance,
            List<ResourceSelector> selectors,
            UUID workGroupId,
            WorkIntent.Role role,
            List<ResourceSelector> reviewTargetSelectors,
            List<String> knownDependencies,
            UUID expectedIntentId,
            Long expectedClaimEpoch) throws Exception {
        return announcements.announce(projectRoot, provider, connectionInstanceId, goal, acceptance, selectors, workGroupId, role, reviewTargetSelectors, knownDependencies, expectedIntentId, expectedClaimEpoch);
    }

    /**
     * Joins an immutable conflict repair lane from a newly authenticated isolated binding. The local
     * project identity is the recovery authority; the old lane and snapshot remain immutable while
     * the new binding receives a fresh intent ID and claim epoch.
     *
     * @param projectRoot          control project root
     * @param provider             provider ID
     * @param connectionInstanceId exact new connection
     * @param repairIntentId       currently reserved repair intent
     * @param snapshotId           immutable conflicting snapshot
     * @return acquired claim result
     * @throws Exception resolution, materialization, or append failure
     */
    public ClaimResult joinRepair(Path projectRoot, String provider, String connectionInstanceId, UUID repairIntentId, String snapshotId) throws Exception {
        return announcements.joinRepair(projectRoot, provider, connectionInstanceId, repairIntentId, snapshotId);
    }

    /**
     * Releases the exact session intent and all of its claims.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void release(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        withSession(projectRoot, provider, connectionInstanceId, (store, identity, binding) -> {
            WorkIntentService service = new WorkIntentService(store, identity);
            WorkIntent current = heldIntent(store, provider, binding.sessionId()).orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
            service.release(current.intentId(), participantHandle(binding.sessionId()), WorkIntentMutationPrecondition.capture(current));
            return null;
        });
    }

    /**
     * Completes the exact bound intent successfully without publishing a snapshot.
     *
     * <p>All caller-selected identifiers are treated as optimistic evidence.
     * The participant, binding identity, authority lineage, and workspace commit are derived from the
     * verified binding and current intent.</p>
     *
     * @param projectRoot              project root
     * @param provider                 provider ID
     * @param connectionInstanceId     exact connection ID
     * @param expectedIntentId         expected active intent
     * @param expectedWorkGroupId      expected work group
     * @param expectedClaimEpoch       expected claim epoch
     * @param expectedWorkGroupVersion expected work-group version
     * @param expectedRevision         expected project event revision
     * @param expectedParticipant      expected participant handle
     * @param summary                  completion explanation
     * @return durable no-change completion evidence
     * @throws Exception when authority, workspace, or lifecycle evidence is stale
     */
    public NoChangeCompletion completeNoChange(Path projectRoot,
            String provider,
            String connectionInstanceId,
            UUID expectedIntentId,
            UUID expectedWorkGroupId,
            long expectedClaimEpoch,
            long expectedWorkGroupVersion,
            long expectedRevision,
            String expectedParticipant,
            String summary) throws Exception {
        return noChangeCompletions.completeNoChange(projectRoot, provider, connectionInstanceId, expectedIntentId, expectedWorkGroupId, expectedClaimEpoch, expectedWorkGroupVersion, expectedRevision, expectedParticipant, summary);
    }

    /**
     * Detaches the exact session lane after a clean connection shutdown.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void detach(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        withSession(projectRoot, provider, connectionInstanceId, (store, identity, binding) -> {
            new WorkIntentService(store, identity).detach(participantHandle(binding.sessionId()));
            return null;
        });
    }

    /**
     * Opens a request against a conflicting intent owned by another participant.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @param conflictingIntentId  conflicting intent
     * @param kind                 request kind
     * @param proposal             proposal
     * @return durable request
     * @throws Exception when resolution or append fails
     */
    public CoordinationRequest request(Path projectRoot, String provider, String connectionInstanceId, UUID conflictingIntentId, CoordinationRequest.Kind kind, String proposal) throws Exception {
        return request(projectRoot, provider, connectionInstanceId, conflictingIntentId, kind, proposal, null);
    }

    /**
     * Opens a request optionally bound to one immutable review candidate.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @param conflictingIntentId  conflicting intent
     * @param kind                 request kind
     * @param proposal             proposal
     * @param snapshotId           exact review candidate, or {@code null}
     * @return durable request
     * @throws Exception when resolution or append fails
     */
    public CoordinationRequest request(Path projectRoot, String provider, String connectionInstanceId, UUID conflictingIntentId, CoordinationRequest.Kind kind, String proposal, String snapshotId) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = kind == CoordinationRequest.Kind.REVIEW ? reviewBinding(location, provider, connectionInstanceId) : binding(location, provider, connectionInstanceId);
        NodeIdentity identity = location.loadOrCreateIdentity();
        PredictionEventStore store = location.openCoordinationStore();
        if (kind == CoordinationRequest.Kind.REVIEW && "COMPLETED".equalsIgnoreCase(binding.status())) {
            String participant = participantHandle(binding.sessionId());
            WorkIntent target = store.collaborationProjection().intent(conflictingIntentId).orElseThrow(() -> new IOException("INTENT_NOT_FOUND"));
            boolean sameCompletedGroup = store.taskCompletionProjection().allSnapshots().stream()
                    .anyMatch(snapshot -> snapshot.provenance().participant().equals(participant) && snapshot.provenance().workGroupId().equals(target.workGroupId()));
            boolean activeTarget = store.collaborationProjection().activeIntents().stream().anyMatch(intent -> intent.intentId().equals(target.intentId()) && !intent.participant().equals(participant));
            if (!sameCompletedGroup || !activeTarget) {
                throw new IOException("COMPLETED_REVIEW_SCOPE_MISMATCH");
            }
        }
        return new WorkIntentService(store, identity).request(participantHandle(binding.sessionId()), conflictingIntentId, kind, proposal, snapshotId);
    }

    /**
     * Responds to a request addressed to the exact provider session.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @param requestId            request ID
     * @param status               response status
     * @param proposal             revised proposal
     * @throws Exception when resolution or append fails
     */
    public void respond(Path projectRoot, String provider, String connectionInstanceId, UUID requestId, CoordinationRequest.Status status, String proposal) throws Exception {
        withSession(projectRoot, provider, connectionInstanceId, (store, identity, binding) -> {
            new WorkIntentService(store, identity).respond(participantHandle(binding.sessionId()), requestId, status, proposal);
            return null;
        });
    }

    /**
     * Acknowledges a durable inbox item for the exact calling session.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @param itemId               server-issued inbox item ID
     * @throws Exception when authority or persistence validation fails
     */
    public void acknowledgeInbox(Path projectRoot, String provider, String connectionInstanceId, UUID itemId) throws Exception {
        withSession(projectRoot, provider, connectionInstanceId, (store, identity, binding) -> {
            new WorkIntentService(store, identity).acknowledgeInbox(participantHandle(binding.sessionId()), itemId);
            return null;
        });
    }

    /**
     * Resolves and acknowledges one inbox item for the exact caller.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @param itemId               inbox item ID
     * @param status               terminal response status
     * @param proposal             resolution proposal
     * @throws Exception authorization or persistence failure
     */
    public void resolveInbox(Path projectRoot, String provider, String connectionInstanceId, UUID itemId, CoordinationRequest.Status status, String proposal) throws Exception {
        withSession(projectRoot, provider, connectionInstanceId, (store, identity, binding) -> {
            new WorkIntentService(store, identity).resolveInbox(participantHandle(binding.sessionId()), itemId, status, proposal);
            return null;
        });
    }

    /**
     * Offers a claim handoff to an active target participant.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @param intentId             intent ID
     * @param target               target participant
     * @param proposal             handoff proposal
     * @return pending request
     * @throws Exception when resolution or append fails
     */
    public CoordinationRequest handoff(Path projectRoot, String provider, String connectionInstanceId, UUID intentId, String target, String proposal) throws Exception {
        return withSession(projectRoot, provider, connectionInstanceId,
                (store, identity, binding) -> new WorkIntentService(store, identity).offerHandoff(participantHandle(binding.sessionId()), intentId, target, proposal));
    }

    /**
     * Records verified activity for the exact provider session's participant.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void heartbeat(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        withSession(projectRoot, provider, connectionInstanceId, (store, identity, binding) -> {
            new WorkIntentService(store, identity).heartbeat(participantHandle(binding.sessionId()));
            return null;
        });
    }

    /**
     * Records a heartbeat when this session has an announced collaboration participant.
     *
     * <p>A verified session may own a lease without announcing a claim. Such a session has no
     * collaboration participant to heartbeat, so the absence is not a heartbeat failure. Any other
     * persistence, binding, or signing failure remains blocking.</p>
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection instance ID
     * @throws Exception when a registered participant cannot be heartbeated
     */
    public void heartbeatIfPresent(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        try {
            heartbeat(projectRoot, provider, connectionInstanceId);
        } catch (IOException failure) {
            if (!"PARTICIPANT_NOT_FOUND".equals(failure.getMessage())) {
                throw failure;
            }
        }
    }

    /**
     * Reports whether the exact bound participant has been fenced for recovery. No provider-wide or
     * latest-session fallback is permitted because several lanes may share one project.
     *
     * @param projectRoot control project root
     * @param provider provider identifier
     * @param connectionInstanceId exact provider connection
     * @return true when the bound participant is suspended or recovery-held
     * @throws Exception when the binding or the coordination state cannot be read
     */
    public boolean recoveryFenced(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        return recoveryContinuations.recoveryFenced(projectRoot, provider, connectionInstanceId);
    }

    /**
     * Reports whether a fresh authenticated session may defer an overlapping claim long enough to
     * discover an explicit recovery-continuation action.
     *
     * <p>This is an admission read only. It never creates a participant, releases a claim, issues a
     * grant, selects a continuation, or transfers authority. A caller still has to observe
     * {@code get_next_action} and submit the exact server-projected continuation request before it
     * receives the held lane's authority. Exact selectors are required when the caller supplied
     * ownership claims; an empty selector list is accepted only for a role-matching recovery lane,
     * which lets a blind replacement discover its server-projected claim before declaring a new
     * intent.</p>
     *
     * @param projectRoot          project root
     * @param connectionInstanceId fresh connection instance
     * @param role                 requested semantic role
     * @param selectors            requested ownership selectors
     * @param workGroupId          optional group restriction
     * @return {@code true} only when a matching active recovery-held lane exists
     */
    public boolean recoveryContinuationAdmission(Path projectRoot, String connectionInstanceId, WorkIntent.Role role, List<ResourceSelector> selectors, UUID workGroupId) {
        return recoveryContinuations.recoveryContinuationAdmission(projectRoot, connectionInstanceId, role, selectors, workGroupId);
    }

    /**
     * Lists active intents and pending/resolved coordination requests.
     *
     * @param projectRoot project root
     * @return collaboration snapshot
     * @throws Exception when project state cannot be read
     */
    public CollaborationSnapshot status(Path projectRoot) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = location.openCoordinationStore();
        WorkIntentService service = new WorkIntentService(store, location.loadOrCreateIdentity());
        return new CollaborationSnapshot(service.activeIntents(),
                service.requests(),
                store.collaborationProjection().participants(),
                store.workGroupProjection().groups(),
                store.workGroupProjection().grants(),
                store.taskCompletionProjection().allSnapshots());
    }

    /**
     * Creates a logical work group through the shared coordination service.
     *
     * @param projectRoot project root
     * @param group       group
     * @throws Exception persistence failure
     */
    public void createWorkGroup(Path projectRoot, WorkGroup group) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = location.openCoordinationStore();
        new WorkGroupService(store, location.loadOrCreateIdentity()).create(group);
    }

    /**
     * Creates a work group with its structured review policy.
     *
     * @param projectRoot  project root
     * @param groupId      group ID
     * @param goal         shared goal
     * @param acceptance   shared acceptance criteria
     * @param reviewPolicy structured review policy
     * @throws Exception persistence failure
     */
    public void createWorkGroup(Path projectRoot, UUID groupId, String goal, String acceptance, ReviewPolicy reviewPolicy) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        createWorkGroup(projectRoot, new WorkGroup(groupId, location.projectId(), goal, acceptance, 1, WorkGroup.Status.ACTIVE, reviewPolicy));
    }

    /**
     * Consumes a targeted grant.
     *
     * @param projectRoot root
     * @param grantId     grant
     * @param participant participant
     * @param intentId    intent
     * @param epoch       epoch
     * @throws Exception persistence failure
     */
    public void consumeLaneGrant(Path projectRoot, UUID grantId, String participant, UUID intentId, long epoch) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = location.openCoordinationStore();
        new WorkGroupService(store, location.loadOrCreateIdentity()).consume(new LaneGrantId(grantId),
                new ParticipantId(participant),
                new WorkIntentId(intentId),
                new ClaimEpoch(epoch));
    }

    /**
     * Continues a held recovery lane in the exact caller's new worktree.
     *
     * @param projectRoot          project root
     * @param provider             target provider
     * @param connectionInstanceId target connection
     * @param grantId              optional pre-issued single-use continuation grant
     * @param sourceIntentId       suspended source intent
     * @param claimEpoch           expected source epoch
     * @return durable continuation result, including idempotent replay state
     * @throws Exception when authority, snapshot, or grant validation fails
     */
    public ContinuationResult continueLane(Path projectRoot, String provider, String connectionInstanceId, UUID grantId, UUID sourceIntentId, long claimEpoch) throws Exception {
        return recoveryContinuations.continueLane(projectRoot, provider, connectionInstanceId, grantId, sourceIntentId, claimEpoch);
    }

    /**
     * Closes a logical work group without releasing sibling lane claims.
     *
     * @param projectRoot     project root
     * @param groupId         group ID
     * @param status          terminal status
     * @param expectedVersion current group version
     * @throws Exception persistence failure
     */
    public void closeWorkGroup(Path projectRoot, UUID groupId, WorkGroup.Status status, long expectedVersion) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = location.openCoordinationStore();
        new WorkGroupService(store, location.loadOrCreateIdentity()).close(new WorkGroupId(groupId), status, new WorkGroupVersion(expectedVersion));
    }

    /**
     * Publishes a signed shared contract revision for this project.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @param contractId           contract identifier
     * @param body                 contract body
     * @param selectors            declared selector references
     * @return published contract
     * @throws Exception when session or persistence resolution fails
     */
    public ContractRecord publishContract(Path projectRoot, String provider, String connectionInstanceId, UUID contractId, String body, List<String> selectors) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = location.loadOrCreateIdentity();
        PredictionEventStore store = location.openCoordinationStore();
        return new ContractService(store, identity).publish(new ContractId(contractId), new ParticipantId(participantHandle(binding.sessionId())), body, selectors);
    }

    /**
     * Binds an intent to an exact active contract revision.
     *
     * @param projectRoot          project root
     * @param provider             provider ID
     * @param connectionInstanceId exact connection ID
     * @param intentId             intent identifier
     * @param contractId           contract identifier
     * @param revision             exact revision
     * @throws Exception when session or persistence resolution fails
     */
    public void bindContract(Path projectRoot, String provider, String connectionInstanceId, UUID intentId, UUID contractId, long revision) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = location.loadOrCreateIdentity();
        PredictionEventStore store = location.openCoordinationStore();
        new ContractService(store, identity).bind(new WorkIntentId(intentId), new ParticipantId(participantHandle(binding.sessionId())), new ContractId(contractId), new ContractRevision(revision));
    }

    /**
     * Lists replayed contracts and exact consumer dependencies.
     *
     * @param projectRoot project root
     * @return contract snapshot
     * @throws Exception when project state cannot be read
     */
    public ContractSnapshot contractStatus(Path projectRoot) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = location.openCoordinationStore();
        ContractService service = new ContractService(store, location.loadOrCreateIdentity());
        return new ContractSnapshot(service.contracts(), service.dependencies());
    }

    /**
     * Returns a stable mutation authorization classification.
     *
     * @param projectRoot          project root
     * @param provider             provider
     * @param connectionInstanceId connection ID
     * @param relativePath         repository-relative target
     * @return {@code allowed}, {@code overlapping_claim}, or {@code coordination_intent_required}
     */
    public String mutationReason(Path projectRoot, String provider, String connectionInstanceId, String relativePath) {
        try {
            ProjectLocation location = projectService.locate(projectRoot);
            ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
            return mutationReason(location, binding, relativePath);
        } catch (Exception failure) {
            return "coordination_intent_required";
        }
    }

    /**
     * Returns a stable mutation authorization classification for an already resolved binding.
     *
     * @param location     initialized project location
     * @param binding      exact provider binding
     * @param relativePath repository-relative target
     * @return {@code allowed}, {@code overlapping_claim}, or {@code coordination_intent_required}
     */
    public String mutationReason(ProjectLocation location, ProviderSessionBindingService.Binding binding, String relativePath) {
        return MutationAdmission.mutationReason(location, binding, relativePath);
    }

    /**
     * Returns whether an exact active claim authorizes this binding to mutate the target.
     *
     * <p>This is narrower than {@link #mutationReason(ProjectLocation,
     * ProviderSessionBindingService.Binding, String)}, which intentionally allows legacy projects
     * that have not activated collaboration. A claim is required when continuing a stale assigned
     * worktree after a sibling advances the control checkout.</p>
     *
     * @param location     initialized project location
     * @param binding      exact provider binding
     * @param relativePath repository-relative target
     * @return true only when the binding's active intent owns the target and has no completion fence
     */
    public boolean hasActiveMutationClaim(ProjectLocation location, ProviderSessionBindingService.Binding binding, String relativePath) {
        return MutationAdmission.hasActiveMutationClaim(location, binding, relativePath);
    }

    @FunctionalInterface
    private interface SessionAction<T> {
        T apply(PredictionEventStore store, NodeIdentity identity, ProviderSessionBindingService.Binding binding) throws Exception;
    }

    private <T> T withSession(Path projectRoot, String provider, String connectionInstanceId, SessionAction<T> action) throws Exception {
        ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = location.loadOrCreateIdentity();
        PredictionEventStore store = location.openCoordinationStore();
        return action.apply(store, identity, binding);
    }

    private ProviderSessionBindingService.Binding binding(ProjectLocation location, String provider, String connectionId) throws Exception {
        return authorityResolver.resolve(location, provider, connectionId);
    }

    private ProviderSessionBindingService.Binding reviewBinding(ProjectLocation location, String provider, String connectionId) throws Exception {
        return authorityResolver.resolveReview(location, provider, connectionId);
    }

}
