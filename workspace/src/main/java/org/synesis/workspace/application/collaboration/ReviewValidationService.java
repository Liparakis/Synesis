package org.synesis.workspace.application.collaboration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.synesis.coordination.domain.collaboration.CollaborationCodec;
import org.synesis.coordination.domain.collaboration.LaneGrant;
import org.synesis.coordination.domain.collaboration.ReviewValidationPayload;
import org.synesis.coordination.domain.collaboration.WorkGroup;
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
import org.synesis.workspace.application.provider.ProviderManualService;
import org.synesis.workspace.application.provider.ProviderSessionBindingService;
import org.synesis.workspace.application.provider.SessionAuthorityResolver;

/** Records a grant-authorized decision over an immutable work-group snapshot. */
public final class ReviewValidationService {
    private final ProjectApplicationService projectService = new ProjectApplicationService();
    private final ProviderSessionBindingService bindingService = new ProviderSessionBindingService();
    private final SessionAuthorityResolver authorityResolver = new SessionAuthorityResolver(bindingService);
    private final ProviderManualService manualService = new ProviderManualService();

    /** Parameters for one review decision. */
    public record ValidateRequest(Path projectRoot, String provider, String connectionInstanceId,
            UUID grantId, String snapshotId, UUID intentId, long claimEpoch, String result, String reason) {
        /** Validates required request fields and normalizes only structural whitespace. */
        public ValidateRequest {
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(connectionInstanceId, "connectionInstanceId");
            Objects.requireNonNull(grantId, "grantId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(intentId, "intentId");
            Objects.requireNonNull(result, "result");
            if (claimEpoch < 1) throw new IllegalArgumentException("claimEpoch must be positive");
        }
    }

    /** Consumes no authority itself; records a decision only after the targeted grant was consumed. */
    public AgentResponse validate(ValidateRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            manualService.requireAttested(request.provider());
            Path root = request.projectRoot().toAbsolutePath().normalize();
            if (!Files.exists(root.resolve(".synesis/project.json"))) {
                return blocked("WORKSPACE_NOT_READY", AgentNextAction.ENSURE_SESSION);
            }
            ProjectApplicationService.ProjectLocation location = projectService.locate(root);
            ProviderSessionBindingService.Binding binding = authorityResolver.resolve(
                    location, request.provider(), request.connectionInstanceId());
            NodeIdentity identity = new IdentityBootstrap(location.profile().resolve("link")).loadOrCreate().identity();
            String reviewer = WorkspaceCollaborationService.participantHandle(binding.sessionId());
            Path coordDir = location.root().resolve(".synesis/coordination");
            try (ProjectAppendLock appendLock = ProjectAppendLock.acquire(coordDir)) {
                if (!appendLock.isHeld()) throw new IOException("event append lock unavailable");
                PredictionEventStore store = new PredictionEventStore(coordDir, location.projectId());
                LaneGrant grant = store.workGroupProjection().grants().stream()
                        .filter(value -> value.grantId().equals(request.grantId())).findFirst()
                        .orElseThrow(() -> new IOException("LANE_GRANT_NOT_FOUND"));
                if (!grant.targetParticipant().equals(reviewer)) {
                    return blocked("REVIEW_GRANT_TARGET_MISMATCH", AgentNextAction.RETRY);
                }
                if (!grant.targetIntentId().equals(request.intentId()) || grant.claimEpoch() != request.claimEpoch()) {
                    return blocked("REVIEW_GRANT_BINDING_MISMATCH", AgentNextAction.RETRY);
                }
                if (!store.workGroupProjection().grantConsumed(grant.grantId())) {
                    return blocked("REVIEW_GRANT_NOT_CONSUMED", AgentNextAction.REQUEST_COORDINATION);
                }
                TaskSnapshotRecord snapshot = store.taskCompletionProjection().findSnapshotById(request.snapshotId())
                        .orElseThrow(() -> new IOException("REVIEW_SNAPSHOT_NOT_FOUND"));
                if (!snapshot.provenance().workGroupId().equals(grant.workGroupId())
                        || !snapshot.provenance().laneId().equals(grant.targetIntentId())
                        || snapshot.provenance().claimEpoch() != grant.claimEpoch()) {
                    return blocked("REVIEW_SNAPSHOT_MISMATCH", AgentNextAction.RETRY);
                }
                String normalizedResult = request.result().trim().toUpperCase(java.util.Locale.ROOT);
                if (normalizedResult.equals("ACCEPT")) normalizedResult = "ACCEPTED";
                if (normalizedResult.equals("REJECT")) normalizedResult = "REJECTED";
                if (!normalizedResult.equals("ACCEPTED") && !normalizedResult.equals("REJECTED")) {
                    return blocked("REVIEW_RESULT_INVALID", AgentNextAction.RETRY);
                }
                if (normalizedResult.equals("REJECTED") && (request.reason() == null || request.reason().isBlank())) {
                    return blocked("REVIEW_REJECTION_REASON_REQUIRED", AgentNextAction.RETRY);
                }
                ReviewValidationPayload decision = new ReviewValidationPayload(
                        grant.grantId(), grant.workGroupId(), grant.targetIntentId(), grant.targetParticipant(),
                        grant.claimEpoch(), snapshot.taskId(), snapshot.snapshotId(), normalizedResult,
                        request.reason(), snapshot.provenance().participant());
                var existing = store.workGroupProjection().reviewValidationForGrant(grant.grantId());
                if (existing.isPresent() && !existing.get().equals(decision)) {
                    return blocked("REVIEW_DECISION_CONFLICT", AgentNextAction.RETRY);
                }
                if (existing.isEmpty()) {
                    UUID eventId = UUID.nameUUIDFromBytes(("review-validation:" + grant.grantId() + ":" + snapshot.snapshotId())
                            .getBytes(StandardCharsets.UTF_8));
                    store.append(eventId, PredictionEventType.REVIEW_VALIDATION_RECORDED,
                            identity.nodeId(), decision.encode(), identity);
                }
                WorkGroup group = store.workGroupProjection().group(grant.workGroupId())
                        .orElseThrow(() -> new IOException("WORK_GROUP_NOT_FOUND"));
                UUID groupId = group.workGroupId();
                if (normalizedResult.equals("ACCEPTED") && group.status() == WorkGroup.Status.ACTIVE
                        && store.collaborationProjection().activeIntents().stream()
                                .noneMatch(intent -> intent.workGroupId().equals(groupId))
                        && store.workGroupProjection().grants().stream()
                                .noneMatch(value -> value.workGroupId().equals(groupId)
                                        && store.workGroupProjection().grantAvailable(value.grantId()))) {
                    WorkGroup completed = new WorkGroup(groupId, group.projectId(), group.goal(),
                            group.acceptance(), group.version() + 1, WorkGroup.Status.COMPLETED);
                    store.append(groupId, PredictionEventType.WORK_GROUP_STATUS_CHANGED,
                            identity.nodeId(), CollaborationCodec.encodeWorkGroup(completed), identity);
                    group = completed;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("grantId", grant.grantId().toString());
                result.put("snapshotId", snapshot.snapshotId());
                result.put("workGroupId", group.workGroupId().toString());
                result.put("result", normalizedResult);
                result.put("workGroupStatus", normalizedResult.equals("ACCEPTED") ? "COMPLETED" : group.status().name());
                if (normalizedResult.equals("REJECTED")) {
                    result.put("route", Map.of("targetParticipant", snapshot.provenance().participant(),
                            "targetIntentId", snapshot.provenance().laneId().toString(),
                            "snapshotId", snapshot.snapshotId(), "nextAction", "ensure_session"));
                }
                return new AgentResponse(AgentStatus.COMPLETED, null, null, result);
            }
        } catch (Exception failure) {
            return blocked(failure.getMessage() == null ? "REVIEW_VALIDATION_FAILED" : failure.getMessage(), AgentNextAction.RETRY);
        }
    }

    private static AgentResponse blocked(String error, AgentNextAction action) {
        return new AgentResponse(AgentStatus.BLOCKED, AgentReason.POLICY_DENIED, action, Map.of("error", error));
    }
}
