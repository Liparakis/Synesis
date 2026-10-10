package org.synesis.workspace.application.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.synesis.coordination.domain.capability.CapabilityLifecycleState;
import org.synesis.coordination.domain.capability.CapabilityRequestRecord;
import org.synesis.coordination.domain.collaboration.ResourceSelector;
import org.synesis.coordination.domain.collaboration.WorkIntent;
import org.synesis.coordination.domain.integration.ImplementationEventPayload;
import org.synesis.coordination.domain.integration.ImplementationRevisionRecord;
import org.synesis.coordination.domain.prediction.PredictionEventType;
import org.synesis.coordination.domain.task.TaskSnapshotRecord;
import org.synesis.coordination.persistence.PredictionEventStore;
import org.synesis.coordination.persistence.ProjectAppendLock;

import org.synesis.link.identity.IdentityBootstrap;
import org.synesis.link.identity.NodeIdentity;

import org.synesis.workspace.agent.AgentNextAction;
import org.synesis.workspace.agent.AgentReason;
import org.synesis.workspace.agent.AgentResponse;
import org.synesis.workspace.agent.AgentStatus;
import org.synesis.workspace.application.ProjectApplicationService;
import org.synesis.workspace.application.collaboration.WorkspaceCollaborationService;
import org.synesis.workspace.application.project.ProjectLocation;
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService.WorkspaceCheck;
import org.synesis.workspace.application.provider.session.SessionAuthorityResolver;
import org.synesis.workspace.application.task.TaskSnapshotService;
import org.synesis.workspace.application.workspace.ReadinessResult;
import org.synesis.workspace.application.workspace.WorkspaceReadinessService;

/** Publishes an immutable claimed-source snapshot for an accepted capability request. */
@SuppressWarnings("DuplicatedCode")
public final class ImplementationPublicationService {

    private final ProjectApplicationService projectService;
    private final SessionAuthorityResolver authorityResolver;
    private final ProviderManualService manualService;
    private final TaskSnapshotService snapshotService;
    private final ProviderSessionBindingService bindingService;
    private final WorkspaceReadinessService readinessService;

    /** Creates the publication service with repository-backed authority and snapshot services. */
    public ImplementationPublicationService() {
        this.projectService = new ProjectApplicationService();
        this.bindingService = new ProviderSessionBindingService();
        this.authorityResolver = new SessionAuthorityResolver(bindingService);
        this.manualService = new ProviderManualService();
        this.snapshotService = new TaskSnapshotService();
        this.readinessService = new WorkspaceReadinessService(bindingService);
    }

    private static AgentResponse blocked(String reason, AgentReason agentReason, AgentNextAction nextAction) {
        return new AgentResponse(AgentStatus.BLOCKED, agentReason, nextAction, Map.of("reason", reason));
    }

    private static boolean validTreeHash(String value) {
        return value != null && (value.matches("[0-9a-f]{40}") || value.matches("[0-9a-f]{64}"));
    }

    private static boolean sameBindingIncarnation(ProviderSessionBindingService.Binding left,
            ProviderSessionBindingService.Binding right) {
        return left.sessionId().equals(right.sessionId())
                && left.nodeId().equals(right.nodeId())
                && left.supervisorId().equals(right.supervisorId())
                && left.workerId().equals(right.workerId())
                && left.bindingVersion() == right.bindingVersion()
                && left.providerInstanceFingerprint().equals(right.providerInstanceFingerprint())
                && left.baseCommit().equals(right.baseCommit())
                && Objects.equals(left.worktreePath(), right.worktreePath());
    }

    private static String summary(PublishRequest request) {
        String value = request.summary();
        if (value == null || value.isBlank()) {
            return "Implementation published";
        }
        String normalized = value.trim();
        return normalized.substring(0, Math.min(normalized.length(), ImplementationRevisionRecord.MAX_SUMMARY_LENGTH));
    }

    private static Optional<WorkIntent> exactOwnerIntent(PredictionEventStore store,
            PublishRequest request,
            CapabilityRequestRecord capability,
            String participant) {
        WorkIntent intent = store.collaborationProjection().intent(request.intentId()).orElse(null);
        if (intent == null || intent.status() != WorkIntent.Status.ANNOUNCED
                || !store.collaborationProjection().activeIntents().stream().anyMatch(active -> active.intentId().equals(request.intentId()))
                || !intent.participant().equals(participant)
                || !intent.provider().equals(request.provider())
                || intent.version() != request.claimEpoch()
                || !intent.authorityLineageId().equals(capability.authorityLineageId())) {
            return Optional.empty();
        }
        return Optional.of(intent);
    }

