package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.synesis.coordination.application.WorkIntentService;
import org.synesis.coordination.domain.collaboration.ClaimResult;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.collaboration.CoordinationRequest;
import org.synesis.coordination.domain.collaboration.Participant;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.application.ContractService;
import org.synesis.coordination.domain.contract.ContractDependency;
import org.synesis.coordination.domain.contract.ContractRecord;
import org.synesis.coordination.domain.collaboration.WorkGroup;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.application.WorkGroupService;
import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.SessionAuthorityResolver;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.lifecycle.recovery.RecoverySnapshotService;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.workspace.application.integration.IntegrationWorkspaceService;

/** Resolves authenticated workspace sessions into collaboration intents and claims. */
public final class WorkspaceCollaborationService {
    private final ProjectApplicationService projectService = new ProjectApplicationService();
    private final ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
    private final SessionAuthorityResolver authorityResolver = new SessionAuthorityResolver(bindingService);
    private final ProviderManualService manualService = new ProviderManualService();

    /** Creates a workspace collaboration adapter. */
    public WorkspaceCollaborationService() {
    }

    /** Announces an intent for one verified provider session.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId connection ID
     * @param goal goal
     * @param acceptance acceptance criteria
     * @param selectors requested selectors
     * @return claim result
     * @throws Exception when the project, session, identity, or event store cannot be resolved
     */
    public ClaimResult announce(Path projectRoot, String provider, String connectionInstanceId,
                                String goal, String acceptance, List<ResourceSelector> selectors)
            throws Exception {
        return announce(projectRoot, provider, connectionInstanceId, goal, acceptance, selectors, null);
    }