    private static List<ResourceSelector> foreignClaims(PredictionEventStore store, String participant) {
        return store.collaborationProjection().activeIntents().stream()
                .filter(intent -> !intent.participant().equals(participant))
                .flatMap(intent -> intent.selectors().stream())
                .distinct()
                .toList();
    }

    /** Revalidates authority and appends while holding the project event-log lock. */
    private AgentResponse publishLocked(PublishRequest request,
            ProjectLocation location,
            ProviderSessionBindingService.Binding binding,
            NodeIdentity identity,
            Path ownerWorktree) throws Exception {
        Path coordination = location.root().resolve(".synesis/coordination");
        try (ProjectAppendLock lock = ProjectAppendLock.acquire(coordination)) {
            if (!lock.isHeld()) {
                return blocked("EVENT_APPEND_LOCK_UNAVAILABLE", AgentReason.TASK_NOT_READY, AgentNextAction.RETRY);
            }

            PredictionEventStore store = new PredictionEventStore(coordination, location.projectId());
            Optional<CapabilityRequestRecord> capabilityOpt = store.capabilityRequestProjection().findByHandle(request.requestHandle());
            if (capabilityOpt.isEmpty()) {
                return blocked("CAPABILITY_REQUEST_NOT_FOUND", AgentReason.REQUEST_NOT_FOUND, AgentNextAction.RETRY);
            }
            CapabilityRequestRecord capability = capabilityOpt.get();
            String ownerNodeId = identity.nodeId();
            if (!capability.matchesOwner(ownerNodeId, binding.supervisorId(), binding.workerId())) {
                return blocked("CAPABILITY_OWNER_MISMATCH", AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP);
            }
            if (capability.contractRevision() != request.contractRevision()) {
                return blocked("CAPABILITY_CONTRACT_REVISION_STALE", AgentReason.STALE_REQUEST, AgentNextAction.RETRY);
            }

            String participant = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            Optional<WorkIntent> intentOpt = exactOwnerIntent(store, request, capability, participant);
            if (intentOpt.isEmpty()) {
                return blocked("CAPABILITY_PUBLISHER_STALE", AgentReason.CAPABILITY_PUBLISHER_STALE, AgentNextAction.ENSURE_SESSION);
            }
            WorkIntent intent = intentOpt.get();
            if (intent.selectors().isEmpty()) {
                return blocked("CAPABILITY_PUBLISHER_CLAIM_REQUIRED", AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP);
            }

            CapabilityLifecycleState state = capability.state();
            boolean replayState = state == CapabilityLifecycleState.IMPLEMENTATION_AVAILABLE
                    || state == CapabilityLifecycleState.VALIDATING
                    || state == CapabilityLifecycleState.VALIDATED;
            if (!replayState && state != CapabilityLifecycleState.ACCEPTED && state != CapabilityLifecycleState.IMPLEMENTING) {
                return blocked("CAPABILITY_REQUEST_NOT_PUBLISHABLE", AgentReason.STALE_REQUEST, AgentNextAction.RETRY);
            }

            ProviderSessionBindingService.Binding currentBinding = bindingService.find(location, request.provider(), request.connectionInstanceId()).orElse(null);
            if (currentBinding == null || !"BOUND".equals(currentBinding.status()) || !"VERIFIED".equals(currentBinding.providerTrustState())
                    || currentBinding.worktreePath() == null
                    || !sameBindingIncarnation(currentBinding, binding)
                    || currentBinding.completedAt() != null
                    || !Path.of(currentBinding.worktreePath()).toAbsolutePath().normalize().equals(ownerWorktree)) {
                return blocked("CAPABILITY_PUBLISHER_STALE", AgentReason.CAPABILITY_PUBLISHER_STALE, AgentNextAction.ENSURE_SESSION);
            }
            if (bindingService.isSessionTerminal(location, currentBinding.sessionId())) {
                return blocked("CAPABILITY_PUBLISHER_SESSION_TERMINAL", AgentReason.CAPABILITY_PUBLISHER_STALE, AgentNextAction.ENSURE_SESSION);
            }
            WorkspaceCheck workspaceCheck = bindingService.verifyCompletionWorkspace(location, currentBinding, ownerWorktree);
            if (!workspaceCheck.verified()) {
                return blocked("CAPABILITY_PUBLISHER_WORKSPACE_STALE", AgentReason.WORKSPACE_STALE, AgentNextAction.ENSURE_SESSION);
            }

            List<ResourceSelector> foreignClaims = foreignClaims(store, participant);
            String laneBase = snapshotService.snapshotBaseForBoundWorktree(ownerWorktree, intent.baseCommit(), binding.baseCommit());
            String currentTreeHash = snapshotService.capabilityPublicationTreeHash(ownerWorktree, laneBase, intent.selectors(), foreignClaims);
            if (!currentTreeHash.equals(request.expectedTreeHash())) {
                return blocked("CAPABILITY_PUBLICATION_TREE_STALE", AgentReason.STALE_REQUEST, AgentNextAction.RETRY);
            }

            List<CapabilityRequestRecord> dependencies = store.capabilityRequestProjection().findAllForRequester(ownerNodeId).stream()
                    .filter(record -> record.matchesRequester(ownerNodeId, binding.supervisorId(), binding.workerId()))
                    // Superseded by an owner that finished without an answer: not a dependency.
                    .filter(record -> !org.synesis.coordination.application.RequesterAnswerPolicy.ownerFinishedWithoutAnswer(record))
                    .toList();
            TaskSnapshotRecord snapshot = snapshotService.createSnapshot(
                    intent.taskId(),
                    ownerNodeId,
                    binding.supervisorId(),
                    binding.workerId(),
                    binding.sessionId(),
                    ownerWorktree,
                    location.root(),
                    summary(request),
                    Optional.empty(),
                    dependencies,
                    intent.selectors(),
                    intent.workGroupId(),
                    intent.intentId(),
                    participant,
                    binding.sessionId(),
                    intent.version(),
                    intent.authorityLineageId(),
                    List.of(),
                    false,
                    laneBase,
                    false,
                    foreignClaims,
                    request.expectedTreeHash());
            if (snapshot.changedPaths().size() > ImplementationRevisionRecord.MAX_CHANGED_PATHS) {
                return blocked("CAPABILITY_PUBLICATION_PATH_LIMIT_EXCEEDED", AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP);
            }

            Optional<ImplementationRevisionRecord> latestOpt = store.capabilityRequestProjection().findLatestImplementation(request.requestHandle());
            if (replayState) {
                if (latestOpt.isEmpty()) {
                    return blocked("CAPABILITY_PUBLICATION_REPLAY_MISMATCH", AgentReason.STALE_REQUEST, AgentNextAction.RETRY);
                }
                ImplementationRevisionRecord latest = latestOpt.get();
                if (!latest.authorityLineageId().equals(intent.authorityLineageId())
                        || !latest.baseCommit().equals(snapshot.baseCommit())
                        || !latest.commitSha().equals(snapshot.commitSha())
                        || !latest.changedPaths().equals(snapshot.changedPaths())) {
                    return blocked("CAPABILITY_PUBLICATION_REPLAY_MISMATCH", AgentReason.STALE_REQUEST, AgentNextAction.RETRY);
                }
                return publicationResult(capability, latest.revisionNumber(), snapshot, request.expectedTreeHash(), true);
            }

            int nextRevision = latestOpt.map(revision -> revision.revisionNumber() + 1).orElse(1);
            ImplementationEventPayload payload = new ImplementationEventPayload(
                    capability.handle(),
                    intent.authorityLineageId(),
                    nextRevision,
                    snapshot.baseCommit(),
                    snapshot.commitSha(),
                    snapshot.changedPaths(),
                    summary(request),
                    "",
                    "",
                    List.of(),
                    "");
            store.append(UUID.randomUUID(), PredictionEventType.CAPABILITY_IMPLEMENTATION_PUBLISHED, ownerNodeId, payload.encode(), identity);
            return publicationResult(capability, nextRevision, snapshot, request.expectedTreeHash(), false);
        }
    }

    private static AgentResponse publicationResult(CapabilityRequestRecord request,
            int revision,
            TaskSnapshotRecord snapshot,
            String expectedTreeHash,
            boolean replay) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", request.handle().value());
        result.put("revision", revision);
        result.put("intentId", snapshot.provenance().laneId().toString());
        result.put("claimEpoch", snapshot.provenance().claimEpoch());
        result.put("snapshotId", snapshot.snapshotId());
        result.put("commitSha", snapshot.commitSha());
        result.put("treeHash", expectedTreeHash);
        result.put("replayed", replay);
        if (request.state() == CapabilityLifecycleState.VALIDATED) {
            result.put("validationState", "validated");
            return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
        }
        return new AgentResponse(AgentStatus.WAITING, AgentReason.VALIDATION_REQUIRED, AgentNextAction.WAIT, result);
    }

    /**
     * Publishes an immutable implementation snapshot for the exact projected owner lane.
     *
     * @param request publication handle, owner lineage, and projected source-tree evidence
     * @return publication status and immutable revision metadata
     */
    public AgentResponse publishImplementation(PublishRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.contractRevision() == null || request.intentId() == null || request.claimEpoch() == null
                || !validTreeHash(request.expectedTreeHash())) {
            return blocked("CAPABILITY_PUBLICATION_EVIDENCE_REQUIRED", AgentReason.POLICY_DENIED, AgentNextAction.REQUEST_HUMAN_HELP);
        }

        Path root = request.projectRoot().toAbsolutePath().normalize();
        try {
            manualService.requireAttested(request.provider());
        } catch (Exception failure) {
            return blocked("MANUAL_ATTESTATION_REQUIRED", AgentReason.POLICY_DENIED, AgentNextAction.RETRY);
        }
        if (!Files.exists(root.resolve(".synesis/project.json"))) {
            return new AgentResponse(AgentStatus.RETRY_REQUIRED, AgentReason.WORKSPACE_NOT_READY, AgentNextAction.ENSURE_SESSION, null);
        }

        try {
            ProjectLocation location = projectService.locate(root);
            ReadinessResult readiness = readinessService.assessCompletion(location, request.provider(), request.connectionInstanceId());
            if (!readiness.ready()) {
                return readiness.response();
            }
            ProviderSessionBindingService.Binding binding = readiness.binding();
            ProviderSessionBindingService.Binding authorized = authorityResolver.resolve(location, request.provider(), request.connectionInstanceId());
            if (!sameBindingIncarnation(authorized, binding)
                    || !Path.of(authorized.worktreePath()).toAbsolutePath().normalize().equals(readiness.worktree())) {
                return blocked("CAPABILITY_PUBLISHER_STALE", AgentReason.CAPABILITY_PUBLISHER_STALE, AgentNextAction.ENSURE_SESSION);
            }
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            if (!identity.nodeId().equals(binding.nodeId())) {
                return blocked("CAPABILITY_PUBLISHER_IDENTITY_STALE", AgentReason.CAPABILITY_PUBLISHER_STALE, AgentNextAction.ENSURE_SESSION);
            }
            Path worktree = readiness.worktree();
            return publishLocked(request, location, binding, identity, worktree);
        } catch (IOException stale) {
            String message = Objects.toString(stale.getMessage(), "CAPABILITY_PUBLICATION_FAILED");
            if (isPublicationPolicyFailure(message)) {
                return blocked(message, AgentReason.STALE_REQUEST, AgentNextAction.RETRY);
            }
            return new AgentResponse(AgentStatus.FAILED, AgentReason.INTERNAL_FAILURE, AgentNextAction.REQUEST_HUMAN_HELP,
                    Map.of("error", stale.getClass().getSimpleName(), "message", message));
        } catch (Exception failure) {
            String message = Objects.toString(failure.getMessage(), "publication failed");
            if (isPublicationPolicyFailure(message)) {
                return blocked(message, AgentReason.STALE_REQUEST, AgentNextAction.RETRY);
            }
            return new AgentResponse(AgentStatus.FAILED,
                    AgentReason.INTERNAL_FAILURE,
                    AgentNextAction.REQUEST_HUMAN_HELP,
                    Map.of("error", failure.getClass().getSimpleName(), "message", message));
        }
    }

    private static boolean isPublicationPolicyFailure(String message) {
        return message.startsWith("CAPABILITY_PUBLICATION")
                || message.startsWith("NO_CHANGES_TO_PUBLISH")
                || message.startsWith("UNCLAIMED_SNAPSHOT_PATH")
                || message.startsWith("SNAPSHOT_ARTIFACT_POLICY");
    }

    /**
     * Request data for publishing the source tree projected for one accepted capability owner lane.
     *
     * @param projectRoot control project root
     * @param provider provider identifier
     * @param connectionInstanceId exact provider connection
     * @param requestHandle server-issued capability request handle
     * @param summary implementation summary
     * @param contractRevision accepted contract revision shown by the current projection
     * @param intentId active owner intent shown by the current projection
     * @param claimEpoch active owner claim epoch shown by the current projection
     * @param expectedTreeHash projected Git tree hash of the claimed source
     */
    public record PublishRequest(Path projectRoot,
            String provider,
            String connectionInstanceId,
            String requestHandle,
            String summary,
            Long contractRevision,
            UUID intentId,
            Long claimEpoch,
            String expectedTreeHash) {

        /** Validates required transport context; publication evidence is checked by the service. */
        public PublishRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(requestHandle, "requestHandle");
        }

    }
}