    /** Announces an intent in an optional logical work group.
     * @param projectRoot project root
     * @param provider provider
     * @param connectionInstanceId connection ID
     * @param goal goal
     * @param acceptance acceptance
     * @param selectors claims
     * @param workGroupId group ID
     * @return claim result
     * @throws Exception resolution or append failure
     */
    public ClaimResult announce(Path projectRoot, String provider, String connectionInstanceId,
                                String goal, String acceptance, List<ResourceSelector> selectors, UUID workGroupId)
            throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        manualService.requireAttested(provider);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        String participant = participantHandle(binding.sessionId());
        var existing = store.collaborationProjection().participants().stream()
                .filter(candidate -> candidate.id().equals(participant)).findFirst();
        if (existing.isPresent() && existing.get().state() != Participant.State.ACTIVE) {
            throw new IOException("SESSION_EPOCH_FENCED");
        }
        WorkIntentService service = new WorkIntentService(store, identity);
        UUID taskId = UUID.nameUUIDFromBytes(connectionInstanceId.getBytes(StandardCharsets.UTF_8));
        UUID group = workGroupId == null
                ? store.workGroupProjection().groups().stream()
                        .filter(candidate -> candidate.projectId().equals(location.projectId()))
                        .filter(candidate -> candidate.status() == WorkGroup.Status.ACTIVE)
                        .map(WorkGroup::workGroupId)
                        .findFirst()
                        .orElseGet(() -> UUID.nameUUIDFromBytes(("default-work-group:" + location.projectId())
                                .getBytes(StandardCharsets.UTF_8)))
                : workGroupId;
        WorkIntent intent = new WorkIntent(UUID.nameUUIDFromBytes((provider + ":" + binding.sessionId())
                .getBytes(StandardCharsets.UTF_8)), location.projectId(), participant,
                provider, taskId, goal == null ? "Unspecified work" : goal,
                acceptance == null ? "Unspecified acceptance" : acceptance,
                binding.baseCommit(), selectors, 1, group, WorkIntent.Status.ANNOUNCED);
        return service.announce(intent);
    }

    /**
     * Joins an immutable conflict repair lane from a newly authenticated
     * isolated binding. The local project identity is the recovery authority;
     * the old lane and snapshot remain immutable while the new binding receives
     * a fresh intent ID and claim epoch.
     *
     * @param projectRoot control project root
     * @param provider provider ID
     * @param connectionInstanceId exact new connection
     * @param repairIntentId currently reserved repair intent
     * @param snapshotId immutable conflicting snapshot
     * @return acquired claim result
     * @throws Exception resolution, materialization, or append failure
     */
    public ClaimResult joinRepair(Path projectRoot, String provider, String connectionInstanceId,
                                  UUID repairIntentId, String snapshotId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        manualService.requireAttested(provider);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        if (binding.worktreePath() == null) throw new IOException("REPAIR_BINDING_NOT_READY");
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        WorkIntent source = store.collaborationProjection().intent(repairIntentId)
                .orElseThrow(() -> new IOException("REPAIR_INTENT_NOT_FOUND"));
        TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotById(snapshotId)
                .orElseThrow(() -> new IOException("REPAIR_SNAPSHOT_NOT_FOUND"));
        if (!snapshot.provenance().workGroupId().equals(source.workGroupId())
                || !snapshot.provenance().claimSelectors().equals(source.selectors().stream()
                        .map(selector -> selector.kind().name() + ":" + selector.value()).toList())) {
            throw new IOException("REPAIR_LINEAGE_OR_SCOPE_MISMATCH");
        }
        UUID targetId = UUID.nameUUIDFromBytes(("repair-join|" + repairIntentId + "|" + binding.sessionId())
                .getBytes(StandardCharsets.UTF_8));
        WorkIntent target = new WorkIntent(targetId, location.projectId(), participantHandle(binding.sessionId()),
                provider, snapshot.taskId(), source.goal(), source.acceptance(), binding.baseCommit(),
                source.selectors(), source.version() + 1, source.workGroupId(), WorkIntent.Status.ANNOUNCED);
        new IntegrationWorkspaceService().materializeRepairRepresentation(Path.of(binding.worktreePath()), snapshot.commitSha());
        new WorkIntentService(store, identity).createRepairLane(repairIntentId, target);
        return new ClaimResult(true, target, List.of());
    }

    /** Releases the exact session intent and all of its claims.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void release(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        UUID intentId = UUID.nameUUIDFromBytes((provider + ":" + binding.sessionId()).getBytes(StandardCharsets.UTF_8));
        new WorkIntentService(store, identity).release(intentId, participantHandle(binding.sessionId()));
    }

    /** Detaches the exact session lane after a clean connection shutdown.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void detach(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).detach(participantHandle(binding.sessionId()));
    }

    /** Cancels and permanently fences the exact session lane.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void cancel(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).cancel(participantHandle(binding.sessionId()));
    }

    /** Opens a request against a conflicting intent owned by another participant.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param conflictingIntentId conflicting intent
     * @param kind request kind
     * @param proposal proposal
     * @return durable request
     * @throws Exception when resolution or append fails
     */
    public CoordinationRequest request(Path projectRoot, String provider, String connectionInstanceId,
            UUID conflictingIntentId, CoordinationRequest.Kind kind, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        return new WorkIntentService(store, identity).request(participantHandle(binding.sessionId()), conflictingIntentId, kind, proposal);
    }

    /** Responds to a request addressed to the exact provider session.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param requestId request ID
     * @param status response status
     * @param proposal revised proposal
     * @throws Exception when resolution or append fails
     */
    public void respond(Path projectRoot, String provider, String connectionInstanceId,
            UUID requestId, CoordinationRequest.Status status, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).respond(participantHandle(binding.sessionId()), requestId, status, proposal);
    }

    /** Acknowledges a durable inbox item for the exact calling session.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param itemId server-issued inbox item ID
     * @throws Exception when authority or persistence validation fails
     */
    public void acknowledgeInbox(Path projectRoot, String provider, String connectionInstanceId, UUID itemId)
            throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).acknowledgeInbox(participantHandle(binding.sessionId()), itemId);
    }

    /** Resolves and acknowledges one inbox item for the exact caller.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param itemId inbox item ID
     * @param status terminal response status
     * @param proposal resolution proposal
     * @throws Exception authorization or persistence failure
     */
    public void resolveInbox(Path projectRoot, String provider, String connectionInstanceId, UUID itemId,
            CoordinationRequest.Status status, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).resolveInbox(participantHandle(binding.sessionId()), itemId, status, proposal);
    }

    /** Offers a claim handoff to an active target participant.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param intentId intent ID
     * @param target target participant
     * @param proposal handoff proposal
     * @return pending request
     * @throws Exception when resolution or append fails
     */
    public CoordinationRequest handoff(Path projectRoot, String provider, String connectionInstanceId,
            UUID intentId, String target, String proposal) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        return new WorkIntentService(store, identity).offerHandoff(participantHandle(binding.sessionId()), intentId, target, proposal);
    }

    /** Records verified activity for the exact provider session's participant.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @throws Exception when resolution or append fails
     */
    public void heartbeat(Path projectRoot, String provider, String connectionInstanceId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkIntentService(store, identity).heartbeat(participantHandle(binding.sessionId()));
    }

    /** Lists active intents and pending/resolved coordination requests.
     * @param projectRoot project root
     * @return collaboration snapshot
     * @throws Exception when project state cannot be read
     */
    public CollaborationSnapshot status(Path projectRoot) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        WorkIntentService service = new WorkIntentService(store,
                new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity());
        return new CollaborationSnapshot(service.activeIntents(), service.requests(),
                store.collaborationProjection().participants(), store.workGroupProjection().groups(),
                store.workGroupProjection().grants());
    }

    /** Creates a logical work group through the shared coordination service.
     * @param projectRoot project root
     * @param group group
     * @throws Exception persistence failure
     */
    public void createWorkGroup(Path projectRoot, WorkGroup group) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkGroupService(store, new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity()).create(group);
    }

    /** Creates a logical work group using the project's authenticated identity.
     * @param projectRoot project root
     * @param groupId group ID
     * @param goal shared goal
     * @param acceptance shared acceptance criteria
     * @throws Exception persistence failure
     */
    public void createWorkGroup(Path projectRoot, UUID groupId, String goal, String acceptance) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        createWorkGroup(projectRoot, new WorkGroup(groupId, location.projectId(), goal, acceptance, 1,
                WorkGroup.Status.ACTIVE));
    }

    /** Issues a targeted lane grant.
     * @param projectRoot project root
     * @param grant grant
     * @throws Exception persistence failure
     */
    public void issueLaneGrant(Path projectRoot, LaneGrant grant) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkGroupService(store, new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity()).issue(grant);
    }

    /** Consumes a targeted grant.
     * @param projectRoot root
     * @param grantId grant
     * @param participant participant
     * @param intentId intent
     * @param epoch epoch
     * @throws Exception persistence failure
     */
    public void consumeLaneGrant(Path projectRoot, UUID grantId, String participant, UUID intentId, long epoch) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkGroupService(store, new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity())
                .consume(grantId, participant, intentId, epoch);
    }

    /** Revokes a targeted grant.
     * @param projectRoot root
     * @param grantId grant
     * @throws Exception persistence failure
     */
    public void revokeLaneGrant(Path projectRoot, UUID grantId) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkGroupService(store, new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity()).revoke(grantId);
    }

    /** Continues a held recovery lane in the exact caller's new worktree.
     * @param projectRoot project root
     * @param provider target provider
     * @param connectionInstanceId target connection
     * @param grantId single-use continuation grant
     * @param sourceIntentId suspended source intent
     * @param claimEpoch expected source epoch
     * @throws Exception when authority, snapshot, or grant validation fails
     */
    public void continueLane(Path projectRoot, String provider, String connectionInstanceId,
            UUID grantId, UUID sourceIntentId, long claimEpoch) throws Exception {
        manualService.requireAttested(provider);
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        if (binding.worktreePath() == null) throw new IOException("CONTINUATION_TARGET_WORKTREE_REQUIRED");
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        WorkIntent source = store.collaborationProjection().intent(sourceIntentId)
                .orElseThrow(() -> new IOException("CONTINUATION_SOURCE_NOT_FOUND"));
        var grant = store.workGroupProjection().grants().stream()
                .filter(candidate -> candidate.grantId().equals(grantId)).findFirst()
                .orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
        String targetParticipant = participantHandle(binding.sessionId());
        if (!grant.targetParticipant().equals(targetParticipant)) throw new IOException("LANE_GRANT_TARGET_MISMATCH");
        String snapshotReference = store.collaborationProjection().recoverySnapshotReference(source.participant())
                .orElseThrow(() -> new IOException("RECOVERY_SNAPSHOT_REQUIRED"));
        new RecoverySnapshotService().restoreToLane(snapshotReference, Path.of(binding.worktreePath()));
        UUID targetIntentId = grant.targetIntentId();
        WorkIntent target = new WorkIntent(targetIntentId, source.projectId(), targetParticipant, provider,
                UUID.nameUUIDFromBytes((connectionInstanceId + ":" + targetIntentId).getBytes(StandardCharsets.UTF_8)),
                source.goal(), source.acceptance(), binding.baseCommit(), source.selectors(),
                source.version() + 1, source.workGroupId(), WorkIntent.Status.ANNOUNCED);
        CollaborationCodec.Continuation continuation = new CollaborationCodec.Continuation(
                grantId, sourceIntentId, target, source.participant(), targetParticipant, claimEpoch, snapshotReference);
        new WorkIntentService(store, identity).continueFromRecovery(continuation);
    }

    /** Closes a logical work group without releasing sibling lane claims.
     * @param projectRoot project root
     * @param groupId group ID
     * @param status terminal status
     * @param expectedVersion current group version
     * @throws Exception persistence failure
     */
    public void closeWorkGroup(Path projectRoot, UUID groupId, WorkGroup.Status status, long expectedVersion) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new WorkGroupService(store, new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity())
                .close(groupId, status, expectedVersion);
    }

    /** Publishes a signed shared contract revision for this project.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param contractId contract identifier
     * @param body contract body
     * @param selectors declared selector references
     * @return published contract
     * @throws Exception when session or persistence resolution fails
     */
    public ContractRecord publishContract(Path projectRoot, String provider, String connectionInstanceId,
            UUID contractId, String body, List<String> selectors) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        return new ContractService(store, identity).publish(contractId, participantHandle(binding.sessionId()), body, selectors);
    }

    /** Binds an intent to an exact active contract revision.
     * @param projectRoot project root
     * @param provider provider ID
     * @param connectionInstanceId exact connection ID
     * @param intentId intent identifier
     * @param contractId contract identifier
     * @param revision exact revision
     * @throws Exception when session or persistence resolution fails
     */
    public void bindContract(Path projectRoot, String provider, String connectionInstanceId,
            UUID intentId, UUID contractId, long revision) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
        NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        new ContractService(store, identity).bind(intentId, participantHandle(binding.sessionId()), contractId, revision);
    }

    /** Lists replayed contracts and exact consumer dependencies.
     * @param projectRoot project root
     * @return contract snapshot
     * @throws Exception when project state cannot be read
     */
    public ContractSnapshot contractStatus(Path projectRoot) throws Exception {
        ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
        PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
        ContractService service = new ContractService(store,
                new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity());
        return new ContractSnapshot(service.contracts(), service.dependencies());
    }

    /** Shared contract discovery result used by CLI and MCP adapters.
     * @param contracts contract revisions
     * @param dependencies consumer bindings
     */
    public record ContractSnapshot(List<ContractRecord> contracts, List<ContractDependency> dependencies) { }

    /** Shared collaboration discovery result used by CLI and MCP adapters.
     * @param intents intents
     * @param requests requests
     * @param participants participants
     * @param groups logical work groups
     * @param grants continuation grants
     */
    public record CollaborationSnapshot(List<WorkIntent> intents, List<CoordinationRequest> requests,
            List<Participant> participants, List<WorkGroup> groups, List<LaneGrant> grants) { }

    /** Returns whether the session owns the target or no collaboration protocol is active.
     * @param projectRoot project root
     * @param provider provider
     * @param connectionInstanceId connection ID
     * @param relativePath repository-relative target
     * @return authorization decision
     */
    public boolean permitsMutation(Path projectRoot, String provider, String connectionInstanceId,
                                   String relativePath) {
        return "allowed".equals(mutationReason(projectRoot, provider, connectionInstanceId, relativePath));
    }

    /** Returns a stable mutation authorization classification.
     * @param projectRoot project root
     * @param provider provider
     * @param connectionInstanceId connection ID
     * @param relativePath repository-relative target
     * @return {@code allowed}, {@code overlapping_claim}, or {@code coordination_intent_required}
     */
    public String mutationReason(Path projectRoot, String provider, String connectionInstanceId,
                                 String relativePath) {
        try {
            ProjectApplicationService.ProjectLocation location = projectService.locate(projectRoot);
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            PredictionEventStore store = new PredictionEventStore(location.root().resolve(".synesis/coordination"), location.projectId());
            WorkIntentService service = new WorkIntentService(store, identity);
            if (service.activeIntents().isEmpty() && !store.collaborationProjection().activated()) {
                return "allowed";
            }
            ResourceSelector selector = ResourceSelector.pathExact(relativePath);
            ProviderSessionBindingService.Binding binding = binding(location, provider, connectionInstanceId);
            // A completion transaction fences its exact lane before the
            // prepared tree is durable.  Do not permit a caller to mutate a
            // worktree after preparation or publication, even if the claim
            // projection still contains the reserved selector.
            String participant = participantHandle(binding.sessionId());
            boolean fenced = store.taskCompletionProjection().allSnapshots().stream()
                    .filter(snapshot -> snapshot.provenance().bindingIdentity().equals(binding.sessionId())
                            || snapshot.provenance().participant().equals(participant))
                    .map(snapshot -> store.taskCompletionProjection().taskState(snapshot.taskId()))
                    .anyMatch(state -> state == org.synesis.coordination.domain.task.TaskCompletionState.COMPLETION_PREPARED
                            || state == org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATION_PENDING
                            || state == org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATING
                            || state == org.synesis.coordination.domain.task.TaskCompletionState.INTEGRATION_BLOCKED
                            || state == org.synesis.coordination.domain.task.TaskCompletionState.REPAIR_REQUIRED);
            fenced = fenced || store.taskCompletionProjection().allPrepared().stream()
                    .anyMatch(prepared -> service.activeIntents().stream()
                            .anyMatch(intent -> intent.intentId().equals(prepared.laneId())
                                    && intent.participant().equals(participant)));
            if (fenced) return "coordination_intent_required";
            if (service.owns(participant, selector)) {
                return "allowed";
            }
            return service.activeIntents().stream().anyMatch(intent -> intent.selectors().stream()
                    .anyMatch(selector::overlaps)) ? "overlapping_claim" : "coordination_intent_required";
        } catch (Exception failure) {
            return "coordination_intent_required";
        }
    }

    /** Returns the stable opaque participant handle for a connection.
     * @param connectionInstanceId connection ID
     * @return opaque participant handle
     */
    public static String participantHandle(String connectionInstanceId) {
        Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
        return "agt_" + UUID.nameUUIDFromBytes(connectionInstanceId.getBytes(StandardCharsets.UTF_8));
    }

    private ProviderSessionBindingService.Binding binding(ProjectApplicationService.ProjectLocation location,
                                                           String provider, String connectionId) throws Exception {
        return authorityResolver.resolve(location, provider, connectionId);
    }
}
